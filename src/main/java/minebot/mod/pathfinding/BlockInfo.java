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
    public final boolean door; // a hand-openable door, open or closed
    public final boolean closedDoor; // a hand-openable door currently blocking this space
    public final boolean stairsOrSlab; // walkable like a full block, but its real top surface can sit up to 0.5 lower -- see height()'s own docstring

    public BlockInfo(
        final int x, final int y, final int z, final boolean known, final boolean safe,
        final boolean physical, final boolean liquid, final boolean climbable, final boolean door, final boolean closedDoor,
        final boolean stairsOrSlab
    ) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.known = known;
        this.safe = safe;
        this.physical = physical;
        this.liquid = liquid;
        this.climbable = climbable;
        this.door = door;
        this.closedDoor = closedDoor;
        this.stairsOrSlab = stairsOrSlab;
    }

    /**
     * A stair or (bottom-half) slab is walkable exactly like a full block
     * (see Movements.getBlock's own docstring on why it's classified
     * `physical` at all), but its real top surface isn't a flat y+1.0 the
     * way an actual full block's is -- a stair's low half sits at y+0.5,
     * and which side of the block is "low" depends on its facing, which
     * this class doesn't track. Reported live: a jump planned from/onto a
     * stairs block consistently landed short by a fraction of a block --
     * the real vanilla jump physics were being asked to clear a height
     * difference computed assuming a full y+1.0 top surface that, for a
     * stairs block, doesn't actually exist on at least one side. Rather
     * than model stair facing/orientation precisely (real geometric work,
     * not attempted here), conservatively reports the lowest possible top
     * surface (y+0.5) for a stairs/slab block -- this can only ever make a
     * jump involving one look *taller* than reality (never shorter), so a
     * jump the planner accepts is still guaranteed physically possible;
     * it may just reject a few jumps that were actually fine, which is a
     * far smaller cost than repeatedly failing a jump the planner thought
     * was safe.
     */
    public double height() {
        if (!physical) {
            return y + 0.0;
        }
        return stairsOrSlab ? y + 0.5 : y + 1.0;
    }
}
