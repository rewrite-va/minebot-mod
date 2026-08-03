package minebot.mod;

import minebot.mod.config.Messages;
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

    // Tracks whether the low-health chat lines have already been sent for
    // the *current* low-health episode, so each fires (at most) once per
    // episode instead of every tick for as long as health stays low, but
    // fires again if health recovers above the threshold and later drops
    // again.
    private boolean announcedEating = false;
    private boolean announcedNoFood = false;

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
        if (player.getHealth() > player.getMaxHealth() * LOW_HEALTH_FRACTION) {
            announcedEating = false;
            announcedNoFood = false;
            return;
        }

        if (!announcedEating) {
            announcedEating = true;
            int hearts = Math.round(player.getHealth() / 2.0f);
            sendChat(player, Messages.get("food_eater.eating", "hearts", hearts));
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

        if (!announcedNoFood) {
            announcedNoFood = true;
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
