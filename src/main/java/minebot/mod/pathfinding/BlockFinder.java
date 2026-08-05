package minebot.mod.pathfinding;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.Collections;
import java.util.Set;

/**
 * Finds the nearest block of a given registry type ("minecraft:oak_log",
 * "minecraft:stone", ...) within a search radius -- backs the !find/
 * !searchForBlock command. One-shot/on-demand, unlike the rest of
 * pathfinding/ (which runs every tick against the current goal); this is
 * only ever called in response to an explicit command.
 *
 * BlockPos.findClosestMatch is a real built-in "nearest match" primitive
 * (confirmed via decompiled bytecode: it delegates to withinManhattan,
 * which iterates in increasing Manhattan-distance shells, so the first
 * predicate match really is the nearest by that metric) -- not something
 * this class needs to hand-roll a spiral/BFS search for itself.
 */
public final class BlockFinder {
    private BlockFinder() {
    }

    /**
     * Returns the nearest loaded block position matching `blockType`
     * (its full registry id, e.g. "minecraft:oak_log") within `radius`
     * blocks of `center` (used for both horizontal and vertical search
     * radius), or null if none is found within range or the search
     * exhausts without a match.
     *
     * Manhattan-nearest, not Euclidean-nearest -- findClosestMatch's own
     * search order guarantees this, and for a one-shot "go find me some
     * X" command the difference is not worth a custom Euclidean-sorted
     * scan.
     */
    public static BlockPos findNearestBlock(final ClientLevel level, final BlockPos center, final String blockType, final int radius) {
        return findNearestBlock(level, center, blockType, radius, Collections.emptySet());
    }

    /**
     * Same as the three-arg overload, but skips any position in
     * `excluded` -- backs !collect's give-up-and-retry logic
     * (MinebotMod.tickCollect): a block found by pure distance can be
     * genuinely unreachable (behind a wall from every angle the
     * pathfinder considers within stopDistance, so BlockBreaker's
     * line-of-sight check never passes), and without this, re-searching
     * after giving up on it would just find the exact same block again
     * every time (findClosestMatch is fully deterministic for a fixed
     * center/predicate) -- an infinite abandon loop instead of trying a
     * different one.
     */
    public static BlockPos findNearestBlock(
        final ClientLevel level, final BlockPos center, final String blockType, final int radius, final Set<BlockPos> excluded
    ) {
        return BlockPos.findClosestMatch(
            center, radius, radius,
            pos -> !excluded.contains(pos)
                && level.isLoaded(pos)
                && BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString().equals(blockType)
        ).orElse(null);
    }
}
