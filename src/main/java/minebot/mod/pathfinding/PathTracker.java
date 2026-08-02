package minebot.mod.pathfinding;

import minebot.mod.MinebotMod;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.ArrayDeque;
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

    /**
     * (Re)computes a path toward (targetX, targetY, targetZ) if we don't
     * have a current one, the target has moved far enough that the
     * existing one is stale, or we ourselves have drifted too far from
     * the path we're supposedly following. Leaves the path empty (not an
     * exception) when we don't have block data under our own feet yet or
     * no path is found -- callers should fall back to raw target-following
     * in that case, same as the Python port, rather than freezing.
     */
    public void maybeReplan(
        final ClientLevel level, final double selfX, final double selfY, final double selfZ,
        final double targetX, final double targetY, final double targetZ, final double stopDistance
    ) {
        if (pathComputedFor != null && !currentPath.isEmpty()) {
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
        currentPath.clear();

        int startX = (int) Math.floor(selfX);
        int startY = (int) Math.floor(selfY);
        int startZ = (int) Math.floor(selfZ);

        if (!level.isLoaded(new net.minecraft.core.BlockPos(startX, startY - 1, startZ))) {
            // No block data under our own feet -- e.g. chunks haven't
            // loaded yet. Pathfinding can't do better than guessing here,
            // so don't pretend to have a plan.
            return;
        }

        Movements movements = new Movements(level);
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
            // fallback already stops us correctly once close enough.
            return;
        }

        if ((result.status() == AStar.Status.SUCCESS || result.status() == AStar.Status.PARTIAL) && !result.path().isEmpty()) {
            currentPath.addAll(result.path());
            MinebotMod.LOGGER.debug(
                "pathfinding: planned path of {} waypoints (status={}, cost={}): {}",
                result.path().size(), result.status(), result.cost(), result.path()
            );
        } else {
            MinebotMod.LOGGER.debug("pathfinding: no path found (status={}), falling back to raw target-following", result.status());
        }
    }

    /**
     * Pops and returns waypoints we've already reached, then returns the
     * next one still ahead of us -- or null once the path is exhausted
     * (the caller falls back to the target's raw position, which by then
     * should be within stopDistance anyway since the path was planned
     * toward a GoalNear around it).
     */
    public Move nextWaypoint(final double selfX, final double selfY, final double selfZ) {
        while (!currentPath.isEmpty()) {
            Move waypoint = currentPath.peekFirst();
            boolean reached = Math.floor(selfX) == waypoint.x
                && Math.floor(selfZ) == waypoint.z
                && Math.abs(selfY - waypoint.y) < 1.0;
            if (!reached) {
                return waypoint;
            }
            currentPath.pollFirst();
        }
        return null;
    }

    public void reset() {
        currentPath.clear();
        pathComputedFor = null;
    }
}
