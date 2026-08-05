package minebot.mod.statemachine;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared read model every StateMachine publishes its current state to
 * once per tick, and any StateMachine's Edge conditions can read from --
 * see STATE_MACHINE.md's "The blackboard" section for the full reasoning
 * (why this is what lets independent peer SMs read each other's state
 * without a direct reference/import-cycle risk between them, and why the
 * one-tick-lag this implies is deliberate, matching ControlState's own
 * existing stopDistance/attackRetreating pattern).
 *
 * Keyed by StateMachine identity itself (not a String id) -- avoids a
 * whole separate id-registration/lookup mechanism; a StateMachine
 * instance IS its own key.
 *
 * Not thread-safe -- same expectation as the rest of this mod's per-tick
 * state (ControlState, EdgeTrigger, ...): only ever touched from the
 * client tick thread.
 */
public final class Blackboard {
    private final Map<StateMachine<?>, Enum<?>> states = new HashMap<>();

    /** Called once per tick by each StateMachine after resolving its transitions for that tick. */
    <S extends Enum<S>> void publish(final StateMachine<S> machine, final S state) {
        states.put(machine, state);
    }

    /**
     * Reads another (or the same) StateMachine's current published
     * state. Returns machine.initialState() if that machine hasn't
     * published yet this run (e.g. read during the very first tick,
     * before any StateMachine.tick() has run at all) -- never null, so
     * callers never need a null check just because of tick ordering.
     */
    @SuppressWarnings("unchecked")
    public <S extends Enum<S>> S get(final StateMachine<S> machine) {
        Enum<?> value = states.get(machine);
        return value != null ? (S) value : machine.initialState();
    }
}
