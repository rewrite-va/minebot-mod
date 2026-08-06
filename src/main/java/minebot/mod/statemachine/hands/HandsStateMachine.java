package minebot.mod.statemachine.hands;

import minebot.mod.statemachine.Edge;
import minebot.mod.statemachine.StateMachine;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import minebot.mod.statemachine.general.GeneralSelfHealNode;
import minebot.mod.statemachine.legs.LegsNavigateNode;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Builds the Hands StateMachine -- see HandsState's own docstring for
 * scope. IDLE<->OPEN_DOOR edges read Legs' published WAYPOINT_COORDINATES
 * directly (the same peer-reads-peer pattern Head's own edges already
 * use for Legs' state) and classify it fresh each tick via
 * WaypointClassifier -- see LegsNavigateNode/WaypointClassifier's own
 * docstrings for why this is live-checked, not baked into the plan.
 *
 * EAT takes priority over OPEN_DOOR -- reachable from both IDLE and
 * OPEN_DOOR whenever General:SELF_HEAL's NEEDS_HEAL is true, interrupting
 * a door-opening attempt if a heal becomes needed mid-approach. Exits
 * directly to IDLE (no RESUME needed -- see HandsState's own docstring
 * for why).
 */
public final class HandsStateMachine {
    private HandsStateMachine() {
    }

    public static StateMachine<HandsState> create() {
        Map<HandsState, StateNode<HandsState>> nodes = Map.of(
            HandsState.IDLE, new HandsIdleNode(),
            HandsState.OPEN_DOOR, new HandsOpenDoorNode(),
            HandsState.EAT, new HandsEatNode()
        );

        Predicate<TickContext> needsHeal = ctx -> Boolean.TRUE.equals(ctx.blackboard.get(GeneralSelfHealNode.NEEDS_HEAL));
        Predicate<TickContext> closedDoorAhead = ctx -> HandsOpenDoorNode.isClosedDoor(ctx, ctx.blackboard.get(LegsNavigateNode.WAYPOINT_COORDINATES));

        List<Edge<HandsState>> edges = List.of(
            new Edge<>(HandsState.IDLE, HandsState.EAT, needsHeal),
            new Edge<>(HandsState.OPEN_DOOR, HandsState.EAT, needsHeal),
            new Edge<>(HandsState.EAT, HandsState.IDLE, ctx -> !needsHeal.test(ctx)),
            new Edge<>(HandsState.IDLE, HandsState.OPEN_DOOR, closedDoorAhead),
            new Edge<>(HandsState.OPEN_DOOR, HandsState.IDLE, ctx -> !closedDoorAhead.test(ctx))
        );
        return new StateMachine<>("hands", HandsState.IDLE, nodes, edges);
    }
}
