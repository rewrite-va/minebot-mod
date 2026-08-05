package minebot.mod.statemachine.general;

import minebot.mod.statemachine.Command;
import minebot.mod.statemachine.Edge;
import minebot.mod.statemachine.StateMachine;
import minebot.mod.statemachine.StateNode;

import java.util.List;
import java.util.Map;

/**
 * Builds the General StateMachine -- see STATE_MACHINE.md's
 * "Implementation order" for why this axis is built node by node rather
 * than all at once, and GeneralState's own docstring for why FOLLOW is
 * currently a label only.
 *
 * Edges here are the first real use of Command/CommandBus (see their own
 * docstrings) instead of reading ControlState directly -- a deliberate
 * choice to keep new SM work from growing a second dependency on
 * ControlState's shape.
 */
public final class GeneralStateMachine {
    private GeneralStateMachine() {
    }

    public static StateMachine<GeneralState> create() {
        Map<GeneralState, StateNode> nodes = Map.of(
            GeneralState.IDLE, new GeneralIdleNode(),
            GeneralState.FOLLOW, new GeneralFollowNode()
        );
        List<Edge<GeneralState>> edges = List.of(
            new Edge<>(GeneralState.IDLE, GeneralState.FOLLOW, ctx -> hasCommand(ctx.commands, Command.Follow.class)),
            new Edge<>(GeneralState.FOLLOW, GeneralState.IDLE, ctx -> hasCommand(ctx.commands, Command.Stop.class))
        );
        return new StateMachine<>("general", GeneralState.IDLE, nodes, edges);
    }

    private static boolean hasCommand(final List<Command> commands, final Class<? extends Command> type) {
        for (Command command : commands) {
            if (type.isInstance(command)) {
                return true;
            }
        }
        return false;
    }
}
