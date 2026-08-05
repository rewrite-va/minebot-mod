package minebot.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

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
 *
 * Change detection is a plain structural comparison against a lightweight
 * `Snapshot` record, not a JSON round-trip -- this used to build a full
 * `JsonObject` and compare its serialized string against the last one
 * sent, every single tick, purely to answer "did anything change" (JSON
 * is a wire-serialization format, not a comparison tool -- allocating and
 * stringifying a whole tree every tick just to `.equals()` two strings
 * was real, unnecessary overhead for what's actually a small, cheap
 * value comparison). The JSON `JsonObject`/`JsonArray` construction now
 * only happens in `buildEvent`, called at most once per tick and only
 * when a broadcast is actually about to be sent.
 */
public final class InventoryReporter {
    private Snapshot lastSnapshot;
    // A freshly (re)connected Python process has no memory of the bot's
    // current inventory (InventoryTracker starts empty every time) --
    // without forcing one full snapshot right after connect, Python would
    // only ever learn the starting inventory once something *changes*
    // (maybeBroadcast's normal dedup-on-content-change gate), and until
    // then treats itself as carrying nothing at all. Set from
    // MinebotMod.onControlChannelConnected.
    private boolean forceBroadcastOnNextTick = true;

    /** One (slot, item, count, damage, maxDamage) entry -- damage/maxDamage are 0 for an undamaged item, mirroring how buildEvent omits them from the wire event in that case. */
    private record SlotEntry(int slot, String item, int count, int damage, int maxDamage) {
    }

    /** The whole inventory's comparable state for one tick -- two Snapshots are equal (via the free record equals/hashCode) exactly when nothing meaningful changed, entry order included (order tracks slot order, so a pure position swap -- e.g. a tool-switch hotbar swap -- still correctly compares unequal). */
    private record Snapshot(int selectedSlot, List<SlotEntry> entries) {
    }

    /** Safe to call every tick -- computes a cheap snapshot and only actually builds/sends the wire event if it differs from the last one sent, or a fresh connection needs the current state regardless of whether it changed. */
    public void maybeBroadcast(final Inventory inventory, final ControlClient controlClient) {
        Snapshot snapshot = buildSnapshot(inventory);
        boolean changed = !snapshot.equals(lastSnapshot);
        if (!changed && !forceBroadcastOnNextTick) {
            return;
        }
        // Temporarily promoted to info (same reasoning as BlockBreaker's
        // tool-switch logging -- this client's default log4j config
        // filters debug output entirely): added to confirm live whether
        // a pure slot-position swap (same item, same total count, just a
        // different slot index -- e.g. InventoryActions.moveToHotbar's
        // hotbar-swap for tool-switching) counts as a "change" here and
        // triggers its own broadcast. It does, by design -- Snapshot's
        // entry order tracks slot order and selectedSlot is compared
        // too, so a pure swap still compares unequal even though every
        // item count stays identical -- but this makes it directly
        // observable rather than inferred, since a spurious extra
        // broadcast right after a real pickup is suspected of evicting
        // that pickup from InventoryTracker's 2-snapshot gained-items
        // window before !give ever gets a chance to see it.
        MinebotMod.LOGGER.info("inventory: broadcasting change (forced={})", forceBroadcastOnNextTick);
        lastSnapshot = snapshot;
        forceBroadcastOnNextTick = false;
        controlClient.sendEvent(buildEvent(snapshot).toString());
    }

    /** Called on every fresh control-channel connection so the next tick's snapshot is sent unconditionally, even if it's identical to whatever was last sent to a previous (now-gone) connection. */
    public void forceNextBroadcast() {
        forceBroadcastOnNextTick = true;
    }

    private static Snapshot buildSnapshot(final Inventory inventory) {
        List<SlotEntry> entries = new ArrayList<>();
        int size = inventory.getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            int damage = stack.isDamaged() ? stack.getDamageValue() : 0;
            int maxDamage = stack.isDamaged() ? stack.getMaxDamage() : 0;
            entries.add(new SlotEntry(slot, item, stack.getCount(), damage, maxDamage));
        }
        return new Snapshot(inventory.getSelectedSlot(), entries);
    }

    private static JsonObject buildEvent(final Snapshot snapshot) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "inventory");
        event.addProperty("selected_slot", snapshot.selectedSlot());

        JsonArray slots = new JsonArray();
        for (SlotEntry entry : snapshot.entries()) {
            JsonObject slotJson = new JsonObject();
            slotJson.addProperty("slot", entry.slot());
            slotJson.addProperty("item", entry.item());
            slotJson.addProperty("count", entry.count());
            if (entry.damage() > 0 || entry.maxDamage() > 0) {
                slotJson.addProperty("damage", entry.damage());
                slotJson.addProperty("max_damage", entry.maxDamage());
            }
            slots.add(slotJson);
        }
        event.add("slots", slots);
        return event;
    }
}
