package minebot.mod.pathfinding;

import net.minecraft.core.BlockPos;

import java.util.Collections;
import java.util.List;

/**
 * One A* graph node: a whole-block position the bot could stand at.
 * Direct port of minebot's earlier Python pathfinding port
 * (pure-protocol-backend branch, itself a port of
 * mineflayer-pathfinder@2.4.5's lib/move.js). `cost` is the edge cost of
 * the move that produced this node, added to the parent's g by the
 * search -- not a total path cost by itself.
 *
 * `toBreak` lists the block(s), if any, that must be dug through to
 * actually execute this move -- mirrors move.js's own `toBreak` field
 * (minus its sibling `toPlace`/`remainingBlocks`, which this port
 * deliberately drops; see Movements.java's docstring for why placement
 * stays out of scope). Empty (not null) for every walk/climb/parkour move
 * that doesn't require digging, so callers never need a null check.
 */
public final class Move {
    public final int x;
    public final int y;
    public final int z;
    public final double cost;
    public final List<BlockPos> toBreak;

    public Move(final double x, final double y, final double z, final double cost) {
        this(x, y, z, cost, Collections.emptyList());
    }

    public Move(final double x, final double y, final double z, final double cost, final List<BlockPos> toBreak) {
        this.x = (int) Math.floor(x);
        this.y = (int) Math.floor(y);
        this.z = (int) Math.floor(z);
        this.cost = cost;
        this.toBreak = toBreak;
    }

    public long hash() {
        // Packs (x, y, z) into a single long for cheap hashing/equality in
        // the search's open/closed sets -- real Minecraft coordinates fit
        // comfortably within 21 bits per axis (+-1,048,575), far beyond any
        // reachable world position.
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (long) (z & 0x1FFFFF);
    }

    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof Move other)) {
            return false;
        }
        return x == other.x && y == other.y && z == other.z;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(hash());
    }

    @Override
    public String toString() {
        return "Move(" + x + ", " + y + ", " + z + ", cost=" + cost + ")";
    }
}
