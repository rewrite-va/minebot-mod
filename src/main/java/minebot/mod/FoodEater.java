package minebot.mod;

import minebot.mod.config.Messages;
import minebot.mod.util.EdgeTrigger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Eats food from the bot's own inventory once health drops to 20% or
 * below, the same way a real player would -- selects a food item into the
 * hotbar if needed and sends a real MultiPlayerGameMode.useItem interaction
 * (the same client API DoorOpener uses for useItemOn), not a direct
 * world/inventory-state mutation.
 *
 * "Is this food" is a data-component check (DataComponents.FOOD), not the
 * older Item.getFoodProperties() method, which no longer exists in this
 * version -- confirmed via decompiled ItemStack/DataComponentHolder.
 */
public final class FoodEater {
    private static final float LOW_HEALTH_FRACTION = 0.20f;

    // Each fires once on the tick health first drops to/below the
    // threshold (the "eating" line) or once per such episode if that tick
    // (or a later one in the same episode) finds no edible item (the
    // "no food" line) -- both keyed off the same low-health condition, so
    // recovering above the threshold resets both and arms them to fire
    // again on the next drop. See EdgeTrigger's docstring for why this
    // isn't just a couple of hand-rolled booleans.
    private final EdgeTrigger lowHealth = new EdgeTrigger();
    private final EdgeTrigger noFoodWhileLow = new EdgeTrigger();

    /**
     * Safe to call every tick -- isUsingItem() naturally makes this a
     * no-op while a previous call's eating animation is still playing, so
     * there's no separate throttle needed the way DoorOpener needs one
     * for its instant (non-animated) interaction.
     */
    public void maybeEat(final LocalPlayer player) {
        if (player.isUsingItem()) {
            return; // already chewing -- let it finish, don't restart/spam
        }

        boolean isLowHealth = player.getHealth() <= player.getMaxHealth() * LOW_HEALTH_FRACTION;
        if (lowHealth.fire(isLowHealth)) {
            // One decimal place, not Math.round -- health is reported in
            // half-heart increments (2 HP per heart), so e.g. 0.5 HP is a
            // real, nonzero quarter-heart; rounding that to the nearest
            // whole heart reported "0 hearts" for a player who was still
            // alive, which read as a bug (found live: chat showed "I have
            // 0 hearts!" while the player watching could see a sliver of
            // a heart still up).
            String hearts = String.format(java.util.Locale.ROOT, "%.1f", player.getHealth() / 2.0f);
            sendChat(player, Messages.get("food_eater.eating", "hearts", hearts));
        }
        if (!isLowHealth) {
            noFoodWhileLow.reset(); // arm it to fire again on the next low-health episode
            return;
        }

        if (isEdible(player.getOffhandItem())) {
            Minecraft.getInstance().gameMode.useItem(player, InteractionHand.OFF_HAND);
            return;
        }

        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!isEdible(stack)) {
                continue;
            }

            if (Inventory.isHotbarSlot(slot)) {
                inventory.setSelectedSlot(slot); // synced to the server automatically next tick
            } else {
                inventory.pickSlot(slot); // swaps this main-inventory item into the current hotbar slot
            }
            Minecraft.getInstance().gameMode.useItem(player, InteractionHand.MAIN_HAND);
            return;
        }

        if (noFoodWhileLow.fire(true)) {
            sendChat(player, Messages.get("food_eater.no_food"));
        }
    }

    private static boolean isEdible(final ItemStack stack) {
        return !stack.isEmpty() && stack.get(DataComponents.FOOD) != null;
    }

    private static void sendChat(final LocalPlayer player, final String text) {
        player.connection.sendChat(text);
    }
}
