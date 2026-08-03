package minebot.mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

/**
 * Inventory manipulation triggered by Python's control-channel commands
 * (move_to_hotbar/equip/drop) -- everything here goes through the same
 * real-interaction paths a human client uses (container clicks / the Q-drop
 * action), never a direct ItemStack/Inventory mutation, matching FoodEater
 * and DoorOpener's existing "no protocol reimplementation" rule.
 *
 * The one exception, `moveToHotbar`, is FoodEater's own existing
 * setSelectedSlot/pickSlot pair (not packet-synced by itself -- see its
 * docstring) generalized to an explicit target hotbar slot instead of
 * always "whichever slot happens to be selected"; kept as-is rather than
 * switched to a container click because that's the same idiom FoodEater
 * already relies on, confirmed live-safe there.
 */
public final class InventoryActions {
    private InventoryActions() {
    }

    /**
     * Moves the item in `slot` into hotbar slot `hotbarSlot` (0-8),
     * selecting it as the active hotbar slot in the process -- same
     * mechanism as pressing a number key while holding a main-inventory
     * item's matching item, generalized to a specific target slot instead
     * of "whatever's currently selected". A no-op if `slot` is already
     * hotbar slot `hotbarSlot`.
     */
    public static void moveToHotbar(final LocalPlayer player, final int slot, final int hotbarSlot) {
        Inventory inventory = player.getInventory();
        if (hotbarSlot < 0 || hotbarSlot > 8) {
            MinebotMod.LOGGER.warn("move_to_hotbar: hotbar_slot {} out of range 0-8", hotbarSlot);
            return;
        }
        inventory.setSelectedSlot(hotbarSlot);
        if (slot == hotbarSlot) {
            return;
        }
        if (Inventory.isHotbarSlot(slot)) {
            // Swapping two hotbar slots: pickSlot always targets the
            // *selected* slot, and slot is itself a hotbar slot here, so
            // select it and pick the destination instead to get a swap
            // rather than a no-op.
            inventory.setSelectedSlot(slot);
            inventory.pickSlot(hotbarSlot);
            inventory.setSelectedSlot(hotbarSlot);
        } else {
            inventory.pickSlot(slot); // swaps slot's contents into the now-selected hotbarSlot
        }
    }

    /**
     * Equips whatever's in `slot` into its matching armor/offhand slot via
     * a real container click (InventoryMenu.quickMoveStack routes armor
     * items to the correct empty armor slot automatically), the same
     * shift-click-to-equip a human would use -- sends an actual
     * ServerboundContainerClickPacket, unlike moveToHotbar's local-state
     * swap above. No-op (server will simply reject/ignore) if the item
     * isn't equippable.
     */
    public static void equip(final LocalPlayer player, final int slot) {
        int containerMenuSlot = mainInventorySlotToContainerSlot(slot);
        Minecraft.getInstance().gameMode.handleContainerInput(
            player.containerMenu.containerId, containerMenuSlot, 0, ContainerInput.QUICK_MOVE, player
        );
    }

    /**
     * Drops up to `count` of `slot`'s contents (capped at however many are
     * actually there): the currently-selected hotbar slot goes through
     * LocalPlayer.drop (the real Q/Ctrl+Q action, sends
     * ServerboundPlayerActionPacket), any other slot goes through a
     * container-click THROW (the real click-and-drop-from-inventory-screen
     * action, sends ServerboundContainerClickPacket) -- both are real
     * client-parity paths, neither is a direct inventory mutation.
     *
     * Real drop actions only support "drop one" or "drop the whole
     * stack", not an arbitrary count in a single action -- `count >=`
     * the stack size drops the whole stack in one shot (matching
     * Ctrl+Q), anything smaller drops one at a time in a loop, same as
     * repeatedly pressing plain Q.
     */
    public static void drop(final LocalPlayer player, final int slot, final int count) {
        ItemStack stack = player.getInventory().getItem(slot);
        if (stack.isEmpty() || count <= 0) {
            return;
        }
        int toDrop = Math.min(count, stack.getCount());
        boolean isSelectedHotbarSlot = slot == player.getInventory().getSelectedSlot();

        if (toDrop >= stack.getCount()) {
            if (isSelectedHotbarSlot) {
                player.drop(true);
            } else {
                Minecraft.getInstance().gameMode.handleContainerInput(
                    player.containerMenu.containerId, mainInventorySlotToContainerSlot(slot), 1, ContainerInput.THROW, player
                );
            }
            return;
        }

        for (int i = 0; i < toDrop; i++) {
            if (isSelectedHotbarSlot) {
                player.drop(false);
            } else {
                Minecraft.getInstance().gameMode.handleContainerInput(
                    player.containerMenu.containerId, mainInventorySlotToContainerSlot(slot), 0, ContainerInput.THROW, player
                );
            }
        }
    }

    /**
     * InventoryMenu (the always-live containerMenu even with no screen
     * open) numbers slots differently from Inventory itself. Confirmed by
     * disassembling AbstractContainerMenu.addStandardInventorySlots (the
     * method InventoryMenu's constructor uses to build its slot list):
     * it calls addInventoryExtendedSlots first (which wraps Inventory's
     * own indices 9-35, the 27 main-storage slots, unchanged -- landing
     * at InventoryMenu slots 9-35 too, i.e. no shift), then
     * addInventoryHotbarSlots (which wraps Inventory's indices 0-8, the
     * hotbar -- landing at InventoryMenu slots 36-44, a +36 shift, right
     * after the 27 main-storage slots already added). So: hotbar slots
     * need +36, main-storage slots need +0 -- NOT a uniform +9 as an
     * earlier version of this method assumed (that would have clicked
     * the wrong slot for every single call). Armor/offhand (Inventory
     * slots 36-42) aren't addressed this way -- equip's whole point is
     * that the destination armor slot is picked automatically by
     * quickMoveStack, and drop doesn't currently support dropping
     * directly from an equipped slot.
     */
    private static int mainInventorySlotToContainerSlot(final int inventorySlot) {
        return Inventory.isHotbarSlot(inventorySlot) ? inventorySlot + 36 : inventorySlot;
    }
}
