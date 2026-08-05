package minebot.mod;

import minebot.mod.config.Messages;
import minebot.mod.util.EdgeTrigger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/**
 * Eats food from the bot's own inventory once health drops to 80% or
 * below, the same way a real player would.
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
 * a canAlwaysEat item and to report the real reason ("hunger full") rather
 * than silently failing.
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
 * work where the programmatic call didn't.
 */
public final class FoodEater {
    private static final float LOW_HEALTH_FRACTION = 0.80f;

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

    /** Releases the use key if it happens to be held -- see MinebotMod's death-tick handling for why this needs to be reachable even when maybeEat itself isn't being called. */
    public void releaseUseKeyIfHeld() {
        releaseUseKey();
    }

    /** Safe to call every tick. */
    public void maybeEat(final LocalPlayer player) {
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
            releaseUseKey();
            return;
        }

        // Prefer the offhand if it's edible AND actually eatable right
        // now -- a canAlwaysEat item there beats hunting through the
        // main inventory, and needs no hotbar selection at all.
        if (isEatableNow(player, player.getOffhandItem())) {
            hungerBlockedWhileLow.reset();
            holdUseKey();
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
            // Delegates to InventoryActions.moveToHotbar's own hotbar
            // slot 8 (arbitrary but consistent -- same slot BlockBreaker's
            // own tool-switch reuses) rather than duplicating its swap
            // logic here -- this method used to do its own direct
            // getItem/setItem swap for the main-storage case, the exact
            // same bug class (see moveToHotbar's own docstring) confirmed
            // live to genuinely desync the server's view of the held item
            // from what other clients actually see, not just a redundant
            // local mutation. moveToHotbar's current implementation is a
            // real container-click swap for that case.
            InventoryActions.moveToHotbar(player, slot, Inventory.isHotbarSlot(slot) ? slot : 8);
            holdUseKey();
            return;
        }

        releaseUseKey();

        if (fallbackEdibleSlot >= 0) {
            // There IS food, but canEat() would reject every bit of it
            // right now (normal food + full hunger bar) -- report the
            // real reason once per episode instead of holding the use
            // key against food that can't actually be eaten.
            if (hungerBlockedWhileLow.fire(true)) {
                sendChat(player, Messages.get("food_eater.hunger_full"));
            }
            return;
        }

        if (noFoodWhileLow.fire(true)) {
            sendChat(player, Messages.get("food_eater.no_food"));
        }
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

    /** Releases the use key -- must be called once health/food state no longer calls for eating, or a human retaking real control would find it stuck held. */
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
