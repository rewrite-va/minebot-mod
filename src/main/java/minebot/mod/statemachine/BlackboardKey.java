package minebot.mod.statemachine;

/**
 * A typed key for arbitrary cross-SM data on the Blackboard, beyond just
 * "which state is this SM currently in" (see Blackboard.publish/get for
 * that). E.g. Legs publishes its current waypoint aim point under a key
 * Head reads, without either SM needing a direct reference to the
 * other's node instance -- see STATE_MACHINE.md's "The blackboard"
 * section for why this indirection matters (avoids import cycles/direct
 * coupling between independent peer SMs).
 *
 * Identity-based (a plain `new BlackboardKey<>()` instance, not a
 * String) for the same reason StateMachine itself is used as its own map
 * key -- no separate id-registration/collision risk.
 */
public final class BlackboardKey<T> {
}
