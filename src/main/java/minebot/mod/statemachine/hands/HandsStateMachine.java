package minebot.mod.statemachine.hands;

import minebot.mod.InventoryController;
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
 * eats decide"). lowHealth()/hasFood()/legsFleeing are checked
 * CONTINUOUSLY with no debounce -- these mean "no longer needed" or
 * "structurally impossible right now", not proximity noise, so eating
 * should stop the instant any of them fails, same as before.
 *
 * farEnoughFromThreat, by contrast, is DEBOUNCED once EAT has actually
 * started (see canStartEating/canKeepEating split below) -- confirmed
 * live that checking it continuously with zero debounce made EAT
 * flicker in and out constantly while a chasing zombie kept crossing
 * back within EAT_SAFE_DISTANCE, briefly interrupting an eat already in
 * progress over and over instead of ever completing a single bite. Once
 * eating has started, farEnoughFromThreat failing does NOT immediately
 * exit -- it only does once handsEatNode.isBiteStillProtected() (the
 * SAME HandsEatNode instance wired into `nodes` below, called directly
 * rather than routed through the Blackboard -- see its own docstring for
 * why: this is internal bookkeeping for one node's own lifecycle, not a
 * cross-SM fact) reports the current bite's real vanilla eat duration
 * (Item.getUseDuration() for that stack) has elapsed, protecting exactly
 * one full bite from being interrupted by a threat that merely brushes
 * past EAT_SAFE_DISTANCE and backs off again, per explicit direction
 * ("lets add some debouncing algorithm... if we can at least eat one
 * item and then flee again to a safe distance"). ENTRY still requires
 * farEnoughFromThreat with no debounce (canStartEating) -- there's
 * nothing to protect yet on a tick EAT isn't even active. legsFleeing on
 * its own (not just distance) is
 * still required for entry too, since FLEE is a continuous, ongoing
 * repositioning now (see LegsFleeNode's own docstring for why there's no
 * "arrived" concept anymore) -- Legs being in FLEE is what indicates
 * "currently trying to get to safety" at all; checking distance alone
 * would let Hands eat even while Legs was mid-navigate toward a fight
 * with a target that just happened to be far away.
 *
 * MELEE_ATTACK, DRAW_BOW and DRAW_CROSSBOW are mutually exclusive, all
 * gated on (PlayerIntention==KILL || PlayerIntention==DEFEND) &&
 * !legsFleeing (either real fighting state -- see PlayerIntentionState's
 * own docstring for why all three drive the same shared
 * CombatEngagement-published facts; see LegsState's own docstring for
 * what FLEE means) -- per explicit direction, Hands stops attacking
 * entirely the instant Legs is fleeing, regardless of range/line-of-sight
 * to the target: swinging or drawing a bow/crossbow while also trying to
 * retreat works against the retreat itself (DRAW_BOW/DRAW_CROSSBOW in
 * particular need a steady aim, directly opposed to actively
 * repositioning), and the whole point of FLEE is prioritizing survival
 * over continuing the fight. Checked with no debounce, same reasoning as
 * canEatAtAll's own core conditions -- this isn't proximity noise to
 * protect against, it's "should combat be happening at all right now",
 * which should stop the instant FLEE starts. legsFleeing folded into
 * inCombat itself (all three states' own entry conditions already gate
 * through it) rather than added as a second separate check on each.
 * split by CombatEngagement.SELECTED_WEAPON's own kind: MELEE_ATTACK
 * additionally requires being within real melee reach of the ACTUAL
 * TARGET (Attributes.ENTITY_INTERACTION_RANGE, the same live attribute
 * CombatEngagement itself uses -- checked directly here, NOT via
 * NavIntent.NAV_ARRIVED, since that flag now also means "reached the
 * current KITING RETREAT POINT" while backing off between swings -- see
 * CombatEngagement's own docstring for the melee-kiting cycle; trusting
 * NAV_ARRIVED here would have let Hands think it was "in range" the
 * moment the bot finished retreating AWAY from the target, which is
 * exactly backwards). DRAW_BOW/DRAW_CROSSBOW do NOT check range at all --
 * each gates on real line-of-sight internally instead (see
 * HandsDrawBowNode/HandsDrawCrossbowNode's own docstrings for why: a
 * ranged weapon's whole point is acting from range, and PlayerIntention
 * already publishes BOW_RANGE as the distance Legs holds for a ranged
 * fight -- same distance for either weapon, see CombatEngagement's own
 * docstring for why bow/crossbow share one reach category -- so by the
 * time either state would even be reachable the bot is already roughly
 * at the right distance; what range-gating alone can't tell is whether a
 * wall is in the way).
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
        // Held by name (not just inlined into `nodes` below) so the
        // canKeepEating predicate can call isBiteStillProtected() on this
        // SAME instance -- see this class's own docstring for why that's
        // an internal method call, not a Blackboard read.
        HandsEatNode handsEatNode = new HandsEatNode();
        Map<HandsState, StateNode<HandsState>> nodes = Map.of(
            HandsState.IDLE, new HandsIdleNode(),
            HandsState.OPEN_DOOR, new HandsOpenDoorNode(),
            HandsState.EAT, handsEatNode,
            HandsState.MELEE_ATTACK, new HandsMeleeAttackNode(),
            HandsState.DRAW_BOW, new HandsDrawBowNode(),
            HandsState.DRAW_CROSSBOW, new HandsDrawCrossbowNode()
        );

        Predicate<TickContext> legsFleeing = ctx -> ctx.blackboard.get(legsStateMachine) == LegsState.FLEE;
        Predicate<TickContext> farEnoughFromThreat = ctx -> {
            Integer targetEntityId = ctx.blackboard.get(CombatEngagement.TARGET_ENTITY_ID);
            Entity target = targetEntityId != null ? ctx.level.getEntity(targetEntityId) : null;
            // No live target at all -- nothing to be too close to.
            return target == null || ctx.player.position().distanceTo(target.position()) > EAT_SAFE_DISTANCE;
        };
        // Non-debounced core -- see this class's own docstring for why
        // these three always exit EAT immediately, no protection window.
        Predicate<TickContext> canEatAtAll = ctx -> HandsEatNode.lowHealth(ctx) && InventoryController.hasFood(ctx.player) && legsFleeing.test(ctx);
        // Entry gate -- nothing to protect yet on a tick EAT isn't active.
        Predicate<TickContext> canStartEating = ctx -> canEatAtAll.test(ctx) && farEnoughFromThreat.test(ctx);
        // Once active, farEnoughFromThreat alone is debounced -- see this
        // class's own docstring for why (protects one full real bite,
        // using HandsEatNode's own per-item duration, from a threat that
        // merely brushes past EAT_SAFE_DISTANCE and backs off again).
        Predicate<TickContext> canKeepEating = ctx -> canEatAtAll.test(ctx) && (farEnoughFromThreat.test(ctx) || handsEatNode.isBiteStillProtected());
        Predicate<TickContext> closedDoorAhead = ctx -> HandsOpenDoorNode.isClosedDoor(ctx, ctx.blackboard.get(LegsNavigateNode.WAYPOINT_COORDINATES));
        Predicate<TickContext> inCombat = ctx -> {
            PlayerIntentionState state = ctx.blackboard.get(playerIntentionStateMachine);
            return (state == PlayerIntentionState.KILL || state == PlayerIntentionState.DEFEND) && !legsFleeing.test(ctx);
        };
        Predicate<TickContext> usingBow = ctx -> {
            InventoryController.Choice weapon = ctx.blackboard.get(CombatEngagement.SELECTED_WEAPON);
            return weapon != null && weapon.kind() == InventoryController.Kind.BOW;
        };
        Predicate<TickContext> usingCrossbow = ctx -> {
            InventoryController.Choice weapon = ctx.blackboard.get(CombatEngagement.SELECTED_WEAPON);
            return weapon != null && weapon.kind() == InventoryController.Kind.CROSSBOW;
        };
        Predicate<TickContext> inMeleeAttackRange = ctx -> {
            if (!inCombat.test(ctx) || usingBow.test(ctx) || usingCrossbow.test(ctx)) {
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
        Predicate<TickContext> shouldDrawCrossbow = ctx -> inCombat.test(ctx) && usingCrossbow.test(ctx);

        List<Edge<HandsState>> edges = List.of(
            new Edge<>(HandsState.IDLE, HandsState.EAT, canStartEating),
            new Edge<>(HandsState.OPEN_DOOR, HandsState.EAT, canStartEating),
            new Edge<>(HandsState.MELEE_ATTACK, HandsState.EAT, canStartEating),
            new Edge<>(HandsState.DRAW_BOW, HandsState.EAT, canStartEating),
            new Edge<>(HandsState.DRAW_CROSSBOW, HandsState.EAT, canStartEating),
            new Edge<>(HandsState.EAT, HandsState.IDLE, ctx -> !canKeepEating.test(ctx)),
            new Edge<>(HandsState.IDLE, HandsState.OPEN_DOOR, closedDoorAhead),
            new Edge<>(HandsState.OPEN_DOOR, HandsState.IDLE, ctx -> !closedDoorAhead.test(ctx)),
            new Edge<>(HandsState.IDLE, HandsState.MELEE_ATTACK, inMeleeAttackRange),
            new Edge<>(HandsState.MELEE_ATTACK, HandsState.IDLE, ctx -> !inMeleeAttackRange.test(ctx)),
            new Edge<>(HandsState.IDLE, HandsState.DRAW_BOW, shouldDrawBow),
            new Edge<>(HandsState.DRAW_BOW, HandsState.IDLE, ctx -> !shouldDrawBow.test(ctx)),
            new Edge<>(HandsState.IDLE, HandsState.DRAW_CROSSBOW, shouldDrawCrossbow),
            new Edge<>(HandsState.DRAW_CROSSBOW, HandsState.IDLE, ctx -> !shouldDrawCrossbow.test(ctx)),
            // A weapon swap mid-fight (e.g. a melee weapon breaking and a
            // bow/crossbow becoming the best choice, or vice versa, or a
            // bow<->crossbow swap) -- every pair of these three states
            // can transition directly into each other, not just through
            // IDLE, so a swap doesn't need to wait a tick idle first.
            new Edge<>(HandsState.MELEE_ATTACK, HandsState.DRAW_BOW, shouldDrawBow),
            new Edge<>(HandsState.DRAW_BOW, HandsState.MELEE_ATTACK, inMeleeAttackRange),
            new Edge<>(HandsState.MELEE_ATTACK, HandsState.DRAW_CROSSBOW, shouldDrawCrossbow),
            new Edge<>(HandsState.DRAW_CROSSBOW, HandsState.MELEE_ATTACK, inMeleeAttackRange),
            new Edge<>(HandsState.DRAW_BOW, HandsState.DRAW_CROSSBOW, shouldDrawCrossbow),
            new Edge<>(HandsState.DRAW_CROSSBOW, HandsState.DRAW_BOW, shouldDrawBow)
        );
        return new StateMachine<>("hands", HandsState.IDLE, nodes, edges);
    }
}
