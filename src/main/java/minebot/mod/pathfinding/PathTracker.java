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

    private final Deque<Move> currentPath = new ArrayDeque<>();
    private double[] pathComputedFor; // {x, y, z}, or null if no path has been computed yet

    /**
     * (Re)computes a path toward (targetX, targetY, targetZ) if we don't
     * have a current one, or the target has moved far enough that the
     * existing one is stale. Leaves the path empty (not an exception) when
     * we don't have block data under our own feet yet or no path is
     * found -- callers should fall back to raw target-following in that
     * case, same as the Python port, rather than freezing.
     */
    public void maybeReplan(
        final ClientLevel level, final double selfX, final double selfY, final double selfZ,
        final double targetX, final double targetY, final double targetZ, final double stopDistance
    ) {
        if (pathComputedFor != null) {
            double dx = pathComputedFor[0] - targetX;
            double dy = pathComputedFor[1] - targetY;
            double dz = pathComputedFor[2] - targetZ;
            double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (moved <= REPLAN_DISTANCE && !currentPath.isEmpty()) {
                return; // existing path is still aimed close enough to the target
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

        if ((result.status() == AStar.Status.SUCCESS || result.status() == AStar.Status.PARTIAL) && !result.path().isEmpty()) {
            currentPath.addAll(result.path());
            MinebotMod.LOGGER.debug("pathfinding: planned path of {} waypoints (status={})", result.path().size(), result.status());
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
