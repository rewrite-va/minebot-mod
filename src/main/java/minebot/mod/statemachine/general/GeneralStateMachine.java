package minebot.mod.statemachine.general;

import minebot.mod.statemachine.Edge;
import minebot.mod.statemachine.StateMachine;
import minebot.mod.statemachine.StateNode;

import java.util.List;
import java.util.Map;

/**
 * Builds the General StateMachine -- see STATE_MACHINE.md's "General SM
 * last" note for why this axis currently has only one real node (IDLE):
 * ControlState.Mode already plays General's role for lack of a better
 * home, so pulling more of it out is only worth doing once real
 * higher-level behaviors (COMBAT/FLEEING/FARMING/...) exist to justify
 * their own states here, rather than just re-labeling what
 * ControlState.Mode already tracks.
 */
public final class GeneralStateMachine {
    private GeneralStateMachine() {
    }

    public static StateMachine<GeneralState> create() {
        Map<GeneralState, StateNode> nodes = Map.of(
            GeneralState.IDLE, new GeneralIdleNode()
        );
        List<Edge<GeneralState>> edges = List.of();
        return new StateMachine<>(GeneralState.IDLE, nodes, edges);
    }
}
