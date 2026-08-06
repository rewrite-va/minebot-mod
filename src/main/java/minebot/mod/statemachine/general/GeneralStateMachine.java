package minebot.mod.statemachine.general;

import minebot.mod.statemachine.Command;
import minebot.mod.statemachine.Commands;
import minebot.mod.statemachine.Edge;
import minebot.mod.statemachine.ResumeNode;
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
 * SELF_HEAL/RESUME are General's first use of the "interrupt and resume"
 * pattern (see StateNode.isFinished/ResumeNode's own docstrings) --
 * RESUME's own edges are declared once here, one per real destination
 * state (IDLE/FOLLOW), not per transient state that might interrupt into
 * it (see the conversation that produced this pattern for why Edge.to
 * stays static rather than becoming dynamically computed).
 */
public final class GeneralStateMachine {
    private GeneralStateMachine() {
    }

    public static StateMachine<GeneralState> create() {
        GeneralSelfHealNode selfHealNode = new GeneralSelfHealNode();
        ResumeNode<GeneralState> resumeNode = new ResumeNode<>();

        Map<GeneralState, StateNode<GeneralState>> nodes = Map.of(
            GeneralState.IDLE, new GeneralIdleNode(),
            GeneralState.FOLLOW, new GeneralFollowNode(),
            GeneralState.SELF_HEAL, selfHealNode,
            GeneralState.RESUME, resumeNode
        );

        Predicate<TickContext> lowHealth = ctx ->
            ctx.player.getHealth() <= ctx.player.getMaxHealth() * GeneralSelfHealNode.lowHealthFraction();

        List<Edge<GeneralState>> edges = List.of(
            new Edge<>(GeneralState.IDLE, GeneralState.FOLLOW, ctx -> Commands.has(ctx.commands, Command.Follow.class)),
            new Edge<>(GeneralState.FOLLOW, GeneralState.IDLE, ctx -> Commands.has(ctx.commands, Command.Stop.class)),
            new Edge<>(GeneralState.IDLE, GeneralState.SELF_HEAL, lowHealth),
            new Edge<>(GeneralState.FOLLOW, GeneralState.SELF_HEAL, lowHealth),
            new Edge<>(GeneralState.SELF_HEAL, GeneralState.RESUME, ctx -> selfHealNode.isFinished()),
            new Edge<>(GeneralState.RESUME, GeneralState.IDLE, ctx -> resumeNode.stateToResumeInto() == GeneralState.IDLE),
            new Edge<>(GeneralState.RESUME, GeneralState.FOLLOW, ctx -> resumeNode.stateToResumeInto() == GeneralState.FOLLOW)
        );

        StateMachine<GeneralState> machine = new StateMachine<>("general", GeneralState.IDLE, nodes, edges);
        resumeNode.bindTo(machine);
        return machine;
    }
}
