package minebot.mod.statemachine.hands;

import minebot.mod.WeaponSelector;
import minebot.mod.statemachine.Edge;
import minebot.mod.statemachine.StateMachine;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import minebot.mod.statemachine.playerintention.CombatEngagement;
import minebot.mod.statemachine.playerintention.PlayerIntentionState;
import minebot.mod.statemachine.legs.LegsNavigateNode;
import minebot.mod.statemachine.legs.LegsState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;

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
 * EAT takes priority over OPEN_DOOR/MELEE_ATTACK/DRAW_BOW -- reachable
 * from any of them whenever ALL of: health is low, something is actually
 * eatable right now, Legs is currently in FLEE, AND the live combat
 * target (if any) is farther than EAT_SAFE_DISTANCE away (HandsEatNode.
 * lowHealth()/hasFood(), plus the legsFleeing/farEnoughFromThreat checks
 * below -- see HandsEatNode's own docstring for why this moved here from
 * a deleted PlayerIntention:SELF_HEAL state, and for why THIS node
 * decides its own safe-to-eat distance rather than relying on Legs to
 * report an "arrived" signal: "we just need 2 conditions to eat,
 * legs:flee and distance to hostiles more than X... this is something
 * eats decide"). ALL FOUR conditions are checked CONTINUOUSLY, not just
 * on entry -- eating stops immediately (falls back to IDLE, no RESUME
 * needed -- see HandsState's own docstring for why) the instant any one
 * of them stops holding, including a threat closing back within
 * EAT_SAFE_DISTANCE mid-eat: eating itself further slows movement
 * (confirmed live/via decompiled LocalPlayer.isSlowDueToUsingItem()), so
 * continuing to eat once a threat has genuinely closed back in would be
 * actively dangerous, not just unhelpful. legsFleeing on its own (not
 * just distance) is required too since FLEE is a continuous, ongoing
 * repositioning now (see LegsFleeNode's own docstring for why there's no
 * "arrived" concept anymore) -- Legs being in FLEE is what indicates
 * "currently trying to get to safety" at all; checking distance alone
 * would let Hands eat even while Legs was mid-navigate toward a fight
 * with a target that just happened to be far away.
 *
 * MELEE_ATTACK and DRAW_BOW are mutually exclusive, both gated on
 * PlayerIntention==KILL || PlayerIntention==DEFEND (either real fighting state -- see
 * PlayerIntentionState's own docstring for why both drive the same shared
 * CombatEngagement-published facts), split by CombatEngagement.
 * SELECTED_WEAPON's own kind: MELEE_ATTACK additionally requires being
 * within real melee reach of the ACTUAL TARGET (Attributes.
 * ENTITY_INTERACTION_RANGE, the same live attribute CombatEngagement
 * itself uses -- checked directly here, NOT via NavIntent.
 * NAV_ARRIVED, since that flag now also means "reached the current
 * KITING RETREAT POINT" while backing off between swings -- see
 * CombatEngagement's own docstring for the melee-kiting cycle; trusting
 * NAV_ARRIVED here would have let Hands think it was "in range" the
 * moment the bot finished retreating AWAY from the target, which is
 * exactly backwards), DRAW_BOW does NOT check range at all -- it gates on
 * real line-of-sight internally instead (see HandsDrawBowNode's own
 * docstring for why: a bow's whole point is acting from range, and
 * PlayerIntention already publishes BOW_RANGE as the distance Legs holds for a
 * bow fight, so by the time DRAW_BOW would even be reachable the bot is
 * already roughly at the right distance; what range-gating alone can't
 * tell is whether a wall is in the way).
 */
public final class HandsStateMachine {
    private HandsStateMachine() {
    }

    // How far the live combat target must be for eating to be considered
    // safe -- see this class's own docstring: this node (not Legs) owns
    // this distance, per explicit direction. Deliberately smaller than
    // LegsFleeNode's own FLEE_DISTANCE -- Legs aims to get well clear, but
    // eating can reasonably start once merely out of easy striking
    // distance, not only once maximally far away.
    private static final double EAT_SAFE_DISTANCE = 8.0;

    public static StateMachine<HandsState> create(final StateMachine<PlayerIntentionState> playerIntentionStateMachine, final StateMachine<LegsState> legsStateMachine) {
        Map<HandsState, StateNode<HandsState>> nodes = Map.of(
            HandsState.IDLE, new HandsIdleNode(),
            HandsState.OPEN_DOOR, new HandsOpenDoorNode(),
            HandsState.EAT, new HandsEatNode(),
            HandsState.MELEE_ATTACK, new HandsMeleeAttackNode(),
            HandsState.DRAW_BOW, new HandsDrawBowNode()
        );

        Predicate<TickContext> legsFleeing = ctx -> ctx.blackboard.get(legsStateMachine) == LegsState.FLEE;
        Predicate<TickContext> farEnoughFromThreat = ctx -> {
            Integer targetEntityId = ctx.blackboard.get(CombatEngagement.TARGET_ENTITY_ID);
            Entity target = targetEntityId != null ? ctx.level.getEntity(targetEntityId) : null;
            // No live target at all -- nothing to be too close to.
            return target == null || ctx.player.position().distanceTo(target.position()) > EAT_SAFE_DISTANCE;
        };
        Predicate<TickContext> needsHeal = ctx -> HandsEatNode.lowHealth(ctx) && HandsEatNode.hasFood(ctx)
            && legsFleeing.test(ctx) && farEnoughFromThreat.test(ctx);
        Predicate<TickContext> closedDoorAhead = ctx -> HandsOpenDoorNode.isClosedDoor(ctx, ctx.blackboard.get(LegsNavigateNode.WAYPOINT_COORDINATES));
        Predicate<TickContext> inCombat = ctx -> {
            PlayerIntentionState state = ctx.blackboard.get(playerIntentionStateMachine);
            return state == PlayerIntentionState.KILL || state == PlayerIntentionState.DEFEND;
        };
        Predicate<TickContext> usingBow = ctx -> {
            WeaponSelector.Choice weapon = ctx.blackboard.get(CombatEngagement.SELECTED_WEAPON);
            return weapon != null && weapon.kind() == WeaponSelector.Kind.BOW;
        };
        Predicate<TickContext> inMeleeAttackRange = ctx -> {
            if (!inCombat.test(ctx) || usingBow.test(ctx)) {
                return false;
            }
            Integer targetEntityId = ctx.blackboard.get(CombatEngagement.TARGET_ENTITY_ID);
            if (targetEntityId == null) {
                return false;
            }
            Entity target = ctx.level.getEntity(targetEntityId);
            if (target == null) {
                return false;
            }
            double meleeRange = ctx.player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
            return ctx.player.position().distanceTo(target.position()) <= meleeRange;
        };
        Predicate<TickContext> shouldDrawBow = ctx -> inCombat.test(ctx) && usingBow.test(ctx);

        List<Edge<HandsState>> edges = List.of(
            new Edge<>(HandsState.IDLE, HandsState.EAT, needsHeal),
            new Edge<>(HandsState.OPEN_DOOR, HandsState.EAT, needsHeal),
            new Edge<>(HandsState.MELEE_ATTACK, HandsState.EAT, needsHeal),
            new Edge<>(HandsState.DRAW_BOW, HandsState.EAT, needsHeal),
            new Edge<>(HandsState.EAT, HandsState.IDLE, ctx -> !needsHeal.test(ctx)),
            new Edge<>(HandsState.IDLE, HandsState.OPEN_DOOR, closedDoorAhead),
            new Edge<>(HandsState.OPEN_DOOR, HandsState.IDLE, ctx -> !closedDoorAhead.test(ctx)),
            new Edge<>(HandsState.IDLE, HandsState.MELEE_ATTACK, inMeleeAttackRange),
            new Edge<>(HandsState.MELEE_ATTACK, HandsState.IDLE, ctx -> !inMeleeAttackRange.test(ctx)),
            new Edge<>(HandsState.IDLE, HandsState.DRAW_BOW, shouldDrawBow),
            new Edge<>(HandsState.DRAW_BOW, HandsState.IDLE, ctx -> !shouldDrawBow.test(ctx)),
            // A weapon swap mid-fight (e.g. a melee weapon breaking and a
            // bow becoming the best choice, or vice versa) -- these two
            // states can transition directly into each other, not just
            // through IDLE, so a swap doesn't need to wait a tick idle
            // first.
            new Edge<>(HandsState.MELEE_ATTACK, HandsState.DRAW_BOW, shouldDrawBow),
            new Edge<>(HandsState.DRAW_BOW, HandsState.MELEE_ATTACK, inMeleeAttackRange)
        );
        return new StateMachine<>("hands", HandsState.IDLE, nodes, edges);
    }
}
