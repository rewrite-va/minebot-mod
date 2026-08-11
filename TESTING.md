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

## Two entry points into the same test bodies -- do not conflate them

The actual test logic (send a goal via `ModBridge`, poll tracked state
until it holds or times out) lives once, in the `minebot` repo under
`minebot/testing/tests.py`, as plain async functions. There are two
separate, independent ways to run them, and they exist for genuinely
different purposes -- neither is a replacement for the other, and
**automation must never go through the first one**:

- **`!runtest [name]` -- manual/interactive only, a human types this.**
  The bot is already running, connected to its normal Prism instance (or
  a second, throwaway one loaded into a creative world specifically for
  this). A person joins as a second player, watches the bot on screen,
  and types `!runtest` (or `!runtest <name>` for one specific test) in
  chat themselves. This exists specifically so a human can *watch* a test
  execute live -- per explicit direction, this is NOT meant to be
  triggered by any script, CI job, or other automated process. If
  something needs to run tests unattended, it uses the second entry point
  below instead.
- **A scripted pytest driver -- DONE, see "Automated pytest driver"
  below -- unattended/automated.** A Python test process launches a
  fresh, disposable client itself (`./gradlew runClient
  -Pminebot.bootstrapTestWorld=...`, see "Launch mechanism"/"Launch
  stability" below), connects its own `ModBridge`, and calls the exact
  same test functions from `minebot/testing/tests.py` directly -- no
  chat, no `!runtest`, no human typing anything. This is the path CI or
  any other automation should use. Lives in the `minebot` repo at
  `tests/integration/`, run explicitly via `uv run pytest
  tests/integration/` (never swept up by a plain `uv run pytest`).

Both entry points call into the same `TestContext`/test-function shapes
in `minebot/testing/tests.py`, so a test is only ever written once; which
entry point runs it is purely a question of "does a human want to watch
this happen live right now" (`!runtest`) vs. "does this need to run
unattended" (the pytest driver).

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

## Launch mechanism -- CONFIRMED (see "Launch stability -- RESOLVED" below for the full story)

Fabric Loom's `runClient` Gradle task (confirmed present: `./gradlew
tasks --all` lists it) is the same launch this repo's own `CLAUDE.md`
build/deploy loop already uses manually. Two things needed on top of the
plain task, both now actually exercised, not just assumed:

- **Skip the main menu, boot straight into a world.** Vanilla supports
  `--quickPlaySingleplayer <world folder name>` as a launch argument
  specifically for this (used by the real launcher's "quick play"
  feature). Wired via `loom.runConfigs.client.programArgs(...)` in
  `build.gradle`, driven by a `-Pminebot.quickPlayWorld=<name>` Gradle
  project property (see `build.gradle`'s own comment) so this never
  fires during a normal manual `./gradlew runClient`.
- **A real, VISIBLE window, not headless** -- this changed mid-session
  once the user clarified the actual requirement (needing to watch the
  bot perform tests, not just read event logs). Launch against WSL2's own
  real WSLg X display (`DISPLAY=:0`) rather than `xvfb-run`/Xvfb -- see
  "Launch stability -- RESOLVED" below for the full investigation
  (`xvfb-run` was tried first, worked mechanically but is invisible by
  definition; WSLg was tried next and, once a GPU-adapter-selection bug
  was found and fixed, turned out to be both visible AND stable, so it's
  now the one and only launch path this doc recommends).

Not planned: any attempt to strip this down to a "fake"/mocked renderer
or a custom minimal client entrypoint. The entire value of this tier is
running the *real* client code path; a stripped-down stand-in would just
reintroduce the same gap unit tests already have.

## The disposable test world -- RESOLVED: regenerate fresh every run, not copy-restore

**Superseded the original plan below** (a checked-in "clean" save,
restored by copy before each run) **in favor of regenerating the world
from scratch on every single test run**, via `TestWorldBootstrap` (see
below) -- no checked-in save file at all. This changed after a real,
concrete failure: a manual play session (the user took control of the
bot and died after digging into the ground) left real state behind in
`run/saves/minebot-test-world` -- `DeathWatcher.DEATH_POSITION` and dug
blocks -- that silently persisted across an automated pytest run
afterward and made it hang indefinitely. The world was never actually
disposable with copy-restore alone unless something remembers to run the
restore step every time, including after ad hoc manual sessions; nothing
did. Regenerating fresh removes that whole class of bug -- there is no
"previous state" left to restore badly, since nothing survives between
runs at all. `TestWorldBootstrap` already makes this cheap (a few real
seconds of world creation inside the same client launch, not a separate
step), so the speed cost of abandoning copy-restore turned out to be
small. The pytest driver (`tests/integration/conftest.py`, see below)
deletes any existing `run/saves/<world>` directory before every launch
and always passes `-Pminebot.bootstrapTestWorld=<name>` (never
`-Pminebot.quickPlayWorld=<name>`, since `WorldOpenFlows.
createFreshLevel` already joins the freshly-created world in the same
call -- see below).

Each individual test's own setup (summon a zombie, clear an area,
teleport the bot to a fixed origin) still happens per-test via real `/`
commands per point 3 above -- the freshly-generated world only guarantees
the *starting* state (flat ground, creative mode, cheats on, empty of
mobs/structures), not per-test state.

**Generating the save headlessly, no GUI click needed, is a solved
problem** (see `minebot.mod.testsupport.TestWorldBootstrap`): rather than
hand-crafting `level.dat` NBT (fragile/version-fragile, no nbtlib/amulet
available in this environment) or relying on a human to click through
`CreateWorldScreen` once, `TestWorldBootstrap` calls
`WorldOpenFlows.createFreshLevel(...)` directly -- the exact method
`CreateWorldScreen`'s own "Create New World" button calls internally,
confirmed via decompiled bytecode to block synchronously on data-pack/
world-data loading and then call `Minecraft.doWorldLoad` itself, unlike
`CreateWorldScreen.testWorld()`/`openFresh()` (both still only ever open
a real `CreateWorldScreen` screen that needs a human/click to confirm --
tried first, rejected once the bytecode showed neither auto-confirms).
Gated behind a `minebot.bootstrapTestWorld` system property (never active
during normal play), wired to a `-Pminebot.bootstrapTestWorld=<name>`
Gradle property the same way quickplay's own property works. Confirmed
live, repeatedly (including as the actual mechanism the working pytest
driver now uses on every run, not just a one-off manual check): produces
a real, valid `run/saves/<name>/` (`level.dat` + `data`/`dimensions`/
`players`/`datapacks`), and the bot actually spawns into it and starts
broadcasting real `position`/`inventory`/`health` events within a few
seconds.

**World generation rules, per explicit direction:**

| Setting | Value |
|---|---|
| Game mode | Creative |
| Difficulty | Peaceful |
| Allow commands | On |
| World type | Superflat |
| Customized preset | The Void (`minecraft:air` layer over the `minecraft:the_void` biome) |
| Generate structures | Off |
| Bonus chest | Off |

Implemented in `TestWorldBootstrap`: `LevelSettings` carries game mode/
difficulty/allow-commands, `WorldOptions(0L, false, false)` carries a
fixed seed + `generateStructures=false` + `generateBonusChest=false`, and
the world-type/preset combination is built by
`OneShotCreator.createVoidWorldDimensions` -- there's no single public
`WorldPreset` constant for "superflat + void layers" the way
`WorldPresets.FLAT` is a ready-made preset for the classic flat layers,
so this takes `WorldPresets.createFlatWorldDimensions` (still needed for
a correctly-configured nether/end, not just the overworld) and swaps in a
fresh `FlatLevelSource` built from `FlatLevelGeneratorPresets.THE_VOID`'s
own settings via `WorldDimensions.replaceOverworldGenerator` -- the exact
same built-in "The Void" preset `CreateWorldScreen`'s own World Type ->
Superflat -> Customize -> preset dropdown offers, not a hand-rolled
layer list. Confirmed live: the bot spawns with `on_ground: true` at a
constant y and walks normally across the flat void floor (real
`position` events from a full `!goto` run showed `on_ground:true`
throughout, at a fixed y, while x/z moved) -- the void preset's single
generated layer still gives real solid footing, it just has nothing
below or around it.

## First slice -- prove the harness, nothing else

Deliberately the smallest possible thing that proves every link in the
chain actually works, before investing in more tests or more harness
polish. Retargeted mid-session from the original `!kill`-based idea below
to a plain `!goto x y z` walk -- simpler to assert (no combat RNG, no
mob-AI variance, just "did the bot's position converge on a fixed point"),
and it needed a real new command anyway (see below), which was worth
building either way.

**Status: DONE, fully working end to end.** `uv run pytest
tests/integration/` (in the `minebot` repo) launches a real client,
generates a fresh disposable world, connects, sends `!goto`, and asserts
real arrival -- confirmed passing live (`1 passed in 19.41s`). See
"Automated pytest driver" below for the real bugs found and fixed getting
here (an asyncio event-loop-scope mismatch, a double-consumer race on
`ModBridge.events()`, and the world-state-leak issue described in "The
disposable test world" above).

1. **`!goto` command -- DONE.** Reintroduced (it was one of the commands
   stripped during the peer-state-machine rewrite -- see
   `MinebotMod.dispatchMessage`'s own docstring for the removal/
   reintroduction policy) as a real `Command.Goto(x, y, z)` +
   `LegsGotoNode` + `LegsState.GOTO`, wired into `LegsStateMachine`'s edge
   table the same way `Command.Pickup`/`LegsPickupItemsNode` are (a
   one-shot Legs-only concern, ranked below death-recovery/flee, above
   ordinary navigate/idle, not reachable from FLEE). `ModBridge.send_goto`
   added Python-side as the test driver's entry point.
2. **Launch mechanism -- CONFIRMED working** (see "Launch mechanism" and
   "Launch stability -- RESOLVED" above): `DISPLAY=:0 ./gradlew runClient
   -Pminebot.bootstrapTestWorld=<name>` genuinely boots a real, VISIBLE
   client that connects `ControlClient` out to a real `ModBridge` and
   broadcasts a real `hello` event, then a real `position` a few seconds
   later once the freshly-created world actually loads, and stays up
   reliably for the length of a real test run with no crash.
3. **Disposable test world generation -- CONFIRMED working, and now the
   ONLY mechanism used** (see "The disposable test world" above --
   copy-restore was abandoned in favor of always regenerating fresh):
   `TestWorldBootstrap` + `-Pminebot.bootstrapTestWorld=<name>` produces a
   real, valid save and joins it in the same launch (no separate
   quickplay re-join needed or used).
4. **The `!goto` assertion -- DONE.** `minebot/testing/tests.py::
   test_goto_moves_bot_to_target` -- waits for the first real `position`
   event (not just `hello`, which fires before the world even loads, see
   its own docstring for the race this guards against), sends `!goto` to
   a point 20 blocks from the bot's own current position, then polls
   `position` events until the bot's reported `(x, z)` is within
   `NavIntent.defaultStopDistance()` (2.0 blocks, matching
   `LegsGotoNode`'s own arrival tolerance) of the target. Run either via
   `!runtest goto` (manual) or `uv run pytest tests/integration/`
   (automated) -- see "Two entry points" above.

Original first-slice idea, kept for reference / as a natural second test
once `!goto` is solid -- a real end-to-end assertion via `{"type":"chat",
"text":"/summon minecraft:zombie ~2 ~ ~"}` then `{"type":"kill","query":
"zombie"}`, then wait for an `entity` "remove" event for that zombie's id
within a generous tick budget, and assert the bot's own `health` never
drops below some floor during the fight. This exercises real pathfinding
(`AStar`/`LegsNavigateNode`), the real `PlayerIntentionKillNode`/
`HandsMeleeAttackNode` fight loop, and real `MultiPlayerGameMode.attack`
-- the actual class of logic unit tests structurally can't reach -- but
has combat RNG/mob-AI variance `!goto` doesn't, which is why `!goto` was
chosen to prove the harness itself first.

Now that this slice is confirmed working end-to-end, it makes sense to
plan out a broader test suite (movement/pathfinding scenarios,
bow-drawing, block breaking, door opening, item pickup) or to decide on
CI integration (this tier is real-client-launch slow AND now has a real
VISIBLE-window requirement -- see "Launch stability" below -- likely not
something that runs on every commit the way `uv run pytest` does on the
Python side today, and possibly not CI-friendly at all; see "Open
questions" below).

## Automated pytest driver -- DONE, real bugs found and fixed getting here

Lives in the `minebot` repo: `tests/integration/conftest.py` (the
session-scoped fixture that launches the client) + `tests/integration/
test_goto.py` (a thin wrapper calling the shared `minebot/testing/
tests.py::test_goto_moves_bot_to_target` function -- same body `!runtest`
runs, see "Two entry points" above). Excluded from a plain `uv run
pytest` via `testpaths`/`norecursedirs` in `pyproject.toml`; run
explicitly with `uv run pytest tests/integration/`.

**Design, per explicit direction:** ONE real client launched per test
*session*, not one per test -- every test in the directory shares the
same running client/connection, each responsible for its own scenario
setup/teardown around it. This mirrors how real cross-project (mod +
backend) integration suites are usually built elsewhere (e.g. a
session-scoped browser fixture in Selenium/Playwright suites), and keeps
the slow part (real client boot, ~15-20s) paid once per run, not once per
test.

Three real, non-obvious bugs were found and fixed building this, beyond
the world-state-leak issue already covered in "The disposable test world"
above:

1. **`ControlClient`'s port was hardcoded, colliding with the
   always-running dev backend.** `ControlClient` always connected to
   `localhost:47893`, the SAME port `./start.sh`'s normal dev backend
   listens on -- a test-launched client would have fought the dev
   backend for that port (or silently connected to the WRONG server) if
   both were ever running at once. Fixed by making the port overridable:
   `MinebotMod.onInitializeClient` now reads `-Dminebot.controlPort`
   (defaulting to `ControlClient.DEFAULT_PORT`, so ordinary play/manual
   `runClient` is completely unaffected), wired through `build.gradle`
   via a `-Pminebot.controlPort=<port>` Gradle property the same way
   `bootstrapTestWorld`/`quickPlayWorld` already work. The pytest fixture
   asks the OS for a free ephemeral port up front (`socket.bind(("127.0.
   0.1", 0))`, read back, then released) and passes it through to both
   its own `ModBridge` and the launched client's `-Pminebot.controlPort`.
   Per explicit direction, the user is fine with the assumption that
   automated test runs may need the dev backend stopped first in
   general, but this fix means that's not actually REQUIRED anymore for
   the port specifically -- the two can coexist on different ports; it
   just removes one whole class of accidental collision.
2. **`ModBridge.events()` can only ever have ONE real consumer at a
   time -- a second call races the first for the same connection.**
   `events()` iterates the raw WebSocket connection object directly
   (`async for raw in connection`), so two independent `bridge.events()`
   calls each create their own generator competing to read the SAME
   underlying connection -- messages get silently split between whichever
   generator's `__anext__()` happens to be pending first, not delivered
   to both. The fixture's first version called `bridge.events()` once to
   wait for the initial `hello`, then called it AGAIN inside the
   background reader task that feeds `self_position`/`tracker` -- every
   event after `hello` (including every `position` broadcast) vanished
   into whichever of the two generators won the race that time, hanging
   the test forever waiting on a `self_position` that was never actually
   going to update, despite the mod's own log clearly showing real
   `position` events going out on the wire. Fixed by creating exactly ONE
   `bridge.events()` generator in the fixture and threading that same
   object through to both the `hello` check and the background reader.
3. **A session-scoped async fixture needs a session-scoped event
   loop, or tasks it creates go nowhere.** pytest-asyncio's default
   fixture/test loop scope is `"function"` -- a fresh event loop per
   test. The `ingame_session` fixture is `scope="session"` (per the
   "one client per run" design above) and creates a background
   `asyncio.Task` (the event reader) that has to keep running across
   every test in the session. Under the default function-scoped loop,
   that task ends up bound to whichever loop happened to be active when
   the fixture first ran, while the actual test function runs on a
   DIFFERENT (fresh, per-test) loop -- so events the reader task received
   were never actually visible to anything the test itself awaited on.
   Confirmed live: `send_goto` was never reached at all despite
   `self_position` clearly updating in the mod's own log; the whole test
   just hung until its timeout, with no error, no exception, nothing to
   point at the real cause. Fixed in `pyproject.toml`: `asyncio_default_
   fixture_loop_scope = "session"` and `asyncio_default_test_loop_scope =
   "session"`, keeping the fixture and every test in `tests/integration/`
   on the SAME loop for the whole run. Confirmed this doesn't affect the
   main unit suite (`uv run pytest` from the repo root, 148 tests) --
   still passes unchanged.
4. **A test body written as a bare, unbounded poll loop is only safe
   when something ELSE wraps it in a timeout -- and `test_goto.py`
   didn't.** `TestRunner._run_one` (the `!runtest` path) wraps every test
   in `asyncio.wait_for`, so a test function itself never NEEDED its own
   timeout -- but the pytest integration test calls `test_goto_moves_bot_
   to_target` directly, with no such wrapper. Reported live: after
   tightening `GOTO_ARRIVAL_TOLERANCE` to 0.5 blocks (see the next item),
   the pytest run hung indefinitely with no error, no timeout, nothing in
   the output to explain why -- the test's own `while True: ... await
   asyncio.sleep(...)` arrival loop had no bound of its own at all. Fixed
   by extracting the send-and-poll logic into reusable, SELF-timing-out
   primitives in a new `minebot/testing/actions.py`
   (`goto(ctx, x, y, z, distance_tolerance, timeout)`,
   `wait_for_position(ctx, timeout)`), each wrapped in its own
   `asyncio.wait_for` and raising a real `asyncio.TimeoutError` on
   expiry -- safe to call from ANY caller (`!runtest`'s `TestRunner` or a
   bare pytest test) with no dependency on an outer wrapper providing the
   bound. `test_goto_moves_bot_to_target` now reads as `start =
   await actions.wait_for_position(ctx, timeout=...)` then
   `await actions.goto(ctx, ..., distance_tolerance=..., timeout=...)` --
   a real, immediately-failing assertion instead of a loop that can hang
   forever.
5. **The tolerance-tightening itself surfaced a separate, genuine
   mod-side precision gap.** `LegsGotoNode` (the Java node behind `!goto`)
   was reusing `NavIntent.defaultStopDistance()` (2.0 blocks) -- the same
   loose "don't crowd a followed player" tolerance `!follow` uses -- as
   its own arrival check. Confirmed via real `position` events in the
   mod's own log: the bot consistently stopped ~2.0 blocks short of the
   requested `!goto` target, exactly matching that constant, not
   overshooting or landing randomly. Once the test's own tolerance was
   tightened to 0.5 (a reasonable expectation for "go to this exact
   spot," unlike `!follow`'s "stay near a moving target without crowding
   it"), the mod's own arrival check could never actually satisfy it --
   the bot genuinely never got that close, so the test's poll loop (once
   properly bounded per the item above) failed with a clear, honest
   `TimeoutError` rather than hanging. Fixed mod-side, as a real product
   improvement, not just a test tweak: `LegsGotoNode` now has its own
   dedicated `ARRIVAL_DISTANCE = 0.5` instead of reusing
   `NavIntent.defaultStopDistance()`, matching `LegsPickupItemsNode`'s
   own precedent for "not every publisher should share one tolerance"
   (see `NavIntent`'s own docstring). Confirmed live afterward: real
   `position` events show the bot converging to within the new tolerance
   of the target.

**Confirmed working, real run (after both fixes above):** `uv run pytest
-v tests/integration/` -> `1 passed in 17.86s` (fresh world generation +
client boot + connect + `!goto` + a real 5-block walk + arrival within
0.5 blocks, all within that time).

Test timeouts were deliberately kept short per explicit direction (not
generously long): `TestCase.timeout_seconds` (the `!runtest`/`TestRunner`
path) defaults to 20s (down from an original 60s), and the pytest-facing
`actions.goto`/`wait_for_position` primitives take their own explicit
`timeout` per call (`test_goto_moves_bot_to_target` currently passes 15s)
-- a short flat-ground `!goto` walk should complete well under either, and
a test still running past it is much more likely to be genuinely stuck
(bad target, blocked path, stale world state, or -- as happened this
session -- a tolerance tighter than what the thing under test can
actually achieve) than just slow.

## Test setup: fixed-origin teleport -- DONE

Per explicit direction: tests were computing their targets relative to
wherever the bot happened to already be standing (its live position at
the moment the test started), which made them order-dependent and
non-reproducible -- a test's actual behavior depended on what ran before
it, or where a manual play session last left the bot. Fixed by adding a
`TestCase.setup` hook (see `runner.py`'s own `TestCase`/`run_test_case`)
that runs before the test body, inside the SAME `timeout_seconds` budget
(not a separate one, so a stuck setup still fails fast). `!goto`'s own
setup (`minebot/testing/tests.py::setup_goto`) teleports to a fixed
origin `(0, -60, 0)` via a real `/tp @s x y z` chat/console command
before every run, so the test itself always starts from the exact same
known spot regardless of run order or a previous test's own end state.
`run_test_case(ctx, test)` is the shared sequencing both `!runtest`'s
`TestRunner` and the pytest integration driver use -- `tests/integration/
test_goto.py` now runs the actual registered `TestCase` (setup included)
via `run_test_case`, not the bare test function directly, specifically so
the automated driver exercises the exact same setup step a human running
`!runtest` would.

**A real, separate mod-side bug was found and fixed getting this
working:** `/tp` sent via the existing `{"type":"chat","text":"/tp
..."}` wire command was arriving at the server as a literal CHAT MESSAGE
("<PlayerName> /tp @s 0.0 -60.0 0.0" visible in vanilla's own chat log),
never executing as a command at all -- the bot never actually moved, and
the test's own arrival-polling loop simply timed out with nothing to
explain why. Root cause: `MinebotMod.sendChat` always called
`ClientPacketListener.sendChat(text)`, vanilla's plain chat-message send,
regardless of whether `text` started with `/`. Confirmed via decompiled
`ChatScreen` bytecode that real vanilla chat input does NOT do this --
it checks for a leading `/` and routes to `ClientPacketListener.
sendCommand(text.substring(1))` (the slash stripped) instead, only
falling back to `sendChat` for text that isn't a command at all. Fixed
`sendChat` to replicate that exact routing. Since every existing caller
of this method (the "chat" wire dispatch case, `TaskController`'s
`busyReporter`) already sends both plain chat and real `/`-commands
through the same path, this fixes both, not just the new `/tp` use --
worth noting since it means any earlier `{"type":"chat","text":"/..."}`
send (e.g. TESTING.md's own original "Core idea" section's `/summon`
example) would have had this exact same silent-no-op bug, never actually
tested until this session.

Confirmed live, twice -- once revealing a real timing bug of its own,
once clean afterward: the FIRST attempt after fixing `sendChat` still
timed out, but the mod's own log showed the teleport genuinely succeeding
(`[Player: Teleported Player to 0.000000, -60.000000, 0.000000]`, and a
matching exact-match `position` event) -- the real cause turned out to be
a second bug, in `actions.py` itself: `goto()`/`teleport()` both waited
for the FIRST real position (world-load can take several real seconds)
using the SAME `timeout` parameter the caller passed in for the actual
action, so a slow world load could burn most of a tight timeout before
the real action even started, leaving it almost no fair budget despite
succeeding well within its own reasonable time. Fixed by giving that
initial wait its own separate, generous, fixed budget
(`INITIAL_POSITION_TIMEOUT_SECONDS`, 30s) instead of sharing the caller's
`timeout`. Confirmed working end to end afterward: `uv run pytest -v
tests/integration/` -> `1 passed in 18.54s`, with real `position` events
showing origin `(0, -60, 0)` -> teleport confirmed -> `!goto` walk to
`z ~ 5.25` (target `z=5.0`, within the 0.5-block tolerance).

## Launch stability -- RESOLVED (visible-window launch, not headless)

**The user's own real requirement, surfaced mid-session, changed the whole
launch strategy: they need to actually SEE the client window during a
test run** (watch the bot move, not just read event logs) -- not the
originally-planned fully headless/no-window tier. That ruled out
`xvfb-run` outright (no window to see) in favor of launching against
WSL2's own real WSLg X display (`DISPLAY=:0`, already running, a real X11
socket at `/tmp/.X11-unix/X0`) -- confirmed the user can genuinely see and
interact with the real Minecraft window this way.

Two real, now-fixed bugs stood between "window appears" and "bot is
actually walking around in a live world visibly on screen":

1. **First-run "accessibility onboarding" screen blocks quickplay
   entirely, silently.** `--quickPlaySingleplayer` never advances past it
   -- not a crash, not a hang, the client stays fully responsive at the
   main menu (confirmed by the user directly: "not frozen I can see
   animations, probably I can click on buttons") because quickplay's own
   join logic apparently never even fires while this screen is still
   pending. The user correctly guessed the cause and the fix: `run/
   options.txt`'s `onboardAccessibility:true` -> `false`. This is a
   `run/`-directory dev-environment file, not committed mod source --
   worth remembering if `run/` is ever wiped/regenerated (a fresh Loom
   `run/` will need this set again, OR seeded via a checked-in template
   `options.txt`, not yet decided which).
2. **Real GPU instability under WSLg's default D3D12 adapter selection.**
   This machine has both an integrated AMD GPU and a discrete NVIDIA RTX
   4090 (confirmed via `nvidia-smi`, genuinely visible from WSL2 through
   `/dev/dxg`). WSLg's Mesa D3D12 Gallium driver defaulted to the AMD
   adapter, and running real gameplay on it was reliably unstable:
   repeated crashes ~50-60s after a successful login, with three
   DIFFERENT underlying signatures across attempts (a real `GL_OUT_OF_
   MEMORY` in `glBufferStorage`, a genuine `GpuOutOfMemoryException`
   during texture upload complete with a full crash report, and a native
   `SIGSEGV` inside Mesa's own software rasterizer `swrast_dri.so`) --
   all three crashes were inside vanilla/Mesa's own code (confirmed via
   full stack traces/crash reports), never this mod's, and all shared the
   same suspicious ~1-minute-post-login timing regardless of which
   specific failure mode hit. Forcing the real NVIDIA adapter via the
   `MESA_D3D12_DEFAULT_ADAPTER_NAME=NVIDIA` env var fixed this outright --
   confirmed by two separate clean runs afterward, each running the full
   length of a 150-180s test window with zero crashes and real ongoing
   chunk/UBO rendering the whole time. Now baked permanently into
   `build.gradle`'s `loom.runConfigs.client.environmentVariable(...)`, so
   no manual env var is needed for any future `./gradlew runClient`.

**Net launch recipe, confirmed stable end to end:**
`DISPLAY=:0 ./gradlew runClient -Pminebot.bootstrapTestWorld=<world>
-Pminebot.controlPort=<port>` for a fresh disposable-world run (what the
pytest driver actually uses -- see "Automated pytest driver" above), or
`-Pminebot.quickPlayWorld=<world>` instead of `bootstrapTestWorld` to
re-join an EXISTING save without regenerating it (useful for manual
`!runtest` sessions against a world you want to keep exploring between
runs, but not what the automated driver does). The NVIDIA adapter
targeting lives in `build.gradle` unconditionally either way; `DISPLAY=:0`
is the only thing that has to be passed by hand, since Gradle doesn't
always inherit a login-session `DISPLAY` the same way an interactive
shell does. `xvfb-run` is no longer part of the plan at all -- superseded
by this real-WSLg-display approach, which is strictly better here since
it's both visible AND (once targeting the right GPU) stable, whereas
Xvfb was only ever "possibly stable, definitely invisible."

## Open questions / not yet decided

- **RESOLVED**: Java-side test driver vs. reusing Python's `ModBridge`
  from a pytest fixture -- went with Python-reuse (`tests/integration/`
  in the `minebot` repo, see "Automated pytest driver" above), which
  turned out to be the right call: no protocol code duplicated, and the
  same `minebot/testing/tests.py` test bodies are shared with `!runtest`.
- **RESOLVED**: "assert an event arrives within N ticks" -- ended up not
  needing a generic N-ticks helper at all; `test_goto_moves_bot_to_target`
  just polls `ctx.self_position.current` on a plain `asyncio.sleep(0.5)`
  loop with the whole test bounded by `TestCase.timeout_seconds` (20s).
  Simple enough in practice that a dedicated polling/timeout abstraction
  wasn't worth building for one test; revisit if a second, differently-
  shaped assertion (e.g. "wait for an `entity` remove event", the
  original `!kill`-based idea) makes the plain-poll pattern feel
  repetitive.
- CI integration, or whether this tier stays local/manual-run only given
  its cost (real client launch, real Minecraft assets, a real GPU, and
  a VISIBLE window/real display requirement -- see "Launch stability"
  above, this is no longer even attempting to be a headless/CI-friendly
  tier the way the doc originally imagined) -- still undecided. Worth
  being honest that a visible-window requirement may mean this tier
  never runs in CI at all, only locally/manually, which is a real scope
  change from the doc's original framing.
- **RESOLVED**: world-reset mechanism -- regenerate fresh via
  `TestWorldBootstrap` on every run, not copy-restore. See "The
  disposable test world" above for the real state-leak bug (a manual
  death/dig session hanging a later automated run) that settled this.
- `run/options.txt`'s dev-environment fixes (`onboardAccessibility:
  false`, `pauseOnLostFocus:false`, `tutorialStep:none` -- see "Launch
  stability" above) live in the gitignored Loom `run/` directory, not
  committed source -- if `run/` is ever wiped/regenerated, these will
  need to be re-applied by hand, or seeded via a checked-in template
  `options.txt` copied into a fresh `run/` (not yet decided which; a
  template file is probably worth adding, especially now that the
  automated pytest driver exists and could plausibly want to set these
  up itself on a fresh checkout rather than relying on someone
  remembering to do it manually first).
- Broader test suite scope (movement/pathfinding scenarios, bow-drawing,
  block breaking, door opening, item pickup, and the original
  `!kill`-based zombie-fight idea kept for reference in "First slice"
  above) -- not started, now that the harness itself (launch, disposable
  world, both entry points, one real passing test) is proven solid
  end to end.
