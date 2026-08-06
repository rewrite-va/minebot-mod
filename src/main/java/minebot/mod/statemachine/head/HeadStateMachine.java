package minebot.mod.statemachine.head;

import minebot.mod.statemachine.Edge;
import minebot.mod.statemachine.StateMachine;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.legs.LegsState;

import java.util.List;
import java.util.Map;

/**
 * Builds the Head StateMachine -- see HeadState's own docstring for
 * scope. Its NAVIGATE<->IDLE edges read Legs' published state off the
 * Blackboard (ctx.blackboard.get(legsStateMachine)) rather than a
 * Command, unlike General/Legs' own edges -- this is a real example of
 * one peer SM's edges reacting to another peer's state directly (see
 * STATE_MACHINE.md's "no hierarchy" section: Head isn't "commanded" by
 * Legs, it's independently deciding to mirror Legs' navigating state
 * because that happens to be the right condition for its own NAVIGATE
 * state, the same way any other SM's edges could read General's,
 * Hands', or anyone else's published state).
 */
public final class HeadStateMachine {
    private HeadStateMachine() {
    }

    public static StateMachine<HeadState> create(final StateMachine<LegsState> legsStateMachine) {
        Map<HeadState, StateNode<HeadState>> nodes = Map.of(
            HeadState.IDLE, new HeadIdleNode(),
            HeadState.NAVIGATE, new HeadNavigateNode()
        );
        List<Edge<HeadState>> edges = List.of(
            new Edge<>(HeadState.IDLE, HeadState.NAVIGATE, ctx -> ctx.blackboard.get(legsStateMachine) == LegsState.NAVIGATE),
            new Edge<>(HeadState.NAVIGATE, HeadState.IDLE, ctx -> ctx.blackboard.get(legsStateMachine) != LegsState.NAVIGATE)
        );
        return new StateMachine<>("head", HeadState.IDLE, nodes, edges);
    }
}
