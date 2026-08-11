package minebot.mod.pathfinding;

import minebot.mod.MinebotMod;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;

/**
 * Keeps a planned A* path fresh against a moving target and hands out the
 * next waypoint to aim at -- direct port of minebot's earlier Python
 * pathfinding integration (pure-protocol-backend branch,
 * bot/movement.py's _maybe_replan_path/_next_waypoint), the piece that
 * was missing from the mod's first goto/follow implementation (it walked
 * in a straight line toward the target's raw (x, y, z) and only relied on
 * real gravity/step-height to handle the vertical difference -- which
 * works for a small ledge but not a real drop/climb requiring an actual
 * route, confirmed live: the bot stood at the edge of a floor instead of
 * finding a way down to a lower one).
 *
 * "Fresh" means fresh against the CALLER'S constraints too, not just the
 * target's position -- stopDistance and avoidLiquid (see maybeReplan's
 * own docstring) are both baked into the plan itself (via GoalNear and
 * Movements.avoidLiquid respectively), so a change to either one is
 * staleness on its own, independent of whether the target or the bot
 * actually moved (see stopDistanceComputedFor/avoidLiquidComputedFor's
 * own docstrings for the live bugs this guards against).
 */
public final class PathTracker {
    private static final long PATHFINDING_TIMEOUT_MILLIS = 1000;
    // Re-run A* only once the target has moved this far from where the
    // last computed path was aimed -- matches GoalFollow.hasChanged()'s
    // role in mineflayer-pathfinder: avoids recomputing a path every
    // single tick for a target that's barely moved.
    private static final double REPLAN_DISTANCE = 2.0;
    // If we're farther than this from the waypoint we're supposedly
    // walking toward, the plan is stale relative to *us*, not just the
    // target -- e.g. died and respawned elsewhere, got knocked off a
    // ledge, or teleported. Found live: after a death mid-follow, the bot
    // kept "detecting" a door on the other side of the map from its actual
    // (respawned) position forever, since only the target's movement was
    // ever checked.
    private static final double SELF_DRIFT_REPLAN_DISTANCE = 4.0;

    private final Deque<Move> currentPath = new ArrayDeque<>();
    private double[] pathComputedFor; // {x, y, z}, or null if no path has been computed yet
    // The stopDistance the current path was actually planned for --
    // GoalNear bakes stopDistance into the plan itself (see setAttack's
    // own call site), so a path computed for one stopDistance can walk
    // straight past a *different*, later one with nothing here ever
    // noticing, since target-moved/self-drift alone say nothing about
    // this. Reported live: !attack initially plans with ControlState.
    // setAttack's fixed 2.5 default (melee range) before tickAttack's own
    // per-tick weapon check has a chance to raise it to bow range -- a
    // stationary target (targetMoved always ~0) and the bot staying on
    // its own path (selfDrift always low) meant the stale melee-range
    // path was never replanned even after stopDistance changed to bow
    // range moments later, so the bot walked all the way into melee
    // range with a bow equipped and never actually backed off to shoot
    // from range.
    private double stopDistanceComputedFor = Double.NaN;
    // Same staleness role as stopDistanceComputedFor above -- a path
    // planned with avoidLiquid=false is not valid to keep reusing once a
    // caller asks with avoidLiquid=true (e.g. LegsFleeNode kicking in
    // right after LegsNavigateNode was using this same shared tracker --
    // see LegsNavigateNode's own class docstring for why one PathTracker
    // instance is shared across every Legs node that walks), and vice
    // versa.
    private boolean avoidLiquidComputedFor;
    // True only immediately after a real, COMPLETED search genuinely
    // found no route at all (AStar.Status.NO_PATH or TIMEOUT with an
    // empty path) -- see maybeReplan's own body for exactly where this is
    // set/cleared. Deliberately NOT the same thing as "currentPath is
    // empty": a fresh PathTracker (nothing computed yet) and "we're
    // already at the goal" (AStar.Status.SUCCESS with a trivially empty
    // path, see maybeReplan's own early-return comment) both also leave
    // currentPath empty, and neither of those means "genuinely blocked" --
    // conflating them would make walkTowardNavTarget's own noPath() gate
    // (see its own docstring for why it exists) refuse to walk in cases
    // that were never actually blocked at all. Reset to false the instant
    // a later replan succeeds, so a temporarily-unreachable target (e.g.
    // a moving FOLLOW target that walks back into range) isn't stuck
    // "blocked" forever once a real route exists again.
    private boolean lastSearchFoundNoPath;

    /**
     * (Re)computes a path toward (targetX, targetY, targetZ) if we don't
     * have a current one, the target has moved far enough that the
     * existing one is stale, or we ourselves have drifted too far from
     * the path we're supposedly following. Leaves the path empty (not an
     * exception) when we don't have block data under our own feet yet or
     * no path is found -- callers should fall back to raw target-following
     * in that case, same as the Python port, rather than freezing.
     *
     * `avoidLiquid` is forwarded straight to Movements (see its own
     * docstring) -- LegsFleeNode passes true so a retreat route never
     * plans through water, every other caller passes false (unchanged
     * behavior: water merely discouraged, not disallowed).
     */
    public void maybeReplan(
        final ClientLevel level, final LocalPlayer player,
        final double selfX, final double selfY, final double selfZ,
        final double targetX, final double targetY, final double targetZ, final double stopDistance,
        final boolean avoidLiquid
    ) {
        if (pathComputedFor != null && !currentPath.isEmpty() && stopDistance == stopDistanceComputedFor
            && avoidLiquid == avoidLiquidComputedFor) {
            double dx = pathComputedFor[0] - targetX;
            double dy = pathComputedFor[1] - targetY;
            double dz = pathComputedFor[2] - targetZ;
            double targetMoved = Math.sqrt(dx * dx + dy * dy + dz * dz);

            Move nextWaypoint = currentPath.peekFirst();
            double sdx = nextWaypoint.x - selfX;
            double sdy = nextWaypoint.y - selfY;
            double sdz = nextWaypoint.z - selfZ;
            double selfDrift = Math.sqrt(sdx * sdx + sdy * sdy + sdz * sdz);

            if (targetMoved <= REPLAN_DISTANCE && selfDrift <= SELF_DRIFT_REPLAN_DISTANCE) {
                return; // existing path is still aimed close enough to the target, and we're still on it
            }
        }

        pathComputedFor = new double[]{targetX, targetY, targetZ};
        stopDistanceComputedFor = stopDistance;
        avoidLiquidComputedFor = avoidLiquid;
        currentPath.clear();

        int startX = (int) Math.floor(selfX);
        int startY = (int) Math.floor(selfY);
        int startZ = (int) Math.floor(selfZ);

        if (!level.isLoaded(new net.minecraft.core.BlockPos(startX, startY - 1, startZ))) {
            // No block data under our own feet -- e.g. chunks haven't
            // loaded yet. Pathfinding can't do better than guessing here,
            // so don't pretend to have a plan. NOT the same as a real
            // completed NO_PATH search (see lastSearchFoundNoPath's own
            // docstring) -- a transient missing-chunk-data condition
            // shouldn't permanently block movement the way a genuinely
            // unreachable target should.
            lastSearchFoundNoPath = false;
            return;
        }

        Movements movements = new Movements(level, player);
        movements.avoidLiquid = avoidLiquid;
        // Digging disabled unconditionally -- per explicit direction,
        // mining-through-obstacles pathfinding was a persistent, hard-to-
        // fix source of stuck bots (bounce loops between jump/mine states,
        // permanently-unreachable dig stances with no line of sight,
        // waypoints that could hold the bot forever with no exclusion
        // mechanism to route around a bad one -- see this file's own git
        // history and BlockBreaker/HandsMineNode's docstrings for the
        // full chain of live-reported bugs this caused), and a real
        // walkable route without digging is available essentially always
        // in practice. Movements.allowDig already existed and safeOrBreak
        // already respects it (returns BLOCKED for any non-safe block
        // instead of coring a dig cost) -- just never had a caller opt in
        // until now.
        movements.allowDig = false;
        Move start = new Move(startX, startY, startZ, 0.0);
        GoalNear goal = new GoalNear(targetX, targetY, targetZ, stopDistance);
        AStar astar = new AStar(start, movements::getNeighbors, goal::heuristic, goal::isEnd, PATHFINDING_TIMEOUT_MILLIS);
        AStar.Result result = astar.compute();

        if (MinebotMod.LOGGER.isDebugEnabled()) {
            BlockDump.logGrid(movements, startX, startY, startZ, (int) Math.floor(targetX), (int) Math.floor(targetZ));
            if ((int) Math.floor(targetY) != startY) {
                BlockDump.logGrid(movements, startX, (int) Math.floor(targetY), startZ, (int) Math.floor(targetX), (int) Math.floor(targetZ));
            }
        }

        if (result.status() == AStar.Status.SUCCESS && result.path().isEmpty()) {
            // Not a failure -- the start position already satisfies
            // GoalNear.isEnd() (we're already within stopDistance), so A*
            // succeeded trivially with a zero-length path. Leave
            // currentPath empty; resolveMovementIntent's raw-target
            // fallback already stops us correctly once close enough. NOT
            // a NO_PATH -- explicitly clear the flag (a REAL success, just
            // trivially short).
            lastSearchFoundNoPath = false;
            return;
        }

        if ((result.status() == AStar.Status.SUCCESS || result.status() == AStar.Status.PARTIAL) && !result.path().isEmpty()) {
            currentPath.addAll(result.path());
            lastSearchFoundNoPath = false;
            // Temporarily promoted from debug to info -- this client's
            // default log4j config filters debug output entirely (see
            // BlockBreaker's own per-tick diagnostic for the same
            // reasoning), and a live report of a bot repeatedly jumping
            // at an unreachable gap instead of using a real, longer
            // walkable detour needs direct visibility into what path (if
            // any) A* actually chose, not just inference from position
            // traces.
            MinebotMod.LOGGER.info(
                "pathfinding: planned path of {} waypoints (status={}, cost={}): {}",
                result.path().size(), result.status(), result.cost(), result.path()
            );
        } else {
            // A genuine, completed search that found no route at all --
            // see lastSearchFoundNoPath's own docstring for why
            // walkTowardNavTarget now stops instead of blindly walking
            // straight at the raw target here (per explicit direction:
            // "walking in straight line towards the target is undesired
            // behavior, NO_PATH should block the bot" -- the old
            // straight-line fallback risked walking off ledges/into
            // obstacles/into hazards exactly when pathfinding had already
            // determined there was no safe way to actually get there).
            lastSearchFoundNoPath = true;
            MinebotMod.LOGGER.info("pathfinding: no path found (status={}), blocking movement", result.status());
        }
    }

    /** True only right after a real, completed search found no route at all -- see the field's own docstring. */
    public boolean lastSearchFoundNoPath() {
        return lastSearchFoundNoPath;
    }

    /**
     * Pops and returns waypoints we've already reached, then returns the
     * next one still ahead of us -- or null once the path is exhausted
     * (the caller falls back to the target's raw position, which by then
     * should be within stopDistance anyway since the path was planned
     * toward a GoalNear around it).
     *
     * `onGround` gates how strict the Y-reached check is. Reported live:
     * a straight-up staircase climb has several consecutive waypoints
     * sharing the exact same (x, z) column, differing only by y (one
     * step higher each time) -- so the old check's only real
     * discriminator between "reached step 3" and "reached step 5" was
     * `Math.abs(selfY - waypoint.y) < 1.0`. Mid-air during a jump's
     * vertical arc, selfY swings by more than a full block within a
     * handful of ticks (real gravity, not error) -- easily satisfying
     * that loose tolerance against several different waypoints in quick
     * succession while genuinely still airborne, well before actually
     * landing on any of them. Each spurious match popped a waypoint the
     * bot hadn't really reached, racing the plan far ahead of the bot's
     * real position -- observed live as the bot climbing correctly up to
     * jump height, the aim then reversing 180 degrees mid-air (chasing
     * whatever waypoint got spuriously reached next), and falling all
     * the way back down to retry the same climb, repeatedly, instead of
     * ever completing the final jump onto the target platform. While
     * airborne (`!onGround`), require selfY to be within 0.1 of the
     * waypoint's own y (a real landed foothold, not "somewhere in the
     * arc that happens to be within a block") before counting it
     * reached; once back on solid ground, the original 1.0 tolerance is
     * fine (real per-tick Y jitter while walking is tiny, nothing like a
     * jump arc's swing).
     */
    /**
     * `level` is used only to double-check a waypoint carrying a real
     * toBreak list (see Move's own docstring) hasn't been popped as
     * "reached" while any of its blocks are still solid -- reported live:
     * the X/Z/Y proximity check alone doesn't know digging is even
     * involved, so a bot standing right under a jump-up waypoint's own
     * column (X/Z already matching, only Y still off because the jump
     * keeps failing) got that SAME waypoint popped off as reached the
     * instant Y drifted within tolerance mid-jump-arc, before the leaves
     * blocking it were actually broken through. The next tick's replan
     * then re-derived the identical jump move with toBreak populated
     * again from scratch (A* doesn't remember partial mining progress
     * either), HandsMineNode's own MINE state exited and re-entered
     * (WAYPOINT_TO_BREAK genuinely went empty then non-empty again), and
     * BlockBreaker's real destroy progress reset to zero every single
     * cycle (its own class docstring already documents why any target
     * switch does this) -- neither leaf block ever finished breaking, a
     * real self-sustaining stuck loop. A waypoint with a non-empty
     * toBreak is now only ever "reached" once ALL its listed blocks are
     * confirmed air, regardless of how closely X/Y/Z otherwise match.
     */
    public Move nextWaypoint(final ClientLevel level, final double selfX, final double selfY, final double selfZ, final boolean onGround) {
        double yTolerance = onGround ? 1.0 : 0.1;
        while (!currentPath.isEmpty()) {
            Move waypoint = currentPath.peekFirst();
            boolean positionReached = Math.floor(selfX) == waypoint.x
                && Math.floor(selfZ) == waypoint.z
                && Math.abs(selfY - waypoint.y) < yTolerance;
            boolean diggingDone = waypoint.toBreak.stream().allMatch(pos -> level.getBlockState(pos).isAir());
            if (!positionReached || !diggingDone) {
                return waypoint;
            }
            currentPath.pollFirst();
        }
        return null;
    }

    public void reset() {
        currentPath.clear();
        pathComputedFor = null;
        stopDistanceComputedFor = Double.NaN;
        lastSearchFoundNoPath = false;
    }

    /** Read-only view of the currently planned path, in walk order. */
    public Collection<Move> waypoints() {
        return currentPath;
    }
}
