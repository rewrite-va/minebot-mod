package minebot.mod.statemachine.general;

import minebot.mod.statemachine.Command;
import minebot.mod.statemachine.Commands;
import minebot.mod.statemachine.Edge;
import minebot.mod.statemachine.StateMachine;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

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
 *
 * SELF_HEAL's own exit edges go straight to the real destination state
 * (one per possible destination, each checking both isFinished() and
 * stateToResume() -- see GeneralSelfHealNode's own docstring for why
 * there's no intermediate "resume" waypoint).
 */
public final class GeneralStateMachine {
    private GeneralStateMachine() {
    }

    public static StateMachine<GeneralState> create() {
        GeneralSelfHealNode selfHealNode = new GeneralSelfHealNode();

        Map<GeneralState, StateNode<GeneralState>> nodes = Map.of(
            GeneralState.IDLE, new GeneralIdleNode(),
            GeneralState.FOLLOW, new GeneralFollowNode(),
            GeneralState.SELF_HEAL, selfHealNode
        );

        Predicate<TickContext> lowHealth = ctx ->
            ctx.player.getHealth() <= ctx.player.getMaxHealth() * GeneralSelfHealNode.lowHealthFraction();

        List<Edge<GeneralState>> edges = List.of(
            new Edge<>(GeneralState.IDLE, GeneralState.FOLLOW, ctx -> Commands.has(ctx.commands, Command.Follow.class)),
            new Edge<>(GeneralState.FOLLOW, GeneralState.IDLE, ctx -> Commands.has(ctx.commands, Command.Stop.class)),
            new Edge<>(GeneralState.IDLE, GeneralState.SELF_HEAL, lowHealth),
            new Edge<>(GeneralState.FOLLOW, GeneralState.SELF_HEAL, lowHealth),
            new Edge<>(GeneralState.SELF_HEAL, GeneralState.IDLE, ctx -> selfHealNode.isFinished() && selfHealNode.stateToResume() == GeneralState.IDLE),
            new Edge<>(GeneralState.SELF_HEAL, GeneralState.FOLLOW, ctx -> selfHealNode.isFinished() && selfHealNode.stateToResume() == GeneralState.FOLLOW)
        );

        return new StateMachine<>("general", GeneralState.IDLE, nodes, edges);
    }
}
