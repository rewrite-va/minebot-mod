package minebot.mod.statemachine;

import minebot.mod.MinebotMod;

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
 * Every state entry is logged here, as part of entering it (see the
 * private enter() helper -- the log happens immediately before the
 * node's own onEnter runs, so it's genuinely part of "entering", not a
 * separate step bolted on alongside it) -- not left for individual
 * nodes' onEnter to each remember to log themselves (same reasoning as
 * the SharedResourceArbiter's reapAbandonedOwnership() safety net in
 * STATE_MACHINE.md: a per-node-remembers-to-do-it convention is exactly
 * the kind of thing that silently doesn't happen somewhere and is hard
 * to notice).
 *
 * Not thread-safe -- only ever touched from the client tick thread, same
 * as every other piece of per-tick mod state (ControlState, EdgeTrigger,
 * ...).
 */
public final class StateMachine<S extends Enum<S>> {
    private final String name;
    private final S initialState;
    private final Map<S, StateNode<S>> nodes;
    private final Map<S, List<Edge<S>>> edgesByFromState;

    private S currentState;
    private boolean started;

    /** `name` identifies this SM in log lines (e.g. "general", "legs") -- purely cosmetic, doesn't affect behavior. */
    public StateMachine(final String name, final S initialState, final Map<S, StateNode<S>> nodes, final List<Edge<S>> edges) {
        this.name = name;
        this.initialState = initialState;
        this.nodes = nodes;
        this.edgesByFromState = edges.stream()
            .collect(java.util.stream.Collectors.groupingBy(Edge::from, java.util.LinkedHashMap::new, java.util.stream.Collectors.toList()));
        this.currentState = initialState;
    }

    public String name() {
        return name;
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
            enter(null, currentState, ctx);
        }

        nodeFor(currentState).onTick(ctx);

        for (Edge<S> edge : edgesByFromState.getOrDefault(currentState, List.of())) {
            if (edge.condition().test(ctx)) {
                nodeFor(currentState).onExit(ctx);
                S previousState = currentState;
                currentState = edge.to();
                enter(previousState, currentState, ctx);
                break; // first matching edge wins, per STATE_MACHINE.md -- not evaluating the rest against the new state until next tick
            }
        }

        ctx.blackboard.publish(this, currentState);
    }

    /** The single place a state is ever entered -- logs, THEN calls the node's own onEnter, so the log line is genuinely part of "entering", not a separate step alongside it. `previousState` is null only for the machine's very first-ever entry (see StateNode.onEnter's own docstring). */
    private void enter(final S previousState, final S state, final TickContext ctx) {
        MinebotMod.LOGGER.info("{}: entering {}", name, state);
        nodeFor(state).onEnter(ctx, previousState);
    }

    private StateNode<S> nodeFor(final S state) {
        StateNode<S> node = nodes.get(state);
        if (node == null) {
            throw new IllegalStateException("no StateNode registered for " + state);
        }
        return node;
    }
}
