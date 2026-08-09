package minebot.mod.statemachine.playerintention;

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
 * Builds the PlayerIntention StateMachine (renamed from
 * GeneralStateMachine -- see git history) -- see STATE_MACHINE.md's
 * "Implementation order" for why this axis is built node by node rather
 * than all at once, and PlayerIntentionState's own docstring for why
 * FOLLOW is currently a label only.
 *
 * Edges here are the first real use of Command/CommandBus (see their own
 * docstrings) instead of reading ControlState directly -- a deliberate
 * choice to keep new SM work from growing a second dependency on
 * ControlState's shape.
 *
 * IDLE/FOLLOW/DEFEND are all real PlayerIntention values, wired as full
 * peers of each other throughout this edge table (any of the three
 * commands -- Follow/Defend/Stop -- transitions directly from any of the
 * three states, not just from IDLE) -- see PlayerIntention's own
 * docstring for why DEFEND is a standing goal rather than an incidental
 * trigger.
 *
 * There is deliberately no DEAD state on this axis anymore (see
 * PlayerIntentionState's own docstring for why) -- dying no longer
 * interrupts IDLE/FOLLOW/DEFEND at all; DeathWatcher (ticked standalone,
 * outside this or any peer SM) handles respawn() directly, and
 * LegsState's own GO_TO_DEATH_POSITION/PICKUP_ITEMS chain reacts to
 * DeathWatcher.DEATH_SEQUENCE independently.
 *
 * KILL/SLEEP are deliberately NOT on this axis -- see
 * PlayerIntentionState's own docstring for why (!kill/!sleep are queued
 * TaskController.Tasks, task/KillTask and task/SleepTask, the same home
 * !give's GiveTask already established).
 */
public final class PlayerIntentionStateMachine {
    private PlayerIntentionStateMachine() {
    }

    public static StateMachine<PlayerIntentionState> create(final PlayerIntention intention) {
        PlayerIntentionDefendNode defendNode = new PlayerIntentionDefendNode(intention);

        Map<PlayerIntentionState, StateNode<PlayerIntentionState>> nodes = Map.of(
            PlayerIntentionState.IDLE, new PlayerIntentionIdleNode(),
            PlayerIntentionState.FOLLOW, new PlayerIntentionFollowNode(intention),
            PlayerIntentionState.DEFEND, defendNode
        );

        Predicate<TickContext> isFollowCommand = ctx -> Commands.has(ctx.commands, Command.Follow.class);
        Predicate<TickContext> isDefendCommand = ctx -> Commands.has(ctx.commands, Command.Defend.class);
        Predicate<TickContext> isStopCommand = ctx -> Commands.has(ctx.commands, Command.Stop.class);

        List<Edge<PlayerIntentionState>> edges = List.of(
            // IDLE/FOLLOW/DEFEND are full peers -- any real command
            // switches directly between any of the three.
            new Edge<>(PlayerIntentionState.IDLE, PlayerIntentionState.FOLLOW, isFollowCommand),
            new Edge<>(PlayerIntentionState.DEFEND, PlayerIntentionState.FOLLOW, isFollowCommand),
            new Edge<>(PlayerIntentionState.FOLLOW, PlayerIntentionState.IDLE, isStopCommand),
            new Edge<>(PlayerIntentionState.DEFEND, PlayerIntentionState.IDLE, isStopCommand),
            new Edge<>(PlayerIntentionState.IDLE, PlayerIntentionState.DEFEND, isDefendCommand),
            new Edge<>(PlayerIntentionState.FOLLOW, PlayerIntentionState.DEFEND, isDefendCommand),
            // A fresh !defend while ALREADY defending (re-target).
            new Edge<>(PlayerIntentionState.DEFEND, PlayerIntentionState.DEFEND, isDefendCommand)
        );

        return new StateMachine<>("playerintention", PlayerIntentionState.IDLE, nodes, edges);
    }
}
