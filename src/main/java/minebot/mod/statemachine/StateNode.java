package minebot.mod.statemachine;

/**
 * A single node in a StateMachine's graph -- owns real per-tick behavior
 * (not just a label), per STATE_MACHINE.md's "Why nodes own behavior"
 * section. Generic over the same state enum `S` its owning StateMachine<S>
 * uses, so `previousState` below is properly typed rather than a raw
 * `Enum<?>` every caller would need to cast.
 */
public interface StateNode<S extends Enum<S>> {
    /**
     * Called once, the tick a StateMachine transitions INTO this node.
     * `previousState` is the state being left -- null only on the very
     * first entry ever (the machine's initial state, entered before any
     * real transition has happened), never null on a real transition.
     * Lets a node remember what to return to later (e.g.
     * GeneralSelfHealNode resuming whatever General state it interrupted)
     * without needing an ad-hoc side channel for it.
     */
    default void onEnter(final TickContext ctx, final S previousState) {
    }

    /** Called every tick this node is the StateMachine's current state (including the tick it was entered, right after onEnter). */
    void onTick(TickContext ctx);

    /** Called once, the tick a StateMachine transitions AWAY from this node -- e.g. release any SharedResourceArbiter ownership acquired in onEnter/onTick here (see STATE_MACHINE.md's "Ownership release model"). */
    default void onExit(final TickContext ctx) {
    }

    /**
     * Whether this node considers its own job done -- a plain fact the
     * node itself tracks and exposes (not something the engine infers),
     * readable from any Edge condition to drive a normal transition out
     * of this state, the same way any other Edge condition works (e.g.
     * `new Edge<>(SELF_HEAL, RESUME, ctx -> node.isFinished())`). Default
     * false (never finished) for nodes that don't have a meaningful
     * "done" concept at all (most IDLE-style nodes). A node that DOES
     * have one (e.g. GeneralSelfHealNode once health recovers,
     * LegsNavigateNode once it reaches its target, HandsEatNode once
     * hunger is full) tracks its own boolean internally and returns it
     * here -- deliberately not tied to any notion of "transient state" in
     * the type system (no separate marker interface): any node can
     * become finish-aware without changing what it extends/implements.
     */
    default boolean isFinished() {
        return false;
    }
}
