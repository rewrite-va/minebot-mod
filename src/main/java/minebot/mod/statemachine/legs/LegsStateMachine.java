package minebot.mod.statemachine.legs;

import minebot.mod.statemachine.Command;
import minebot.mod.statemachine.Commands;
import minebot.mod.statemachine.Edge;
import minebot.mod.statemachine.StateMachine;
import minebot.mod.statemachine.StateNode;

import java.util.List;
import java.util.Map;

/** Builds the Legs StateMachine -- see LegsState/LegsNavigateNode's own docstrings for scope. */
public final class LegsStateMachine {
    private LegsStateMachine() {
    }

    public static StateMachine<LegsState> create() {
        Map<LegsState, StateNode> nodes = Map.of(
            LegsState.IDLE, new LegsIdleNode(),
            LegsState.NAVIGATE, new LegsNavigateNode()
        );
        List<Edge<LegsState>> edges = List.of(
            new Edge<>(LegsState.IDLE, LegsState.NAVIGATE, ctx -> Commands.has(ctx.commands, Command.Follow.class)),
            new Edge<>(LegsState.NAVIGATE, LegsState.IDLE, ctx -> Commands.has(ctx.commands, Command.Stop.class))
        );
        return new StateMachine<>("legs", LegsState.IDLE, nodes, edges);
    }
}
