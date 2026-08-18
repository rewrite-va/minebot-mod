# In-game tests

Real-client integration tests for the bot's movement/pathfinding behavior,
driven against an already-running bot over its normal control-channel
protocol -- no headless server, no mocked client, no separate test-only
protocol. This doc describes the test system as it actually exists today;
see git history for the earlier design-doc-first process that produced it
and the launch/world-generation investigation that proved a real-client
tier was even feasible.

## Two entry points, one shared test body

The actual test logic (place a schematic, send `!goto`, assert on the
bot's real broadcast state) lives once, in the `minebot-backend` repo under
`minebot/testing/tests.py`, as plain async functions registered into a
`TestRegistry`. There are two ways to run them:

- **`!runtest [name] [stop_on_first_failure]` -- manual/interactive.** A
  human types this in chat against an already-connected, already-running
  bot. `!runtest` with no name runs every registered test, stopping at
  the first failure by default (a failed test's leftover state --
  placed-but-uncleared schematic blocks, a stuck `LegsState` -- tends to
  cascade into unrelated-looking failures in everything that runs after
  it). `!runtest all false` runs the whole suite regardless of failures,
  e.g. to see a total pass/fail count in one pass ("all" is the literal
  run-everything spelling, since chat command grammar can't supply a
  second positional arg while leaving the first unset). `!runtest
  goto_jump_2` runs one test by name. See `minebot/bot/testing.py`.
- **`uv run pytest tests/integration/` -- automated/unattended.** Launches
  a real, disposable client itself and calls the exact same registered
  `TestCase`s via `run_test_case`. Excluded from a plain `uv run pytest`
  (that only runs the unit suite); run explicitly. See minebot-mod's own
  earlier design-doc history (`git log -- TESTING.md`) for the launch
  mechanism (a real visible WSLg window, disposable world regenerated
  fresh every run via `TestWorldBootstrap`) -- that plumbing is unchanged
  since it was originally built and isn't repeated here.

Both entry points share the same `TestCase`/`TestContext` shapes, so a
test is only ever written once. Both also save whatever gamemode their
own session actually started in, switch to survival before running
anything, and restore the original gamemode once done -- see "Gamemode
command/query" below for why (creative lets an accidental double jump-key
press toggle flight, which can otherwise look exactly like a permanent
physics wedge).

## Litematica schematic fixtures + wool waypoints

Most tests build their scenario as a small Litematica schematic (placed
by hand in a normal creative-mode session, exported via `/litematic
create`, copied into `tests/fixtures/schematics/*.litematic` via
`import_schematic.py`) rather than hardcoding block coordinates in test
source. `minebot/testing/litematic.py` is a from-scratch minimal NBT +
Litematica bit-packing reader (no external NBT library, no Litematica mod
dependency on the test client) -- see its own docstring for why, and for
where the bit-packing math was cross-checked against Litematica's real
Java source.

Colored wool blocks in a schematic are read as **waypoint markers**, not
ordinary placeable blocks (`litematic.WAYPOINT_BLOCK_ROLES`):

| Wool color | Role | Meaning |
|---|---|---|
| White | `start` | Where the test's `setup` teleports the bot before sending `!goto` |
| Yellow | `path` | The bot's real walked trail must pass within `WAYPOINT_RADIUS` (0.5 blocks -- exactly the block's own extent from its real CENTER, so the sphere never bleeds into a neighboring cell) of this point at some point |
| Red | `forbidden` | The bot's real walked trail must NEVER come within `WAYPOINT_RADIUS` of this point |
| Green or Lime | `end` | The `!goto` target itself |
| Magenta | `unreachable` | A target the test asserts the bot never reaches at all (`actions.assert_goto_never_arrives`) |

A scenario is authored purely by placing these wool blocks visually in
Litematica -- no separate coordinates file to keep in sync by hand. Every
role except `start`/`end` (validated down to exactly one via
`actions._one`) is a list, since a `path`/`forbidden` scenario can mark
several points.

`minebot/testing/actions.py::goto_with_waypoints` sends `!goto` toward
the schematic's `end` waypoint and, via a `SelfPositionTracker` listener,
tags every real broadcast `position` event against every `path`/
`forbidden` waypoint using real 3D distance (not horizontal-only --
found live: an (x, z)-only check couldn't tell "walked through this
exact cell" from "passed directly above/below it during a jump", both a
false-forbidden-hit and a false-path-miss bug at different points).
Returns per-waypoint hit counts (`path_hits`, `forbidden_hits`); a test
asserts `path_hits[i] > 0` / `forbidden_hits[i] == 0` for whichever
waypoints it cares about.

## Registered tests

| Name | Fixture | Asserts |
|---|---|---|
| `goto` | none (bare coordinates) | Sends the bot 5 blocks away via `!goto`, asserts arrival |
| `goto_onto_schematic` | `simple_goto.litematic` | Places a small fixture, `!goto`s onto a block on it, asserts arrival -- then always clears the placed blocks |
| `goto_jump_1` | `goto_jump_1.litematic` | A 1-block gap; asserts the bot jumps across without falling into the forbidden column below the gap |
| `goto_jump_2` | `goto_jump_2.litematic` | A 2-block gap + 1-block climb; asserts the bot jumps across without falling into either forbidden column |
| `goto_jump_3` | `goto_jump_3.litematic` | A longer course combining a required path checkpoint with a 2-block forbidden gap; asserts the bot walks the checkpoint without falling into either forbidden column |
| `goto_leaves_1` | `goto_leaves_1.litematic` | A target blocked only by head clearance under floating leaves (2-block-tall hitbox, not a horizontal wall); asserts the bot never reaches it AND never attempts a jump (`assert_never_jumps`) |
| `goto_leaves_2` | `goto_leaves_2.litematic` | A reachable room with the same leaves fixture; asserts the bot walks the required checkpoint, avoids every forbidden wall cell, and jumps exactly once (`assert_jumps_done`) |
| `goto_impossible` | `goto_impossible_1.litematic` | A target genuinely blocked by a wall; asserts the bot never reaches it (`assert_goto_never_arrives`) |

Every schematic test's `setup` (see `minebot/testing/tests.py`) teleports
to a fixed HOLDING position, places the schematic via batched `/fill`
commands, then teleports to the schematic's own `start` waypoint -- all
teleports offset to the target block's real horizontal CENTER (`x+0.5`,
`z+0.5`; `y` left unchanged, since a block's own top surface is already
the correct standing height), found live as a real bug when teleports
were landing bots at a block's edge instead of its middle. Every
schematic test's `teardown` unconditionally clears the placed blocks
(even on failure, so a failed run never leaves debris for the next test)
and ends with a fire-and-forget `/tp` back to HOLDING, so a failure
doesn't leave the bot standing in the way of whatever runs next.

Fixed, short timeouts throughout (`TELEPORT_TIMEOUT_SECONDS`,
`GOTO_TIMEOUT_SECONDS`, `SCHEMATIC_TIMEOUT_SECONDS` = 5.0s each, per
explicit direction) -- a real short flat-ground or single-jump `!goto`
should complete well under that; a test still running past it is more
likely genuinely stuck than just slow. `goto_leaves_2` is the one
exception (`LEAVES2_GOTO_TIMEOUT_SECONDS` = 15.0s) -- its own start
position sits in an open pit with no floor block under it at all, so a
real route out involves more ticks of fall/recovery/climb than a normal
flat-ground or single-gap-jump course before ever reaching `end`.

## Live navigate debugging

**Set `-Dminebot.debugNavigate=true` on the client by default while
actively working on movement/pathfinding**, not just after a failure
prompts going back to reproduce it with logging on -- per explicit
direction, to avoid the back-and-forth of "reproduce the failure, THEN
relaunch with debug flags on, THEN reproduce it again" every single time
a jump/navigate bug needs diagnosing. Only turn it off again once done
(see its own verbosity note below for why it's not just left on always).

`LegsNavigateNode`'s own `navigate[diag]` log line (self position, current
waypoint, jump-related booleans, `runupTicks`) fires every 10th tick by
default -- fine for eyeballing a normal walk, but too coarse to catch a
real bug that only shows up across 2-3 consecutive ticks (e.g. a genuine
double jump-key-press close enough together to trigger vanilla's own
double-tap-space-toggles-flying detection -- confirmed live as the actual
root cause of an intermittent `goto_leaves_2` failure that otherwise
looked like a permanent physics wedge: the bot really was hovering,
`deltaMovement.y == 0.0` and `LocalPlayer.getAbilities().flying == true`,
not stuck against collision at all). Separately, the same method also
logs `navigate[diag]` on EVERY tick (regardless of this flag) whenever
`approachingJump` is true -- i.e. while a real jump waypoint is imminent,
including the plain edge waypoint immediately before it (see
`LegsNavigateNode`'s own `jumpWaypoint` look-ahead comment) -- since that
handful of ticks right around a jump is where run-up/edge-timing bugs
actually live, and logging them unconditionally is cheap (it's a tiny
fraction of a normal walk's total ticks).

Set `-Dminebot.debugNavigate=true` on the launched client's own JVM to
switch `navigate[diag]` to EVERY tick and enable a second
`navigate[collision]` line (real bounding box, `horizontalCollision`/
`verticalCollision`, `deltaMovement`, `flying`) whenever the bot is
walking toward a `requiresJump` waypoint. For the Gradle-launched client
both this repo's own manual runs and the pytest integration driver use:

```bash
./gradlew runClient -Pminebot.debugNavigate=true
```

(same forwarding shape `build.gradle`'s `loom.runConfigs.client` already
uses for `minebot.bootstrapTestWorld`/`minebot.controlPort` -- a bare
Gradle `-P` project property never reaches the forked game process on its
own). OFF by default -- deliberately not wired into
`tests/integration/conftest.py`'s own `gradlew runClient` invocation,
since every-tick logging is verbose enough to be a genuine cost on every
routine test run; opt in only while actively chasing a live per-tick bug.

## Real vanilla jump physics: `JumpPhysics`

`src/main/java/minebot/mod/pathfinding/JumpPhysics.java` computes, for a
given horizontal distance, the smallest ground run-up (in ticks) and
whether to sprint that lands a `requiresJump` move without overshooting
-- derived directly from decompiled vanilla `LivingEntity`/`Entity`
physics (see the Loom decompile cache, `~/.gradle/caches/fabric-loom/
decompile/v1.zip`; CLAUDE.md explains how to browse its content-addressed
blob store), not tuned by trial and error against one jump:

- On-ground horizontal acceleration per tick: `getSpeed()` (the
  `MOVEMENT_SPEED` attribute, sprint-modified via a flat +30% multiplier
  if sprinting -- base 0.1, sprint 0.13), for the default block friction
  this collapses exactly to that value via `getFrictionInfluencedSpeed`'s
  own `0.216 / blockFriction^3` term.
- Airborne horizontal acceleration per tick: a small constant `0.02`
  (`getFlyingSpeed()`, NOT sprint-scaled) -- holding forward while
  airborne barely changes trajectory; what actually determines jump
  distance is the velocity already built up at liftoff.
- Velocity decays by a friction factor every tick: `0.6 * 0.91 = 0.546`
  on ground (default block friction), `1.0 * 0.91 = 0.91` airborne.
- Liftoff vertical velocity: `LivingEntity.BASE_JUMP_POWER = 0.42`;
  gravity `0.08` blocks/tick², subtracted from vertical velocity every
  tick.

`JumpPhysics.planRunup(horizontalDistance)` brute-forces over run-up
tick counts (0-20) and sprint/walk, picks the option that lands at or
past the target distance, and among options landing similarly close
prefers FEWER run-up ticks (a walking jump landing 0.1 blocks more
precisely than a sprinting one, but needing several more run-up ticks
that don't physically fit a short platform, is not actually better --
found live as a real bug: minimizing landing-distance alone picked
options that walked the bot off a short platform's edge before ever
reaching the required tick count).

`LegsNavigateNode.walkTowardNavTarget` calls this once per fresh jump
waypoint's own run-up (frozen the tick run-up starts building, not
recomputed every tick against the shrinking live distance -- found live
as a real bug: recomputing live fed the planner a moving target and
`hasRunup` could never stably converge), and separately detects the real
edge of a platform (`hasGroundOneBlockAhead` -- no solid ground one block
further in the walking direction) to fire the jump on the platform's
actual last safe tick with whatever run-up was built, rather than
waiting for a fixed tick count a short runway may never allow reaching
at all.

This replaced an earlier fixed `SPRINT_RUNUP_TICKS_REQUIRED=5`-and-
always-sprint constant, which was tuned against exactly one jump scenario
and systematically overshot shorter ones (sprinting compounds horizontal
speed too fast for a short runway).

## Real bugs found building/tuning this suite

Beyond the launch mechanism and disposable-world work (see git history
for that earlier investigation), the schematic/jump-physics test suite
itself surfaced a long chain of real, previously-undiagnosed bugs --
worth keeping as a reference for the shape of bug this test tier is good
at catching:

- **`HeadStateMachine` never aimed during `!goto`.** `LEGS_NAVIGATING_
  STATES` was missing `LegsState.GOTO`, so Head never entered NAVIGATE
  while the bot walked toward a `!goto` target -- `facingWaypoint` was
  essentially random, and a `requiresJump` move's own facing-gate could
  never reliably fire. The single root cause behind an initial "bot does
  not jump at all" report.
- **`LegsStateMachine` had no `!stop` edge out of `GOTO`.** `!stop`
  correctly cleared other states but didn't abandon an in-progress
  `!goto`, leaving the bot still walking toward the old target.
- **`LegsGotoNode` never reset `PathTracker`/`SPRINT_RUNUP_TICKS` on
  entry**, unlike `LegsNavigateNode`'s own `onEnter` (which resets both).
  A fresh `!goto` could inherit a stale cached A* plan or a stale run-up
  counter from whatever walk ran immediately before it -- observed live
  as a perfectly alternating FAIL/PASS/FAIL/PASS pattern across identical
  repeated runs of the same jump.
- **`PathTracker.maybeReplan`'s self-drift staleness check was a single
  3D Euclidean distance.** After a fall off a jump's own landing spot,
  the bot could still be within the (generously large, 4.0-block)
  self-drift tolerance of the stale waypoint, so the tracker kept
  reusing a now-physically-impossible "jump back up onto the platform"
  plan forever, instead of ever replanning from the bot's real (fallen)
  position. Fixed by splitting into horizontal and vertical components,
  vertical capped at real max single-jump height (`Movements.
  MAX_STEP_HEIGHT`, ~1.2 blocks) and checked ONLY while `onGround` (found
  live as a second bug once the first fix landed: checking vertical
  drift while still airborne spuriously triggered `NO_PATH` mid-flight
  during an otherwise-successful jump, since any real jump arc swings
  more than 1.2 blocks vertically well before landing).
- **Jump input was asserted every tick `wantsToJump` stayed true,
  including while already airborne.** Vanilla reads repeated/held
  jump-key-down edges while airborne (then again on landing) as a
  double-tap-space, which toggles CREATIVE FLIGHT on by accident --
  confirmed live, the bot spontaneously started flying mid-test-run.
  Fixed by only ever asserting jump input while `onGround()`.
- **A jump waypoint "effectively already reached" (real distance within
  tolerance) could still fire a second jump.** `PathTracker.nextWaypoint`'s
  own reach check is `Math.floor(selfX/selfZ) == waypoint.x/z` -- an
  exact-block match that can miss a landing real-world close to the
  waypoint but on the wrong side of an integer boundary (observed live:
  landed at `z=-0.11`, `floor(-0.11)=-1`, waypoint `z=0`). The waypoint
  never got popped, `requiresJump` stayed true, and the very same tick
  landing completed, a second pointless "repositioning" jump fired and
  overshot the bot further away. Fixed by suppressing a fresh jump
  whenever real horizontal distance to the current waypoint is already
  within `ARRIVAL_DISTANCE_FOR_JUMP_SUPPRESSION` (0.5 blocks).
- **`LegsGotoNode`'s arrival check didn't require being on the ground.**
  A jump's own airborne arc can pass within `ARRIVAL_DISTANCE` of the
  target well before actually landing on it -- satisfying the old
  distance-only check exited `GOTO` back to `IDLE` (zero further
  steering) while still mid-flight, letting uncontrolled momentum alone
  decide where the bot actually came down.
- **...and even after requiring `onGround`, still needed near-zero
  residual velocity.** A landing that's genuinely on-target and on-ground
  can still carry real leftover horizontal momentum from the jump; `GOTO`
  exiting to `IDLE` the same tick (zero steering) let that one tick of
  slide carry the bot off a small (1-block-deep) landing platform,
  falling all the way to bedrock before the next position broadcast.
  Fixed by also requiring horizontal speed below `ARRIVAL_MAX_
  HORIZONTAL_SPEED` (0.02 blocks/tick) before counting as arrived --
  waits the 1-2 ticks real ground friction takes to bleed off jump
  momentum before ever handing control to `IDLE`.
- **`goto_with_waypoints`' hit-detection checked a waypoint's raw
  minimum-corner coordinate, not its real block center.** A physics-
  correct jump landing close to (but not exactly on) a path waypoint's
  own corner (~1.16 blocks away) was actually well within
  `WAYPOINT_RADIUS` of that same block's real center (~0.43 blocks) --
  the corner-based check produced a false "never walked through path
  waypoint" failure on an otherwise-correct jump. Fixed Python-side
  (`minebot/testing/actions.py`) to check against `(x+0.5, y+0.5,
  z+0.5)`.
- **`SelfPositionTracker`'s exact-value dedup meant a stopped bot stopped
  broadcasting entirely**, indistinguishable from a dead connection when
  polling `.current` -- see CLAUDE.md's own "Chat rate limiting"-adjacent
  guidance. Motivated adding `!query position` as an on-demand check
  distinct from the passive broadcast tracker (per explicit direction:
  no forced periodic heartbeat broadcast -- an on-demand query instead).
- **`Movements.getMoveDiagonal`'s own "stepping up while moving
  diagonally" branch hardcoded `requiresJump=false`**, despite using the
  exact same `MAX_STEP_HEIGHT`/`jumpHeightPenalty` budget
  `getMoveJumpUp`'s real jump-up move does (which correctly sets `true`).
  Vanilla's real auto-step-up assist is shallow (~0.6 blocks) and doesn't
  apply moving diagonally the way it does moving straight ahead, so
  `LegsNavigateNode` never fired a jump input for these moves at all --
  the bot just walked off the edge into open air. Also missing: headroom
  checks at the two orthogonal corner columns a diagonal jump's real arc
  swings through mid-flight (only the takeoff/landing columns were ever
  checked), which could plan a diagonal move through an obstruction
  neither endpoint's own column ever touched.
- **`ctx.player.onGround()` was called separately at several different
  points within the same `LegsNavigateNode.walkTowardNavTarget` tick**,
  and observed live to return DIFFERENT values across those calls within
  what should have been one consistent tick's worth of decision-making --
  `buildingRunup`'s own read came back `false` while a later read in the
  same tick's own diagnostic log came back `true`. This permanently
  starved `runupTicks` from ever incrementing past 0 even though every
  other input `buildingRunup` depends on was already satisfied, so the
  bot stood still forever, never building the run-up a `requiresJump`
  move needs before it can fire. Fixed by reading `onGround()` exactly
  once per tick and reusing that single value everywhere.
- **`SPRINT_RUNUP_TICKS`/`JUMP_PLAN` leaked across waypoint changes.**
  Both were only ever reset on node entry/exit, never when
  `PathTracker.nextWaypoint` actually advances to a different waypoint
  mid-walk -- a long flat run-up built approaching an ordinary
  (`requiresJump=false`) step immediately before a real jump waypoint in
  the same planned path left its own stale tick count (and a stale/null
  `JUMP_PLAN`) sitting in the blackboard the instant the waypoint
  changed. `hasRunup`'s own `jumpPlan == null` fallback (meant for "no
  jump needed, always ready") then wrongly read "no frozen plan yet" as
  "already have enough run-up," firing a real jump with ZERO actual
  approach distance, one full waypoint too early -- followed moments
  later by the real jump once the bot genuinely reached the true edge.
  Two real jump-key presses close enough together is itself vanilla's
  own double-tap-space-toggles-flying trigger (see the next entry).
  Fixed via a new `RUNUP_WAYPOINT` blackboard key that resets both
  `SPRINT_RUNUP_TICKS` and `JUMP_PLAN` the instant the current waypoint
  changes.
- **A world running in CREATIVE lets any double jump-key press --
  however it happens -- silently toggle flight instead of failing
  loudly.** Even after fixing the specific double-jump trigger above, a
  real jump sequence can still legitimately press jump twice in quick
  succession in other scenarios (retried jumps, replans, ...); creative
  makes every one of those a potential flight-toggle instead of a
  harmless no-op. A `goto_leaves_2` failure that looked like a permanent
  physics wedge (bot frozen mid-air, never moving again) was actually the
  bot flying: confirmed live via the `-Dminebot.debugNavigate` flag's own
  `navigate[collision]` line -- `deltaMovement.y == 0.0` (zero gravity)
  and `LocalPlayer.getAbilities().flying == true` from the moment
  collision logging started.

  First fixed narrowly (`TestWorldBootstrap` switching the freshly-
  bootstrapped pytest world's own player to survival right after they
  spawn) -- but that fix has no reach into a real LAN/`!runtest` session,
  a completely separate world this mod's own account can independently
  be in creative on. Generalized into a real `gamemode` wire command +
  `gamemode` query (see "Gamemode command/query" below) that EITHER entry
  point can use, then wired into both: `tests/integration/conftest.py`'s
  own session fixture and `TestRunner.run` (backing `!runtest`) each now
  save whatever gamemode the session actually started in, switch to
  survival before running any test, and restore the ORIGINAL gamemode
  (not a hardcoded value) once every test in that run/session has
  finished -- world-level `allowCommands` stays on throughout for
  `/fill`/`/tp` (operator command permission is independent of any
  individual player's own gamemode), so this makes the whole flight-
  toggle failure mode structurally impossible for a test run without
  permanently changing whatever gamemode a human was actually using the
  session for.
- **`on_ground` rides along on ordinary position broadcasts, flickering
  independently of any real jump.** `MinebotMod.maybeBroadcastPositionEvent`
  dedups broadcasts against a `PositionSnapshot` of `(x, y, z, yaw,
  pitch)` only -- `on_ground` is deliberately excluded, per that method's
  own comment, on the assumption that no Python code read it and
  including it would spam a broadcast every time it flipped independent
  of movement (e.g. brief ground-contact flicker while standing still on
  stairs/slabs). That assumption held until minebot's own
  `count_jumps`/`assert_jumps_done` (added for `goto_leaves_1`/
  `goto_leaves_2`) started trusting every `on_ground` transition in the
  broadcast stream as a real liftoff/land cycle. Since real movement
  changes x/y/z on nearly every tick anyway, `on_ground` still rides
  along on nearly every broadcast during a walk, and vanilla's own known
  ground-contact flicker at a block edge or on landing produces spurious
  `true`/`false` toggles with no real height change at all -- confirmed
  live: `goto_leaves_2` reported 3 jumps for a run where the mod's own
  `navigate[diag]` log showed `jump=true` exactly once. Fixed Python-side
  (`minebot/testing/actions.py::count_jumps`), not mod-side: requires a
  minimum real height gain (`JUMP_MIN_HEIGHT_GAIN`, 0.1 blocks) above the
  liftoff `y` before counting an airborne on_ground excursion as a jump,
  comfortably below `BASE_JUMP_POWER`'s (0.42) real liftoff height so
  every genuine jump still counts, while filtering ground noise that
  never leaves the floor.

## Gamemode command/query

`!gamemode <mode>` sends a real `/gamemode <mode> @s` (`MinebotMod`'s
"gamemode" wire case); `!query gamemode` reads the client's own real
current `GameType` off `Minecraft.getInstance().gameMode.getPlayerMode()`
-- NOT `LocalPlayer.getAbilities().instabuild`/`mayfly`, which only
reflect creative-DERIVED ability flags an operator could independently
toggle without a real gamemode change, so aren't a reliable stand-in for
"what gamemode is this player actually in right now."

`minebot/testing/actions.py::wait_for_gamemode(ctx, mode, timeout)` is
the shared send-then-poll primitive both test entry points use around
their own run (see the "double jump-key press" bug entry above for why):
sends the command, then polls the query until it reads back the
requested mode, the same "don't trust a fixed delay, confirm the change
actually landed" shape `teleport()`/`send_teleport` already use for a
real `/tp`. A test entry point wanting the SAME protection on some other
world only needs to call this once around its own run -- it's not tied
to either `conftest.py` or `TestRunner` specifically.

## Open questions / not yet decided

- CI integration for the pytest-driven tier -- still undecided; it needs
  a real GPU and a visible display (WSLg), so it may never run
  unattended in CI, only locally.
- Broader test suite scope beyond movement/pathfinding (bow-drawing,
  block breaking, door opening, item pickup) -- not started.

## Debugging a failure: check the replay before investigating code

Per explicit direction: when a test fails and a real per-tick replay
exists (or can be recorded), let the human look at it in
minebot-frontend's replay viewer BEFORE diving into the mod/backend
source to diagnose the failure -- a person watching the actual recorded
trajectory (bot hitbox, real position/velocity per axis, per-tick input
button state, the schematic's own wool-marker waypoints with their real
hit-radius, placed-block platforms -- see minebot's own `replay.ts`/
`ReplayViewer3D.tsx`) routinely spots the real mechanism (e.g. "it's
walking off the edge, not failing to jump") faster than re-deriving it
from log traces alone, and can redirect the investigation before time is
spent chasing the wrong hypothesis.

Practical shape: run the failing test with `MINEBOT_DEBUG_NAVIGATE=true`
(this repo's own `tests/integration/conftest.py` -- ONE flag now drives
both the replay recording and the verbose navigate[diag]/
navigate[collision] text log together, since a real debugging session
always wants both at once -- see that env var's own comment for the live
bug two separate flags caused: recording without the debug flag produced
a replay with 0 frames), start/confirm the backend (`./start.sh`,
`ObserverServer` on `MINEBOT_OBSERVER_PORT`/47894 serves `GET /replays`
independent of any mod client being connected) and the frontend (`pnpm
dev` in minebot-frontend, port 5173) are both running, then point the
human at the replay tab before proposing a fix.

## Resolved: `goto_jump_4` jump-arc physics (previous handoff, now fixed)

A prior session's handoff here described `goto_jump_4` reliably colliding
with its landing wall (`horizontalCollision=true`) despite 5 jump-physics
fixes (sprint-jump liftoff boost, sprint airborne accel, jump-waypoint
look-ahead, live run-up replanning, ledge-edge height check -- all still
correct and still in place). The actual root cause turned out to be
unrelated to `AIR_FRICTION_DECAY`/the velocity model the old handoff
suspected: **`LegsNavigateNode.hasGroundOneBlockAhead`** checked solid
ground a full block ahead of the player's own CENTER (not its leading
hitbox face), which fired `atLastSafeTick` -- and therefore the real
jump -- up to ~0.85 blocks too early, starving every jump of real run-up
distance regardless of how accurate the airborne model was. Fixed to
check the block directly under the leading face itself. A second,
independent bug (`JUMP_PLAN` being recomputed every airborne tick instead
of staying frozen from liftoff, since `runupTicks` resets to 0 the moment
`onGround` goes false) was found and fixed alongside it. `goto_jump_4`
now passes reliably.

## Handoff: `goto_stairs_1` intermittently fails (real void-world bug, not pathfinding)

`goto_stairs_1` (a 3-step full-height staircase, `oak_stairs` blocks atop
solid stone risers, `facing=south`, bot climbing south -- see its own
registration in `minebot/testing/tests.py`) intermittently times out or
mis-plans. Real root cause, confirmed via `AStar`'s own diagnostic
instrumentation during this investigation (since removed -- see below for
how to re-add it): **the disposable test world's own "void" floor is not
actually void one block below the schematic's intended floor layer.**

### What's confirmed

- Querying real blocks in the running world: `minecraft:stone` exists at
  every `(x, y=-61, z)` sampled, including far from the schematic
  (e.g. near `HOLDING` at `x=-7,z=-6`) -- this isn't schematic-placement
  bleed, it's a property of the generated world itself.
- `TestWorldBootstrap.createVoidWorldDimensions` reads `THE_VOID`
  preset's settings correctly (`voidSettings.getLayersInfo()` prints
  `[minecraft:air]`, confirmed via direct logging) and constructs a
  genuinely different `FlatLevelSource` instance via
  `WorldDimensions.replaceOverworldGenerator` (confirmed via object
  identity logging: a different `FlatLevelSource@...` before vs after the
  replace). The correct object is still what gets returned from the
  `Function<HolderLookup.Provider, WorldDimensions>` supplier passed into
  `WorldOpenFlows.createFreshLevel`.
- Despite that, the LIVE world still generates the default flat preset's
  real stone/dirt layers one block below the intended void floor. The
  substitution is lost somewhere inside `createFreshLevel`'s own internal
  dimension-baking/world-creation plumbing -- not reachable to inspect
  further without decompiled source (bytecode-only access here; a
  research pass also couldn't pin the exact internal mechanism with
  confidence).
- This phantom floor is NOT what makes A* prefer a detour over the real
  stairs route -- a person correctly pointed out the detour is a genuine
  dead end (nothing at y=-61 connects upward toward the goal without
  passing back through the stairs) and a correct search should just
  backtrack. What actually happens: the phantom floor multiplies the
  branching factor at every explored node (4-8 viable directions off the
  1-wide stairs column, vs 1-2 on it), so the search's 40ms per-tick
  budget (`PathTracker.PATHFINDING_TIMEOUT_MILLIS`'s own tick slice) gets
  consumed disproportionately exploring the larger, ultimately-dead-end
  flat region before it can also finish the short real stairs climb --
  confirmed with real numbers via a temporary `AStar` closed-node counter
  split by `x==1` (on the stairs column) vs off it: the first search
  closed only 11-38 total nodes before bailing out `PARTIAL`, and
  80-95% of those were off-column.
- A red herring ruled out during this investigation: an early "only 11
  nodes but still 40ms" measurement looked like a per-node performance
  bug, but that was entirely a side effect of a temporary diagnostic
  `LOGGER.info` call left inside `getBlock` itself (logging is
  expensive per-call at this frequency) -- with it removed, `getBlock`
  runs at ~0.3 microseconds/call and the search closes 500+ nodes in the
  same budget, still dominated by the off-column branching factor.

### What's NOT the bug (real, independently-confirmed fixes, keep these)

Three genuine bugs were found and fixed while chasing this, unrelated to
the void-floor issue and confirmed correct on their own:

1. **No real move type existed for a no-jump stairs step-up**
   (`Movements.getMoveStepUp`, new) -- every grid-Y-up move previously
   fell through to `getMoveJumpUp` unconditionally, regardless of whether
   the real height difference (via a stairs block's own `BlockInfo.
   height()`, already correctly modeling its lowered y+0.5 top surface)
   was small enough for real vanilla auto-step to handle with zero jump
   input. Facing-aware: only succeeds when the stairs block's own real
   `StairBlock.FACING` matches the bot's direction of travel (walking
   INTO the low/open step side) -- approaching from the solid riser side
   correctly still falls through to a real `getMoveJumpUp`. Two
   sub-bugs found and fixed while building this: the facing comparison
   was initially backwards (fixed to same-direction, not opposite, after
   real per-tick replay data disproved the first version), and the
   landing block itself was incorrectly passed through `safeOrBreak`
   (which expects a passable/air cell, not a solid landing surface --
   rejected every real stairs block outright until removed).
2. **`wantsToJump`'s stale `dy > 0.1` fallback** (`LegsNavigateNode`) --
   independently fired jump input any time the next waypoint was more
   than 0.1 blocks higher, regardless of the move's own `requiresJump`
   classification -- a pre-`Movements`-era heuristic that predates real
   move classification. Once `getMoveStepUp` started correctly producing
   `requiresJump=false` for a real no-jump step, this clause still fired
   jump input right alongside it (harmless in isolation -- vanilla's own
   auto-step silently absorbed the redundant press -- but wrong).
   Removed; `waypointRequiresJump` (the move's own real classification)
   is now the single authoritative signal.
3. **`litematic.py` discarded blockstate properties entirely** -- only a
   palette entry's `Name` was ever read, never its `Properties` compound,
   so `/fill` always placed every block (stairs included) in its bare
   registry-default orientation regardless of what was actually built in
   Litematica. Fixed: `SchematicBlock` now carries a `properties` dict
   and a `blockstate_command_suffix()` method; `place_schematic`'s
   `_fill_runs` keys a run by `(block, properties)` together (an oriented
   block never silently merges across a facing change) and emits the
   real `/fill ... block[key=value,...]` syntax.

### Re-adding the diagnostics if picking this up again

All temporary instrumentation used to reach the diagnosis above was
removed after confirming the finding (kept the codebase clean rather than
leaving dead diagnostic code behind) -- to reproduce:

- `AStar.compute()`: log `closedDataSet.size()`/`openHeap.size()`/
  `bestNode.data` on the `PARTIAL` bailout branch, plus a counter split
  by whatever column/region is relevant to the scenario under test.
- `Movements.getBlock`/`getNeighbors`: wrap with `System.nanoTime()`
  before/after and accumulate into static counters, logged once per
  `PathTracker.maybeReplan` call (after `astar.compute()` returns) --
  confirms real per-call cost isn't the bottleneck before assuming search
  breadth is.
- `TestWorldBootstrap.createVoidWorldDimensions`: log
  `voidSettings.getLayersInfo()`, and both `flatDimensions.overworld()`
  (before replace) vs `voidDimensions.overworld()` (after replace) by
  object identity, to confirm the substitution itself is correct up to
  the point it's handed to `createFreshLevel`.

### Concrete next steps, not yet done

- Confirm exactly where inside `WorldOpenFlows.createFreshLevel` the
  substituted `WorldDimensions` gets discarded -- needs real decompiled
  source (this session only had bytecode/`javap` access) or an
  interactive comparison against the real GUI flow
  (`CreateWorldScreen`/`WorldCreationContext`, which is known to work
  correctly in normal play) to see what it does differently.
- Once the real void-world bug is fixed, re-run `goto_stairs_1` --
  expected to pass reliably on the first search once `getMoveDiagonal`'s
  DROP branch and `getMoveDropDown`'s `getLandingBlock` scan both
  correctly find no floor off the stairs column at all.
- A pragmatic workaround exists if the engine bug proves hard to pin down
  further: `/fill air replace` a generous region below/around each
  schematic's own anchor as part of `place_schematic`'s setup, clearing
  any phantom floor regardless of root cause -- not yet implemented, per
  explicit direction to keep chasing the real bug first.
