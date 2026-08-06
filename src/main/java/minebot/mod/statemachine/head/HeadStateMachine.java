package minebot.mod.statemachine.head;

import minebot.mod.statemachine.Edge;
import minebot.mod.statemachine.StateMachine;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import minebot.mod.statemachine.playerintention.CombatEngagement;
import minebot.mod.statemachine.playerintention.PlayerIntentionState;
import minebot.mod.statemachine.legs.LegsState;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Builds the Head StateMachine -- see HeadState's own docstring for
 * scope. Its NAVIGATE<->IDLE edges read Legs' published state off the
 * Blackboard (ctx.blackboard.get(legsStateMachine)) rather than a
 * Command, unlike PlayerIntention/Legs' own edges -- this is a real example of
 * one peer SM's edges reacting to another peer's state directly (see
 * STATE_MACHINE.md's "no hierarchy" section: Head isn't "commanded" by
 * Legs, it's independently deciding to mirror Legs' navigating state
 * because that happens to be the right condition for its own NAVIGATE
 * state, the same way any other SM's edges could read PlayerIntention's,
 * Hands', or anyone else's published state).
 *
 * AIM_AT_TARGET's own entry condition checks CombatEngagement.
 * TARGET_ENTITY_ID directly (a LIVE THREAT actually exists), not just
 * "PlayerIntention is in a fighting-capable state" -- confirmed live this
 * distinction matters: PlayerIntention==DEFEND is a STANDING mode (active
 * continuously, with or without an actual threat nearby -- see
 * PlayerIntentionDefendNode's own docstring), so checking PlayerIntention==DEFEND alone
 * put Head into AIM_AT_TARGET the entire time DEFEND was active, even
 * with nothing to aim at; HeadAimAtTargetNode's own "nothing real to
 * face right now -- leave yaw/pitch as they are" bail-out then left the
 * bot's look direction FROZEN for as long as DEFEND had no threat,
 * reported live as "defend follows the target, but does not look at
 * it". PlayerIntention==KILL never had this problem (it's a one-shot fight,
 * TARGET_ENTITY_ID is set for its whole active lifetime), but checking
 * the real published fact instead of the PlayerIntention state name is correct
 * for both and doesn't special-case DEFEND.
 *
 * AIM_AT_TARGET's own entry edges are listed FIRST from every source
 * state (other than FLEE -- see below) -- combat aim has to win over
 * NAVIGATE on any tick both conditions are true (a live threat AND
 * Legs:NAVIGATE at once, e.g. still closing distance to melee range),
 * matching the old shared pipeline's own "combat aim always overrides
 * waypoint aim" rule (see HeadState's own docstring).
 *
 * FLEE mirrors Legs:FLEE directly (ctx.blackboard.get(legsStateMachine)
 * == LegsState.FLEE), the same "read a peer's published state" shape
 * NAVIGATE already uses for Legs:NAVIGATE -- and is checked BEFORE
 * hasLiveThreat from every source state, since a live target always
 * exists while fleeing (that's what triggered FLEE in the first place --
 * see LegsFleeNode's own docstring) and AIM_AT_TARGET would otherwise
 * always win. This is deliberately NOT expressed as "AIM_AT_TARGET, but
 * overridden sometimes" -- FLEE is its own real state with its own edges,
 * per explicit direction ("there is no priority, is just another state in
 * the SM"). Maps to the SAME HeadNavigateNode instance NAVIGATE uses
 * (not a separate node) -- LegsFleeNode publishes through the identical
 * WAYPOINT_COORDINATES/NavIntent.NAV_TARGET channel LegsNavigateNode
 * does (see its own docstring for why), so HeadNavigateNode's existing
 * "look at whatever Legs is walking toward" logic is already exactly the
 * right behavior here with zero new aiming code needed -- per explicit
 * direction: fleeing needs the bot looking toward its travel direction,
 * not at the threat, since backing away/strafing while facing a target is
 * real-vanilla slower than sprinting forward.
 */
public final class HeadStateMachine {
    private HeadStateMachine() {
    }

    public static StateMachine<HeadState> create(final StateMachine<LegsState> legsStateMachine, final StateMachine<PlayerIntentionState> playerIntentionStateMachine) {
        HeadNavigateNode navigateNode = new HeadNavigateNode();
        Map<HeadState, StateNode<HeadState>> nodes = Map.of(
            HeadState.IDLE, new HeadIdleNode(),
            HeadState.NAVIGATE, navigateNode,
            HeadState.AIM_AT_TARGET, new HeadAimAtTargetNode(),
            HeadState.FLEE, navigateNode
        );

        Predicate<TickContext> legsFleeing = ctx -> ctx.blackboard.get(legsStateMachine) == LegsState.FLEE;
        Predicate<TickContext> hasLiveThreat = ctx -> ctx.blackboard.get(CombatEngagement.TARGET_ENTITY_ID) != null;
        Predicate<TickContext> legsNavigating = ctx -> ctx.blackboard.get(legsStateMachine) == LegsState.NAVIGATE;

        List<Edge<HeadState>> edges = List.of(
            // FLEE checked first from every other state -- see this
            // class's own docstring for why this must win over
            // AIM_AT_TARGET despite a live threat always existing while
            // fleeing.
            new Edge<>(HeadState.IDLE, HeadState.FLEE, legsFleeing),
            new Edge<>(HeadState.NAVIGATE, HeadState.FLEE, legsFleeing),
            new Edge<>(HeadState.AIM_AT_TARGET, HeadState.FLEE, legsFleeing),

            new Edge<>(HeadState.IDLE, HeadState.AIM_AT_TARGET, hasLiveThreat),
            new Edge<>(HeadState.NAVIGATE, HeadState.AIM_AT_TARGET, hasLiveThreat),
            new Edge<>(HeadState.FLEE, HeadState.AIM_AT_TARGET, ctx -> !legsFleeing.test(ctx) && hasLiveThreat.test(ctx)),
            new Edge<>(HeadState.AIM_AT_TARGET, HeadState.IDLE, ctx -> !hasLiveThreat.test(ctx) && !legsNavigating.test(ctx)),
            new Edge<>(HeadState.AIM_AT_TARGET, HeadState.NAVIGATE, ctx -> !hasLiveThreat.test(ctx) && legsNavigating.test(ctx)),

            new Edge<>(HeadState.IDLE, HeadState.NAVIGATE, legsNavigating),
            new Edge<>(HeadState.NAVIGATE, HeadState.IDLE, ctx -> !legsNavigating.test(ctx)),
            new Edge<>(HeadState.FLEE, HeadState.NAVIGATE, ctx -> !legsFleeing.test(ctx) && !hasLiveThreat.test(ctx) && legsNavigating.test(ctx)),
            new Edge<>(HeadState.FLEE, HeadState.IDLE, ctx -> !legsFleeing.test(ctx) && !hasLiveThreat.test(ctx) && !legsNavigating.test(ctx))
        );
        return new StateMachine<>("head", HeadState.IDLE, nodes, edges);
    }
}
