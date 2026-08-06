package minebot.mod.statemachine.general;

/**
 * The bot's overall behavioral intent -- one of the peer axes described
 * in STATE_MACHINE.md (no hierarchy over Legs/Hands/Head; this is not a
 * "parent" state machine). Built node by node, not all at once (see
 * STATE_MACHINE.md's "Implementation order"); more states (COMBAT,
 * FLEEING, FARMING, BUILDING, DEAD, ...) get added as real behaviors
 * that need them are ported in.
 *
 * SELF_HEAL is reachable from any other state once health drops low
 * enough (see GeneralSelfHealNode) -- General only ever PUBLISHES the
 * "we need to heal" fact (NEEDS_HEAL on the Blackboard); it never
 * touches inventory/keyUse itself. Hands:EAT (a separate SM) is what
 * actually swaps to food and eats, reacting to that published fact --
 * see GeneralSelfHealNode's own docstring for why General never reaches
 * into another axis's actual mechanics, the same rule FOLLOW already
 * follows for Legs/Head.
 *
 * RESUME is the generic "go back to whatever was interrupted" waypoint
 * (see ResumeNode's own docstring) -- SELF_HEAL transitions here once
 * its own isFinished() reports true (health recovered), and RESUME's own
 * edges (one per real destination state, see GeneralStateMachine) send
 * it on to whichever state was actually running before SELF_HEAL
 * interrupted it.
 */
public enum GeneralState {
    IDLE,
    FOLLOW,
    SELF_HEAL,
    RESUME
}
