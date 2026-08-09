# In-game tests -- design

Design doc for a real, in-game test tier for this mod, written before any
code per this repo's own convention (see `STATE_MACHINE.md`'s own
opening). **Read this file before touching anything described here.**
Keep it up to date as the real implementation diverges from the plan.

## Why not Mojang's GameTest framework

Fabric's usual answer for "in-game tests" is Mojang's `GameTest` API
(`fabric-gametest-api-v1`): spawn a structure inside a headless
*dedicated server*, tick it, assert on world state. Ruled out here for
two reasons, checked directly rather than assumed:

- This mod is declared `"environment": "client"` (`fabric.mod.json`) and
  most of its actual logic is real client-side interaction --
  `BowShooter`/`HandsDrawBowNode` holding the real `Options.keyUse`
  keybind, `FoodEater`/`HandsEatNode` doing the same, direct
  `Minecraft.getInstance()` calls throughout. GameTest runs on a headless
  *server* with no client, no `LocalPlayer`, no keybinds -- none of that
  code is reachable from a GameTest at all. It could only ever cover the
  minority of this mod that's pure server-visible state (`AStar`,
  `BlockInfo`, `WaypointClassifier`), not the client-interaction core
  that's actually driven this repo's real bug history (see
  `FINDINGS.md`'s "Bow-drawing never actually fired an arrow").
- Checked the actual cached Fabric API jar for this Minecraft version
  (`26.1.2`, `fabric-api-0.151.0+26.1.2.jar`): zero `gametest` classes
  present. Whether that module exists at all for this MC version wasn't
  confirmed either way, but it's moot given the point above.

Conclusion: the only tier worth building is one that drives a **real
client** end to end, the same way the bot is actually run today.

## Core idea: drive a real launched client through its own existing control protocol

No new mod-side protocol. `MinebotMod`/`ControlClient` already speak a
complete, high-level command/event protocol to an external process
(today, the Python backend's `ModBridge`) -- see `MinebotMod.
dispatchMessage`/`broadcast*Event`. A test is:

1. Launch a real Minecraft client (not headless-server, an actual client
   with this mod loaded) into a known, disposable creative world.
2. Have something stand in for the Python backend: a small WebSocket
   server the test drives directly (either new minimal Java/JUnit code,
   or -- if assertions read more naturally there -- the *existing*
   `minebot`/Python repo's `ModBridge` class launched from a pytest
   fixture, since it already implements this exact server role and is
   already unit-tested against a fake client in `test_mod_bridge.py`).
   Reusing `ModBridge` is the leaner option and is the default plan
   below; a pure-Java test-only server is the fallback if keeping this
   entirely inside the `minebot-mod` repo (no cross-repo test
   dependency) turns out to matter more.
3. Send real world-setup commands over the *same* channel real gameplay
   commands go over -- `{"type": "chat", "text": "/summon ..."}" etc. --
   since the mod's own `"chat"` case already sends real vanilla chat/
   commands via `client.player.connection.sendChat(...)`. No RCON, no
   second connection, no server-admin side channel: setup rides the
   identical path a real player/bot action does, which is the whole
   point (test the same thing that's actually shipped).
4. Send the real command under test (`{"type": "kill", ...}`, `follow`,
   `defend`, ...).
5. Assert against the real events the mod already broadcasts back
   (`position`, `health`, `entity` add/remove/move, `death`, `respawn`,
   `hello`) -- the same events Python already consumes, so a test
   assertion is never inventing a new observability path, just reading
   the one that already ships.

This reuses the entire existing wire protocol and event surface
unchanged. The only genuinely new pieces are the headless launch and the
disposable test world.

## Headless launch

Fabric Loom's `runClient` Gradle task (confirmed present: `./gradlew
tasks --all` lists it) is the same launch this repo's own `CLAUDE.md`
build/deploy loop already uses manually. Two things needed on top of the
plain task:

- **Skip the main menu, boot straight into a world.** Vanilla supports
  `--quickPlaySingleplayer <world folder name>` as a launch argument
  specifically for this (used by the real launcher's "quick play"
  feature) -- point it at the disposable test world (below) so the
  client is in-game and ticking within seconds of process start, no
  scripted mouse/keyboard menu navigation needed.
- **No visible window required, but a real GL context still is.** The
  client still needs to initialize a renderer even if nothing displays
  it -- run under `xvfb-run` (virtual framebuffer, standard on
  Linux/WSL2) rather than trying to strip rendering out of the client
  itself. Confirm this actually works in this environment as the very
  first implementation step (see "First slice" below) before designing
  anything further on top of it -- if `xvfb-run` turns out not to boot a
  real MC client reliably here, that changes the whole approach and is
  worth knowing immediately, not after the rest is built.

Not planned: any attempt to strip this down to a "fake"/mocked renderer
or a custom minimal client entrypoint. The entire value of this tier is
running the *real* client code path; a stripped-down stand-in would just
reintroduce the same gap unit tests already have.

## The disposable test world

A small, checked-in singleplayer creative-mode world save (flat/
superflat preset, cheats enabled), reset to a known-clean state between
test runs rather than reused/mutated across them -- concretely,
restoring from a checked-in "clean" copy before each run (copy-on-launch)
rather than relying on individual tests to undo their own setup, so one
test's leftover mobs/blocks/inventory can never leak into the next.
World file lives under version control the same way any other fixture
would (small; a flat superflat save with nothing built is tiny).

Each individual test's own setup (summon a zombie, clear an area,
teleport the bot to a fixed origin) still happens per-test via real `/`
commands per point 3 above -- the checked-in world only guarantees the
*starting* state (flat ground, creative mode, cheats on, empty of
mobs/structures), not per-test state.

## First slice -- prove the harness, nothing else

Deliberately the smallest possible thing that proves every link in the
chain actually works, before investing in more tests or more harness
polish:

1. `xvfb-run ./gradlew runClient` with `--quickPlaySingleplayer` pointed
   at the checked-in test world actually launches, loads the world, and
   the mod's `ControlClient` connects out successfully to a
   `ModBridge`-based test server listening on a test port.
2. The `hello` event arrives (confirms the full chain: client up, mod
   initialized, control channel connected, event broadcast working).
3. One real end-to-end assertion: `{"type":"chat","text":"/summon
   minecraft:zombie ~2 ~ ~"}` then `{"type":"kill","query":"zombie"}`,
   then wait for an `entity` "remove" event for that zombie's id within a
   generous tick budget, and assert the bot's own `health` never drops
   below some floor during the fight. This single test already exercises
   real pathfinding (`AStar`/`LegsNavigateNode`), the real
   `PlayerIntentionKillNode`/`HandsMeleeAttackNode` fight loop, and real
   `MultiPlayerGameMode.attack` -- the actual class of logic unit tests
   structurally can't reach.

Only after this slice is confirmed working end-to-end (including
confirming `xvfb-run` viability, per the launch section above) does it
make sense to plan out a broader test suite (movement/pathfinding
scenarios, bow-drawing, block breaking, door opening, item pickup) or to
decide on CI integration (this tier is real-client-launch slow, likely
not something that runs on every commit the way `uv run pytest` does on
the Python side today -- more likely an opt-in/pre-release tier, similar
in spirit to the "both, as separate tiers" idea considered and shelved
for the Python side of this project).

## Open questions / not yet decided

- Java-side test driver vs. reusing Python's `ModBridge` from a pytest
  fixture (see step 2 above) -- leaning Python-reuse for less duplicated
  protocol code, not fixed yet; revisit once the first slice is actually
  built and it's clear which is less friction in practice.
- How "assert an event arrives within N ticks" is best expressed --
  needs a real timeout/polling helper either way (async in Python, or a
  blocking-with-timeout read in Java), not designed yet.
- CI integration, or whether this tier stays local/manual-run only given
  its cost (real client launch, real Minecraft assets, GPU/GL
  requirement even under Xvfb) -- explicitly deferred to after the first
  slice, per "First slice" above.
- World-reset mechanism's exact implementation (file copy vs. some other
  snapshot approach) -- copy-on-launch is the current lean, not yet
  built or verified.
