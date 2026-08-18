package minebot.mod.pathfinding;

import net.minecraft.core.Direction;

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
    // Non-null ONLY for a bottom-half stairs block (never top-half slabs,
    // double slabs, or non-stairs blocks) -- the real StairBlock.FACING
    // property, i.e. which way the block's own low/open step opens
    // toward. A player walking IN the direction FACING points (i.e.
    // walking toward the block from the side its low step opens onto)
    // steps onto the real y+0.5 low half with zero jump input needed --
    // real vanilla auto-step handles a rise this small automatically, the
    // same as stepping onto a half-slab or a single stair tread in real
    // play. Approaching from the OTHER side (walking in the OPPOSITE
    // direction FACING points, into the block's solid riser face) is a
    // genuine full-height wall needing a real jump -- confirmed live per
    // explicit direction: a naive "stairs always means no jump" rule is
    // wrong exactly half the time, since which side is climbable depends
    // on both the block's own facing AND
    // which direction the bot is actually walking through it.
    public final Direction stairsFacing;
    // A block that hurts the player just by touching/standing on it --
    // lava, magma block -- as opposed to merely being a solid obstacle.
    // Kept distinct from `safe`/`physical` rather than folded into
    // either: lava is a real liquid (would otherwise pass `safe` exactly
    // like water) and magma block is a real full-block floor (would
    // otherwise pass `physical` exactly like stone) -- both need their
    // normal classification preserved for collision/landing geometry
    // while still being refused as a route, which a single boolean
    // couldn't do on its own. See Movements.getBlock's own docstring for
    // the live danger this flag exists to route around.
    public final boolean dangerous;

    public BlockInfo(
        final int x, final int y, final int z, final boolean known, final boolean safe,
        final boolean physical, final boolean liquid, final boolean climbable, final boolean door, final boolean closedDoor,
        final boolean stairsOrSlab, final boolean dangerous, final Direction stairsFacing
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
        this.dangerous = dangerous;
        this.stairsFacing = stairsFacing;
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
