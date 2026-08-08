package minebot.mod.statemachine.legs;

import minebot.mod.DeathWatcher;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import minebot.mod.statemachine.playerintention.NavIntent;
import net.minecraft.world.phys.Vec3;

/**
 * Walks the bot back to wherever it died -- reads DeathWatcher.
 * DEATH_POSITION (captured the instant death was observed, before
 * respawn() moved the player) and publishes it via the shared NavIntent
 * channel (see its own docstring), then delegates the actual walk to
 * LegsNavigateNode's own walkTowardNavTarget (shared ctx.pathTracker --
 * see TickContext's own docstring), same shape LegsFleeNode uses. Moved
 * here from the deleted PlayerIntentionGoToDeathPositionNode (see
 * PlayerIntentionState's own docstring for the full story of why) -- this
 * is purely a navigation concern (walk somewhere), so it belongs on Legs
 * like every other "walk toward a point" behavior, not on the
 * PlayerIntention axis.
 *
 * Waits for ctx.player.isDeadOrDying() to actually go false before
 * measuring distance/walking at all -- confirmed live this state was
 * entered/finished within the SAME tick, over and over, never actually
 * walking anywhere: LegsStateMachine enters this node the instant
 * DeathWatcher publishes a fresh DEATH_POSITION, which happens on the
 * very same tick DeathWatcher also calls player.respawn() -- but
 * respawn() is a real server round-trip, not an instant local teleport
 * (see DeathWatcher's own docstring/the old PlayerIntionDeadNode's same
 * documented wait), so the player's own position hadn't actually moved
 * yet on that first tick -- still standing exactly where it died, reading
 * as ~0 distance from deathPosition and instantly satisfying
 * NavIntent.defaultStopDistance(), long before any real walk happened.
 * Standing still and reporting not-finished while still dead avoids
 * measuring against that stale pre-teleport position at all.
 *
 * isFinished() once within NavIntent.defaultStopDistance() of the death
 * position (i.e. NAV_ARRIVED) -- at that point LegsStateMachine's own
 * exit edge hands off to LegsPickupItemsNode, which does its own, much
 * tighter walk-onto-the-item approach for each dropped item individually
 * (see its own docstring for why a single shared stop distance doesn't
 * work for both purposes) -- this node only needs to get generally
 * close, not pick up anything itself.
 *
 * onEnter publishes NAV_TARGET/NAV_ARRIVED immediately (delegates
 * straight to onTick) rather than waiting for this node's own onTick to
 * run starting next tick -- see PlayerIntentionKillNode's own docstring for
 * the live bug this exact gap caused there.
 */
public final class LegsGoToDeathPositionNode implements StateNode<LegsState> {
    private boolean finished;

    @Override
    public void onEnter(final TickContext ctx, final LegsState previousState) {
        finished = false;
        onTick(ctx);
    }

    @Override
    public void onTick(final TickContext ctx) {
        if (ctx.player.isDeadOrDying()) {
            // Still mid-respawn -- see this class's own docstring for why
            // measuring/walking against the player's own position is
            // unreliable until this goes false. Just wait it out.
            ctx.blackboard.put(NavIntent.NAV_TARGET, null);
            ctx.blackboard.put(NavIntent.NAV_ARRIVED, true);
            return;
        }

        Vec3 deathPosition = ctx.blackboard.get(DeathWatcher.DEATH_POSITION);
        if (deathPosition == null) {
            // Defensive only -- LegsStateMachine only ever enters this
            // state off DEATH_POSITION itself being non-null (see its own
            // docstring), and DeathWatcher always publishes it in the
            // same tick death is observed. Nothing to walk toward; report
            // finished rather than get stuck.
            ctx.blackboard.put(NavIntent.NAV_TARGET, null);
            ctx.blackboard.put(NavIntent.NAV_ARRIVED, true);
            finished = true;
            return;
        }

        ctx.blackboard.put(NavIntent.NAV_TARGET, new NavIntent.Target(deathPosition, NavIntent.defaultStopDistance()));
        LegsNavigateNode.walkTowardNavTarget(ctx, false, false);
        double distance = ctx.player.position().distanceTo(deathPosition);
        boolean withinRange = distance <= NavIntent.defaultStopDistance();
        ctx.blackboard.put(NavIntent.NAV_ARRIVED, withinRange);
        finished = withinRange;
    }

    @Override
    public void onExit(final TickContext ctx) {
        ctx.blackboard.put(NavIntent.NAV_TARGET, null);
        ctx.blackboard.put(NavIntent.NAV_ARRIVED, true);
        ctx.blackboard.put(LegsNavigateNode.WAYPOINT_COORDINATES, null);
    }

    @Override
    public boolean isFinished() {
        return finished;
    }
}
