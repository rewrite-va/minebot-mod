package minebot.mod.statemachine.general;

/**
 * The bot's overall behavioral intent -- one of the peer axes described
 * in STATE_MACHINE.md (no hierarchy over Legs/Hands/Head; this is not a
 * "parent" state machine). Starting with just IDLE (see STATE_MACHINE.md's
 * "Implementation order" -- built node by node, not all at once); more
 * states (COMBAT, FLEEING, FARMING, BUILDING, DEAD, ...) get added as
 * real behaviors that need them are ported in.
 */
public enum GeneralState {
    IDLE
}
