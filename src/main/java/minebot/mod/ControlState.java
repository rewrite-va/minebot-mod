package minebot.mod;

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
    public enum Mode { IDLE, GOTO, FOLLOW }

    public volatile Mode mode = Mode.IDLE;

    // GOTO: a fixed world position.
    public volatile double gotoX;
    public volatile double gotoY;
    public volatile double gotoZ;

    // FOLLOW: a live entity, re-resolved to its current position every tick.
    public volatile int followEntityId;

    public volatile double stopDistance = 2.0;

    public void clear() {
        mode = Mode.IDLE;
    }

    public void setGoto(final double x, final double y, final double z, final double stopDistance) {
        this.mode = Mode.GOTO;
        this.gotoX = x;
        this.gotoY = y;
        this.gotoZ = z;
        this.stopDistance = stopDistance;
    }

    public void setFollow(final int entityId, final double stopDistance) {
        this.mode = Mode.FOLLOW;
        this.followEntityId = entityId;
        this.stopDistance = stopDistance;
    }
}
