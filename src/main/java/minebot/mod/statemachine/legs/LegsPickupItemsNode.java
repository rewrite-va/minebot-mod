package minebot.mod.statemachine.legs;

import minebot.mod.DeathWatcher;
import minebot.mod.mixin.AbstractArrowAccessor;
import minebot.mod.statemachine.Command;
import minebot.mod.statemachine.Commands;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import minebot.mod.statemachine.playerintention.NavIntent;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * Walks to a dropped item (or a pickable ground arrow), over and over,
 * until none remain within RADIUS of a committed anchor position --
 * Minecraft auto-picks-up items just by walking close enough to them (no
 * explicit interact needed, confirmed by ItemDropTracker's own docstring/
 * existing use of the same entitiesForRendering()-filtered scan this
 * reuses), so "recover the items" is really just "keep walking toward
 * one" until the area's clear. Moved here from the deleted
 * PlayerIntentionPickupItemsNode (see PlayerIntentionState's own docstring
 * for the full story of why) -- this is purely a navigation concern, so it
 * belongs on Legs like every other "walk toward a point" behavior, not on
 * the PlayerIntention axis.
 *
 * Ground arrows are AbstractArrow entities, not ItemEntity -- widened to
 * scan both per explicit direction ("arrows that are pickable... picked
 * by the PICKING_ITEMS algorithm of the legs"). Confirmed via decompiled
 * AbstractArrow.playerTouch that stuck arrows (isInGround()) use the
 * exact same passive walk-into-it pickup mechanism ItemEntity does (both
 * ultimately go through Player.touch(Entity) -> entity.playerTouch(this)
 * from the entity-collision tick loop), so no separate interaction is
 * needed here -- walking the bot onto an eligible arrow's position is
 * enough, same as for items. isArrowPickable(ctx, arrow) below is the
 * eligibility gate, matching real vanilla pickup rules as closely as
 * client-side data allows -- see its own docstring for why it can't be
 * exact.
 *
 * Two distinct triggers share this same node, per explicit direction that
 * !pickup should reuse the death-recovery mechanics rather than duplicate
 * them:
 * - Death recovery (see LegsStateMachine's own docstring) -- entered
 *   straight from GO_TO_DEATH_POSITION once it finishes; the anchor is
 *   DeathWatcher.DEATH_POSITION, already known by the time this node's
 *   onEnter runs.
 * - !pickup (Command.Pickup) -- entered directly from IDLE/NAVIGATE/FLEE;
 *   the anchor is the bot's own LIVE position the instant the command is
 *   first seen (resolved on the tick thread inside onEnter, NOT captured
 *   on the WebSocket thread when the command was dispatched -- see
 *   Command.Pickup's own docstring for why), since there's no "walk back
 *   somewhere first" step the way death recovery has -- !pickup means
 *   "grab whatever's near me right now".
 * anchor() below picks whichever of the two applies -- Commands.has(ctx.
 * commands, Command.Pickup.class) is only ever true on the exact tick a
 * fresh !pickup arrives, which is also the only tick this node's onEnter
 * could be running because OF that command (the death-recovery entry
 * path never has a Command.Pickup present), so checking it in onEnter is
 * enough to disambiguate correctly without any extra bookkeeping.
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
 * Scoped to RADIUS around the committed anchor (not the bot's own
 * current, continuously-changing position) so this doesn't chase an item
 * that scattered far away (rolled down a slope, swept off by water)
 * indefinitely -- once outside that radius, an item is treated as
 * unrecoverable, matching "recover items on the floor near [the anchor]",
 * not "chase every dropped item in the world". New targets are picked
 * nearest-to-the-BOT (not nearest-to-anchor) so each hop is as short as
 * possible -- an efficient sweep outward from wherever the bot currently
 * is, rather than always preferring whatever's closest to the anchor
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
    // The committed anchor for THIS activation -- see this class's own
    // docstring for the two ways it gets resolved. Null only defensively
    // (see resolveAndPublishTarget's own null check).
    private Vec3 anchor;

    @Override
    public void onEnter(final TickContext ctx, final LegsState previousState) {
        targetItemId = -1;
        ticksElapsed = 0;
        finished = false;
        anchor = resolveAnchor(ctx);
        resolveAndPublishTarget(ctx);
    }

    @Override
    public void onTick(final TickContext ctx) {
        ticksElapsed++;
        resolveAndPublishTarget(ctx);
        finished = finished || ticksElapsed >= TIMEOUT_TICKS;
    }

    /** See this class's own docstring for why Command.Pickup being present this tick is enough to disambiguate which trigger caused entry, with no extra bookkeeping needed. */
    private static Vec3 resolveAnchor(final TickContext ctx) {
        if (Commands.has(ctx.commands, Command.Pickup.class)) {
            return ctx.player.position();
        }
        return ctx.blackboard.get(DeathWatcher.DEATH_POSITION);
    }

    private void resolveAndPublishTarget(final TickContext ctx) {
        Entity target = currentTarget(ctx);
        if (target == null) {
            target = anchor != null ? nearestPickableToPlayer(ctx, anchor) : null;
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
        LegsNavigateNode.walkTowardNavTarget(ctx, false, false);
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
        // search radius is scoped against it). Harmless no-op when this
        // activation was actually triggered by !pickup instead -- clears
        // a key that was never set to non-null by this run in that case.
        ctx.blackboard.put(DeathWatcher.DEATH_POSITION, null);
        anchor = null;
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    /** The still-committed target entity, or null if there's no committed target or it's no longer loaded (picked up by us/someone else, or despawned) -- getEntity() itself is how ItemDropTracker/broadcastEntityEvents already check "is this id still around" elsewhere in this mod. Re-validates arrow eligibility every tick (not just at commit time) since it's cheap and there's no scenario where an arrow's owner/in-ground state would flip mid-walk anyway. */
    private Entity currentTarget(final TickContext ctx) {
        if (targetItemId == -1) {
            return null;
        }
        Entity entity = ctx.level.getEntity(targetItemId);
        if (entity instanceof ItemEntity) {
            return entity;
        }
        if (entity instanceof AbstractArrow arrow && isArrowPickable(ctx, arrow)) {
            return arrow;
        }
        return null;
    }

    /** Same entitiesForRendering()-filtered scan ItemDropTracker already uses -- see this class's own docstring for why (naturally bounded by render/simulation distance, no separate bounded-AABB search needed). Nearest to the BOT (not to the anchor) so each newly-committed hop is as short as possible -- the anchor only bounds the eligible RADIUS, not which of the eligible candidates gets picked. */
    private static Entity nearestPickableToPlayer(final TickContext ctx, final Vec3 anchor) {
        Vec3 selfPosition = ctx.player.position();
        Entity nearest = null;
        double nearestDistanceSq = Double.MAX_VALUE;
        for (Entity entity : ctx.level.entitiesForRendering()) {
            boolean eligible = entity instanceof ItemEntity
                || (entity instanceof AbstractArrow arrow && isArrowPickable(ctx, arrow));
            if (!eligible) {
                continue;
            }
            if (entity.position().distanceToSqr(anchor) > RADIUS * RADIUS) {
                continue;
            }
            double distanceSq = entity.position().distanceToSqr(selfPosition);
            if (distanceSq < nearestDistanceSq) {
                nearestDistanceSq = distanceSq;
                nearest = entity;
            }
        }
        return nearest;
    }

    /**
     * Real vanilla pickup eligibility (AbstractArrow.Pickup: DISALLOWED/
     * ALLOWED/CREATIVE_ONLY) is server-only data -- confirmed via
     * decompiled source that `pickup`/`firedFromWeapon` are plain fields,
     * never registered with SynchedEntityData, so a client-side mod can't
     * read the real flag directly. Approximated instead from what IS
     * client-visible:
     *
     * - The arrow must actually be embedded in a block (isInGround(),
     *   confirmed via decompiled tick() to mean "position is inside some
     *   solid block's collision AABB", with no floor/wall/ceiling
     *   distinction -- an arrow stuck in a wall or tree counts same as
     *   one on the floor) -- a still-flying arrow isn't touchable/
     *   pickable yet regardless of ownership (see AbstractArrow.
     *   playerTouch's own precondition). isInGround() itself is
     *   `protected` with no public vanilla equivalent, despite IN_GROUND
     *   being real synced entity data (unlike pickup/firedFromWeapon
     *   below) -- reached via AbstractArrowAccessor, a one-method Mixin
     *   @Invoker (see its own docstring).
     * - getOwner() must resolve to SOME Player -- the bot's own LocalPlayer
     *   OR any other player. Ownership itself isn't SynchedEntityData
     *   either, but it IS packed into the entity's spawn packet
     *   (Projectile.getAddEntityPacket) and resolved client-side on
     *   receipt (recreateFromPacket) -- so getOwner() reliably
     *   distinguishes "some player fired this" from "anything else fired
     *   this" (a skeleton's arrows resolve to the skeleton, never a
     *   Player, so they're excluded for free, no separate skeleton-check
     *   needed). Arrows whose owner didn't resolve (not loaded at spawn
     *   time) are excluded too, erring toward "don't chase it" over
     *   risking a real DISALLOWED pickup. Widened from "bot's own
     *   LocalPlayer only" per explicit direction -- vanilla's real
     *   Pickup.ALLOWED rule already lets any player grab an arrow fired by
     *   any other player, this just matches that.
     * - Infinity-fired arrows get CREATIVE_ONLY server-side, not pickable
     *   in survival -- that flag isn't observable on the arrow itself
     *   either. Only checkable for the BOT's own shots, by asking the
     *   bot's own inventory: if the bot carries ANY Infinity-enchanted bow
     *   at all, conservatively treat every one of the bot's OWN ground
     *   arrows as not worth chasing -- there's no way to tell after the
     *   fact which specific bow fired a given already-landed arrow, so
     *   this errs toward "don't chase it" rather than risking a real
     *   CREATIVE_ONLY (unpickable in survival) target. Bare hands/no bow
     *   carried at all means every own-arrow is presumed a normal shot.
     *   Other players' Infinity bows aren't observable at all (their
     *   inventory isn't client-visible the way the bot's own is) -- their
     *   arrows are always treated as pickable, same as vanilla can't be
     *   second-guessed here either way.
     */
    private static boolean isArrowPickable(final TickContext ctx, final AbstractArrow arrow) {
        if (!((AbstractArrowAccessor) arrow).invokeIsInGround()) {
            return false;
        }
        Entity owner = arrow.getOwner();
        if (!(owner instanceof Player)) {
            return false;
        }
        boolean isOwnArrow = owner.getId() == ctx.player.getId();
        return !isOwnArrow || !carriesInfinityBow(ctx);
    }

    private static boolean carriesInfinityBow(final TickContext ctx) {
        Optional<Holder.Reference<Enchantment>> infinity =
            ctx.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(Enchantments.INFINITY);
        if (infinity.isEmpty()) {
            return false;
        }
        Inventory inventory = ctx.player.getInventory();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.getItem() instanceof BowItem && EnchantmentHelper.getItemEnchantmentLevel(infinity.get(), stack) > 0) {
                return true;
            }
        }
        return false;
    }
}
