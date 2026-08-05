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
 * action / a real hotbar-select), never a direct ItemStack/Inventory
 * mutation, matching FoodEater and DoorOpener's existing "no protocol
 * reimplementation" rule.
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
     *
     * Two real client-parity paths, mirroring exactly how AutoTools (a
     * real, working, third-party auto-tool-switch mod -- read directly to
     * find this gap, see FINDINGS.md) implements the identical operation
     * in its own `selectItem`:
     * - `slot` already in the hotbar (0-8): no swap needed at all, the
     *   item's already reachable -- just a real `setSelectedSlot`, same
     *   as pressing that number key (`Minecraft.handleKeybinds`'s own
     *   hotbar-select branch does exactly this one call).
     * - `slot` in main storage (9-35): a real container click
     *   (`MultiPlayerGameMode.handleContainerInput`,
     *   `ContainerInput.SWAP`) -- NOT a direct `Inventory.setItem`
     *   mutation. This used to do the swap locally, which does update
     *   this client's own `Inventory` correctly but is not itself
     *   packet-synced (relying entirely on `ensureHasSentCarriedItem`'s
     *   own later, separate, best-effort sync of just the *selected
     *   index* -- never the actual slot *contents* swap). Confirmed live
     *   via an extensive human-driven A/B test (see FINDINGS.md's "the
     *   real cause was InventoryActions.moveToHotbar's local-only swap
     *   genuinely desyncing the server's view of the held item" section):
     *   even a real physical keypress on the bot's own client left the
     *   server (and so every other client, including a human observer)
     *   disagreeing about which item the bot was holding, reproducibly,
     *   in both directions -- and it stopped the moment the swap went
     *   through a real container-click-synced action instead.
     *   `handleContainerInput` both predicts the change locally AND sends
     *   the real `ServerboundContainerClickPacket` reporting exactly
     *   which slots changed (confirmed via decompiled source), which is
     *   the piece the old local-only mutation always skipped.
     *
     * `SWAP`'s own real slot-numbering split (confirmed via decompiled
     * `AbstractContainerMenu.clicked`): the container-menu `slotNum`
     * parameter needs the same +36 hotbar-index shift
     * `mainInventorySlotToContainerSlot` already applies elsewhere in
     * this class, but `buttonNum` (which hotbar slot to swap *into*) is
     * a raw 0-8 index into `Inventory` directly, unshifted.
     *
     * Earlier history: this method used to call `Inventory.pickSlot`
     * (found to silently ignore any chosen destination slot entirely --
     * see git history/FINDINGS.md), then a direct `getItem`/`setItem`
     * swap (found to leave the server-side view of the held item
     * genuinely desynced, per the investigation above) -- both were real
     * bugs, not just theoretical concerns, each confirmed by a live
     * report before being fixed.
     */
    public static void moveToHotbar(final LocalPlayer player, final int slot, final int hotbarSlot) {
        Inventory inventory = player.getInventory();
        if (hotbarSlot < 0 || hotbarSlot > 8) {
            MinebotMod.LOGGER.warn("move_to_hotbar: hotbar_slot {} out of range 0-8", hotbarSlot);
            return;
        }
        if (slot == hotbarSlot) {
            inventory.setSelectedSlot(hotbarSlot);
            return;
        }
        if (Inventory.isHotbarSlot(slot)) {
            // Already in the hotbar somewhere else -- no swap needed,
            // the item's already reachable, just select it directly
            // (the exact same call pressing that number key would make).
            inventory.setSelectedSlot(slot);
            return;
        }
        int containerMenuSlot = mainInventorySlotToContainerSlot(slot);
        Minecraft.getInstance().gameMode.handleContainerInput(
            player.containerMenu.containerId, containerMenuSlot, hotbarSlot, ContainerInput.SWAP, player
        );
        inventory.setSelectedSlot(hotbarSlot);
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
     * Shift-click-moves whatever's in hotbar slot `hotbarSlot` into main
     * storage (InventoryMenu.quickMoveStack shifts a hotbar item into the
     * first available main-storage slot, the same as a human
     * shift-clicking it there) -- a real ServerboundContainerClickPacket,
     * same as equip above, unlike moveToHotbar's own local-only swap. Added
     * specifically to test whether moveToHotbar's local mutation is the
     * actual cause of a live-reported client/server held-item desync (see
     * FINDINGS.md) -- this gets an item genuinely out of the hotbar via a
     * real synced action first, as a clean baseline before moveToHotbar
     * brings it back.
     */
    public static void moveToMainStorage(final LocalPlayer player, final int hotbarSlot) {
        int containerMenuSlot = mainInventorySlotToContainerSlot(hotbarSlot);
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
