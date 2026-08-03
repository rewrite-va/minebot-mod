package minebot.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Broadcasts a full inventory snapshot whenever it changes, the same
 * change-only-broadcast shape MinebotMod already uses for health -- Python
 * keeps its own mirror (InventoryTracker) fed purely from these events, no
 * polling/on-demand query needed.
 *
 * `Inventory.getItem(slot)` is a uniform accessor across the whole
 * 0..getContainerSize()-1 range in 26.1.2 -- unlike some earlier versions,
 * armor/offhand/body/saddle (slots 36-42) aren't a separate array the
 * caller has to reach into specially, `Inventory` dispatches to
 * `EntityEquipment` internally for those indices. Slot numbers in the
 * broadcast event match this same indexing, so InventoryActions' commands
 * (which reference slots the same way) and this reporter always agree on
 * what a given slot number means.
 */
public final class InventoryReporter {
    private String lastSnapshotJson;

    /** Safe to call every tick -- builds a snapshot and only actually sends it if it differs from the last one sent. */
    public void maybeBroadcast(final Inventory inventory, final ControlClient controlClient) {
        JsonObject event = buildSnapshot(inventory);
        String json = event.toString();
        if (json.equals(lastSnapshotJson)) {
            return;
        }
        lastSnapshotJson = json;
        controlClient.sendEvent(json);
    }

    private static JsonObject buildSnapshot(final Inventory inventory) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "inventory");
        event.addProperty("selected_slot", inventory.getSelectedSlot());

        JsonArray slots = new JsonArray();
        int size = inventory.getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            JsonObject slotJson = new JsonObject();
            slotJson.addProperty("slot", slot);
            slotJson.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            slotJson.addProperty("count", stack.getCount());
            if (stack.isDamaged()) {
                slotJson.addProperty("damage", stack.getDamageValue());
                slotJson.addProperty("max_damage", stack.getMaxDamage());
            }
            slots.add(slotJson);
        }
        event.add("slots", slots);
        return event;
    }
}
