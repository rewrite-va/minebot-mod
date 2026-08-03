package minebot.mod;

import minebot.mod.pathfinding.PathTracker;

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
    public enum Mode { IDLE, GOTO, FOLLOW, GIVE }

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

    // Owns the currently-planned A* route toward whatever target the mode
    // above resolves to -- lives here (rather than as a MinebotMod field)
    // so it naturally gets discarded on clear()/setGoto()/setFollow(),
    // same lifetime as the goal it was planned for.
    public final PathTracker pathTracker = new PathTracker();

    public void clear() {
        mode = Mode.IDLE;
        pathTracker.reset();
    }

    public void setGoto(final double x, final double y, final double z, final double stopDistance) {
        this.mode = Mode.GOTO;
        this.gotoX = x;
        this.gotoY = y;
        this.gotoZ = z;
        this.stopDistance = stopDistance;
        this.pathTracker.reset();
    }

    public void setFollow(final int entityId, final double stopDistance) {
        this.mode = Mode.FOLLOW;
        this.followEntityId = entityId;
        this.stopDistance = stopDistance;
        this.pathTracker.reset();
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
    }
}
