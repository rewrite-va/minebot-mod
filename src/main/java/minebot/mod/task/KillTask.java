package minebot.mod.task;

import minebot.mod.EntityFinder;
import minebot.mod.statemachine.TickContext;
import minebot.mod.statemachine.playerintention.CombatEngagement;
import net.minecraft.world.entity.Entity;

/**
 * !kill -- fights a single, one-shot target. Converted from
 * PlayerIntentionKillNode (a PlayerIntentionState peer-SM node, see git
 * history) to a queued Task per the same reasoning !sleep's own conversion
 * used (see task/SleepTask's own docstring): a kill has a genuine
 * start/finish and doesn't need to be a value on the PlayerIntention axis
 * at all -- it was already documented there as "a one-shot TRIGGER, NOT a
 * PlayerIntention value" even before this move, so Task (which has exactly
 * that shape -- see Task's own docstring) is the more honest home for it,
 * the same conclusion !give/!sleep already reached.
 *
 * The actual engagement bookkeeping (target tracking, kiting, weapon
 * selection) stays in the shared CombatEngagement utility (see its own
 * docstring) -- this class only owns TARGET RESOLUTION and its own
 * isFinished()/exit semantics, both genuinely specific to !kill.
 *
 * A fresh Command.Kill arriving while a KillTask is already
 * TaskController's current task is plain FIFO-enqueued as a SECOND,
 * separate KillTask behind it, same as every other Task -- per explicit
 * direction, no re-targeting the in-progress fight in place (see
 * TaskController's own docstring for why: an earlier version special-
 * cased this, mirroring the old KILL->KILL self-loop from before !kill
 * was a Task at all, removed since a queued second kill is the simpler,
 * preferred behavior).
 *
 * isFinished() once the target dies/is removed/can't be resolved at all --
 * TaskController then just moves on to whatever's next in the queue (or
 * nothing). Death no longer interacts with this task's own lifecycle any
 * more than it ever did as a peer-SM node -- a kill in progress simply
 * keeps running through the bot's own death/respawn, same as any other
 * Task would.
 */
public final class KillTask implements Task {
    private int targetEntityId = -1;
    private boolean finished;

    public KillTask(final Integer entityId, final String query) {
        // Resolution happens in onEnter/retarget, not here -- the
        // constructor runs on TaskController's enqueue() call, but target
        // resolution needs a live TickContext (ctx.level/ctx.player),
        // which isn't available yet. Stash the raw args for onEnter to
        // resolve, same timing GiveTask/SleepTask's own onEnter->tick()
        // convention already establishes.
        this.entityId = entityId;
        this.query = query;
    }

    private final Integer entityId;
    private final String query;

    @Override
    public void onEnter(final TickContext ctx) {
        Entity target = resolveTarget(ctx, entityId, query);
        targetEntityId = target != null ? target.getId() : -1;
        // Publish real values immediately -- see CombatEngagement's own
        // docstring for why waiting for the next tick() call leaves stale
        // data from whatever was running before this visible for one real
        // tick.
        publishOrFinish(ctx, target);
    }

    @Override
    public void tick(final TickContext ctx) {
        if (finished) {
            return;
        }
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

    /** entityId, when present, is a player already resolved Python-side (EntityTracker, same as !defend) -- looked up directly via ctx.level.getEntity, no EntityFinder scan needed, and takes priority over query. Otherwise query == null means "nearest hostile mob" (EntityFinder.findNearestHostile); a real query string means "nearest entity of that exact registry type" (EntityFinder.findNearestEntity), prefixed with "minecraft:" if the query has no namespace of its own. */
    private static Entity resolveTarget(final TickContext ctx, final Integer entityId, final String query) {
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
