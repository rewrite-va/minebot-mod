package minebot.mod;

/**
 * The concrete per-tick movement input resolved from ControlState's
 * high-level goal (see MinebotMod.resolveMovementIntent) -- yaw already
 * computed toward whatever the current goal's target position is, forward
 * held whenever we're still farther than the goal's stop distance, jump
 * held whenever the target is above us. MinebotInput reads this directly
 * rather than resolving goals itself, keeping goal-resolution (which needs
 * live entity/player state) separate from input-plumbing (which doesn't).
 *
 * A fresh instance is constructed every tick and handed to MinebotInput
 * as a whole (via its own volatile field) rather than mutated in place, so
 * the individual fields here don't need their own synchronization.
 */
public final class MovementIntent {
    public boolean forward;
    public boolean jump;
    public boolean sprint;
    public Float yaw; // degrees, vanilla convention; null = don't change look direction
}
