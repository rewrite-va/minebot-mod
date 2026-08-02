package minebot.mod;

/**
 * The bot's current desired movement input, set by whatever command the
 * Python backend last sent over the WebSocket control channel and read
 * every client tick by MinebotInput/MinebotMod. Plain mutable fields, not
 * a record: this is updated from the WebSocket's network thread and read
 * from the client thread, but Minecraft's own tick loop is the only
 * reader/mutator-timing that matters here -- a torn read of a few booleans
 * is harmless (worst case: one tick's input is slightly stale), so no
 * synchronization is used, matching how trivial the actual risk is.
 */
public final class ControlState {
    public volatile boolean forward;
    public volatile boolean jump;
    public volatile boolean sprint;
    // Target look direction in degrees, vanilla convention (yaw 0 = south/+z).
    // Null means "don't change look direction this tick".
    public volatile Float targetYaw;
    public volatile Float targetPitch;

    public void clear() {
        forward = false;
        jump = false;
        sprint = false;
        targetYaw = null;
        targetPitch = null;
    }
}
