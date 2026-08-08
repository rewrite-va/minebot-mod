package minebot.mod.pathfinding;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.Vec3;

/**
 * Finds the nearest block matching a given predicate within a search
 * radius -- the block-scan counterpart to EntityFinder (see its own
 * docstring for why that one isn't under pathfinding/: this one IS, since
 * scanning for a block to walk to is itself a movement-goal concern, not
 * a target-resolution query the way finding a fight/follow target is).
 * First real block-scan helper in the mod -- referenced but never
 * actually built by an earlier PENDING.md draft; built now for !sleep's
 * "find the nearest bed" need.
 *
 * Iterates a cube of BlockPos via BlockPos.betweenClosed (there's no
 * getEntitiesOfClass-style AABB query for blocks the way EntityFinder
 * uses for entities -- blocks aren't loaded/queried that way), filtering
 * by real distanceToSqr against radius^2 same as EntityFinder does, since
 * the cube's corners are farther than `radius` in true (Euclidean)
 * distance. Deliberately scoped by radius rather than scanning the whole
 * loaded world -- a cube this size (e.g. 32 blocks -> ~262k positions) is
 * already the practical bound of "reasonably nearby", matching
 * EntityFinder's own radius-scoped shape.
 */
public final class BlockFinder {
    private BlockFinder() {
    }

    /** Returns the nearest bed block's position (either half of the two-block bed, whichever this scan reaches first at an equal distance -- BedBlock.getBedOrientation/the HEAD/FOOT half doesn't matter here, since sleeping works from either half) within `radius` blocks of `center`, or null if none is found. */
    public static BlockPos findNearestBed(final ClientLevel level, final Vec3 center, final double radius) {
        int r = (int) Math.ceil(radius);
        double radiusSquared = radius * radius;
        BlockPos centerPos = BlockPos.containing(center);

        BlockPos nearest = null;
        double nearestDistanceSquared = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(centerPos.offset(-r, -r, -r), centerPos.offset(r, r, r))) {
            if (!(level.getBlockState(pos).getBlock() instanceof BedBlock)) {
                continue;
            }
            double distanceSquared = Vec3.atCenterOf(pos).distanceToSqr(center);
            if (distanceSquared > radiusSquared) {
                continue;
            }
            if (distanceSquared < nearestDistanceSquared) {
                nearest = pos.immutable();
                nearestDistanceSquared = distanceSquared;
            }
        }

        return nearest;
    }
}
