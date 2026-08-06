package minebot.mod.statemachine.legs;

import minebot.mod.DeathWatcher;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import minebot.mod.statemachine.playerintention.NavIntent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Walks to a dropped item, over and over, until none remain within
 * RADIUS of where the bot died -- Minecraft auto-picks-up items just by
 * walking close enough to them (no explicit interact needed, confirmed
 * by ItemDropTracker's own docstring/existing use of the same
 * entitiesForRendering()-filtered-by-ItemEntity scan this reuses), so
 * "recover the items" is really just "keep walking toward one" until the
 * area's clear. Moved here from the deleted PlayerIntentionPickupItemsNode
 * (see PlayerIntentionState's own docstring for the full story of why) --
 * this is purely a navigation concern, so it belongs on Legs like every
 * other "walk toward a point" behavior, not on the PlayerIntention axis.
 *
 * Commits to a single target entity (targetItemId) once picked, and
 * keeps walking toward THAT SAME entity every tick until it's gone
 * (picked up by us or anyone else, or despawned) -- deliberately NOT
 * re-picking "whichever is nearest" fresh every tick. An earlier version
 * did that, and it thrashes: with two items at different distances, the
 * "nearest" comparison can flip mid-walk (e.g. once the bot has closed
 * distance on the farther one enough that it's now the nearer one by a
 * hair, or a closer item despawns/gets grabbed by someone else),
 * snapping NAV_TARGET to a different position and discarding all
 * progress on the one already being walked toward. Deliberately still
 * stateless about the FULL set of remaining items, per explicit
 * direction -- items can be picked up by another player or despawn out
 * from under a remembered list anyway, so a live re-scan each time a
 * target is needed is simpler and just as correct as maintaining one.
 *
 * Scoped to RADIUS around DeathWatcher.DEATH_POSITION (not the bot's own
 * current position) so this doesn't chase an item that scattered far
 * away (rolled down a slope, swept off by water) indefinitely -- once
 * outside that radius, an item is treated as unrecoverable, matching
 * "recover items on the floor near where I died", not "chase every
 * dropped item in the world". New targets are picked nearest-to-the-BOT
 * (not nearest-to-death) so each hop is as short as possible -- an
 * efficient sweep outward from wherever the bot currently is, rather
 * than always preferring whatever's closest to the original death spot
 * regardless of how far the bot has already walked.
 *
 * isFinished() once no ItemEntity remain in range, or TIMEOUT_TICKS has
 * elapsed regardless (items stuck in lava/underwater/behind a wall could
 * otherwise stall this state forever) -- either way, LegsStateMachine's
 * own edges fall back to whatever Legs would normally be doing next
 * (NAVIGATE/FLEE/IDLE, per its usual edge table).
 *
 * Publishes its own ARRIVAL_DISTANCE as NAV_TARGET's own
 * Target.stopDistance(), NOT NavIntent.defaultStopDistance() -- an
 * earlier version reused the default (2.0 blocks, tuned for "close
 * enough to a followed player, don't crowd them"), which made Legs stop
 * and go back to IDLE a full 2 blocks short of the item, nowhere near
 * vanilla's actual pickup AABB (real touching distance, well under a
 * block) -- confirmed live: items sat visibly right next to the bot and
 * were never actually picked up, since Legs had already stopped walking.
 * ARRIVAL_DISTANCE is deliberately tight (close enough that the walk
 * itself carries the bot through the item's real pickup radius) rather
 * than exactly 0 -- PathTracker/movement naturally overshoots by a small
 * amount each tick, so requiring exact intersection here would fight
 * normal movement granularity.
 *
 * onEnter publishes NAV_TARGET/NAV_ARRIVED immediately (via the
 * same resolveAndPublishTarget() helper onTick uses) rather than waiting
 * for this node's own onTick to run starting next tick -- see
 * PlayerIntentionKillNode's own docstring for the live bug this exact gap
 * caused there. Doesn't delegate straight to onTick() itself (unlike
 * LegsGoToDeathPositionNode's own simpler fix) since onTick also
 * increments ticksElapsed -- onEnter publishing real data shouldn't also
 * burn a tick off the timeout before this state has even had its first
 * real tick.
 */
public final class LegsPickupItemsNode implements StateNode<LegsState> {
    // Widened from an initial 8.0 -- confirmed live that a fall/explosion
    // death can scatter items (physics-flung, or rolling down terrain)
    // well past 8 blocks from the actual death spot, leaving them
    // unreachable/ignored under the old radius.
    private static final double RADIUS = 16.0;
    private static final double ARRIVAL_DISTANCE = 0.3;
    // Widened alongside RADIUS -- 10s was tuned for items clustered right
    // at the death spot; a 16-block radius can need several long walks in
    // a row to reach everything, so the same short timeout would cut
    // recovery off early.
    private static final int TIMEOUT_TICKS = 600; // ~30 seconds

    // -1 means "no committed target right now" -- distinct from a real
    // entity id, which is always >= 0.
    private int targetItemId = -1;
    private int ticksElapsed;
    private boolean finished;

    @Override
    public void onEnter(final TickContext ctx, final LegsState previousState) {
        targetItemId = -1;
        ticksElapsed = 0;
        finished = false;
        resolveAndPublishTarget(ctx);
    }

    @Override
    public void onTick(final TickContext ctx) {
        ticksElapsed++;
        resolveAndPublishTarget(ctx);
        finished = finished || ticksElapsed >= TIMEOUT_TICKS;
    }

    private void resolveAndPublishTarget(final TickContext ctx) {
        ItemEntity target = currentTarget(ctx);
        if (target == null) {
            Vec3 deathPosition = ctx.blackboard.get(DeathWatcher.DEATH_POSITION);
            target = deathPosition != null ? nearestItemToPlayer(ctx, deathPosition) : null;
            targetItemId = target != null ? target.getId() : -1;
        }

        if (target == null) {
            ctx.blackboard.put(NavIntent.NAV_TARGET, null);
            ctx.blackboard.put(NavIntent.NAV_ARRIVED, true);
            finished = true;
            return;
        }

        Vec3 itemPosition = target.position();
        ctx.blackboard.put(NavIntent.NAV_TARGET, new NavIntent.Target(itemPosition, ARRIVAL_DISTANCE));
        LegsNavigateNode.walkTowardNavTarget(ctx);
        double distance = ctx.player.position().distanceTo(itemPosition);
        ctx.blackboard.put(NavIntent.NAV_ARRIVED, distance <= ARRIVAL_DISTANCE);
    }

    @Override
    public void onExit(final TickContext ctx) {
        ctx.blackboard.put(NavIntent.NAV_TARGET, null);
        ctx.blackboard.put(NavIntent.NAV_ARRIVED, true);
        ctx.blackboard.put(LegsNavigateNode.WAYPOINT_COORDINATES, null);
        // Clears the "fresh death to react to" signal DEATH_POSITION
        // doubles as (see DeathWatcher's own docstring) -- deliberately
        // done HERE, not by LegsGoToDeathPositionNode's own onExit, since
        // this node is the last to actually need the value (its own item
        // search radius is scoped against it).
        ctx.blackboard.put(DeathWatcher.DEATH_POSITION, null);
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    /** The still-committed target entity, or null if there's no committed target or it's no longer loaded (picked up by us/someone else, or despawned) -- getEntity() itself is how ItemDropTracker/broadcastEntityEvents already check "is this id still around" elsewhere in this mod. */
    private ItemEntity currentTarget(final TickContext ctx) {
        if (targetItemId == -1) {
            return null;
        }
        Entity entity = ctx.level.getEntity(targetItemId);
        return entity instanceof ItemEntity itemEntity ? itemEntity : null;
    }

    /** Same entitiesForRendering()-filtered-by-ItemEntity scan ItemDropTracker already uses -- see its own docstring for why (naturally bounded by render/simulation distance, no separate bounded-AABB search needed). Nearest to the BOT (not to deathPosition) so each newly-committed hop is as short as possible -- deathPosition only bounds the eligible RADIUS, not which of the eligible items gets picked. */
    private static ItemEntity nearestItemToPlayer(final TickContext ctx, final Vec3 deathPosition) {
        Vec3 selfPosition = ctx.player.position();
        ItemEntity nearest = null;
        double nearestDistanceSq = Double.MAX_VALUE;
        for (Entity entity : ctx.level.entitiesForRendering()) {
            if (!(entity instanceof ItemEntity itemEntity)) {
                continue;
            }
            if (itemEntity.position().distanceToSqr(deathPosition) > RADIUS * RADIUS) {
                continue;
            }
            double distanceSq = itemEntity.position().distanceToSqr(selfPosition);
            if (distanceSq < nearestDistanceSq) {
                nearestDistanceSq = distanceSq;
                nearest = itemEntity;
            }
        }
        return nearest;
    }
}
