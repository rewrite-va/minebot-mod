package minebot.mod.task;

import minebot.mod.InventoryController;
import minebot.mod.MinebotMod;
import minebot.mod.statemachine.TickContext;
import minebot.mod.statemachine.playerintention.NavIntent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The first real Task -- !give. Ported from prompt.txt's own step-by-step
 * design: walk to the recipient (if one was given) by publishing the
 * SAME NavIntent.NAV_TARGET/NAV_ARRIVED channel every other Legs-driving
 * publisher already uses (PlayerIntentionFollowNode/CombatEngagement/...
 * -- see NavIntent's own docstring), then drop the item once arrived.
 * Legs needs no new node for this (see LegsStateMachine's own docstring
 * for the shouldNavigate simplification that made this possible) -- this
 * task publishes exactly the facts LegsNavigateNode already knows how to
 * walk toward, with zero awareness on Legs' side that a Task (rather than
 * a peer SM) is the one asking.
 *
 * `item` is always a concrete registry id by the time this runs -- "the
 * last item picked up" (bare "!give") is resolved Python-side
 * (InventoryTracker.last_gained_item, in minebot/bot/inventory.py), NOT
 * here: an earlier version of this class re-resolved it independently via
 * a mod-side InventoryController.lastPickedUpItem() tracker, but that was
 * a second, differently-timed "what did we most recently pick up"
 * implementation duplicating InventoryTracker's own already-tested one
 * (right down to the same "which item, if several changed at once"
 * ambiguity) -- removed per explicit direction so there's exactly one
 * source of truth for this fact. `quantity <= 0` is passed straight
 * through to InventoryController.dropItem's own "drop the whole stack"
 * meaning (see its own docstring).
 *
 * `recipientEntityId` null means "give to the caller" per prompt.txt --
 * resolved here as "drop at the bot's own feet, no navigation needed",
 * since there's no real Minecraft mechanic for "give to a specific
 * player" any more than GiveTracker's own (superseded) docstring already
 * explained -- dropping at the caller's own feet already means the caller
 * (who is, after all, the one who just typed !give) can simply pick it up
 * themselves.
 */
public final class GiveTask implements Task {
    private final Integer recipientEntityId;
    private final String item;
    private final int quantity;

    private boolean finished;

    public GiveTask(final Integer recipientEntityId, final String item, final int quantity) {
        this.recipientEntityId = recipientEntityId;
        this.item = item;
        this.quantity = quantity;
    }

    @Override
    public void onEnter(final TickContext ctx) {
        tick(ctx);
    }

    @Override
    public void tick(final TickContext ctx) {
        if (finished) {
            return;
        }

        if (recipientEntityId == null) {
            drop(ctx);
            return;
        }

        Entity recipient = ctx.level.getEntity(recipientEntityId);
        if (recipient == null) {
            MinebotMod.LOGGER.warn("give: recipient entity {} no longer visible, abandoning", recipientEntityId);
            finished = true;
            return;
        }

        Vec3 recipientPosition = recipient.position();
        ctx.blackboard.put(NavIntent.NAV_TARGET, new NavIntent.Target(recipientPosition, NavIntent.defaultStopDistance()));
        double distance = ctx.player.position().distanceTo(recipientPosition);
        boolean arrived = distance <= NavIntent.defaultStopDistance();
        ctx.blackboard.put(NavIntent.NAV_ARRIVED, arrived);
        if (arrived) {
            drop(ctx);
        }
    }

    private void drop(final TickContext ctx) {
        InventoryController.dropItem(ctx.player, item, quantity);
        finished = true;
    }

    @Override
    public void onExit(final TickContext ctx) {
        ctx.blackboard.put(NavIntent.NAV_TARGET, null);
        ctx.blackboard.put(NavIntent.NAV_ARRIVED, true);
    }

    @Override
    public boolean isFinished() {
        return finished;
    }
}
