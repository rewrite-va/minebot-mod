package minebot.mod.statemachine.hands;

import minebot.mod.InventoryController;
import minebot.mod.MinebotMod;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import minebot.mod.statemachine.playerintention.CombatEngagement;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Charges and fires a real crossbow at CombatEngagement's live target --
 * the crossbow counterpart to HandsDrawBowNode (see its own docstring for
 * the shared aiming/range-management split: Head:AIM_AT_TARGET aims,
 * CombatEngagement manages range/kiting, this node purely holds/releases
 * the real interaction and fires once ready).
 *
 * A crossbow is mechanically NOT a hold-to-draw-then-auto-release weapon
 * the way a bow is (confirmed via decompiled CrossbowItem source): a real
 * right-click starts CHARGING (Player.startUsingItem, same as a bow's
 * draw), but the charge completing does NOT fire anything -- it just
 * loads a bolt into the stack's own CHARGED_PROJECTILES component
 * (CrossbowItem.isCharged flips true), and that charge persists across
 * ticks even once the use-key is released. Firing is a SEPARATE, later
 * real use() call made while already charged -- CrossbowItem.use()'s own
 * decompiled body branches on isCharged() first, calling
 * performShooting() immediately (no ammo re-check at all) before it ever
 * looks at held ammo. So this node has two distinct phases:
 * - CHARGING: not yet charged -- hold the real draw (useItem() once +
 *   keyUse held, exact same client-parity mechanism HandsDrawBowNode
 *   uses) until CrossbowItem.isCharged(mainHandItem) reads true.
 * - FIRE: already charged -- release any held draw, then a single fresh
 *   useItem() call fires immediately and clears the charge.
 *
 * isCharged() is read directly off the REAL, server-synced ItemStack
 * (player.getMainHandItem(), same as any other live inventory read) --
 * NOT tracked as local node state -- because the actual charge-loading
 * (CrossbowItem.onUseTick's own tryLoadProjectiles call) is gated on
 * `!level.isClientSide()` in its own decompiled bytecode, i.e. it is
 * SERVER-AUTHORITATIVE and never runs locally at all. This is the
 * opposite workaround from HandsDrawBowNode's own (which had to manually
 * drive BowItem.releaseUsing locally because vanilla's own local
 * echo never completes it) -- here there is nothing to drive locally;
 * the fix is simply to trust the real synced isCharged() state rather
 * than any locally-estimated timer, since the server may take a tick or
 * two of round-trip beyond CrossbowItem.getChargeDuration() to actually
 * reflect back down.
 *
 * Gated on real line-of-sight to the target (same Level.clip eye-to-eye
 * raycast HandsDrawBowNode uses) before EITHER charging or firing -- per
 * the same "Hands just always attacks if there is vision of the target"
 * direction that node's own docstring cites. Losing line of sight
 * mid-charge abandons the draw (stop()); losing it while already charged
 * does NOT discard the loaded bolt (a charged crossbow costs a real
 * arrow to reload) -- it simply waits, still charged, for line of sight
 * to return before firing.
 */
public final class HandsDrawCrossbowNode implements StateNode<HandsState> {
    private boolean drawing;

    @Override
    public void onTick(final TickContext ctx) {
        Integer targetEntityId = ctx.blackboard.get(CombatEngagement.TARGET_ENTITY_ID);
        Entity target = targetEntityId != null ? ctx.level.getEntity(targetEntityId) : null;

        if (target == null || !hasLineOfSight(ctx, target) || !isCrossbowSelected(ctx)) {
            stop(ctx);
            return;
        }

        InventoryController.Choice weapon = ctx.blackboard.get(CombatEngagement.SELECTED_WEAPON);
        InventoryController.moveToHotbar(ctx.player, weapon.slot(), 8);

        ItemStack crossbow = ctx.player.getMainHandItem();
        if (CrossbowItem.isCharged(crossbow)) {
            // Already loaded (from a previous tick's completed charge,
            // possibly carried over from before line-of-sight was lost)
            // -- release any stale draw hold, then fire with one fresh
            // press, exactly the real second-right-click a human would
            // make.
            stop(ctx);
            Minecraft.getInstance().gameMode.useItem(ctx.player, InteractionHand.MAIN_HAND);
            MinebotMod.LOGGER.info("hands: crossbow fired at entity {} ({})", target.getId(), target.getType());
            return;
        }

        if (!drawing) {
            drawing = true;
            Minecraft.getInstance().gameMode.useItem(ctx.player, InteractionHand.MAIN_HAND);
        }
        // Same real vanilla safety net HandsDrawBowNode's own docstring
        // describes (Minecraft.handleKeybinds() force-releasing use()
        // the instant keyUse isn't held) -- holding this down is what
        // keeps the real server-side charge ticking forward tick over
        // tick instead of being cancelled the moment it starts.
        Minecraft.getInstance().options.keyUse.setDown(true);
    }

    @Override
    public void onExit(final TickContext ctx) {
        stop(ctx);
    }

    /** Releases a draw left mid-charge -- e.g. line of sight was lost, the target died, or something else took over the main hand. Does NOT discard an already-completed charge (isCharged stays true on the real stack regardless of keyUse state) -- only cancels an IN-PROGRESS one. Idempotent -- safe to call every tick this node isn't actively drawing. */
    private void stop(final TickContext ctx) {
        if (drawing) {
            Minecraft.getInstance().gameMode.releaseUsingItem(ctx.player);
            drawing = false;
        }
        Minecraft.getInstance().options.keyUse.setDown(false);
    }

    private static boolean isCrossbowSelected(final TickContext ctx) {
        InventoryController.Choice weapon = ctx.blackboard.get(CombatEngagement.SELECTED_WEAPON);
        return weapon != null && weapon.kind() == InventoryController.Kind.CROSSBOW;
    }

    /** Real eye-to-eye raycast against solid blocks (ClipContext.Block.COLLIDER, matching what actually stops a real bolt) -- MISS means a clear line of sight. Fluids are deliberately not checked (ClipContext.Fluid.NONE) -- water/lava don't block a real shot's flight the way a solid block does. Identical to HandsDrawBowNode's own hasLineOfSight -- not shared, since each node's onTick already reads its own ctx locally and this is a two-line check, not worth a new shared utility for. */
    private static boolean hasLineOfSight(final TickContext ctx, final Entity target) {
        Vec3 from = ctx.player.getEyePosition();
        Vec3 to = target.getEyePosition();
        ClipContext clipContext = new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.player);
        BlockHitResult hit = ctx.level.clip(clipContext);
        return hit.getType() == HitResult.Type.MISS;
    }
}
