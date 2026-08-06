package minebot.mod.statemachine.hands;

import minebot.mod.InventoryActions;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import minebot.mod.statemachine.general.GeneralSelfHealNode;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/**
 * Eats food from the bot's own inventory while General:SELF_HEAL is
 * active -- ported from the old standalone FoodEater class (see git
 * history), which used to run unconditionally every tick regardless of
 * any mode/state at all. The actual eating mechanism (holding the real
 * `keyUse` keybind -- see class docstring below for why) is unchanged;
 * what's different is WHEN it runs: driven by General:SELF_HEAL's
 * published NEEDS_HEAL fact instead of computing its own health
 * threshold, matching the corrected General/Hands split (General decides
 * "should something be eating right now", Hands is the one that actually
 * does it -- see GeneralSelfHealNode's own docstring).
 *
 * "Is this food" is a data-component check (DataComponents.FOOD), not the
 * older Item.getFoodProperties() method, which no longer exists in this
 * version -- confirmed via decompiled ItemStack/DataComponentHolder.
 *
 * IMPORTANT gating subtlety (found via decompiled Player/Consumable
 * source): eating is gated on HUNGER, not health -- Player.canEat(canAlwaysEat)
 * == invulnerable || canAlwaysEat || foodData.needsFood() (foodLevel < 20).
 * A bot at critical health with a still-full hunger bar can't eat normal
 * food at all; only canAlwaysEat food (golden apple/carrot) bypasses that.
 * This mirrors that gate client-side before trying to eat, both to prefer
 * a canAlwaysEat item and to correctly report isFinished() when there's
 * genuinely nothing eatable right now rather than holding keyUse forever
 * against food that can't actually be consumed.
 *
 * HOW THIS ACTUALLY TRIGGERS EATING (found only after an extensive live
 * investigation -- see FINDINGS.md for the full story): calling
 * MultiPlayerGameMode.useItem() directly, the way DoorOpener does for its
 * instant block interaction, does NOT work for eating specifically, even
 * though every vanilla mechanism involved (canEat gating, hotbar
 * selection, call rate, the client/server synced "using item" flag) was
 * individually confirmed correct via bytecode. A live test proved the
 * actual difference: a human manually holding right-click on the SAME
 * client/account/item ate normally, while the mod's direct useItem() call
 * on the identical setup never completed a single eat. So instead of
 * calling useItem() at all, this holds the real `keyUse` keybind down
 * (Options.keyUse.setDown(true)) -- vanilla's own per-tick
 * Minecraft.handleKeybinds() then drives the interaction exactly as it
 * would for a human physically holding the button, which is confirmed to
 * work where the programmatic call didn't. Only ever holds it while THIS
 * node is active (released in onExit) -- no other node in this mod's
 * current line-up holds keyUse for anything multi-tick anymore
 * (BowShooter isn't wired into any SM yet), but a future one would need
 * the same real-ownership discipline FoodEater's own old holdingUseKey
 * guard established, once that day comes.
 */
public final class HandsEatNode implements StateNode<HandsState> {
    private boolean finished;

    @Override
    public void onEnter(final TickContext ctx, final HandsState previousState) {
        finished = false;
    }

    @Override
    public void onTick(final TickContext ctx) {
        if (!Boolean.TRUE.equals(ctx.blackboard.get(GeneralSelfHealNode.NEEDS_HEAL))) {
            releaseUseKey();
            finished = true;
            return;
        }

        // Prefer the offhand if it's edible AND actually eatable right
        // now -- a canAlwaysEat item there beats hunting through the
        // main inventory, and needs no hotbar selection at all.
        if (isEatableNow(ctx, ctx.player.getOffhandItem())) {
            holdUseKey();
            return;
        }

        Inventory inventory = ctx.player.getInventory();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!isEatableNow(ctx, stack)) {
                continue;
            }
            InventoryActions.moveToHotbar(ctx.player, slot, Inventory.isHotbarSlot(slot) ? slot : 8);
            holdUseKey();
            return;
        }

        // Nothing eatable right now (no food at all, or hunger's full so
        // canEat() rejects every real food item carried) -- nothing more
        // this node can do until NEEDS_HEAL goes false or the inventory
        // situation changes; report finished so General's own RESUME
        // path isn't blocked waiting on an eat that can never happen.
        releaseUseKey();
        finished = true;
    }

    @Override
    public void onExit(final TickContext ctx) {
        releaseUseKey();
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    /**
     * Holds the real `keyUse` keybind down -- Minecraft.handleKeybinds()
     * (called every client tick regardless of this mod) reads this every
     * tick on its own and drives the actual eat interaction from it,
     * confirmed to work where a direct useItem() call didn't. Idempotent
     * to call every tick while eating should continue.
     */
    private static void holdUseKey() {
        Minecraft.getInstance().options.keyUse.setDown(true);
    }

    private static void releaseUseKey() {
        Minecraft.getInstance().options.keyUse.setDown(false);
    }

    private static boolean isEdible(final ItemStack stack) {
        return !stack.isEmpty() && stack.get(DataComponents.FOOD) != null;
    }

    /**
     * True only if starting to eat this stack right now would actually
     * succeed, per the real vanilla gate: Consumable's startConsuming()
     * -> canConsume() -> Player.canEat(canAlwaysEat). Mirroring canEat()
     * itself (rather than re-deriving invulnerable/hunger state by hand)
     * keeps this correct if that logic ever changes.
     */
    private static boolean isEatableNow(final TickContext ctx, final ItemStack stack) {
        if (!isEdible(stack)) {
            return false;
        }
        FoodProperties food = stack.get(DataComponents.FOOD);
        return ctx.player.canEat(food.canAlwaysEat());
    }
}
