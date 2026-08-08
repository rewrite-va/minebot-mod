package minebot.mod.statemachine.head;

import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import minebot.mod.statemachine.hands.HandsMineNode;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Aims at whatever block HandsMineNode is actively committed to breaking
 * right now (HandsMineNode.CURRENT_MINING_TARGET) -- moved here from
 * BlockBreaker's own private aimAt method so Head owns yaw/pitch
 * exclusively for mining too, the same way it already does for combat
 * (HeadAimAtTargetNode) and navigation (HeadNavigateNode) -- see HeadState's
 * own class docstring: "Owns yaw/pitch exclusively".
 *
 * Before this existed, BlockBreaker.tryBreak set yaw/pitch directly from
 * inside Hands' own tick, with HeadNavigateNode ALSO unconditionally
 * setting pitch to 0 every tick Head was in NAVIGATE (see its own
 * docstring) -- two independent writers racing over the same rotation
 * state, with the outcome for any given tick decided purely by
 * MinebotMod's own tick call order, not any real coordination. That worked
 * MOST of the time only because Hands happened to run after Head and
 * BlockBreaker.aimAt happened to run on most ticks tryBreak was called --
 * but tryBreak has real early-return branches BEFORE where aimAt used to
 * be called (e.g. the switchedTool branch, see its own comment), and on
 * exactly those ticks HeadNavigateNode's setXRot(0f) from earlier that same
 * tick was left standing as the tick's final pitch -- a real, reproduced
 * live bug (see FINDINGS.md): the bot got stuck holding keyAttack against
 * a block 4+ blocks away from where vanilla's own real destroyBlockPos
 * (read via BlockBreaker's reflection diagnostic) actually locked on,
 * repeatedly hitting BlockBreaker's own STUCK_TICKS_LIMIT giveup and
 * immediately re-picking the identical target forever. Giving Head its own
 * dedicated MINE state (see HeadStateMachine's own edges) removes the race
 * entirely: whichever HeadState is active is the only thing writing
 * rotation that tick, period, the same guarantee IDLE/NAVIGATE/
 * AIM_AT_TARGET/FLEE already give each other.
 *
 * Reads HandsMineNode.CURRENT_MINING_TARGET, published earlier this same
 * tick -- MinebotMod's own tick order runs Hands before Head specifically
 * so this is never stale (see MinebotMod's own tick-order comment).
 */
public final class HeadMineNode implements StateNode<HeadState> {
    // See BlockBreaker's own former aimAt docstring (git history) for the
    // full live-repro reasoning behind this threshold/offset pair --
    // unchanged from that method, just relocated here along with the rest
    // of the aiming logic it was inseparable from.
    private static final double NEAR_VERTICAL_HORIZONTAL_THRESHOLD = 0.5;
    private static final double NEAR_VERTICAL_AIM_OFFSET = 0.5 - 0.02;

    @Override
    public void onTick(final TickContext ctx) {
        BlockPos pos = ctx.blackboard.get(HandsMineNode.CURRENT_MINING_TARGET);
        if (pos == null) {
            return; // nothing real to face right now -- leave yaw/pitch as they are
        }
        aimAt(ctx.player, ctx.level, pos);
    }

    /**
     * Identical math to BlockBreaker's former private aimAt -- see that
     * method's own git history for the full live-repro reasoning behind
     * both the near-vertical offset and the yRotO/xRotO same-tick snap.
     * Both are still required here for exactly the same reasons: without
     * the offset, a target almost directly underfoot degenerates yaw to
     * floating-point noise as pitch approaches +-90; without the yRotO/
     * xRotO snap, the interpolated partial-tick camera angle
     * (LocalPlayer.raycastHitResult's real basis for `hitResult`) lags
     * behind the discrete rotation this sets and can clip a neighboring
     * block instead of the real target for several rendered frames.
     *
     * The vertical aim point is the real OUTLINE shape's own vertical
     * center (see aimHeightFraction's own docstring), not a blanket
     * pos.getY() + 0.5 assuming every target is a full 1x1x1 cube -- a real
     * live bug this fixed: minecraft:snow (SnowLayerBlock) has a real
     * outline shape only 1/8-1/1 blocks tall sitting at the BOTTOM of its
     * cell (Block.column(16.0, 0.0, layers * 2), confirmed via decompiled
     * source), so aiming at the assumed y+0.5 center overshot clean above
     * the actual shape -- confirmed live via BlockBreaker's own hitResult
     * diagnostic: a clean MISS at exactly the 4.5-block max pick range,
     * despite the target being under 1 block away and both yaw and the
     * old pitch formula matching a full-cube-center aim exactly. A block
     * with an empty outline shape (e.g. something mid-transition) falls
     * back to the old y+0.5 guess -- nothing better to aim at.
     */
    private static void aimAt(final LocalPlayer player, final ClientLevel level, final BlockPos pos) {
        double targetX = pos.getX() + 0.5;
        double targetZ = pos.getZ() + 0.5;
        double roughDx = targetX - player.getX();
        double roughDz = targetZ - player.getZ();
        if (Math.sqrt(roughDx * roughDx + roughDz * roughDz) < NEAR_VERTICAL_HORIZONTAL_THRESHOLD) {
            targetX = pos.getX() + NEAR_VERTICAL_AIM_OFFSET;
            targetZ = pos.getZ() + NEAR_VERTICAL_AIM_OFFSET;
        }

        double dx = targetX - player.getX();
        double dz = targetZ - player.getZ();
        double dy = (pos.getY() + aimHeightFraction(level, pos)) - player.getEyeY();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        // Positive pitch = looking down (same convention confirmed in
        // NearbyPlayerLookAt's own docstring via decompiled
        // Entity.calculateViewVector).
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontalDistance));
        player.setYRot(yaw);
        player.setXRot(pitch);

        // Also snap yRotO/xRotO (the *previous*-tick rotation, used to
        // interpolate the actual rendered/raycast camera angle across
        // partial ticks) to the same value, not just the current-tick
        // rotation -- see this method's own docstring; without this, a
        // single-tick rotation snap from whatever Head was previously
        // doing (e.g. level navigation pitch) toward a steep mining pitch
        // leaves several rendered frames still easing from the old angle,
        // long enough for the real per-frame raycast to clip a
        // neighboring block instead.
        player.yRotO = yaw;
        player.xRotO = pitch;
    }

    /**
     * The real OUTLINE shape's own vertical center, as a [0, 1) fraction of
     * `pos`'s cell -- 0.5 (the old blanket assumption) for a full cube, but
     * correctly low for a thin block like a single snow layer (real shape
     * spans roughly y=[0, 0.125), center ~0.06) or high for something that
     * mostly fills its cell from the top.
     *
     * Deliberately state.getShape() (the OUTLINE/interaction shape), NOT
     * getCollisionShape() -- a real, live bug this fixed: a first attempt
     * used getCollisionShape(), which for a single-layer minecraft:snow is
     * EMPTY (SnowLayerBlock.getCollisionShape indexes SHAPES[LAYERS - 1],
     * i.e. SHAPES[0] for the default LAYERS=1, which Block.boxes(8, height
     * -> ...) built from height=0 -- a real, walkable, zero-height box, not
     * a bug in the block itself: vanilla intentionally lets you walk
     * through one snow layer, confirmed via SnowLayerBlock's own
     * isPathfindable(LAYERS < 5)). But vanilla's own crosshair raycast
     * (Entity.pick, the real basis for Minecraft.hitResult/continueAttack)
     * samples the OUTLINE shape via ClipContext.Block.OUTLINE, same as
     * BlockBreaker.hasLineOfSight already correctly uses for its own
     * visibility check -- getShape() for LAYERS=1 correctly returns the
     * visible ~0.125-tall box (SHAPES[LAYERS], no -1), so this must match
     * that, not the (sometimes empty) collision shape, to compute the same
     * height a real crosshair would actually land on. Falls back to 0.5 for
     * a genuinely empty outline shape (nothing there to aim a fraction of)
     * so this never divides by an undefined range.
     */
    private static double aimHeightFraction(final ClientLevel level, final BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        VoxelShape shape = state.getShape(level, pos);
        if (shape.isEmpty()) {
            return 0.5;
        }
        double min = shape.min(Direction.Axis.Y);
        double max = shape.max(Direction.Axis.Y);
        return (min + max) / 2.0;
    }
}
