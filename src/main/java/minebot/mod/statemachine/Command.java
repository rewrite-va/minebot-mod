package minebot.mod.statemachine;

/**
 * A typed, parsed representation of a backend chat/control-channel
 * command -- the replacement, going forward, for state machines reading
 * ControlState's raw mutated fields directly (see STATE_MACHINE.md and
 * the conversation that produced this class: PlayerIntention SM's FOLLOW node is
 * the first thing built against this instead of ControlState.mode/
 * followEntityId, on purpose, to stop new SM work from growing a second
 * dependency on ControlState's shape).
 *
 * ControlState itself is untouched by this -- existing systems
 * (resolveMovementIntent, GIVE/COLLECT/ATTACK, ...) keep reading it
 * exactly as before. This is a parallel, independent path for whatever
 * SM work chooses to use it, not a replacement for ControlState as a
 * whole (that migration, if it ever happens, is Legs SM's job later --
 * see STATE_MACHINE.md's "Implementation order").
 */
public sealed interface Command {
    record Follow(int entityId, double stopDistance) implements Command {
    }

    /** !stop -- cancel whatever's currently going on. Deliberately carries no data (it's the same signal regardless of what's currently active). */
    record Stop() implements Command {
    }

    /** !kill [query] -- fight a target. `entityId`, when present (non-null), is a player entity id already resolved Python-side via EntityTracker (same Follow-style name->id lookup !defend uses) and takes priority over `query`. Otherwise `query` is a raw entity-type string ("zombie") resolved client-side (mirrors the deleted EntityFinder's old shape -- Python has no non-player entity tracking to resolve this itself). Both null means "nearest hostile mob". */
    record Kill(Integer entityId, String query) implements Command {
    }

    /** !defend [player] -- standing protection mode: auto-fights the nearest hostile to `defendTargetEntityId`, staying near that entity between fights (same real Follow-style name->id resolution Python already does for !follow -- see MovementController's own docstring). `defendTargetEntityId` null means "defend the bot itself" (no argument given). */
    record Defend(Integer defendTargetEntityId) implements Command {
    }

    /** !pickup -- walk to and grab every dropped item within LegsPickupItemsNode's own RADIUS of wherever the bot is standing the instant this command arrives (see its own docstring). Deliberately carries no data (same shape as Stop) -- the anchor position is resolved from the LIVE player position on the tick thread once LegsStateMachine's own edge sees this, not from anything captured here on the WebSocket thread. */
    record Pickup() implements Command {
    }

    /** !give [recipient] [item] [quantity] -- read by TaskController.tick() (not any peer StateMachine) to enqueue a fresh GiveTask, the same cross-thread handoff shape every other Command uses (see CommandBus's own docstring for why this can't just be a direct TaskController.enqueue call from dispatchMessage: TaskController's queue, like Blackboard, is tick-thread-only, and dispatchMessage runs on the WebSocket library's own thread). `recipientEntityId` null means "give to the caller" (no recipient argument -- drop at the bot's own feet, no navigation needed). `item` is always a concrete registry id by the time it reaches here -- "the last item picked up" is resolved Python-side (minebot/bot/inventory.py, off InventoryTracker.last_gained_item), never left for the mod to guess (see GiveTask's own docstring for why: a single source of truth for that fact, not two independently-tracked ones). `quantity <= 0` means "the whole stack" (see InventoryController.dropItem()'s own docstring). */
    record Give(Integer recipientEntityId, String item, int quantity) implements Command {
    }
}
