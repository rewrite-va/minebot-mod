package minebot.mod.statemachine.hands;

import minebot.mod.InventoryActions;
import minebot.mod.MinebotMod;
import minebot.mod.WeaponSelector;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import minebot.mod.statemachine.playerintention.CombatEngagement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Draws and fires a real bow at CombatEngagement's live target (whichever
 * PlayerIntention:KILL/DEFEND fight is currently active) -- ported from the
 * deleted BowShooter class (see its own full docstring in git history for
 * the 4 layered root causes that had to be solved to make a bow shot
 * actually fire: local isUsingItem() never true, the local
 * useItemRemaining never decrementing so releaseUsing's own power
 * computation always floored to 0, vanilla's own handleKeybinds()
 * force-releasing the draw every tick keyUse isn't held down, and
 * FoodEater's old unconditional keyUse release stomping a concurrent
 * BowShooter hold). Reuses that exact draw-timing/release mechanism
 * unchanged -- only the aiming and range/positioning responsibilities
 * moved: BowShooter used to call its own aimAt() (writing yaw/pitch
 * directly) and its caller (tickAttack) used to manage bow range/
 * retreat-kiting itself; both now belong to other axes -- Head:
 * AIM_AT_TARGET already aims (with the same real arc-lift formula, now
 * living there -- see its own docstring) and CombatEngagement already
 * manages bow range/kiting (see its own docstring) -- so this node is
 * purely "hold the draw, release when full, gated on actually having a
 * shot" and never touches yaw/pitch/movement itself, matching every
 * other Hands node's own split.
 *
 * Gated on real line-of-sight to the target (Level.clip, a genuine
 * eye-to-eye raycast against solid blocks) per explicit direction
 * ("Hands just always attacks if there is vision of the target") --
 * Legs/PlayerIntention's own range management gets the bot to roughly the right
 * distance, but says nothing about whether a wall is actually in the
 * way; a lost line of sight mid-draw abandons the draw cleanly (same
 * "stop() releases whatever's in progress" shape BowShooter's own stop()
 * had) rather than holding a draw against an obstruction.
 */
public final class HandsDrawBowNode implements StateNode<HandsState> {
    // AbstractSkeleton.performRangedAttack's own constant (decompiled:
    // aimed direction's Y component is dy + horizontalDistance * 0.2) --
    // exposed here since HeadAimAtTargetNode needs the exact same value
    // for its own arc-lifted aim point, and this is the node that owns
    // "how a bow shot is aimed" conceptually.
    public static final double ARC_LIFT_PER_BLOCK = 0.2;

    // BowItem.MAX_DRAW_DURATION -- holding this long reaches
    // getPowerForTime's max (1.0), a full-strength/full-accuracy shot,
    // matching the old BowShooter's own "always fully draw" behavior
    // rather than firing faster, weaker partial-draw shots.
    private static final int FULL_DRAW_TICKS = 20;

    private boolean drawing;
    private int ticksSinceDrawStarted;

    @Override
    public void onTick(final TickContext ctx) {
        Integer targetEntityId = ctx.blackboard.get(CombatEngagement.TARGET_ENTITY_ID);
        Entity target = targetEntityId != null ? ctx.level.getEntity(targetEntityId) : null;

        if (target == null || !hasLineOfSight(ctx, target) || !isBowSelected(ctx)) {
            stop(ctx);
            return;
        }

        WeaponSelector.Choice weapon = ctx.blackboard.get(CombatEngagement.SELECTED_WEAPON);
        InventoryActions.moveToHotbar(ctx.player, weapon.slot(), 8);

        if (!drawing) {
            drawing = true;
            ticksSinceDrawStarted = 0;
            Minecraft.getInstance().gameMode.useItem(ctx.player, InteractionHand.MAIN_HAND);
        }
        // Root cause (confirmed via decompiled Minecraft.handleKeybinds()
        // bytecode): vanilla itself runs, every tick screen == null,
        // `if (player.isUsingItem() && !options.keyUse.isDown())
        // gameMode.releaseUsingItem(player)` -- its own safety net for a
        // real player letting go of right-click. Holding this down for
        // the draw's duration is what stops vanilla from undoing it;
        // useItem()/releaseUsing() are what actually do the real work.
        Minecraft.getInstance().options.keyUse.setDown(true);

        ticksSinceDrawStarted++;
        if (ticksSinceDrawStarted < FULL_DRAW_TICKS) {
            return;
        }

        release(ctx.player, ticksSinceDrawStarted);
        drawing = false;
        MinebotMod.LOGGER.info("hands: bow shot released at entity {} ({})", target.getId(), target.getType());
    }

    @Override
    public void onExit(final TickContext ctx) {
        stop(ctx);
    }

    /** Releases a shot left mid-draw -- e.g. line of sight was lost, the target died, or something else took over the main hand. Idempotent -- safe to call every tick this node isn't actively drawing. */
    private void stop(final TickContext ctx) {
        if (drawing) {
            release(ctx.player, ticksSinceDrawStarted);
            drawing = false;
        }
        Minecraft.getInstance().options.keyUse.setDown(false);
    }

    /**
     * Sends the real network release (telling the server, which has its
     * own correctly-ticked-down draw state, to fire) and separately
     * drives BowItem.releaseUsing directly with a locally-computed
     * timeLeft so this client's own local echo doesn't silently no-op --
     * see this class's own docstring for why MultiPlayerGameMode.
     * releaseUsingItem()'s own local half can't be relied on (the local
     * useItemRemaining never decrements, since isUsingItem() is never
     * true here to drive LivingEntity.updateUsingItem()).
     */
    private static void release(final LocalPlayer player, final int ticksHeld) {
        Minecraft.getInstance().options.keyUse.setDown(false);
        Minecraft.getInstance().gameMode.releaseUsingItem(player);
        ItemStack bow = player.getMainHandItem();
        if (bow.getItem() instanceof BowItem bowItem) {
            int timeLeft = bowItem.getUseDuration(bow, player) - ticksHeld;
            bowItem.releaseUsing(bow, player.level(), player, timeLeft);
        }
    }

    private static boolean isBowSelected(final TickContext ctx) {
        WeaponSelector.Choice weapon = ctx.blackboard.get(CombatEngagement.SELECTED_WEAPON);
        return weapon != null && weapon.kind() == WeaponSelector.Kind.BOW;
    }

    /** Real eye-to-eye raycast against solid blocks (ClipContext.Block.COLLIDER, matching what actually stops a real arrow) -- MISS means a clear line of sight. Fluids are deliberately not checked (ClipContext.Fluid.NONE) -- water/lava don't block a real arrow's flight the way a solid block does. */
    private static boolean hasLineOfSight(final TickContext ctx, final Entity target) {
        Vec3 from = ctx.player.getEyePosition();
        Vec3 to = target.getEyePosition();
        ClipContext clipContext = new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.player);
        BlockHitResult hit = ctx.level.clip(clipContext);
        return hit.getType() == HitResult.Type.MISS;
    }
}
