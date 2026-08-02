package minebot.mod.pathfinding;

/**
 * A single queried block's pathfinding-relevant classification -- mirrors
 * minebot's earlier Python pathfinding port (pure-protocol-backend
 * branch)'s BlockInfo/movements.js's getBlock, but backed by real live
 * ClientLevel data (BlockState.isAir()/getFluidState()/
 * isCollisionShapeFullBlock()/BlockTags.CLIMBABLE) instead of a
 * pre-generated registry dump -- running inside the actual client means
 * this data is simply already there to query, no extraction step needed.
 */
public final class BlockInfo {
    public final int x;
    public final int y;
    public final int z;
    public final boolean known; // false if the containing chunk isn't loaded
    public final boolean safe; // empty/climbable/liquid and not something to avoid
    public final boolean physical; // a solid full block you can stand on
    public final boolean liquid;
    public final boolean climbable;

    public BlockInfo(
        final int x, final int y, final int z, final boolean known, final boolean safe,
        final boolean physical, final boolean liquid, final boolean climbable
    ) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.known = known;
        this.safe = safe;
        this.physical = physical;
        this.liquid = liquid;
        this.climbable = climbable;
    }

    public double height() {
        return y + (physical ? 1.0 : 0.0);
    }
}
