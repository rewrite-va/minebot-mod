package minebot.mod;

/**
 * The bot's current movement goal, set by whatever command the Python
 * backend last sent over the WebSocket control channel.
 *
 * This used to carry all of GOTO/FOLLOW/GIVE/DIG_DOWN/COLLECT/ATTACK's
 * per-mode fields plus the shared pathfinding/arrival-tracking state
 * (pathTracker, gotoArrived) that MinebotMod.resolveMovementIntent read
 * every tick to drive movement. All of that was removed along with the
 * chat-command surface and resolveMovementIntent itself as part of the
 * state-machine architecture pivot (see STATE_MACHINE.md) -- FOLLOW is
 * now driven by LegsNavigateNode (Command.Follow via CommandBus), which
 * owns its own node-local PathTracker instead of sharing one through
 * here. See git history for the removed fields/methods if reintroducing
 * one of the old modes as a real state-machine node later.
 *
 * Plain mutable field, not a record: updated from the WebSocket's network
 * thread and read from the client thread, but a torn read of a single
 * enum reference is harmless here, matching how trivial the actual race
 * risk is.
 */
public final class ControlState {
    public enum Mode { IDLE }

    public volatile Mode mode = Mode.IDLE;

    public void clear() {
        mode = Mode.IDLE;
    }
}
