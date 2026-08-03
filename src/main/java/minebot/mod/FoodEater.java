package minebot.mod;

import minebot.mod.config.Messages;
import minebot.mod.util.EdgeTrigger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
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
 *
 * IMPORTANT gating subtlety (found via decompiled Player/Consumable
 * source, net/minecraft/world/entity/player/Player.java and
 * net/minecraft/world/item/component/Consumable.java): calling
 * gameMode.useItem() on a food item does NOT unconditionally start eating.
 * Item.use() for a food item delegates to Consumable.startConsuming(),
 * which first calls Consumable.canConsume() ->
 * Player.canEat(foodProperties.canAlwaysEat()), and:
 *
 *     canEat(canAlwaysEat) == invulnerable || canAlwaysEat || foodData.needsFood()
 *     foodData.needsFood() == foodLevel < 20
 *
 * Health and hunger are separate bars. A bot that took combat damage can be
 * at critical health while its hunger bar is still full (foodLevel == 20).
 * In that state canEat(false) is false for every normal food item, so
 * startConsuming() returns InteractionResult.FAIL, startUsingItem() is
 * never called, and useItem() is a silent no-op -- forever, no matter how
 * many times per tick it's called or how low health gets. The only food
 * that still works in that state is one whose FoodProperties.canAlwaysEat()
 * is true (golden apple, golden carrot, etc.), since that short-circuits
 * the hunger check entirely.
 *
 * So this class now mirrors that same gate client-side (via the public
 * Player.canEat()) before calling useItem(), for two reasons: (1) prefer a
 * canAlwaysEat item first, since that's the only kind of food that can
 * actually save a bot that's dying with a full stomach; (2) avoid calling
 * useItem() at all when nothing in inventory is currently eatable per
 * canEat() -- that call would just be rejected every tick with no
 * progress, which is both wasted work and the "spams right-click and never
 * actually eats" symptom that was observed live. When that happens we
 * report it distinctly from "no food" (food_eater.hunger_full), since the
 * bot does have food -- vanilla's own hunger rules are what's blocking it.
 */
public final class FoodEater {
    private static final float LOW_HEALTH_FRACTION = 0.20f;

    // Each fires once on the tick health first drops to/below the
    // threshold (the "eating" line) or once per such episode if that tick
    // (or a later one in the same episode) finds no edible item (the
    // "no food" line) or finds food but can't currently eat any of it
    // because hunger is full (the "hunger full" line) -- all keyed off the
    // same low-health condition, so recovering above the threshold resets
    // them and arms them to fire again on the next drop. See EdgeTrigger's
    // docstring for why this isn't just a couple of hand-rolled booleans.
    private final EdgeTrigger lowHealth = new EdgeTrigger();
    private final EdgeTrigger noFoodWhileLow = new EdgeTrigger();
    private final EdgeTrigger hungerBlockedWhileLow = new EdgeTrigger();

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
            hungerBlockedWhileLow.reset();
            return;
        }

        // Prefer the offhand if it's edible AND actually eatable right
        // now (see class doc) -- a canAlwaysEat item there beats hunting
        // through the main inventory.
        if (isEatableNow(player, player.getOffhandItem())) {
            hungerBlockedWhileLow.reset();
            Minecraft.getInstance().gameMode.useItem(player, InteractionHand.OFF_HAND);
            return;
        }

        Inventory inventory = player.getInventory();
        int fallbackEdibleSlot = -1; // edible but currently blocked by canEat() -- e.g. hunger full
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!isEdible(stack)) {
                continue;
            }
            if (!isEatableNow(player, stack)) {
                if (fallbackEdibleSlot < 0) {
                    fallbackEdibleSlot = slot;
                }
                continue;
            }

            hungerBlockedWhileLow.reset();
            selectSlot(inventory, slot);
            Minecraft.getInstance().gameMode.useItem(player, InteractionHand.MAIN_HAND);
            return;
        }

        if (fallbackEdibleSlot >= 0) {
            // There IS food, but canEat() would reject every bit of it
            // right now (normal food + full hunger bar). Calling
            // useItem() here would be rejected every tick with zero
            // progress -- that's the "spams right-click and never
            // actually eats" symptom, so don't call it; just report the
            // real reason once per episode instead.
            if (hungerBlockedWhileLow.fire(true)) {
                sendChat(player, Messages.get("food_eater.hunger_full"));
            }
            return;
        }

        if (noFoodWhileLow.fire(true)) {
            sendChat(player, Messages.get("food_eater.no_food"));
        }
    }

    private static void selectSlot(final Inventory inventory, final int slot) {
        if (Inventory.isHotbarSlot(slot)) {
            inventory.setSelectedSlot(slot); // synced to the server automatically next tick
        } else {
            inventory.pickSlot(slot); // swaps this main-inventory item into the current hotbar slot
        }
    }

    private static boolean isEdible(final ItemStack stack) {
        return !stack.isEmpty() && stack.get(DataComponents.FOOD) != null;
    }

    /**
     * True only if calling useItem() on this stack right now would
     * actually start eating, per the real vanilla gate: Consumable's
     * startConsuming() -> canConsume() -> Player.canEat(canAlwaysEat).
     * Mirroring canEat() itself (rather than re-deriving invulnerable/
     * hunger state by hand) keeps this correct if that logic ever changes.
     */
    private static boolean isEatableNow(final LocalPlayer player, final ItemStack stack) {
        if (!isEdible(stack)) {
            return false;
        }
        FoodProperties food = stack.get(DataComponents.FOOD);
        return player.canEat(food.canAlwaysEat());
    }

    private static void sendChat(final LocalPlayer player, final String text) {
        player.connection.sendChat(text);
    }
}
