package minebot.mod.statemachine;

import java.util.List;
import java.util.Map;

/**
 * One independent, peer axis of the bot's behavior (Legs/Hands/Head/
 * General, ...) -- see STATE_MACHINE.md for the full design this
 * implements. Ticks its current node, evaluates that node's outgoing
 * edges in declaration order, takes the first whose condition is true
 * (calling onExit/onEnter as the transition happens), then publishes its
 * (possibly just-changed) current state to the shared Blackboard.
 *
 * Not thread-safe -- only ever touched from the client tick thread, same
 * as every other piece of per-tick mod state (ControlState, EdgeTrigger,
 * ...).
 */
public final class StateMachine<S extends Enum<S>> {
    private final S initialState;
    private final Map<S, StateNode> nodes;
    private final Map<S, List<Edge<S>>> edgesByFromState;

    private S currentState;
    private boolean started;

    public StateMachine(final S initialState, final Map<S, StateNode> nodes, final List<Edge<S>> edges) {
        this.initialState = initialState;
        this.nodes = nodes;
        this.edgesByFromState = edges.stream()
            .collect(java.util.stream.Collectors.groupingBy(Edge::from, java.util.LinkedHashMap::new, java.util.stream.Collectors.toList()));
        this.currentState = initialState;
    }

    public S initialState() {
        return initialState;
    }

    public S currentState() {
        return currentState;
    }

    /** Call once per tick. Publishes the resulting current state to `blackboard` itself -- callers don't need a separate publish step. */
    public void tick(final TickContext ctx) {
        if (!started) {
            started = true;
            nodeFor(currentState).onEnter(ctx);
        }

        nodeFor(currentState).onTick(ctx);

        for (Edge<S> edge : edgesByFromState.getOrDefault(currentState, List.of())) {
            if (edge.condition().test(ctx)) {
                nodeFor(currentState).onExit(ctx);
                currentState = edge.to();
                nodeFor(currentState).onEnter(ctx);
                break; // first matching edge wins, per STATE_MACHINE.md -- not evaluating the rest against the new state until next tick
            }
        }

        ctx.blackboard.publish(this, currentState);
    }

    private StateNode nodeFor(final S state) {
        StateNode node = nodes.get(state);
        if (node == null) {
            throw new IllegalStateException("no StateNode registered for " + state);
        }
        return node;
    }
}
