package minebot.mod.pathfinding;

import minebot.mod.MinebotMod;

/**
 * Debug helper: prints a top-down grid of Movements.getBlock's own
 * classification for the area between two points, so we can see exactly
 * what the pathfinder itself believes is there without needing the user to
 * describe the room -- added live while chasing a case where a route
 * detoured through a completely different corridor instead of a much
 * shorter direct line, and the room layout wasn't visible from here.
 *
 * One character per column, at a single fixed y (the start's feet level):
 *  '#' solid/physical (including stairs/slabs -- stand-on-able like a
 *  floor), ' ' safe/passable (open air, liquid, ladder), 'D' an open
 *  door (walkable), 'd' a closed door (DoorOpener will open it as the
 *  bot approaches), '?' unknown (unloaded chunk), 'X' something else not
 *  walkable (safe but no floor beneath, or blocked-but-not-solid).
 */
public final class BlockDump {
    private BlockDump() {
    }

    // A generous margin beyond the two endpoints, but capped so a target
    // far across the map (e.g. mid-teleport, or before the first real
    // path segment) can't trigger a huge block-query grid every replan.
    private static final int MAX_SPAN = 60;
    // Padding around the tight start/target bounding box -- without this,
    // a replan between two nearby points only dumps a sliver right on top
    // of them, missing the walls just outside that box that are the whole
    // reason a detour exists. Found live: the raw start/target box showed
    // an unhelpful 2x3 grid of all-open blocks even though a real wall a
    // couple blocks further out was what forced the route around.
    private static final int PADDING = 4;

    public static void logGrid(final Movements movements, final int x0, final int y, final int z0, final int x1, final int z1) {
        int minX = Math.min(x0, x1) - PADDING;
        int maxX = Math.max(x0, x1) + PADDING;
        int minZ = Math.min(z0, z1) - PADDING;
        int maxZ = Math.max(z0, z1) + PADDING;

        if (maxX - minX > MAX_SPAN || maxZ - minZ > MAX_SPAN) {
            MinebotMod.LOGGER.debug("pathfinding: skipping block grid dump, span too large ({}x{})", maxX - minX, maxZ - minZ);
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("pathfinding: block grid at y=").append(y)
            .append(" x=[").append(minX).append("..").append(maxX)
            .append("] z=[").append(minZ).append("..").append(maxZ).append("]\n");

        for (int z = minZ; z <= maxZ; z++) {
            sb.append(String.format("%5d: ", z));
            for (int x = minX; x <= maxX; x++) {
                BlockInfo block = movements.getBlock(x, y, z, 0, 0, 0);
                char c;
                if (!block.known) {
                    c = '?';
                } else if (block.closedDoor) {
                    c = 'd';
                } else if (block.door) {
                    c = 'D'; // open door
                } else if (block.physical) {
                    c = '#';
                } else if (block.safe) {
                    c = ' ';
                } else {
                    c = 'X';
                }
                sb.append(c);
            }
            sb.append('\n');
        }

        MinebotMod.LOGGER.debug(sb.toString());
    }
}
