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
 * docstring for why DEFEND, unlike KILL, is a standing goal rather than
 * an incidental trigger.
 *
 * KILL's own exit edge resumes PlayerIntention's current state
 * (IDLE/FOLLOW/DEFEND) rather than some node-tracked stateToResume -- see
 * PlayerIntention's own docstring for the multi-hop bug that approach
 * had. There is deliberately no DEAD state on this axis anymore (see
 * PlayerIntentionState's own docstring for why) -- dying no longer
 * interrupts IDLE/FOLLOW/DEFEND/KILL at all; DeathWatcher (ticked
 * standalone, outside this or any peer SM) handles respawn() directly,
 * and LegsState's own GO_TO_DEATH_POSITION/PICKUP_ITEMS chain reacts to
 * DeathWatcher.DEATH_SEQUENCE independently.
 *
 * KILL is reachable from IDLE/FOLLOW/DEFEND via Command.Kill, and exits
 * straight back to whichever of those intention currently says once
 * killNode.isFinished() -- per explicit direction, a kill is a one-shot
 * task; there's no DEAD state anymore to special-case around (an earlier
 * version's docstring here noted KILL was deliberately unreachable from
 * DEAD's own resume edges -- moot now that DEAD isn't on this axis at
 * all, but dying mid-fight still simply lets the current KILL end on its
 * own terms via killNode.isFinished(), same as it always did -- see
 * PlayerIntentionKillNode/PlayerIntention's own docstrings).
 *
 * KILL's own self-loop (from == to == KILL) is ONLY for a fresh
 * Command.Kill arriving while already fighting (a re-target) --
 * StateMachine.tick() re-runs onExit/onEnter on a self-loop exactly like
 * any other transition (confirmed live), which is exactly what a
 * re-target needs (PlayerIntentionKillNode.onEnter re-resolving a target from the
 * NEW command). There is deliberately no second self-loop for "target
 * died, keep fighting" -- isFinished() always exits straight to intention
 * now, never back into KILL (an earlier version had exactly that
 * self-loop and it caused an unwanted infinite-refight; see git history).
 *
 * DEFEND itself never appears as a `to` for any "fight finished" edge --
 * PlayerIntentionDefendNode never sets isFinished() at all (it's a standing
 * state, only !stop/!follow/!kill ever leave it), so there's no
 * "KILL -> DEFEND once done" edge the way there is for IDLE/FOLLOW: KILL
 * superseding DEFEND (via a fresh !kill while defending) exits straight
 * back to DEFEND through the same intendsDefend resume edge every other
 * intention uses, once that kill itself finishes.
 */
public final class PlayerIntentionStateMachine {
    private PlayerIntentionStateMachine() {
    }

    public static StateMachine<PlayerIntentionState> create(final PlayerIntention intention) {
        PlayerIntentionKillNode killNode = new PlayerIntentionKillNode();
        PlayerIntentionDefendNode defendNode = new PlayerIntentionDefendNode(intention);

        Map<PlayerIntentionState, StateNode<PlayerIntentionState>> nodes = Map.of(
            PlayerIntentionState.IDLE, new PlayerIntentionIdleNode(),
            PlayerIntentionState.FOLLOW, new PlayerIntentionFollowNode(intention),
            PlayerIntentionState.DEFEND, defendNode,
            PlayerIntentionState.KILL, killNode
        );

        Predicate<TickContext> isFollowCommand = ctx -> Commands.has(ctx.commands, Command.Follow.class);
        Predicate<TickContext> isDefendCommand = ctx -> Commands.has(ctx.commands, Command.Defend.class);
        Predicate<TickContext> isStopCommand = ctx -> Commands.has(ctx.commands, Command.Stop.class);
        Predicate<TickContext> isKillCommand = ctx -> Commands.has(ctx.commands, Command.Kill.class);
        Predicate<TickContext> intendsIdle = ctx -> intention.current().state() == PlayerIntentionState.IDLE;
        Predicate<TickContext> intendsFollow = ctx -> intention.current().state() == PlayerIntentionState.FOLLOW;
        Predicate<TickContext> intendsDefend = ctx -> intention.current().state() == PlayerIntentionState.DEFEND;

        List<Edge<PlayerIntentionState>> edges = List.of(
            // IDLE/FOLLOW/DEFEND are full peers -- any real command
            // switches directly between any of the three.
            new Edge<>(PlayerIntentionState.IDLE, PlayerIntentionState.FOLLOW, isFollowCommand),
            new Edge<>(PlayerIntentionState.DEFEND, PlayerIntentionState.FOLLOW, isFollowCommand),
            new Edge<>(PlayerIntentionState.FOLLOW, PlayerIntentionState.IDLE, isStopCommand),
            new Edge<>(PlayerIntentionState.DEFEND, PlayerIntentionState.IDLE, isStopCommand),
            new Edge<>(PlayerIntentionState.IDLE, PlayerIntentionState.DEFEND, isDefendCommand),
            new Edge<>(PlayerIntentionState.FOLLOW, PlayerIntentionState.DEFEND, isDefendCommand),
            // A fresh !defend while ALREADY defending (re-target) --
            // same self-loop shape KILL's own re-target edge uses.
            new Edge<>(PlayerIntentionState.DEFEND, PlayerIntentionState.DEFEND, isDefendCommand),

            // KILL reachable from/returns to any of the three.
            new Edge<>(PlayerIntentionState.IDLE, PlayerIntentionState.KILL, isKillCommand),
            new Edge<>(PlayerIntentionState.FOLLOW, PlayerIntentionState.KILL, isKillCommand),
            new Edge<>(PlayerIntentionState.DEFEND, PlayerIntentionState.KILL, isKillCommand),
            new Edge<>(PlayerIntentionState.KILL, PlayerIntentionState.IDLE, ctx -> isStopCommand.test(ctx) || (killNode.isFinished() && intendsIdle.test(ctx))),
            new Edge<>(PlayerIntentionState.KILL, PlayerIntentionState.FOLLOW, ctx -> isFollowCommand.test(ctx) || (killNode.isFinished() && intendsFollow.test(ctx))),
            new Edge<>(PlayerIntentionState.KILL, PlayerIntentionState.DEFEND, ctx -> isDefendCommand.test(ctx) || (killNode.isFinished() && intendsDefend.test(ctx))),
            // A fresh !kill while ALREADY in KILL (re-target) -- see this
            // class's own docstring for why this is the ONLY KILL->KILL
            // edge (no "target died, keep fighting" loop).
            new Edge<>(PlayerIntentionState.KILL, PlayerIntentionState.KILL, isKillCommand)
        );

        return new StateMachine<>("playerintention", PlayerIntentionState.IDLE, nodes, edges);
    }
}
