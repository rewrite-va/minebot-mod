package minebot.mod.statemachine.playerintention;

import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Tracks the currently-followed entity and publishes its live position +
 * whether it's within range, once per tick, via the shared NavIntent
 * channel (see its own docstring for why this moved off a FOLLOW-
 * specific key: other publishers -- CombatEngagement, and Legs' own
 * GO_TO_DEATH_POSITION/PICKUP_ITEMS -- need the exact same "tell Legs/
 * Head where to walk/look" shape for their own targets) --
 * PlayerIntention owns "am I close enough to the thing I'm walking toward"
 * rather than Legs/Head each independently re-deriving the same
 * distance check, per explicit direction: PlayerIntention:FOLLOW is the one
 * thing that isn't idle while actively following, so it's the natural
 * place to decide "should the rest of the bot currently be doing
 * anything about this" -- Legs/Head become pure consumers of what's
 * published here, no target-tracking logic duplicated in either. This
 * is also what lets Legs/Head both fall back to a real IDLE the moment
 * the target is close enough, instead of getting stuck in NAVIGATE
 * forever once first reached (the bug this was built to fix -- confirmed
 * live).
 *
 * The followed entity id itself comes from PlayerIntention, not a
 * locally-tracked field re-armed from Command.Follow -- see
 * PlayerIntention's own docstring for why: this node gets re-entered
 * (with no fresh Command.Follow) every time KILL finishes and resumes
 * FOLLOW, and PlayerIntention is exactly "what the player last asked
 * for", still valid at that point.
 */
public final class PlayerIntentionFollowNode implements StateNode<PlayerIntentionState> {
    private final PlayerIntention intention;

    public PlayerIntentionFollowNode(final PlayerIntention intention) {
        this.intention = intention;
    }

    @Override
    public void onTick(final TickContext ctx) {
        Entity target = ctx.level.getEntity(intention.current().followEntityId());
        if (target == null) {
            ctx.blackboard.put(NavIntent.NAV_TARGET, null);
            ctx.blackboard.put(NavIntent.NAV_ARRIVED, true); // nothing to walk toward -- "close enough" (i.e. don't navigate) by default
            return;
        }

        Vec3 targetPosition = target.position();
        ctx.blackboard.put(NavIntent.NAV_TARGET, new NavIntent.Target(targetPosition, NavIntent.defaultStopDistance()));
        double distance = ctx.player.position().distanceTo(targetPosition);
        ctx.blackboard.put(NavIntent.NAV_ARRIVED, distance <= NavIntent.defaultStopDistance());
    }

    @Override
    public void onExit(final TickContext ctx) {
        ctx.blackboard.put(NavIntent.NAV_TARGET, null);
        ctx.blackboard.put(NavIntent.NAV_ARRIVED, true);
    }
}
