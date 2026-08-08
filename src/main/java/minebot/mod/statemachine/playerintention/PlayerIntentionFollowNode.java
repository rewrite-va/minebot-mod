package minebot.mod.statemachine.playerintention;

import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
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
 * The followed player NAME itself comes from PlayerIntention, not a
 * locally-tracked field re-armed from Command.Follow -- see
 * PlayerIntention's own docstring for why: this node gets re-entered
 * (with no fresh Command.Follow) every time KILL finishes and resumes
 * FOLLOW, and PlayerIntention is exactly "what the player last asked
 * for", still valid at that point.
 *
 * Falls back, via LastKnownEntityTracker (see its own docstring for the
 * full four-tier order: live Entity resolved fresh from the name via
 * PlayerController, then WaypointFinder's own live-streamed Locator Bar
 * position for a UUID ALSO resolved fresh from the name, then a plain
 * last-known snapshot), once a live entity can't be found for the name
 * at all -- per explicit direction ("I want to be able to follow a
 * player even if it is far away" / "there is a player compass in the
 * experience bar that does point to another player even if it is far
 * away, could the position be streamed to the client" / "if a player has
 * never been seen... can we extract the uuid from the bot's compass?"):
 * a followed player leaving the client's own simulation distance (a real
 * server-controlled radius, not anything this mod can widen) used to
 * make this node give up entirely the instant ctx.level.getEntity
 * returned null, clearing NAV_TARGET and reporting NAV_ARRIVED=true
 * (i.e. "stand still, nothing to do") even though the player might just
 * be a few blocks past the edge of render/sim distance, not actually
 * gone -- and a player never yet seen as a loaded entity at all
 * previously couldn't be followed no matter what, since there was no id
 * to resolve in the first place. This tracking logic itself was
 * extracted into LastKnownEntityTracker per explicit direction ("is
 * there any code we can encapsulate?, if I later fix this it should
 * affect defend") so PlayerIntentionDefendNode's own defendAnchor can
 * reuse the exact same fallback chain rather than a second, drifting
 * copy of it.
 */
public final class PlayerIntentionFollowNode implements StateNode<PlayerIntentionState> {
    private final PlayerIntention intention;
    private final LastKnownEntityTracker tracker = new LastKnownEntityTracker();

    public PlayerIntentionFollowNode(final PlayerIntention intention) {
        this.intention = intention;
    }

    @Override
    public void onEnter(final TickContext ctx, final PlayerIntentionState previousState) {
        // PlayerIntention can re-enter this node (e.g. resuming FOLLOW
        // after a KILL interrupt finishes, per this class's own
        // docstring) with the SAME followPlayerName as last time, where
        // real tracked state might still be legitimately useful.
        // LastKnownEntityTracker.resetIfTargetChanged only clears when
        // the name actually changed.
        tracker.resetIfTargetChanged(intention.current().followPlayerName());
    }

    @Override
    public void onTick(final TickContext ctx) {
        String targetName = intention.current().followPlayerName();
        // The target itself can change (a new !follow) without a real
        // onEnter in between -- PlayerIntention can update its own
        // current() value without a state transition when already in
        // FOLLOW (re-issuing !follow while already following someone
        // else).
        tracker.resetIfTargetChanged(targetName);

        if (targetName == null) {
            ctx.blackboard.put(NavIntent.NAV_TARGET, null);
            ctx.blackboard.put(NavIntent.NAV_ARRIVED, true);
            return;
        }

        LastKnownEntityTracker.Resolution resolution = tracker.resolve(ctx, targetName);
        Vec3 targetPosition = resolution.position();
        if (targetPosition == null) {
            // Never actually seen this target at all (or it was reset),
            // and no live waypoint data either -- nothing real to walk
            // toward.
            ctx.blackboard.put(NavIntent.NAV_TARGET, null);
            ctx.blackboard.put(NavIntent.NAV_ARRIVED, true);
            return;
        }

        ctx.blackboard.put(NavIntent.NAV_TARGET, new NavIntent.Target(targetPosition, NavIntent.defaultStopDistance()));
        double distance = ctx.player.position().distanceTo(targetPosition);
        ctx.blackboard.put(NavIntent.NAV_ARRIVED, distance <= NavIntent.defaultStopDistance() && hasLineOfSight(ctx, targetPosition));
    }

    @Override
    public void onExit(final TickContext ctx) {
        ctx.blackboard.put(NavIntent.NAV_TARGET, null);
        ctx.blackboard.put(NavIntent.NAV_ARRIVED, true);
        tracker.reset();
    }

    /**
     * Real eye-to-target raycast against solid blocks (ClipContext.Block.
     * COLLIDER, same formula HandsDrawBowNode's own hasLineOfSight uses) --
     * MISS means clear line of sight. Required in addition to the raw
     * distance check above before NAV_ARRIVED can ever go true -- see
     * PlayerIntentionDefendNode's own identical hasLineOfSight for the live
     * bug this guards against (mining through a wall toward a followed
     * player froze Legs the instant straight-line distance alone crossed
     * defaultStopDistance(), despite a solid wall still fully separating
     * the two -- distanceTo has no notion of what's in between). Aiming at
     * a fixed point 1.5 blocks above `targetPosition` (roughly player eye
     * height) rather than a real entity's own eye position, since
     * targetPosition may be a stale/waypoint fallback (see
     * LastKnownEntityTracker's own docstring) with no live Entity to ask.
     */
    private static boolean hasLineOfSight(final TickContext ctx, final Vec3 targetPosition) {
        Vec3 from = ctx.player.getEyePosition();
        Vec3 to = targetPosition.add(0, 1.5, 0);
        ClipContext clipContext = new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.player);
        BlockHitResult hit = ctx.level.clip(clipContext);
        return hit.getType() == HitResult.Type.MISS;
    }
}
