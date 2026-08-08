package minebot.mod.pathfinding;

import minebot.mod.MinebotMod;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Cost model for A*, ported from minebot's earlier Python pathfinding port
 * (pure-protocol-backend branch, itself a port of
 * mineflayer-pathfinder@2.4.5's lib/movements.js) -- walk/climb/parkour
 * moves, plus real dig-cost moves (this port's one addition beyond the
 * earlier walk-only version). Block *placement* is deliberately still not
 * ported: mineflayer's own toPlace/remainingBlocks/scaffolding-item
 * tracking is a materially bigger feature (needs a real "place this item
 * against that face" interaction plus inventory-aware scaffolding-item
 * bookkeeping) with its own pending command (!place) -- a dig-only bot can
 * already get strictly further than the old walk-only one (breaking a
 * wall to get through, never needing to bridge a gap by placing blocks
 * under itself) without that half, so it's left for a future pass.
 * Entity-avoidance cost weighting is also still dropped -- no
 * entity-list use for pathfinding yet.
 *
 * Block classification queries the real live ClientLevel directly
 * (BlockState.isAir()/getFluidState()/isCollisionShapeFullBlock()/
 * BlockTags.CLIMBABLE) rather than a pre-generated registry dump, since
 * running inside the actual client means this data already exists to
 * query -- no extraction step needed the way the from-scratch Python
 * protocol implementation required (see block_registry_775.json's
 * generation story on pure-protocol-backend for why that was ever
 * necessary in the first place).
 *
 * Every block query landing in a chunk that isn't loaded is treated as
 * unsafe/non-physical/unknown rather than optimistically guessed either
 * way -- same conservative default as the earlier Python port: guessing
 * wrong in either direction could walk the bot off a ledge just as easily
 * as make it think open space is a wall, and "don't route through the
 * unknown" is the safe default for a pathfinder whose whole point is not
 * falling through unseen gaps.
 *
 * Water is walkable by default -- real vanilla liquids have no collision
 * box, so a liquid tile counts as `safe` the same as air, just with a
 * small +1.0 liquidCost nudge per move (getMoveForward/getMoveDiagonal)
 * discouraging it without forbidding it, so A* still crosses a
 * stream/pond when that's genuinely the shortest route. avoidLiquid
 * (false by default) overrides this per-instance: with it set, liquid
 * tiles are `!safe` instead, which safeOrBreak turns into a hard BLOCKED
 * for every move type at once (not just the two that add liquidCost) --
 * see avoidLiquid's own field docstring for why (Legs:FLEE routing INTO
 * water while retreating is worse than a longer dry detour).
 *
 * Lava is the one liquid this doesn't apply to -- per explicit
 * direction, it's never merely discouraged the way water is:
 * BlockInfo.dangerous (computed in getBlock, folded into `safe`) marks
 * it `!safe` unconditionally, same as avoidLiquid does for every liquid
 * when set, so it's always routed around rather than crossed even when
 * it'd be the shortest path. Magma block gets the same `dangerous`
 * treatment despite being a real, normal full-block floor (`physical`
 * stays true -- its actual collision shape is unchanged, so anything
 * that needs to know "is this solid" still gets the right answer) --
 * every move type that accepts a floor block for standing/landing
 * (getMoveForward, getMoveJumpUp, getMoveDiagonal, getLandingBlock, the
 * parkour landing loop) separately checks `dangerous` on that floor
 * block specifically, since `safeOrBreak`'s own `safe` check only ever
 * runs against body-space blocks (what a player's hitbox occupies), not
 * the block stood on.
 *
 * Dig cost: movements.js's safeOrBreak computes a "labor cost" from an
 * estimated digTime looked up in a client-less registry dump (mineflayer
 * has no real client to ask). This mod has an actual LocalPlayer and
 * ClientLevel, so it asks the real game instead --
 * BlockState.getDestroyProgress(player, level, pos) already folds in
 * held-tool speed, enchantments, and status effects (confirmed by reading
 * BlockBehaviour.getDestroyProgress's decompiled source: `player.
 * getDestroySpeed(state) / destroySpeed / modifier`, the exact same call
 * MultiPlayerGameMode.continueDestroyBlock uses for real per-tick mining
 * progress) -- no separate hardness table needed. A block that can never
 * be destroyed at all (getDestroySpeed returns the -1.0F sentinel, e.g.
 * bedrock/barrier) is BLOCKED outright, same as movements.js's
 * blocksCantBreak set; chests are excluded the same way movements.js
 * explicitly excludes them (breaking a container mid-path would spill its
 * contents, not something a pathfinder should ever decide to do as a side
 * effect of routing).
 */
public final class Movements {
    private static final int[][] CARDINAL_DIRECTIONS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    private static final int[][] DIAGONAL_DIRECTIONS = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}};

    private static final double BLOCKED = 100.0; // movements.js's "can't move here" cost sentinel
    // movements.js's hardcoded jump-height cap (blocks) -- kept as the
    // hard reject threshold (a jump this tall or taller is never even
    // offered as a move). Real vanilla max jump height (BASE_JUMP_POWER
    // 0.42, LivingEntity.jumpFromGround) works out to ~1.25 blocks of
    // continuous rise, so 1.2 already sits right at that physical edge
    // with almost no margin -- a jump this close to the cap is legal by
    // this check but has little room for error in actual execution
    // (timing, exact block-edge alignment, forward momentum needed
    // simultaneously), unlike a full 1.0-block step-up which clears with
    // real margin to spare. JUMP_HEIGHT_COMFORTABLE marks where that
    // margin starts running out -- jumps taller than this (but still
    // under MAX_STEP_HEIGHT) get an escalating cost penalty instead of
    // being treated identically to an easy 1-block step, so A* prefers a
    // walk-around whenever one exists and only takes a marginal jump when
    // there's genuinely no better route. Reported live: the bot sometimes
    // picked a jump right at the edge of what's climbable over a longer
    // but easy walk-around, then got stuck repeatedly jumping in place --
    // the flat per-move cost model (a 1.0-block step and a 1.19-block
    // step both cost the same) gave A* no reason to prefer the easier
    // one, even though real jump execution has far less margin for error
    // the closer it gets to the physical cap.
    private static final double MAX_STEP_HEIGHT = 1.2;
    private static final double JUMP_HEIGHT_COMFORTABLE = 1.0; // a plain 1-block step-up -- no penalty up to here
    private static final double JUMP_HEIGHT_PENALTY_MAX = 6.0; // cost added at a jump right at MAX_STEP_HEIGHT itself
    private static final double DIG_COST = 1.0; // movements.js's default Movements#digCost
    // movements.js's default Movements#maxDropDown -- caps how far below the
    // current node getMoveDown/getMoveDropDown are allowed to land. Missing
    // entirely from this port until found live: without it, getLandingBlock
    // happily returns a landing spot dozens of blocks straight down (the
    // first solid floor it finds, however far that is), and getMoveDown
    // costs that as a flat ~1.0 (only safeOrBreak(node, block0) -- the single
    // block directly underfoot) with zero awareness that the real landing
    // is far below. The bot then walked exactly one dig at a time toward a
    // "waypoint" it could never actually reach on a single move: dig the
    // block below, fall one block, PathTracker's selfDrift check notices
    // it's now far from the (still-far-below) waypoint and replans, A*
    // offers the identical getMoveDown edge again from the new position,
    // repeat forever -- observed live as `!follow` getting stuck digging
    // straight down through solid stone in an endless loop, never making
    // horizontal progress toward the followed player at all. Matches
    // upstream movements.js's own maxDropDown value (4) exactly.
    private static final int MAX_DROP_DOWN = 4;

    private final ClientLevel level;
    private final LocalPlayer player;
    public boolean allowParkour = true;
    public boolean allowSprinting = true;
    public boolean allowDig = true;
    // False routes water/lava out of the search entirely (BLOCKED, not
    // just the usual +1.0 liquidCost nudge) -- per explicit direction,
    // Legs:FLEE shouldn't path INTO water while retreating from a threat
    // (a bot mid-flee slowed/trapped in water is worse off than one that
    // took a slightly longer dry route). Every other caller keeps liquid
    // as a merely-discouraged, still-legal move (see getMoveForward/
    // getMoveDiagonal's own liquidCost) -- ordinary navigation/kiting/etc.
    // may still need to cross a stream or pond when that's genuinely the
    // only route.
    public boolean avoidLiquid = false;

    public Movements(final ClientLevel level, final LocalPlayer player) {
        this.level = level;
        this.player = player;
    }

    public BlockInfo getBlock(final int originX, final int originY, final int originZ, final int dx, final int dy, final int dz) {
        int x = originX + dx;
        int y = originY + dy;
        int z = originZ + dz;
        BlockPos pos = new BlockPos(x, y, z);

        if (!level.isLoaded(pos)) {
            return new BlockInfo(x, y, z, false, false, false, false, false, false, false, false, false);
        }

        BlockState state = level.getBlockState(pos);
        boolean isAir = state.isAir();
        boolean isLiquid = !state.getFluidState().isEmpty();
        boolean isLadder = state.getBlock().builtInRegistryHolder().is(BlockTags.CLIMBABLE);
        boolean isFullBlock = state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        // Lava is a real liquid (getFluidState() is non-empty, same as
        // water) but, unlike water, touching it is lethal -- per explicit
        // direction, never treat it as merely "discouraged" the way
        // water's own +1.0 liquidCost nudge does (see class docstring on
        // avoidLiquid). Checked via the fluid state's own tag rather than
        // BlockTags.LAVA (a block tag), since a flowing-lava tile's block
        // is still Blocks.LAVA either way here -- FluidTags mirrors this
        // 1:1 for the vanilla fluids, but asking the fluid state directly
        // is the same check state.is(Blocks.LAVA) would do, minus having
        // to separately account for both the source and flowing variants.
        boolean isLava = state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
        // Magma block has a real, normal full-block collision shape --
        // isFullBlock/isSolid below both correctly say "yes, you can
        // stand on this" -- but standing on it deals real damage
        // (MagmaBlock.stepOn) unless the player has Frost Walker, which
        // this mod doesn't try to detect/require. A geometrically valid
        // landing surface that's still not a safe one to route onto.
        boolean isMagma = state.is(Blocks.MAGMA_BLOCK);
        boolean isDangerous = isLava || isMagma;

        // Stairs and slabs aren't a full-block collision shape
        // (isCollisionShapeFullBlock is false for both), but a real player
        // just walks on top of either exactly like solid ground -- found
        // live: a cobblestone stairs block sitting directly in front of a
        // door was classified as neither physical (a floor to stand on)
        // nor safe (open space to walk through), i.e. as an impassable
        // wall, forcing a long detour around a single perfectly-walkable
        // tile. Bottom-half slabs (SlabType.BOTTOM) sit at normal floor
        // height and are walkable the same way; top-half/double slabs
        // behave like a full block already (isFullBlock covers double,
        // and top slabs need +1 step like stairs -- treated the same here
        // since Movements doesn't yet model the extra half-step height
        // difference, matching how it already doesn't model partial
        // step-up costs for stairs either).
        boolean isStairs = state.getBlock().builtInRegistryHolder().is(BlockTags.STAIRS);
        boolean isSlab = state.getBlock().builtInRegistryHolder().is(BlockTags.SLABS);
        boolean isSolid = isFullBlock || isStairs || isSlab;
        // A stairs block's real top surface (whichever side a jump
        // actually lands on) can sit up to 0.5 below the y+1.0 a real
        // full block's does -- see BlockInfo.height's own docstring for
        // the live jump-falls-short report this fixes. isFullBlock is
        // already true for a double slab (its collision shape genuinely
        // is a full block). A single slab's isFullBlock is false either
        // way (top or bottom half) and this class doesn't currently
        // distinguish SlabType.TOP from SlabType.BOTTOM -- a top slab's
        // real top surface actually is at y+1.0, so treating it the same
        // as a bottom slab here is conservative (may reject a jump that
        // was actually fine) rather than wrong in the unsafe direction
        // (never reports a taller usable surface than reality).
        boolean hasLoweredTopSurface = !isFullBlock && (isStairs || isSlab);

        // A door -- open or closed -- is never air (state.isAir() is false
        // either way, it's still a DoorBlock), but an *open* door is just
        // as walkable as air, and a *closed* one we can always open
        // ourselves, so both count as passable. Found live: without the
        // isDoor check here, an already-open door was classified exactly
        // like a solid wall (not air, not a ladder/liquid, not
        // closedDoor), so the bot detoured all the way around it instead
        // of noticing the doorway right in front of it was open.
        // closedDoor is tracked separately so MinebotMod's tick loop knows
        // to right-click it open as the bot walks up to it, matching how
        // mineflayer-pathfinder's own movements.js doesn't special-case
        // doors in its A* cost model either -- its door-handling plugin
        // opens them on approach, not by costing them here.
        boolean isDoor = state.getBlock() instanceof DoorBlock && DoorBlock.isWoodenDoor(state);
        boolean closedDoor = isDoor && !state.getValue(DoorBlock.OPEN);
        if (isDoor) {
            MinebotMod.LOGGER.debug("pathfinding: door block at {} open={}", pos, !closedDoor);
        }

        // movements.js: b.safe = (boundingBox === 'empty' || climbable || carpet) && !avoid.
        // Liquids have no collision box in vanilla (you can swim through
        // them), so they count as "empty"/safe too -- confirmed against
        // getLandingBlock's `blockLand.liquid && blockLand.safe` check on
        // the earlier Python port, which would be dead code otherwise.
        // avoidLiquid overrides this to false -- see its own docstring;
        // routes liquid out of the search as BLOCKED everywhere `safe`
        // feeds into safeOrBreak (every move type), not just the
        // separate +1.0 liquidCost nudge getMoveForward/getMoveDiagonal
        // already apply when it's merely discouraged, not disallowed.
        //
        // movements.js's own `carpet` case (a thin, walk-through decorative
        // block) was never ported here -- this port's closest real
        // equivalent, a shallow snow layer, fell through to safeOrBreak's
        // dig-cost path instead, exactly like a genuine solid obstacle.
        // Reported live: a bot standing on solid ground with a walkable
        // 1-4-layer snow accumulation in its path got misplanned as
        // "needs digging" for that step -- real vanilla lets a player walk
        // straight through up to 4 layers with zero collision at all
        // (SnowLayerBlock.isPathfindable checks LAYERS < HEIGHT_IMPASSABLE,
        // confirmed via decompiled source: HEIGHT_IMPASSABLE == 5), so
        // charging a dig cost (and, worse, ever attempting to actually
        // mine it) for something a real player would simply walk over is
        // wrong on both fronts. 5+ layers genuinely does block movement
        // (isPathfindable returns false there), so this only counts as
        // safe below that same real vanilla threshold, not unconditionally
        // for every snow layer state.
        boolean isWalkableSnow = state.getBlock() instanceof SnowLayerBlock
            && state.getValue(SnowLayerBlock.LAYERS) < SnowLayerBlock.HEIGHT_IMPASSABLE;
        // Real collision shape at the ACTUAL position (not
        // EmptyBlockGetter.INSTANCE the way isFullBlock above asks --
        // that generic query is fine for "is this a full cube", but
        // getCollisionShape can depend on real neighbor context, so
        // asking at the real pos is the more correct query for "does
        // this block have any collision AT ALL"), general-purpose
        // equivalent of the walkable-snow special case just above --
        // covers every decorative/vegetation block with zero collision
        // (leaf litter, flowers, flower beds, petals, tall grass,
        // saplings, etc.) the same way, rather than hardcoding a
        // per-block-type list. Reported live: leaf litter (and, by the
        // same real-vanilla mechanism, flowers/petals) has zero
        // collision -- a real player walks straight through it exactly
        // like air -- but wasn't air, a liquid, a ladder, a door, or the
        // one hardcoded SnowLayerBlock case above, so it fell through to
        // the same `!safe` obstacle bucket as an actual wall: routed
        // around, or queued up as something to dig through, when a real
        // player would just walk over it without even noticing it was
        // there. Matches this class's own isWalkableSnow reasoning
        // exactly (see its docstring just above) -- generalized from one
        // hardcoded block to "any block with a real empty collision
        // shape", the same test vanilla's own WalkNodeEvaluator-style
        // pathfinding uses to tell a walk-through decoration apart from
        // a real obstacle.
        boolean hasNoCollision = !isAir && state.getCollisionShape(level, pos).isEmpty();
        boolean safe = (isAir || isLadder || isLiquid || isDoor || isWalkableSnow || hasNoCollision)
            && !(avoidLiquid && isLiquid) && !isDangerous;

        return new BlockInfo(x, y, z, true, safe, isSolid, isLiquid, isLadder, isDoor, closedDoor, hasLoweredTopSurface, isDangerous);
    }

    private BlockInfo getBlock(final BlockInfo origin, final int dx, final int dy, final int dz) {
        return getBlock(origin.x, origin.y, origin.z, dx, dy, dz);
    }

    private BlockInfo getBlock(final Move origin, final int dx, final int dy, final int dz) {
        return getBlock(origin.x, origin.y, origin.z, dx, dy, dz);
    }

    /**
     * movements.js's safeOrBreak, ported to ask the real client for digging
     * cost instead of an estimated registry lookup (see class docstring).
     * Unknown (unloaded-chunk) blocks are BLOCKED -- a chunk that isn't
     * loaded can't be dug either, same conservative default this port has
     * always used for unknown blocks. Appends `pos` to `toBreak` whenever
     * it decides digging is the way through, exactly like movements.js's
     * own `toBreak.push`.
     *
     * `stance` is the real position the bot would actually be standing at
     * WHILE digging `block` -- needed for the hasDigLineOfSight check
     * below (see its own docstring for why this can't just be planned as
     * generically diggable without it). Reported live: A* happily planned
     * a jump-up move whose own headroom obstruction sat diagonally behind
     * the bot's real standing position -- genuinely diggable in the
     * abstract (real positive destroySpeed, not a chest/farmland/bedrock
     * exclusion), but BlockBreaker's own real per-tick raycast could never
     * actually land on it from that stance, so the bot got stuck holding
     * keyAttack at a target it could never hit, forever (confirmed live:
     * "no line of sight" logged every single tick with no way out --
     * BlockBreaker's own docstring already flagged "the caller is
     * responsible for eventually giving up on a target that never becomes
     * visible", but the real fix is not planning through it in the first
     * place, matching how a real player scouting the route would notice
     * "I can't actually reach that block" and route around it instead of
     * committing to a path that dead-ends).
     */
    private double safeOrBreak(final Move stance, final BlockInfo block, final List<BlockPos> toBreak) {
        if (!block.known) {
            return BLOCKED;
        }
        if (block.safe) {
            return 0.0;
        }
        if (!allowDig) {
            return BLOCKED;
        }

        BlockPos pos = new BlockPos(block.x, block.y, block.z);
        BlockState state = level.getBlockState(pos);

        // -1.0F is the real "can never be destroyed" sentinel (confirmed
        // via BlockBehaviour.getDestroyProgress's decompiled source --
        // bedrock, barrier, etc. all report this). Chests are excluded the
        // same way movements.js explicitly excludes them: breaking one
        // mid-path would spill its contents as a side effect of routing,
        // never something a pathfinder should decide on its own. Farmland
        // is excluded the same way -- reported live: pathfinding across a
        // real crop farm (!collect carrot) mined straight through
        // farmland blocks as a dig-through-obstacle whenever one happened
        // to sit at head/body height along a waypoint (farmland's real
        // collision IS a full walkable block, same as dirt -- see
        // getBlock's own isFullBlock/isSolid classification, which
        // already treats it as safe to *stand on*; this is specifically
        // the "something's blocking the path *through*, dig it" case, not
        // the floor-underfoot case). Farmland has a real, positive
        // destroySpeed (0.6, confirmed live) like dirt, so nothing above
        // already excluded it. Digging through a real player's farm as an
        // incidental routing choice is destructive in a way none of the
        // pathfinder's other legitimate obstacle-clearing is (a random
        // stone/dirt block regrows nothing; a working crop plot
        // represents real, deliberate player effort) -- excluded
        // unconditionally, the same as a chest, rather than left to the
        // normal cost-based obstacle logic.
        if (state.getDestroySpeed(level, pos) < 0.0F || state.getBlock() instanceof ChestBlock
            || state.is(Blocks.FARMLAND)) {
            return BLOCKED;
        }

        float progressPerTick = state.getDestroyProgress(player, level, pos);
        if (progressPerTick <= 0.0F) {
            return BLOCKED; // held tool (or bare hands) can never break this
        }

        if (!hasDigLineOfSight(stance, pos)) {
            return BLOCKED; // real BlockBreaker execution could never actually hit this from here -- see this method's own docstring
        }

        toBreak.add(pos);
        // movements.js: laborCost = (1 + 3 * digTime/1000) * digCost, where
        // digTime is estimated in ms. Real per-tick progress gives an exact
        // tick count instead of an estimate -- 1 tick = 1/20s = 50ms, so
        // ticksNeeded * 50 is the real-world ms equivalent, dropped into
        // the same formula shape so the two costs stay comparable in scale
        // to the walk-move costs (1.0 per step) they're competing against
        // in the same A* search.
        double ticksNeeded = 1.0 / progressPerTick;
        double digTimeMillis = ticksNeeded * 50.0;
        return (1.0 + 3.0 * digTimeMillis / 1000.0) * DIG_COST;
    }

    // Sample points on the target block, tried in order until one has
    // clear line of sight -- mirrors BlockBreaker.SIGHT_SAMPLE_OFFSETS
    // exactly (not shared -- see that field's own comment on this
    // codebase's established precedent of keeping short raycasts local),
    // since this check's whole point is predicting whether BlockBreaker's
    // OWN real execution-time hasLineOfSight would succeed from this
    // stance -- using a different sampling strategy here would make this
    // check answer a different question than the one that actually
    // matters.
    private static final double[][] DIG_SIGHT_SAMPLE_OFFSETS = {
        {0.5, 0.5, 0.5}, {0.5, 0.9, 0.5}, {0.5, 0.1, 0.5}, {0.1, 0.5, 0.5}, {0.9, 0.5, 0.5}, {0.5, 0.5, 0.1}, {0.5, 0.5, 0.9},
    };

    /**
     * Whether a real player standing at `stance` (a planned Move node --
     * the actual position the bot will be occupying while digging, not
     * necessarily its own live current position, since this runs during
     * A* exploration of hypothetical future nodes) could actually land a
     * real crosshair hit on `pos` -- see safeOrBreak's own docstring for
     * why this exists at all. Synthesizes an eye position from `stance`'s
     * integer coordinates + LocalPlayer.getEyeHeight() (the same real
     * standing eye-height vanilla itself uses, rather than a hardcoded
     * 1.62 constant, so this stays correct even if eye height ever
     * differs, e.g. while sneaking) since `stance` is a hypothetical
     * position, not the bot's own real live Entity with a real
     * getEyePosition() to call.
     */
    private boolean hasDigLineOfSight(final Move stance, final BlockPos pos) {
        Vec3 from = new Vec3(stance.x + 0.5, stance.y + player.getEyeHeight(), stance.z + 0.5);
        for (double[] offset : DIG_SIGHT_SAMPLE_OFFSETS) {
            Vec3 to = new Vec3(pos.getX() + offset[0], pos.getY() + offset[1], pos.getZ() + offset[2]);
            BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            // Must actually HIT `pos` itself -- a real MISS (the ray sails
            // past the sample point without hitting anything solid at
            // all, which can genuinely happen at a corner/edge sample
            // against a block whose real collision outline doesn't fully
            // cover that offset point) does NOT mean BlockBreaker's own
            // real crosshair could land a hit there; it means nothing
            // blocked the ray, not that anything caught it either.
            // Reported live: an earlier version of this check treated
            // MISS as success too, over-approving digs BlockBreaker's own
            // stricter execution-time hasLineOfSight (BLOCK type AND a
            // matching position, never MISS) then consistently failed to
            // find, leaving the bot stuck "no line of sight" forever
            // despite planning having "verified" visibility. Matching
            // BlockBreaker's exact criterion here, not a looser one, is
            // the whole point of this check -- see its own docstring.
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) {
                return true;
            }
        }
        return false;
    }

    /** `node`'s own position as a BlockPos, for Move's own digStance field -- null when `toBreak` ends up empty (nothing to stand anywhere for), matching digStance's own "null whenever toBreak is empty" contract (see Move's own docstring). */
    private static BlockPos digStanceFor(final Move node, final List<BlockPos> toBreak) {
        return toBreak.isEmpty() ? null : new BlockPos(node.x, node.y, node.z);
    }

    /**
     * Extra cost for a step-up/jump move whose height is above
     * JUMP_HEIGHT_COMFORTABLE (a plain 1-block step, no penalty) but
     * still under the hard MAX_STEP_HEIGHT cutoff -- scales linearly from
     * 0 at JUMP_HEIGHT_COMFORTABLE up to JUMP_HEIGHT_PENALTY_MAX right at
     * MAX_STEP_HEIGHT itself, so a marginal jump (little real-world
     * execution margin, per this class's docstring above) reads as
     * meaningfully more expensive than an easy one instead of the two
     * being cost-identical. heightDiff at or below JUMP_HEIGHT_COMFORTABLE
     * (including negative -- stepping onto a lower/equal block) is always
     * free.
     */
    private static double jumpHeightPenalty(final double heightDiff) {
        if (heightDiff <= JUMP_HEIGHT_COMFORTABLE) {
            return 0.0;
        }
        double span = MAX_STEP_HEIGHT - JUMP_HEIGHT_COMFORTABLE;
        double over = Math.min(heightDiff - JUMP_HEIGHT_COMFORTABLE, span);
        return (over / span) * JUMP_HEIGHT_PENALTY_MAX;
    }

    public Move getMoveForward(final Move node, final int dx, final int dz) {
        BlockInfo blockB = getBlock(node, dx, 1, dz);
        BlockInfo blockC = getBlock(node, dx, 0, dz);
        BlockInfo blockD = getBlock(node, dx, -1, dz);

        double cost = 1.0;
        List<BlockPos> toBreak = new ArrayList<>();

        if (!blockD.physical && !blockC.liquid) {
            return null; // would need to place a block to fill the gap -- can't place
        }
        if (blockD.physical && blockD.dangerous) {
            return null; // real floor, but standing on it (magma block) hurts -- see getLandingBlock's own dangerous check
        }

        cost += safeOrBreak(node, blockB, toBreak);
        if (cost > BLOCKED) return null;
        cost += safeOrBreak(node, blockC, toBreak);
        if (cost > BLOCKED) return null;

        if (getBlock(node, 0, 0, 0).liquid) {
            cost += 1.0; // liquidCost
        }

        return new Move(blockC.x, blockC.y, blockC.z, cost, toBreak, false, digStanceFor(node, toBreak));
    }

    public Move getMoveJumpUp(final Move node, final int dx, final int dz) {
        BlockInfo blockA = getBlock(node, 0, 2, 0);
        BlockInfo blockH = getBlock(node, dx, 2, dz);
        BlockInfo blockB = getBlock(node, dx, 1, dz);
        BlockInfo blockC = getBlock(node, dx, 0, dz);

        double cost = 2.0; // move + jump
        List<BlockPos> toBreak = new ArrayList<>();

        if (!blockC.physical) {
            return null; // would need to place a block to stand on -- can't place
        }
        if (blockC.dangerous) {
            return null; // real floor, but standing on it (magma block) hurts -- see getLandingBlock's own dangerous check
        }

        BlockInfo block0 = getBlock(node, 0, -1, 0);
        double stepHeight = blockC.height() - block0.height();
        if (stepHeight > MAX_STEP_HEIGHT) {
            return null; // too high to jump
        }
        cost += jumpHeightPenalty(stepHeight);

        cost += safeOrBreak(node, blockA, toBreak);
        if (cost > BLOCKED) return null;
        cost += safeOrBreak(node, blockH, toBreak);
        if (cost > BLOCKED) return null;
        cost += safeOrBreak(node, blockB, toBreak);
        if (cost > BLOCKED) return null;

        return new Move(blockB.x, blockB.y, blockB.z, cost, toBreak, true, digStanceFor(node, toBreak));
    }

    public Move getMoveDiagonal(final Move node, final int dx, final int dz) {
        double cost = Math.sqrt(2);
        List<BlockPos> toBreak = new ArrayList<>();

        BlockInfo blockC = getBlock(node, dx, 0, dz); // landing block, or the block we'd stand on if stepping up
        int y = blockC.physical ? 1 : 0;

        BlockInfo block0 = getBlock(node, 0, -1, 0);

        double cost1 = 0.0;
        List<BlockPos> toBreak1 = new ArrayList<>();
        BlockInfo blockB1 = getBlock(node, 0, y + 1, dz);
        BlockInfo blockC1 = getBlock(node, 0, y, dz);
        BlockInfo blockD1 = getBlock(node, 0, y - 1, dz);
        cost1 += safeOrBreak(node, blockB1, toBreak1);
        cost1 += safeOrBreak(node, blockC1, toBreak1);
        if (blockD1.height() - block0.height() > MAX_STEP_HEIGHT) {
            cost1 += safeOrBreak(node, blockD1, toBreak1);
        }

        double cost2 = 0.0;
        List<BlockPos> toBreak2 = new ArrayList<>();
        BlockInfo blockB2 = getBlock(node, dx, y + 1, 0);
        BlockInfo blockC2 = getBlock(node, dx, y, 0);
        BlockInfo blockD2 = getBlock(node, dx, y - 1, 0);
        cost2 += safeOrBreak(node, blockB2, toBreak2);
        cost2 += safeOrBreak(node, blockC2, toBreak2);
        if (blockD2.height() - block0.height() > MAX_STEP_HEIGHT) {
            cost2 += safeOrBreak(node, blockD2, toBreak2);
        }

        if (cost1 < cost2) {
            cost += cost1;
            toBreak.addAll(toBreak1);
        } else {
            cost += cost2;
            toBreak.addAll(toBreak2);
        }
        if (cost > BLOCKED) return null;

        cost += safeOrBreak(node, getBlock(node, dx, y, dz), toBreak);
        if (cost > BLOCKED) return null;
        cost += safeOrBreak(node, getBlock(node, dx, y + 1, dz), toBreak);
        if (cost > BLOCKED) return null;

        if (getBlock(node, 0, 0, 0).liquid) {
            cost += 1.0; // liquidCost
        }

        BlockInfo blockD = getBlock(node, dx, -1, dz);
        if (y == 1) { // stepping up by 1 while moving diagonally
            if (blockC.dangerous) {
                return null; // real floor, but standing on it (magma block) hurts -- see getLandingBlock's own dangerous check
            }
            double stepHeight = blockC.height() - block0.height();
            if (stepHeight > MAX_STEP_HEIGHT) {
                return null;
            }
            cost += jumpHeightPenalty(stepHeight);
            cost += safeOrBreak(node, getBlock(node, 0, 2, 0), toBreak);
            if (cost > BLOCKED) return null;
            cost += 1.0;
            return new Move(blockC.x, blockC.y + 1, blockC.z, cost, toBreak, false, digStanceFor(node, toBreak));
        } else if (blockD.physical || blockC.liquid) {
            if (blockD.physical && blockD.dangerous) {
                return null; // real floor, but standing on it (magma block) hurts -- see getLandingBlock's own dangerous check
            }
            return new Move(blockC.x, blockC.y, blockC.z, cost, toBreak, false, digStanceFor(node, toBreak));
        } else if (getBlock(node, dx, -2, dz).physical || blockD.liquid) {
            if (!blockD.safe) {
                return null; // don't self-immolate (e.g. drop into lava)
            }
            BlockInfo blockE = getBlock(node, dx, -2, dz);
            if (blockE.physical && blockE.dangerous) {
                return null; // real floor, but standing on it (magma block) hurts -- see getLandingBlock's own dangerous check
            }
            return new Move(blockC.x, blockC.y - 1, blockC.z, cost, toBreak, false, digStanceFor(node, toBreak));
        }
        return null;
    }

    /**
     * Searches straight down from (node.x+dx, node.y-2, node.z+dz) for a
     * floor or safe liquid to land on. `node.y` is needed (not just where
     * to start the search) to enforce MAX_DROP_DOWN: movements.js's own
     * getLandingBlock only accepts a *physical* landing (a real floor, as
     * opposed to a liquid) if it's within maxDropDown blocks of the
     * originating node -- a liquid landing has no such cap
     * (infiniteLiquidDropdownDistance, the upstream default), since falling
     * into water is always safe regardless of how far down it is. Without
     * this cap, a caller could be handed a "landing spot" arbitrarily far
     * below with no way to actually get there in one move (see this
     * class's MAX_DROP_DOWN docstring for the live bug this fixes).
     */
    private BlockInfo getLandingBlock(final Move node, final int dx, final int dz) {
        BlockInfo blockLand = getBlock(node, dx, -2, dz);
        // No dimension min_y tracking -- bound the fall search by a
        // generous number of blocks instead, same as the earlier Python
        // port, which naturally stops at the bottom of loaded chunk data
        // rather than looping forever over unknown blocks.
        for (int i = 0; i < 256; i++) {
            if (!blockLand.known) {
                return null;
            }
            if (blockLand.liquid && blockLand.safe) {
                return blockLand;
            }
            if (blockLand.physical && blockLand.dangerous) {
                // A real solid floor (magma block) that still hurts to
                // stand on -- geometrically a valid landing, but not a
                // safe one to route onto. Refuse it exactly like an unsafe
                // liquid below does, rather than accepting the first solid
                // surface found regardless of what it is.
                return null;
            }
            if (blockLand.physical) {
                if (node.y - blockLand.y > MAX_DROP_DOWN) {
                    return null; // floor exists, but it's too far below to drop to in one move
                }
                return getBlock(blockLand, 0, 1, 0);
            }
            if (!blockLand.safe) {
                return null;
            }
            blockLand = getBlock(blockLand, 0, -1, 0);
        }
        return null;
    }

    public Move getMoveDropDown(final Move node, final int dx, final int dz) {
        BlockInfo blockB = getBlock(node, dx, 1, dz);
        BlockInfo blockC = getBlock(node, dx, 0, dz);
        BlockInfo blockD = getBlock(node, dx, -1, dz);

        double cost = 1.0;
        List<BlockPos> toBreak = new ArrayList<>();

        BlockInfo blockLand = getLandingBlock(node, dx, dz);
        if (blockLand == null) {
            return null;
        }

        cost += safeOrBreak(node, blockB, toBreak);
        if (cost > BLOCKED) return null;
        cost += safeOrBreak(node, blockC, toBreak);
        if (cost > BLOCKED) return null;
        cost += safeOrBreak(node, blockD, toBreak);
        if (cost > BLOCKED) return null;

        if (blockC.liquid) {
            return null; // don't go underwater
        }

        return new Move(blockLand.x, blockLand.y, blockLand.z, cost, toBreak, false, digStanceFor(node, toBreak));
    }

    public Move getMoveDown(final Move node) {
        BlockInfo block0 = getBlock(node, 0, -1, 0);

        double cost = 1.0;
        List<BlockPos> toBreak = new ArrayList<>();

        BlockInfo blockLand = getLandingBlock(node, 0, 0);
        if (blockLand == null) {
            return null;
        }

        cost += safeOrBreak(node, block0, toBreak);
        if (cost > BLOCKED) return null;

        if (getBlock(node, 0, 0, 0).liquid) {
            return null; // don't go underwater
        }

        return new Move(blockLand.x, blockLand.y, blockLand.z, cost, toBreak, false, digStanceFor(node, toBreak));
    }

    public Move getMoveUp(final Move node) {
        BlockInfo block1 = getBlock(node, 0, 0, 0);
        if (block1.liquid) {
            return null;
        }

        BlockInfo block2 = getBlock(node, 0, 2, 0);

        double cost = 1.0;
        List<BlockPos> toBreak = new ArrayList<>();
        cost += safeOrBreak(node, block2, toBreak);
        if (cost > BLOCKED) return null;

        if (!block1.climbable) {
            return null; // can only climb via a ladder/vine -- no 1x1 towering, which needs placing
        }

        return new Move(node.x, node.y + 1, node.z, cost, toBreak, false, digStanceFor(node, toBreak));
    }

    public List<Move> getMoveParkourForward(final Move node, final int dx, final int dz) {
        List<Move> moves = new ArrayList<>();

        BlockInfo block0 = getBlock(node, 0, -1, 0);
        BlockInfo block1 = getBlock(node, dx, -1, dz);
        if ((block1.physical && block1.height() >= block0.height())
            || !getBlock(node, dx, 0, dz).safe
            || !getBlock(node, dx, 1, dz).safe) {
            return moves;
        }
        if (getBlock(node, 0, 0, 0).liquid) {
            return moves; // can't jump from water
        }

        boolean ceilingClear = getBlock(node, 0, 2, 0).safe && getBlock(node, dx, 2, dz).safe;
        boolean floorCleared = !getBlock(node, dx, -2, dz).physical;

        int maxD = allowSprinting ? 4 : 2;

        for (int d = 2; d <= maxD; d++) {
            // A real running/sprint jump this far is meaningfully harder
            // to land than an equivalent-length plain walk (getMoveForward
            // costs 1.0 per block, same as this used to cost flat
            // regardless of d) -- reported live: A* consistently chose a
            // 3-block parkour jump over a real, longer (~7 block) but
            // reliably walkable staircase detour, because both were
            // priced as roughly equally cheap despite the jump being far
            // less reliable to actually execute (this whole investigation
            // found multiple real ways a sprint-jump landing can fail:
            // aim/waypoint-tracking precision during the airborne arc,
            // insufficient run-up distance before liftoff, etc. -- a
            // plain walked step has none of that risk). Scaling cost with
            // d (quadratically, not linearly, since landing precision
            // gets meaningfully harder the farther the jump) makes A*
            // only take a parkour move when there's genuinely no
            // reasonable walkable alternative, rather than treating every
            // parkour edge as equal to or cheaper than walking the same
            // ground distance.
            double cost = d * d;
            int ddx = dx * d;
            int ddz = dz * d;
            BlockInfo blockA = getBlock(node, ddx, 2, ddz);
            BlockInfo blockB = getBlock(node, ddx, 1, ddz);
            BlockInfo blockC = getBlock(node, ddx, 0, ddz);
            BlockInfo blockD = getBlock(node, ddx, -1, ddz);

            if (ceilingClear && blockB.safe && blockC.safe && blockD.physical && !blockD.dangerous) {
                moves.add(new Move(blockC.x, blockC.y, blockC.z, cost, true));
                break;
            } else if (ceilingClear && blockB.safe && blockC.physical && !blockC.dangerous) {
                if (blockA.safe && d != 4) { // 4-forward-1-up is very difficult and fails often
                    double stepHeight = blockC.height() - block0.height();
                    if (stepHeight > MAX_STEP_HEIGHT) {
                        break; // too high to jump
                    }
                    // A forward-and-up parkour move needs both horizontal
                    // momentum and vertical clearance at once -- strictly
                    // harder to land than a plain step-up of the same
                    // height, so the same escalating-near-the-cap penalty
                    // applies here too (see jumpHeightPenalty's docstring).
                    moves.add(new Move(blockB.x, blockB.y, blockB.z, cost + jumpHeightPenalty(stepHeight), true));
                    break;
                }
            } else if ((ceilingClear || d == 2) && blockB.safe && blockC.safe && blockD.safe && floorCleared) {
                BlockInfo blockE = getBlock(node, ddx, -2, ddz);
                if (blockE.physical && !blockE.dangerous) {
                    moves.add(new Move(blockD.x, blockD.y, blockD.z, cost, true));
                }
                floorCleared = floorCleared && !blockE.physical;
            } else if (!blockB.safe || !blockC.safe) {
                break;
            }

            ceilingClear = ceilingClear && blockA.safe;
        }

        return moves;
    }

    public List<Move> getNeighbors(final Move node) {
        List<Move> neighbors = new ArrayList<>();

        for (int[] dir : CARDINAL_DIRECTIONS) {
            Move forward = getMoveForward(node, dir[0], dir[1]);
            if (forward != null) neighbors.add(forward);
            Move jumpUp = getMoveJumpUp(node, dir[0], dir[1]);
            if (jumpUp != null) neighbors.add(jumpUp);
            Move dropDown = getMoveDropDown(node, dir[0], dir[1]);
            if (dropDown != null) neighbors.add(dropDown);
            if (allowParkour) {
                neighbors.addAll(getMoveParkourForward(node, dir[0], dir[1]));
            }
        }

        for (int[] dir : DIAGONAL_DIRECTIONS) {
            Move diagonal = getMoveDiagonal(node, dir[0], dir[1]);
            if (diagonal != null) neighbors.add(diagonal);
        }

        Move down = getMoveDown(node);
        if (down != null) neighbors.add(down);
        Move up = getMoveUp(node);
        if (up != null) neighbors.add(up);

        return neighbors;
    }
}
