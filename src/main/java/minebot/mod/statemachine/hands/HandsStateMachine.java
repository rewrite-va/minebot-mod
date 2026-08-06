package minebot.mod.statemachine.hands;

import minebot.mod.statemachine.Edge;
import minebot.mod.statemachine.StateMachine;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.legs.LegsNavigateNode;

import java.util.List;
import java.util.Map;

/**
 * Builds the Hands StateMachine -- see HandsState's own docstring for
 * scope. IDLE<->OPEN_DOOR edges read Legs' published WAYPOINT_COORDINATES
 * directly (the same peer-reads-peer pattern Head's own edges already
 * use for Legs' state) and classify it fresh each tick via
 * WaypointClassifier -- see LegsNavigateNode/WaypointClassifier's own
 * docstrings for why this is live-checked, not baked into the plan.
 */
public final class HandsStateMachine {
    private HandsStateMachine() {
    }

    public static StateMachine<HandsState> create() {
        Map<HandsState, StateNode> nodes = Map.of(
            HandsState.IDLE, new HandsIdleNode(),
            HandsState.OPEN_DOOR, new HandsOpenDoorNode()
        );
        List<Edge<HandsState>> edges = List.of(
            new Edge<>(HandsState.IDLE, HandsState.OPEN_DOOR, ctx ->
                HandsOpenDoorNode.isClosedDoor(ctx, ctx.blackboard.get(LegsNavigateNode.WAYPOINT_COORDINATES))
            ),
            new Edge<>(HandsState.OPEN_DOOR, HandsState.IDLE, ctx ->
                !HandsOpenDoorNode.isClosedDoor(ctx, ctx.blackboard.get(LegsNavigateNode.WAYPOINT_COORDINATES))
            )
        );
        return new StateMachine<>("hands", HandsState.IDLE, nodes, edges);
    }
}
