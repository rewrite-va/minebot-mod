package minebot.mod;

import minebot.mod.pathfinding.PathTracker;
import minebot.mod.util.EdgeTrigger;
import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.Set;

/**
 * The bot's current movement goal, set by whatever command the Python
 * backend last sent over the WebSocket control channel. Read every client
 * tick by MinebotMod, which resolves the goal to a concrete (x, y, z),
 * computes yaw/forward/jump toward it, and hands that off to
 * MinebotInput -- Python sends high-level goals ("go here", "follow
 * entity N"), not raw per-tick key state, so movement stays smooth even if
 * the WebSocket connection hiccups for a tick and doesn't need Python to
 * recompute yaw/distance itself every tick.
 *
 * Plain mutable fields, not a record: updated from the WebSocket's network
 * thread and read from the client thread, but a torn read of a few
 * primitives is harmless here (worst case one tick uses a slightly stale
 * goal), so no synchronization is used, matching how trivial the actual
 * race risk is.
 */
public final class ControlState {
    public enum Mode { IDLE, GOTO, FOLLOW, GIVE, DIG_DOWN, COLLECT }

    public volatile Mode mode = Mode.IDLE;

    // GOTO: a fixed world position. COLLECT also uses these three fields
    // for whichever item it's currently walking toward -- same shape as
    // GOTO, just re-set by MinebotMod's collect-loop each time it finds a
    // new nearest match, instead of by a single Python command.
    public volatile double gotoX;
    public volatile double gotoY;
    public volatile double gotoZ;

    // FOLLOW: a live entity, re-resolved to its current position every tick.
    // GIVE also walks toward a live entity (the recipient) the same way,
    // reusing followEntityId as the target. COLLECT reuses this too, for
    // whichever entity (if any -- COLLECT can also target a block, which
    // has no entity id) it's currently walking toward/attacking.
    public volatile int followEntityId;

    // GIVE only: which inventory slot/how much to drop once in range.
    public volatile int giveSlot;
    public volatile int giveCount;

    public volatile double stopDistance = 2.0;

    // DIG_DOWN: the original request and how many are still left to break
    // straight down -- digDownTotal is kept alongside digDownRemaining
    // (mirroring collectTotal/collectRemaining below) so the tick loop can
    // report "how many did we actually break" in the result event without
    // needing to reconstruct it after digDownRemaining's already been
    // mutated. No target position/pathTracker involvement at all -- this
    // is a stationary "break the block below me, repeat" loop, ticked
    // directly from MinebotMod.onClientTick rather than through
    // resolveMovementIntent's GOTO-style target resolution.
    public volatile int digDownTotal;
    public volatile int digDownRemaining;

    // COLLECT: single-item now, not a counted loop -- Python decides how
    // many times to ask (see MiningController.collect on the Python
    // side, which sends one `collect` command per item and watches real
    // inventory gains between each to know when to send the next one,
    // instead of the mod looping internally against a count it used to
    // own). This mode's whole job is "find the nearest still-excluded
    // match for collectQuery, walk to it, break/kill it once, report
    // done" -- one attempt, then back to IDLE either way.
    public volatile String collectQuery;
    public volatile int collectRadius;
    public volatile boolean collectTargetIsEntity;
    // False until a target is found -- MinebotMod's collect tick
    // searches once (on the first tick after setCollect) and flips this
    // true once a match is found (setting gotoX/Y/Z or followEntityId+
    // collectTargetIsEntity above to describe it), staying true until
    // that one item is collected or abandoned as unreachable.
    public volatile boolean collectHasTarget;
    // How many ticks the current target has gone without being collected
    // -- reset to 0 whenever a fresh target is picked, incremented every
    // tick tickCollect makes no progress on it. Reported live: !collect
    // picked a block visible only from the wrong side (obstructed by
    // another block from wherever the bot actually stood), and sat there
    // forever -- BlockBreaker.tryBreak's line-of-sight check now refuses
    // to mine it at all, which is correct but on its own just changes
    // "stuck mining through a wall" into "stuck standing next to it", the
    // same net symptom via a different mechanism. This counter is what
    // lets tickCollect eventually give up on a target that never becomes
    // reachable and try the next-nearest one instead.
    public volatile int collectTargetStuckTicks;
    // Block positions abandoned as unreachable this attempt -- see
    // BlockFinder.findNearestBlock's excluded-set overload. Reset by
    // every setCollect call, same as before -- a single !collect attempt
    // can still retry several candidates (nearest, then next-nearest,
    // ...) if earlier ones turn out unreachable, it just never loops
    // past finding and collecting exactly one anymore.
    public final Set<BlockPos> collectExcludedPositions = new HashSet<>();

    // True during the brief "walk to the dropped item" phase after a
    // target is destroyed/killed, before collect_result is actually
    // reported. Reported live: !collect mined a block successfully, but
    // "makes no effort in picking the drop" -- the original design
    // relied entirely on the bot already standing close enough from
    // mining for vanilla's own pickup radius to grab the item as an
    // incidental side effect, with zero explicit retrieval logic. That
    // works when the drop lands right underfoot, but a drop that rolls/
    // bounces even slightly out of range (real item physics), or a
    // target far enough away that "close enough to mine" isn't "close
    // enough to walk over the resulting drop", can be left uncollected
    // on the ground forever. This flag gates a real walk-to-the-item
    // step (see MinebotMod.tickCollect's own completion handling) using
    // the exact same gotoX/Y/Z + pathTracker resolution GOTO/other
    // COLLECT phases already use, targeting the nearest real ItemEntity
    // matching what DropTable says this query should have produced,
    // rather than reporting collect_result the instant the block/entity
    // is merely gone.
    public volatile boolean collectPickingUp;
    // How many ticks the pickup-walk phase has run without the target
    // item actually disappearing (picked up, by us or otherwise, or
    // despawned) -- bounded the same way collectTargetStuckTicks bounds
    // the mining phase, so a drop that rolled somewhere genuinely
    // unreachable (off a ledge, into a wall gap) can't stall !collect
    // forever; the attempt still completes (reporting collect_result)
    // once this expires, same as it always did before this phase
    // existed, just after a real attempt to close the distance first.
    public volatile int collectPickupStuckTicks;

    // Owns the currently-planned A* route toward whatever target the mode
    // above resolves to -- lives here (rather than as a MinebotMod field)
    // so it naturally gets discarded on clear()/setGoto()/setFollow(),
    // same lifetime as the goal it was planned for.
    public final PathTracker pathTracker = new PathTracker();

    // Fires once (see MinebotMod.resolveMovementIntent) when a GOTO goal's
    // distance-to-target first drops under stopDistance. Lives here (not
    // as a MinebotMod field) for the same reason pathTracker does: a fresh
    // setGoto to a new target must reset it, or a second !find/!goto in a
    // row would never report a fresh arrival once the first one fired.
    public final EdgeTrigger gotoArrived = new EdgeTrigger();

    public void clear() {
        mode = Mode.IDLE;
        pathTracker.reset();
        gotoArrived.reset();
    }

    public void setGoto(final double x, final double y, final double z, final double stopDistance) {
        this.mode = Mode.GOTO;
        this.gotoX = x;
        this.gotoY = y;
        this.gotoZ = z;
        this.stopDistance = stopDistance;
        this.pathTracker.reset();
        this.gotoArrived.reset();
    }

    public void setFollow(final int entityId, final double stopDistance) {
        this.mode = Mode.FOLLOW;
        this.followEntityId = entityId;
        this.stopDistance = stopDistance;
        this.pathTracker.reset();
        this.gotoArrived.reset();
    }

    /**
     * Walks toward `entityId` (the recipient) the same way FOLLOW does,
     * and once within `stopDistance`, MinebotMod's tick loop drops
     * `count` of `slot`'s contents and clears back to IDLE -- see
     * MinebotMod.resolveMovementIntent's GIVE branch for the completion
     * check, since that's where live distance-to-target is already
     * computed every tick.
     */
    public void setGive(final int entityId, final int slot, final int count, final double stopDistance) {
        this.mode = Mode.GIVE;
        this.followEntityId = entityId;
        this.giveSlot = slot;
        this.giveCount = count;
        this.stopDistance = stopDistance;
        this.pathTracker.reset();
        this.gotoArrived.reset();
    }

    /** Starts a straight-down dig loop -- see MinebotMod.tickDigDown for the actual per-tick break/abort logic. */
    public void setDigDown(final int count) {
        this.mode = Mode.DIG_DOWN;
        this.digDownTotal = count;
        this.digDownRemaining = count;
        this.pathTracker.reset();
        this.gotoArrived.reset();
    }

    /** Starts a single !collect attempt (find nearest match, walk to it, break/kill it once) -- see MinebotMod.tickCollect for the actual search/walk/break-or-kill logic. */
    public void setCollect(final String query, final int radius) {
        this.mode = Mode.COLLECT;
        this.collectQuery = query;
        this.collectRadius = radius;
        this.collectHasTarget = false;
        this.collectTargetStuckTicks = 0;
        this.collectExcludedPositions.clear();
        this.collectPickingUp = false;
        this.collectPickupStuckTicks = 0;
        // Walk in close enough for BlockBreaker's own INTERACT_RANGE /
        // tickCollectEntity's COLLECT_MELEE_RANGE to take over from here
        // -- both are a few blocks, so 2.5 lands safely within either.
        this.stopDistance = 2.5;
        this.pathTracker.reset();
        this.gotoArrived.reset();
    }
}
