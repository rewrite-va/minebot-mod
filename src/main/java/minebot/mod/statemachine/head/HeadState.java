package minebot.mod.statemachine.head;

/**
 * Where the bot is looking and why -- one of the peer axes described in
 * STATE_MACHINE.md (no hierarchy over General/Legs/Hands). Owns yaw/
 * pitch exclusively; Legs deliberately never writes either (see
 * LegsNavigateNode's own docstring) -- this is the other half of that
 * split actually being implemented.
 *
 * NAVIGATE aims at whatever point Legs is currently walking toward (its
 * published AIM_POINT on the Blackboard -- see LegsNavigateNode), the
 * same "look at the next waypoint while walking" behavior the old shared
 * pipeline had baked in. More states (aiming at a combat target, looking
 * at a nearby player while idle, etc.) get added as those behaviors are
 * ported in later, per STATE_MACHINE.md's "Implementation order" (built
 * node by node, not all at once).
 */
public enum HeadState {
    IDLE,
    NAVIGATE
}
