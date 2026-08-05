package minebot.mod.statemachine;

/**
 * A single node in a StateMachine's graph -- owns real per-tick behavior
 * (not just a label), per STATE_MACHINE.md's "Why nodes own behavior"
 * section.
 */
public interface StateNode {
    /** Called once, the tick a StateMachine transitions INTO this node. */
    default void onEnter(final TickContext ctx) {
    }

    /** Called every tick this node is the StateMachine's current state (including the tick it was entered, right after onEnter). */
    void onTick(TickContext ctx);

    /** Called once, the tick a StateMachine transitions AWAY from this node -- e.g. release any SharedResourceArbiter ownership acquired in onEnter/onTick here (see STATE_MACHINE.md's "Ownership release model"). */
    default void onExit(final TickContext ctx) {
    }
}
