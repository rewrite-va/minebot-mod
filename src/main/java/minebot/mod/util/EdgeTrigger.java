package minebot.mod.util;

/**
 * Fires exactly once when a per-tick condition transitions from false to
 * true, then stays silent on every following tick the condition is still
 * true -- resets automatically the moment the condition goes false again,
 * ready to fire on the next rising edge. This is the "announce a state
 * change once, not every tick it holds" pattern FoodEater needed for its
 * chat lines (health is checked every tick, but "I have N hearts!,
 * eating..." should only be sent the tick health first drops low, not
 * spammed for as long as it stays low) -- pulled out so the next behavior
 * that needs the same shape doesn't have to hand-roll another
 * announcedX/reset-in-the-else boolean pair.
 *
 * Not thread-safe -- same expectation as the rest of the per-tick mod
 * state (ControlState etc.), which is only ever touched from the client
 * tick thread.
 */
public final class EdgeTrigger {
    private boolean active = false;

    /**
     * Call once per tick with the current condition. Returns true only on
     * the tick it first becomes true (the "rising edge"); returns false on
     * every tick before that and every tick after, until the condition
     * goes false and comes back true again.
     */
    public boolean fire(final boolean condition) {
        if (!condition) {
            active = false;
            return false;
        }
        if (active) {
            return false;
        }
        active = true;
        return true;
    }

    /** Forces the next fire(true) to report a fresh rising edge, as if the condition had just gone false. */
    public void reset() {
        active = false;
    }
}
