package minebot.mod.statemachine.general;

/**
 * The bot's overall behavioral intent -- one of the peer axes described
 * in STATE_MACHINE.md (no hierarchy over Legs/Hands/Head; this is not a
 * "parent" state machine). Built node by node, not all at once (see
 * STATE_MACHINE.md's "Implementation order"); more states (COMBAT,
 * FLEEING, FARMING, BUILDING, DEAD, ...) get added as real behaviors
 * that need them are ported in.
 *
 * FOLLOW is currently a label only -- GeneralFollowNode does nothing yet.
 * It exists to prove the Command/CommandBus pipeline end to end (a real
 * !follow chat command produces a Command.Follow, crosses from the
 * WebSocket thread to the tick thread, and drives a real transition
 * here) ahead of Legs SM actually doing anything with it later. The
 * bot's real FOLLOW movement behavior is still entirely driven by
 * ControlState/resolveMovementIntent, untouched by this.
 */
public enum GeneralState {
    IDLE,
    FOLLOW
}
