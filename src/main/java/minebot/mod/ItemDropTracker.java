package minebot.mod;

import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Diffs the client's currently-loaded ItemEntity list every tick and
 * broadcasts add/remove events -- the ground-truth signal for "a real
 * item actually appeared/disappeared nearby", independent of (and a tick
 * or more ahead of) whatever ends up in the bot's own inventory. Added
 * so MiningController.collect (Python side) can confirm a mined block or
 * killed entity genuinely produced a drop, rather than trusting
 * collect_result's "destroyed/killed" signal alone (which fires the
 * instant the block/entity is gone, before any drop has necessarily
 * even spawned yet, let alone been picked up) -- see the collect_result
 * broadcast's own docstring in MinebotMod for that distinction.
 *
 * Mirrors MinebotMod.broadcastEntityEvents' own add/move/remove diffing
 * shape exactly (same knownIds-diff pattern), just scoped to ItemEntity
 * instead of Player, and broadcasting item/count instead of a name.
 * "remove" covers both real pickup (by this bot or anyone else) and
 * natural despawn (vanilla items despawn after 5 minutes) -- this
 * tracker can't distinguish those two cases from ItemEntity state alone,
 * only "it's not there anymore"; callers that care about *why* it's
 * gone should cross-reference their own inventory/position state
 * instead (e.g. MiningController checks InventoryTracker.gained_items()
 * to tell "picked up by us" apart from "someone/something else took it
 * or it despawned").
 */
public final class ItemDropTracker {
    private final Set<Integer> knownItemEntityIds = new HashSet<>();

    /** Safe to call every tick. */
    public void tick(final ClientLevel level, final ControlClient controlClient) {
        Set<Integer> currentIds = new HashSet<>();
        Map<Integer, ItemEntity> currentById = new HashMap<>();
        // entitiesForRendering() is every entity the client currently
        // has loaded (naturally bounded by render/simulation distance,
        // the same as any client-side entity iteration) -- filtering by
        // type here rather than using a bounded-AABB search (like
        // EntityFinder's one-shot !find scan does) since this runs every
        // tick as a continuous tracker, not an on-demand radius search.
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof ItemEntity itemEntity) {
                currentIds.add(itemEntity.getId());
                currentById.put(itemEntity.getId(), itemEntity);
            }
        }

        for (Integer id : currentIds) {
            if (!knownItemEntityIds.contains(id)) {
                broadcastItemDropEvent("add", id, currentById.get(id), controlClient);
            }
            // No "move" case -- unlike players (which the bot needs live
            // position updates for to keep following/pathing toward),
            // nothing currently needs to track a ground item's position
            // changing tick-to-tick (physics settling, water carrying it,
            // ...). Add this if a future consumer needs it.
        }
        for (Integer id : knownItemEntityIds) {
            if (!currentIds.contains(id)) {
                broadcastItemDropEvent("remove", id, null, controlClient);
            }
        }

        knownItemEntityIds.clear();
        knownItemEntityIds.addAll(currentIds);
    }

    private static void broadcastItemDropEvent(final String action, final int id, final ItemEntity entity, final ControlClient controlClient) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "item_drop");
        event.addProperty("action", action);
        event.addProperty("id", id);
        if (entity != null) {
            ItemStack stack = entity.getItem();
            event.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            event.addProperty("count", stack.getCount());
            event.addProperty("x", entity.getX());
            event.addProperty("y", entity.getY());
            event.addProperty("z", entity.getZ());
        }
        controlClient.sendEvent(event.toString());
    }
}
