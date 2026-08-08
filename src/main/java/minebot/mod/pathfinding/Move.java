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
 *
 * `requiresJump` marks a move that can only actually be executed with a
 * real jump input (a step-up/jump-up move, OR any parkour-forward move --
 * see Movements.getMoveJumpUp/getMoveParkourForward, the only two
 * producers that ever pass true) -- LegsNavigateNode's own execution used
 * to infer "should I jump" purely from the waypoint being higher than the
 * bot's current Y (dy > 0.1), which is wrong for a parkour move that lands
 * level or even slightly BELOW takeoff (getMoveParkourForward's "gap,
 * same height" and "gap, drop down" branches): with no jump input queued
 * at all, the bot just walked off the edge with no forward momentum and
 * fell straight into the gap it was supposed to clear, live-confirmed as
 * a parkour move A* planned and then failed every single time it was
 * attempted. Distinct from dy: a plain step-up already gets a jump input
 * from dy > 0.1 correctly, but a LEVEL parkour gap needs one despite dy
 * being ~0, which is exactly the case this field exists to cover.
 *
 * `digStance` is the EXACT position Movements.hasDigLineOfSight assumed
 * the bot would be standing at while breaking through toBreak (null
 * whenever toBreak is empty -- nothing to stand anywhere for). This is
 * the move's own ORIGIN (the node digging happens FROM, before the move
 * completes -- e.g. a jump-up's own headroom obstruction is cleared
 * before leaving the ground), which is NOT the same position as (x, y, z)
 * above (the move's real DESTINATION/landing spot) -- reported live: an
 * earlier version of HandsMineNode's own arrival gate compared the bot's
 * position against the destination waypoint instead, which the bot can
 * never actually reach until digging finishes in the first place (a real
 * chicken-and-egg deadlock, similar in shape to the still-needs-digging
 * jump-suppression bug this docstring's sibling investigation already
 * fixed in LegsNavigateNode). Storing the real stance directly here,
 * rather than trying to reconstruct "wherever the bot roughly was at
 * planning time" downstream, removes any ambiguity about which position
 * a toBreak entry's own visibility was actually verified from.
 */
public final class Move {
    public final int x;
    public final int y;
    public final int z;
    public final double cost;
    public final List<BlockPos> toBreak;
    public final boolean requiresJump;
    public final BlockPos digStance;

    public Move(final double x, final double y, final double z, final double cost) {
        this(x, y, z, cost, Collections.emptyList(), false, null);
    }

    public Move(final double x, final double y, final double z, final double cost, final List<BlockPos> toBreak) {
        this(x, y, z, cost, toBreak, false, null);
    }

    public Move(final double x, final double y, final double z, final double cost, final boolean requiresJump) {
        this(x, y, z, cost, Collections.emptyList(), requiresJump, null);
    }

    public Move(final double x, final double y, final double z, final double cost, final List<BlockPos> toBreak, final boolean requiresJump) {
        this(x, y, z, cost, toBreak, requiresJump, null);
    }

    public Move(final double x, final double y, final double z, final double cost, final List<BlockPos> toBreak, final boolean requiresJump, final BlockPos digStance) {
        this.x = (int) Math.floor(x);
        this.y = (int) Math.floor(y);
        this.z = (int) Math.floor(z);
        this.cost = cost;
        this.toBreak = toBreak;
        this.requiresJump = requiresJump;
        this.digStance = digStance;
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
