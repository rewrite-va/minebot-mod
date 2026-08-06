package minebot.mod.statemachine.legs;

import minebot.mod.statemachine.Edge;
import minebot.mod.statemachine.StateMachine;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.general.GeneralFollowNode;
import minebot.mod.statemachine.general.GeneralState;

import java.util.List;
import java.util.Map;

/**
 * Builds the Legs StateMachine -- see LegsState/LegsNavigateNode's own
 * docstrings for scope. NAVIGATE<->IDLE edges read General's published
 * state directly (General==FOLLOW and not yet within range -> navigate;
 * otherwise -> idle) rather than reacting to Command.Follow/Stop itself
 * -- General:FOLLOW is the one thing that owns "are we currently close
 * enough to the followed entity" (see GeneralFollowNode's own docstring
 * for why), Legs is a pure consumer of that decision.
 */
public final class LegsStateMachine {
    private LegsStateMachine() {
    }

    /** `navigateNode` is constructed by the caller (not internally) so it can also hold onto the reference directly -- e.g. MinebotMod wires PathVisualizer to navigateNode.pathTracker() for debug rendering. */
    public static StateMachine<LegsState> create(final LegsNavigateNode navigateNode, final StateMachine<GeneralState> generalStateMachine) {
        Map<LegsState, StateNode> nodes = Map.of(
            LegsState.IDLE, new LegsIdleNode(),
            LegsState.NAVIGATE, navigateNode
        );
        List<Edge<LegsState>> edges = List.of(
            new Edge<>(LegsState.IDLE, LegsState.NAVIGATE, ctx ->
                ctx.blackboard.get(generalStateMachine) == GeneralState.FOLLOW && !Boolean.TRUE.equals(ctx.blackboard.get(GeneralFollowNode.WITHIN_RANGE))
            ),
            new Edge<>(LegsState.NAVIGATE, LegsState.IDLE, ctx ->
                ctx.blackboard.get(generalStateMachine) != GeneralState.FOLLOW || Boolean.TRUE.equals(ctx.blackboard.get(GeneralFollowNode.WITHIN_RANGE))
            )
        );
        return new StateMachine<>("legs", LegsState.IDLE, nodes, edges);
    }
}
