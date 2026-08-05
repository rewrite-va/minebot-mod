package minebot.mod;

import minebot.mod.pathfinding.PathTracker;
import minebot.mod.util.EdgeTrigger;

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
    public enum Mode { IDLE, GOTO, FOLLOW, GIVE, DIG_DOWN }

    public volatile Mode mode = Mode.IDLE;

    // GOTO: a fixed world position.
    public volatile double gotoX;
    public volatile double gotoY;
    public volatile double gotoZ;

    // FOLLOW: a live entity, re-resolved to its current position every tick.
    // GIVE also walks toward a live entity (the recipient) the same way,
    // reusing followEntityId as the target.
    public volatile int followEntityId;

    // GIVE only: which inventory slot/how much to drop once in range.
    public volatile int giveSlot;
    public volatile int giveCount;

    public volatile double stopDistance = 2.0;

    // DIG_DOWN: the original request and how many are still left to break
    // straight down -- digDownTotal is kept alongside digDownRemaining so
    // the tick loop can report "how many did we actually break" in the
    // result event without needing to reconstruct it after
    // digDownRemaining's already been mutated. No target position/
    // pathTracker involvement at all -- this is a stationary "break the
    // block below me, repeat" loop, ticked directly from
    // MinebotMod.onClientTick rather than through resolveMovementIntent's
    // GOTO-style target resolution.
    public volatile int digDownTotal;
    public volatile int digDownRemaining;

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
}
