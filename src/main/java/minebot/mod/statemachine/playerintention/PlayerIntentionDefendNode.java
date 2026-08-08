package minebot.mod.statemachine.playerintention;

import minebot.mod.EntityFinder;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Standing protection mode -- auto-fights the nearest hostile to the
 * BOT ITSELF (not the defend target -- see below), and stays near the
 * defend target (FOLLOW-style) between fights. Unlike PlayerIntentionKillNode,
 * DEFEND never leaves itself to fight -- it's a real PlayerIntention value
 * (see its own docstring), so there's no "resume back to DEFEND once the
 * fight ends" transition needed: this node just keeps publishing
 * whichever of "follow the defend target" or "fight the current threat"
 * applies each tick, entirely within DEFEND.
 *
 * The defend target itself comes from PlayerIntention (re-read every
 * tick, same as PlayerIntentionFollowNode's own followEntityId) -- null
 * (defendTargetEntityId()) means "defend the bot itself" (!defend with
 * no argument), in which case the "anchor" position IS the player's own
 * live position, and there's nothing to walk toward between fights (the
 * bot doesn't need to follow itself) -- NAV_TARGET stays null/
 * WITHIN_RANGE stays true in that case until a threat actually appears.
 * `anchor` is used for this follow-between-fights positioning, AND as a
 * hard cap on which threats are even eligible to fight (withinDefendRange
 * below) -- but NOT for ranking eligible threats against each other (see
 * below). Per explicit direction, "the bot fights whichever ELIGIBLE
 * threat is closest to ITSELF" even while defending someone else, not
 * whatever's closest to the person being defended (a threat approaching
 * the defended player from the opposite side of a large gap shouldn't
 * outrank one already adjacent to the bot) -- but a threat too far from
 * the defend target is dropped from consideration entirely before that
 * ranking ever happens, REGARDLESS of how close it is to the bot: per
 * explicit direction ("do not engage in combat too far away of the
 * defending target, we dont want to leave the defending target alone"),
 * chasing a threat far from the target means abandoning the target
 * itself, which defeats the entire point of DEFEND. This applies both to
 * picking a NEW threat (nearestThreat is discarded if too far from
 * anchor, even if it's the closest one to the bot) and to an
 * ALREADY-ENGAGED one (currentThreat is dropped -- a real disengage, not
 * just "stop preferring it" -- the instant it drifts past the cap, e.g.
 * a hostile the bot chased that then fled further away).
 *
 * Threat detection commits to a single target entity (via targetEntityId,
 * same "keep walking toward THIS SAME entity" pattern LegsPickupItemsNode
 * already established for its own item-target selection) but, UNLIKE
 * LegsPickupItemsNode, re-evaluates every tick whether a NEW threat is
 * meaningfully closer to the BOT and switches to it if so -- confirmed
 * live that never switching until the current target dies is wrong for
 * defend specifically: a second hostile approaching the bot from a
 * different angle is a more urgent danger than whatever it happens to
 * already be swinging at, and should preempt. Gated by RETARGET_MARGIN
 * (not a bare "nearest != current" check every tick) to avoid the
 * live-confirmed thrash risk two similar-distance threats would
 * otherwise cause (flip-flopping the fight target every tick, discarding
 * kiting progress each switch) -- only a threat CLEARLY closer (by more
 * than the margin) actually preempts; roughly-equidistant threats keep
 * the current fight going uninterrupted. Both the search radius
 * (EntityFinder.findNearestHostile's own center) and this preemption
 * comparison are anchored on the bot's own live position, matching every
 * other findNearestHostile call site in this codebase
 * (PlayerIntentionKillNode/TaskController) -- this was previously the
 * one outlier searching/ranking around the defend target's position
 * instead, which is the bug this docstring update corrects.
 *
 * ONLY VISIBLE hostiles are ever eligible to fight -- EntityFinder.
 * findNearestVisibleHostile (not the plain findNearestHostile
 * PlayerIntentionKillNode/TaskController still use) requires a clear
 * eye-to-eye line of sight, and an already-engaged currentThreat that
 * loses line of sight mid-fight (fled behind a wall, dropped into a
 * hole) is dropped the same way an out-of-defend-range one already was.
 * Added specifically because DEFEND was locking onto and pathing toward
 * hostiles it had no real line to -- an underground/behind-terrain
 * zombie, for instance -- sending Legs toward a target it could never
 * actually reach in a straight line. Deliberately DEFEND-only: a bare
 * `!kill` with no query, and TaskController's own busy-threat interrupt,
 * both still use plain findNearestHostile and may target a
 * heard-but-not-yet-seen mob, unchanged.
 */
public final class PlayerIntentionDefendNode implements StateNode<PlayerIntentionState> {
    // Centered on the bot's own position (see this class's own docstring
    // for why -- matches every other EntityFinder.findNearestHostile call
    // site in this codebase). Doubles as the max distance a threat may be
    // from the defend target and still be worth engaging at all
    // (withinDefendRange below) -- reused rather than a separate tunable
    // per explicit direction, so "how far the bot looks for threats" and
    // "how far it'll let a fight drift from the target before
    // disengaging" stay the same distance.
    private static final double THREAT_SEARCH_RADIUS = CombatEngagement.SEARCH_RADIUS;
    // How much closer a new threat has to be than the current target
    // (both measured from the BOT, not the defend target/anchor -- see
    // this class's own docstring) to actually preempt it.
    private static final double RETARGET_MARGIN = 0.5;

    private final PlayerIntention intention;

    private int targetEntityId = -1;

    public PlayerIntentionDefendNode(final PlayerIntention intention) {
        this.intention = intention;
    }

    @Override
    public void onEnter(final TickContext ctx, final PlayerIntentionState previousState) {
        targetEntityId = -1;
        onTick(ctx);
    }

    @Override
    public void onTick(final TickContext ctx) {
        Vec3 anchor = defendAnchor(ctx);
        if (anchor == null) {
            // The defend target itself is gone (a defended player left/
            // disconnected) -- nothing to protect right now. Stay in
            // DEFEND (only !stop leaves it -- see PlayerIntentionStateMachine's
            // own edges); just nothing to do until PlayerIntention
            // changes or the target reappears.
            CombatEngagement.clear(ctx);
            return;
        }

        Entity currentThreat = currentTarget(ctx);
        // A threat that's drifted too far from the defend target is no
        // longer worth chasing -- see this class's own docstring for why
        // (leaving the defend target undefended to run down a distant
        // hostile defeats the entire point of DEFEND). Dropped BEFORE
        // retargeting below, so a currentThreat that just wandered out of
        // range is treated exactly like "no current threat", not
        // compared against nearestThreat at all.
        if (currentThreat != null && !withinDefendRange(currentThreat, anchor)) {
            currentThreat = null;
            targetEntityId = -1;
        }
        // Same treatment for a threat that's lost line of sight since the
        // last tick (fled behind a wall, dropped into a hole) -- see this
        // class's own docstring for why DEFEND only ever engages VISIBLE
        // hostiles: chasing a target it can't see means blindly pathing
        // toward its last-known position, exactly the "walking toward an
        // underground zombie" bug this exists to prevent.
        if (currentThreat != null && !EntityFinder.hasLineOfSight(ctx.level, ctx.player, currentThreat)) {
            currentThreat = null;
            targetEntityId = -1;
        }

        Vec3 botPosition = ctx.player.position();
        Entity nearestThreat = EntityFinder.findNearestVisibleHostile(ctx.level, ctx.player, THREAT_SEARCH_RADIUS);
        if (nearestThreat != null && !withinDefendRange(nearestThreat, anchor)) {
            nearestThreat = null;
        }

        if (currentThreat == null) {
            currentThreat = nearestThreat;
            targetEntityId = currentThreat != null ? currentThreat.getId() : -1;
        } else if (nearestThreat != null && nearestThreat != currentThreat) {
            // Only preempt if nearestThreat is CLEARLY closer to the BOT
            // than the one already being fought -- see this class's own
            // docstring for why (avoids thrashing between two
            // roughly-equidistant threats).
            double currentDistance = currentThreat.position().distanceTo(botPosition);
            double nearestDistance = nearestThreat.position().distanceTo(botPosition);
            if (nearestDistance + RETARGET_MARGIN < currentDistance) {
                currentThreat = nearestThreat;
                targetEntityId = currentThreat.getId();
            }
        }

        if (currentThreat != null) {
            CombatEngagement.publish(ctx, currentThreat);
            return;
        }

        // No threat right now -- stay near the defend target (self-defend
        // has nothing to walk toward, see this class's own docstring).
        Integer defendTargetEntityId = intention.current().defendTargetEntityId();
        if (defendTargetEntityId == null) {
            CombatEngagement.clear(ctx);
            return;
        }
        ctx.blackboard.put(NavIntent.NAV_TARGET, new NavIntent.Target(anchor, NavIntent.defaultStopDistance()));
        double distance = ctx.player.position().distanceTo(anchor);
        ctx.blackboard.put(NavIntent.NAV_ARRIVED, distance <= NavIntent.defaultStopDistance());
        ctx.blackboard.put(CombatEngagement.TARGET_ENTITY_ID, null);
        ctx.blackboard.put(CombatEngagement.SELECTED_WEAPON, null);
    }

    @Override
    public void onExit(final TickContext ctx) {
        CombatEngagement.clear(ctx);
        targetEntityId = -1;
    }

    /** Whether `threat` is close enough to the defend target to be worth engaging at all -- see this class's own docstring for why a threat can be the closest one to the bot and still get rejected here (chasing it would mean abandoning the defend target). Reuses THREAT_SEARCH_RADIUS as the cutoff rather than a separate tunable -- see that constant's own comment. */
    private static boolean withinDefendRange(final Entity threat, final Vec3 anchor) {
        return threat.position().distanceTo(anchor) <= THREAT_SEARCH_RADIUS;
    }

    /** The live position to defend AND to stay near between fights -- the bot's own position for self-defend (defendTargetEntityId() == null), or the defend target's live position otherwise. Null if a real defend target was specified but is no longer resolvable (disconnected/despawned). */
    private Vec3 defendAnchor(final TickContext ctx) {
        Integer defendTargetEntityId = intention.current().defendTargetEntityId();
        if (defendTargetEntityId == null) {
            return ctx.player.position();
        }
        Entity target = ctx.level.getEntity(defendTargetEntityId);
        return target != null ? target.position() : null;
    }

    private Entity currentTarget(final TickContext ctx) {
        if (targetEntityId == -1) {
            return null;
        }
        Entity entity = ctx.level.getEntity(targetEntityId);
        return entity != null && !entity.isRemoved() ? entity : null;
    }
}
