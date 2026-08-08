package minebot.mod.statemachine.playerintention;

import minebot.mod.EntityFinder;
import minebot.mod.statemachine.Command;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import net.minecraft.world.entity.Entity;

/**
 * Fights a single, one-shot target -- renamed from GeneralCombatNode
 * (see git history) once !defend needed the exact same fighting
 * mechanics (target-tracking, kiting, weapon selection) but with
 * standing, auto-retargeting semantics instead of !kill's one-shot
 * "fight this, then stop" behavior -- the actual engagement bookkeeping
 * moved to the shared CombatEngagement utility (see its own docstring),
 * this node now only owns TARGET RESOLUTION and its own isFinished()/
 * exit semantics, both genuinely specific to !kill.
 *
 * KILL is a one-shot TRIGGER, NOT a PlayerIntention value -- !kill
 * doesn't change what the player is actually asking the bot to do
 * overall (IDLE/FOLLOW/DEFEND stays exactly as it was), it just
 * interrupts PlayerIntention into fighting something, the same way low
 * health used to interrupt it into the now-deleted SELF_HEAL. This node
 * reads its
 * target query directly off ctx.commands in onEnter (the SAME tick's
 * Command.Kill that caused the entry edge to fire -- onEnter runs
 * synchronously inside the same StateMachine.tick() call that matched
 * the edge, so this tick's ctx.commands is guaranteed to still contain
 * it), not from PlayerIntention -- there's nothing to "combatQuery" in
 * PlayerIntention at all, per explicit direction. A re-issued !kill
 * while already in KILL re-enters (see PlayerIntentionStateMachine's own
 * KILL->KILL self-loop for a fresh Command.Kill) and re-reads whatever
 * query THAT command carried.
 *
 * isFinished() once the target dies/is removed/can't be resolved at all
 * -- PlayerIntentionStateMachine's own exit edges go straight to
 * PlayerIntention's current state (IDLE/FOLLOW/DEFEND). Death no longer
 * interacts with this node's own lifecycle at all -- see DeathWatcher's
 * own docstring for why dying doesn't touch PlayerIntention/KILL anymore
 * (an earlier version had a DEAD state on this axis that KILL was
 * deliberately unreachable FROM, per explicit direction that "!kill for
 * now is a one shot"; that concern is moot now since there's no DEAD
 * state here to be unreachable from in the first place -- a kill in
 * progress simply keeps running through the bot's own death/respawn,
 * same as it would through any other incidental interruption that
 * doesn't touch PlayerIntentionState).
 */
public final class PlayerIntentionKillNode implements StateNode<PlayerIntentionState> {
    private int targetEntityId = -1;
    private boolean finished;

    @Override
    public void onEnter(final TickContext ctx, final PlayerIntentionState previousState) {
        finished = false;
        Entity target = resolveTarget(ctx);
        targetEntityId = target != null ? target.getId() : -1;
        // Publish real values immediately -- see CombatEngagement's own
        // docstring for why waiting for onTick (which doesn't run until
        // NEXT tick) leaves stale data from whatever PlayerIntention state this
        // interrupted visible for one real tick.
        publishOrFinish(ctx, target);
    }

    @Override
    public void onTick(final TickContext ctx) {
        Entity target = ctx.level.getEntity(targetEntityId);
        publishOrFinish(ctx, target != null && !target.isRemoved() ? target : null);
    }

    private void publishOrFinish(final TickContext ctx, final Entity target) {
        if (target == null) {
            CombatEngagement.clear(ctx);
            finished = true;
            return;
        }
        CombatEngagement.publish(ctx, target);
    }

    @Override
    public void onExit(final TickContext ctx) {
        CombatEngagement.clear(ctx);
        targetEntityId = -1;
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    /** entityId, when present, is a player already resolved Python-side (EntityTracker, same as !defend) -- looked up directly via ctx.level.getEntity, no EntityFinder scan needed, and takes priority over query. Otherwise query == null means "nearest hostile mob" (EntityFinder.findNearestHostile); a real query string means "nearest entity of that exact registry type" (EntityFinder.findNearestEntity), prefixed with "minecraft:" if the query has no namespace of its own -- same shape the old (pre-deletion) !attack command's resolveNearestEntityOnly used. Reads the LAST Command.Kill in ctx.commands this tick (there should only ever be one -- CommandBus.drain() is called once per tick -- but last-wins is a harmless, simple tiebreak if that ever changes). No Command.Kill present at all (defensive only -- can't happen given this only ever runs from an edge that itself required one) falls back to "nearest hostile". */
    private static Entity resolveTarget(final TickContext ctx) {
        Integer entityId = null;
        String query = null;
        boolean found = false;
        for (Command command : ctx.commands) {
            if (command instanceof Command.Kill kill) {
                entityId = kill.entityId();
                query = kill.query();
                found = true;
            }
        }
        if (!found) {
            return EntityFinder.findNearestHostile(ctx.level, ctx.player.position(), CombatEngagement.SEARCH_RADIUS);
        }
        if (entityId != null) {
            Entity target = ctx.level.getEntity(entityId);
            return target != null && !target.isRemoved() ? target : null;
        }
        if (query == null || query.isBlank()) {
            return EntityFinder.findNearestHostile(ctx.level, ctx.player.position(), CombatEngagement.SEARCH_RADIUS);
        }
        String registryId = query.contains(":") ? query : "minecraft:" + query;
        return EntityFinder.findNearestEntity(ctx.level, ctx.player.position(), registryId, CombatEngagement.SEARCH_RADIUS);
    }
}
