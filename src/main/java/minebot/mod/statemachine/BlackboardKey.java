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
 * Identity-based (a plain `new BlackboardKey<>(name)` instance, not a
 * String used as the actual map key) for the same reason StateMachine
 * itself is used as its own map key -- no separate id-registration/
 * collision risk; two keys named the same way are still distinct.
 *
 * `name` is purely for display (StatusHud's own Blackboard-values
 * section reads it) and debug logging -- never used for lookup/equality
 * (Blackboard.data is still keyed by identity, per this class's own
 * fields being otherwise empty). Required at construction (not optional)
 * so every key declared from here on is self-documenting at its
 * declaration site, matching how each one is already named via its own
 * Java field name (e.g. `TARGET_ENTITY_ID`) -- this just makes that name
 * visible at runtime too, not only in source.
 */
public final class BlackboardKey<T> {
    private final String name;

    public BlackboardKey(final String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
