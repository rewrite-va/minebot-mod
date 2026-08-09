package minebot.mod.statemachine.playerintention;

import minebot.mod.EntityFinder;
import minebot.mod.PlayerController;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Standing protection mode -- auto-fights the nearest hostile to the
 * BOT ITSELF (not the defend target -- see below), and stays near the
 * defend target (FOLLOW-style) between fights. Unlike KillTask,
 * DEFEND never leaves itself to fight -- it's a real PlayerIntention value
 * (see its own docstring), so there's no "resume back to DEFEND once the
 * fight ends" transition needed: this node just keeps publishing
 * whichever of "follow the defend target" or "fight the current threat"
 * applies each tick, entirely within DEFEND.
 *
 * The defend target itself comes from PlayerIntention (re-read every
 * tick, same as PlayerIntentionFollowNode's own followPlayerName) -- null
 * (defendTargetPlayerName()) means "defend the bot itself" (!defend with
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
 * (KillTask/TaskController) -- this was previously the
 * one outlier searching/ranking around the defend target's position
 * instead, which is the bug this docstring update corrects.
 *
 * Line of sight only gates ACQUIRING a new threat, not keeping one --
 * EntityFinder.findNearestVisibleHostile (not the plain
 * findNearestHostile KillTask/TaskController still use)
 * requires a clear eye-to-eye line of sight when picking nearestThreat
 * each tick, so DEFEND never locks onto a hostile it has no real line to
 * in the first place -- an underground/behind-terrain zombie, for
 * instance, that Legs could never path toward in a straight line. But
 * once currentThreat is actually being fought, a momentary loss of sight
 * (a target ducking behind a tree, stepping behind the bot's own swing
 * animation) does NOT drop it -- per explicit direction, the LOS check is
 * "only to START attacking stuff that are visible, after that, we chase
 * until dead, no matter if it stops being visible". currentThreat is only
 * ever cleared by dying (currentTarget's own isRemoved() check) or
 * drifting out of defend range (withinDefendRange below) -- never by a
 * bare visibility check. Deliberately DEFEND-only: a bare `!kill` with no
 * query, and TaskController's own busy-threat interrupt, both still use
 * plain findNearestHostile and may target a heard-but-not-yet-seen mob,
 * unchanged.
 *
 * A SECOND, independent threat source backs the defended player up
 * against WHATEVER they're personally attacking, regardless of vanilla
 * hostility -- EntityFinder.findNearestAttackedByPlayer, keyed off the
 * defend target's own attacks (not the bot's). Per explicit direction:
 * "this is only to check if the player hit an entity, the bot should
 * help" -- e.g. a defended player punching a pig or a freshly-spawned
 * polar bear should pull the bot in exactly like a real hostile would,
 * even though neither is (or reliably becomes, client-detectably)
 * `isHostileNow`. Only meaningful when actually defending someone else
 * (self-defend has no separate "defended player" to watch attack
 * things -- the bot attacking itself isn't a case that occurs). Treated
 * as just another candidate into the SAME nearestThreat/currentThreat
 * pipeline below (search radius, defend-range cap, acquisition-time
 * visibility, retarget margin, all identical) -- not a separate code
 * path -- so an attacked-by-target threat competes and disengages
 * exactly like a regular hostile one.
 *
 * defendAnchor falls back to a last-known position (then WaypointFinder's
 * own live Locator Bar data) the same way PlayerIntentionFollowNode's own
 * onTick does, for the exact same reason ("I want to be able to follow a
 * player even if it is far away" applies equally to "stay close to the
 * person I'm defending" -- per explicit direction, "defend is also
 * instructing the bot to stay close to the player"). Backed by its own
 * LastKnownEntityTracker instance, keyed off the defend target's real
 * NAME (via PlayerController -- see both classes' own docstrings for why
 * a name, not a pre-resolved id/uuid, is the thing PlayerIntention
 * itself carries) -- extracted out of PlayerIntentionFollowNode into a
 * shared class specifically per explicit direction ("is there any code
 * we can encapsulate?, if I later fix this it should affect defend") so
 * a future improvement to the fallback logic only has to happen once and
 * benefits both FOLLOW and DEFEND. Each node still owns its own tracker
 * INSTANCE (confirmed live, before this encapsulation: DEFEND's own
 * defendAnchor used to go straight back to "stand still" the instant
 * ctx.level.getEntity(defendTargetEntityId) returned null, exactly the
 * bug FOLLOW itself had before its own fix -- two independent copies of
 * the same gap, now one shared fix) -- FOLLOW and DEFEND tracking
 * different (or the same) players never interfere with each other.
 *
 * defendTargetEntity (used by findNearestAttackedByPlayer above) is
 * DELIBERATELY NOT backed by the same fallback -- "what is this player
 * currently attacking" is meaningless for a target that isn't a real,
 * currently-loaded Entity (there's no client-side signal for a non-loaded
 * player's actions at all, waypoint or otherwise), so it stays a plain
 * live-or-null lookup (via PlayerController.getEntityIdByName, resolved
 * fresh every call, same as everywhere else); only "where do I stay
 * near" (defendAnchor) benefits from stale/waypoint data.
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
    private final LastKnownEntityTracker anchorTracker = new LastKnownEntityTracker();

    public PlayerIntentionDefendNode(final PlayerIntention intention) {
        this.intention = intention;
    }

    @Override
    public void onEnter(final TickContext ctx, final PlayerIntentionState previousState) {
        targetEntityId = -1;
        anchorTracker.resetIfTargetChanged(intention.current().defendTargetPlayerName());
        onTick(ctx);
    }

    @Override
    public void onTick(final TickContext ctx) {
        anchorTracker.resetIfTargetChanged(intention.current().defendTargetPlayerName());

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

        Vec3 botPosition = ctx.player.position();
        Entity nearestThreat = EntityFinder.findNearestVisibleHostile(ctx.level, ctx.player, THREAT_SEARCH_RADIUS);
        // Second threat source: whatever the defended player is
        // personally attacking, vanilla-hostile or not -- see this
        // class's own docstring. Only applies when defending someone
        // ELSE (a real Player entity at the anchor), and only preferred
        // over a plain hostile if it's actually closer to the bot, same
        // preemption rule as any other candidate below.
        Entity defendTarget = defendTargetEntity(ctx);
        if (defendTarget instanceof Player defendedPlayer) {
            Entity attackedByTarget = EntityFinder.findNearestAttackedByPlayer(ctx.level, botPosition, defendedPlayer, THREAT_SEARCH_RADIUS);
            if (attackedByTarget != null
                    && EntityFinder.hasLineOfSight(ctx.level, ctx.player, attackedByTarget)
                    && (nearestThreat == null || attackedByTarget.distanceToSqr(botPosition) < nearestThreat.distanceToSqr(botPosition))) {
                nearestThreat = attackedByTarget;
            }
        }
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
        String defendTargetPlayerName = intention.current().defendTargetPlayerName();
        if (defendTargetPlayerName == null) {
            CombatEngagement.clear(ctx);
            return;
        }
        ctx.blackboard.put(NavIntent.NAV_TARGET, new NavIntent.Target(anchor, NavIntent.defaultStopDistance()));
        double distance = ctx.player.position().distanceTo(anchor);
        ctx.blackboard.put(NavIntent.NAV_ARRIVED, distance <= NavIntent.defaultStopDistance() && hasLineOfSight(ctx, anchor));
        ctx.blackboard.put(CombatEngagement.TARGET_ENTITY_ID, null);
        ctx.blackboard.put(CombatEngagement.SELECTED_WEAPON, null);
    }

    @Override
    public void onExit(final TickContext ctx) {
        CombatEngagement.clear(ctx);
        targetEntityId = -1;
        anchorTracker.reset();
    }

    /** Whether `threat` is close enough to the defend target to be worth engaging at all -- see this class's own docstring for why a threat can be the closest one to the bot and still get rejected here (chasing it would mean abandoning the defend target). Reuses THREAT_SEARCH_RADIUS as the cutoff rather than a separate tunable -- see that constant's own comment. */
    private static boolean withinDefendRange(final Entity threat, final Vec3 anchor) {
        return threat.position().distanceTo(anchor) <= THREAT_SEARCH_RADIUS;
    }

    /** The live (or best-available, see LastKnownEntityTracker) position to defend AND to stay near between fights -- the bot's own position for self-defend (defendTargetPlayerName() == null), or the defend target's tracked position otherwise. Null only if a real defend target was specified and this tracker has genuinely never resolved them to anything. */
    private Vec3 defendAnchor(final TickContext ctx) {
        String defendTargetPlayerName = intention.current().defendTargetPlayerName();
        if (defendTargetPlayerName == null) {
            return ctx.player.position();
        }
        return anchorTracker.resolve(ctx, defendTargetPlayerName).position();
    }

    /** The defend target's own Entity (as opposed to defendAnchor's position-only view) -- null for self-defend (nothing to watch attack things separately from the bot itself) or an unresolvable target, same as defendAnchor. Used to key EntityFinder.findNearestAttackedByPlayer off the RIGHT player -- see this class's own docstring for why. */
    private Entity defendTargetEntity(final TickContext ctx) {
        String defendTargetPlayerName = intention.current().defendTargetPlayerName();
        if (defendTargetPlayerName == null) {
            return null;
        }
        Integer entityId = PlayerController.getEntityIdByName(ctx.level, defendTargetPlayerName);
        return entityId != null ? ctx.level.getEntity(entityId) : null;
    }

    private Entity currentTarget(final TickContext ctx) {
        if (targetEntityId == -1) {
            return null;
        }
        Entity entity = ctx.level.getEntity(targetEntityId);
        return entity != null && !entity.isRemoved() ? entity : null;
    }

    /**
     * Real eye-to-target raycast against solid blocks (ClipContext.Block.
     * COLLIDER, same formula HandsDrawBowNode's own hasLineOfSight uses) --
     * MISS means clear line of sight. Required in addition to the raw
     * distance check above before NAV_ARRIVED can ever go true -- reported
     * live: mining through a stone wall toward a defend target on the
     * other side, NAV_ARRIVED flipped true (and Legs froze in place) the
     * instant straight-line distance crossed defaultStopDistance(), even
     * though a solid wall still fully separated the bot from the anchor.
     * distanceTo has no awareness of what's IN BETWEEN the two points, only
     * how far apart they are -- exactly the same gap BlockBreaker's own
     * hasLineOfSight already exists to close for mining itself (see its own
     * docstring), just needed here too for "have I actually arrived", not
     * just "can I swing at this block". `anchor` may be a stale/waypoint
     * fallback position (see defendAnchor/LastKnownEntityTracker's own
     * docstrings) rather than a live entity's real eye position -- aiming
     * at a fixed point 1.5 blocks above it (roughly player eye height) is
     * close enough for this purpose, matching Movements.hasDigLineOfSight's
     * own "no real entity to ask for an eye position" convention.
     */
    private static boolean hasLineOfSight(final TickContext ctx, final Vec3 anchor) {
        Vec3 from = ctx.player.getEyePosition();
        Vec3 to = anchor.add(0, 1.5, 0);
        ClipContext clipContext = new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.player);
        BlockHitResult hit = ctx.level.clip(clipContext);
        return hit.getType() == HitResult.Type.MISS;
    }
}
