# mc/1.20.1 — WIP status

This branch was cut from `master` @ `0966acd` on 2026-09-22, per the plan at
`/home/colaila/.claude/plans/lets-add-support-for-quirky-yao.md` (in the
minebot-backend Claude session). **No port work has happened yet** — this
branch is currently identical to `master` except for this file.

## What's actually done so far (all on `master`, not this branch)

- Part 0 of the plan: `master` fast-forwarded to include all of the former
  `state-machine-architecture` branch (statemachine/ peer-SM architecture,
  task queue, pathfinding overhaul, mixins, TestWorldBootstrap, WaypointFinder,
  replay recording) — see `git log master`.
- **Part 1 (prepare trunk for the branch split) has NOT been done yet**:
  - 1a: pathfinder `NavWorld` interface isolation (Movements/BlockBreaker/
    PathTracker/Move/BlockFinder/BlockInfo/WaypointClassifier still call
    `net.minecraft.*` directly, no `VanillaNavWorld` adapter exists).
  - 1b: `hello` event does NOT yet carry `mc_version`/`data_version`;
    `BuildInfo`/`generateBuildInfo` does NOT yet write `mc_target`.
  - 1c: mixin `compatibilityLevel` still `JAVA_21` (untouched).
- Backend (`minebot-backend` repo) Part 3 (consuming the handshake) also NOT
  started — `mod_version.py` still only compares `commit`.

## Recommended order for this branch

Do Part 1 on `master` FIRST (it's branch-agnostic prep — the NavWorld
isolation and hello-event fields are meant to land on trunk so both `mc/1.20.1`
and any later branch inherit them), merge/rebase this branch on top, **then**
start the actual 1.20.1 port (gradle.properties/build.gradle to Yarn+Java 17,
compiler-driven mappings flip, the per-file rewrites listed in the plan's
Part 2b: PathVisualizer/StatusHud/ConfigScreen render-API rewrites,
ItemBreakMixin signature, WaypointFinder Locator-Bar stub, TestWorldBootstrap
worldgen port, BlockBreaker reflection field names).

If Part 1 keeps getting deferred, this branch can also start the mechanical
mappings-flip port directly against current `master` and rebase onto the
NavWorld refactor later — more rework, but doesn't block on Part 1 landing.

See the full plan doc for details on every subpart:
`/home/colaila/.claude/plans/lets-add-support-for-quirky-yao.md`
(Parts 1 and 2 specifically).
