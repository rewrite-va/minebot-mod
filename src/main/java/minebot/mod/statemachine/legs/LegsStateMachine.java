package minebot.mod.statemachine.legs;

import minebot.mod.DeathWatcher;
import minebot.mod.InventoryController;
import minebot.mod.statemachine.Command;
import minebot.mod.statemachine.Commands;
import minebot.mod.statemachine.Edge;
import minebot.mod.statemachine.StateMachine;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import minebot.mod.statemachine.playerintention.NavIntent;
import minebot.mod.statemachine.hands.HandsEatNode;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Builds the Legs StateMachine -- see LegsState/LegsNavigateNode's own
 * docstrings for scope. NAVIGATE<->IDLE edges read NavIntent directly --
 * "there's a real NAV_TARGET AND we haven't arrived yet" -- rather than an
 * explicit allowlist of which PlayerIntention states want navigation (an
 * earlier version, NAVIGATES_FOR, kept exactly that allowlist against
 * PlayerIntention's own published state; removed once TaskController/
 * GiveTask needed NAVIGATE reachable too, and every existing NAV_TARGET
 * publisher -- PlayerIntentionFollowNode/PlayerIntentionDefendNode/
 * CombatEngagement -- was confirmed to already set NAV_TARGET back to
 * null exactly when there's nothing to walk toward, making the allowlist
 * fully redundant with NAV_TARGET's own null-ness: any future publisher
 * now drives NAVIGATE for free, with zero changes needed here, simply by
 * publishing NAV_TARGET/NAV_ARRIVED the same way everyone else already
 * does).
 *
 * GO_TO_DEATH_POSITION/PICKUP_ITEMS outrank EVERYTHING (checked first,
 * from every source state) -- reachable off DeathWatcher.DEATH_POSITION
 * being non-null, with zero dependency on PlayerIntention's own published
 * state at all (see LegsState's own docstring for the full story of why
 * this moved here from the old PlayerIntention:DEAD's downstream states).
 * A fresh death is the single most overriding event Legs can react to --
 * in practice this rarely competes with FLEE specifically, since health
 * is full immediately post-respawn.
 *
 * FLEE takes priority over NAVIGATE (checked second, from every other
 * source state) -- reachable off HandsEatNode.lowHealth() && hasFood(),
 * with zero dependency on PlayerIntention's own published state at all
 * (see LegsState's own docstring for why: Legs and Hands react to the
 * same facts independently, PlayerIntention never arbitrates this).
 * Deliberately NOT HandsEatNode.NEEDS_HEAL -- NEEDS_HEAL only publishes
 * true once Hands:EAT is already the active Hands state, but Hands:EAT's
 * own entry itself requires Legs to already be in FLEE (see
 * HandsStateMachine's own docstring) -- triggering FLEE off NEEDS_HEAL
 * would therefore have been a genuine deadlock (Legs waiting for Hands to
 * start eating, Hands waiting for Legs to be fleeing already), so Legs
 * reacts to lowHealth()/hasFood() directly instead, the one pair of facts
 * both Legs and Hands can react to independently without waiting on each
 * other.
 *
 * InventoryController.hasFood() is ANDed in (not just lowHealth()) per
 * explicit direction: "flee needs to exit when hunger is full, so it can
 * come back to combat since there is no way to heal" -- fleeing only
 * makes sense as long as eating could eventually help; once hasFood() is
 * false (nothing edible left, or hunger full enough that even inventory
 * food no longer counts as eatable -- see InventoryController.hasFood()'s
 * own docstring), there is genuinely no way to recover health right now,
 * so FLEE exits like any
 * other no-longer-needed state and Legs falls through to whatever
 * shouldNavigate already says (resuming combat positioning if a fight is
 * still active, since standing off in the open self-healing-that-can't-
 * happen was never actually useful).
 *
 * PICKUP_ITEMS is ALSO directly reachable from IDLE/NAVIGATE off a fresh
 * Command.Pickup (!pickup) -- see LegsPickupItemsNode's own docstring for
 * how it distinguishes this trigger from the death-recovery one that
 * also lands on the same state, and Command.Pickup's own docstring for
 * why the anchor position is resolved fresh on the tick thread rather
 * than captured at dispatch time. Ranked BELOW GO_TO_DEATH_POSITION/FLEE
 * in both IDLE's and NAVIGATE's own edge lists (checked after them, so
 * neither an active recovery nor a flee-from-danger gets preempted by a
 * merely-voluntary pickup) but ABOVE the ordinary ->NAVIGATE/->IDLE
 * fallback edges, per explicit direction. Deliberately NOT reachable
 * from FLEE -- a !pickup arriving while fleeing is simply not acted on
 * that tick (Commands are drained fresh every tick -- see CommandBus's
 * own docstring -- so an un-matched one is silently dropped, not queued);
 * per explicit direction, the player retyping !pickup once fleeing ends
 * is an acceptable cost for keeping FLEE's own edges free of a
 * low-priority voluntary interrupt.
 */
public final class LegsStateMachine {
    private LegsStateMachine() {
    }

    /** `navigateNode` is constructed by the caller (not internally) so it can also hold onto the reference directly -- e.g. MinebotMod wires PathVisualizer to navigateNode.pathTracker() for debug rendering. */
    public static StateMachine<LegsState> create(final LegsNavigateNode navigateNode) {
        LegsGoToDeathPositionNode goToDeathPositionNode = new LegsGoToDeathPositionNode();
        LegsPickupItemsNode pickupItemsNode = new LegsPickupItemsNode();

        Map<LegsState, StateNode<LegsState>> nodes = Map.of(
            LegsState.IDLE, new LegsIdleNode(),
            LegsState.NAVIGATE, navigateNode,
            LegsState.FLEE, new LegsFleeNode(),
            LegsState.GO_TO_DEATH_POSITION, goToDeathPositionNode,
            LegsState.PICKUP_ITEMS, pickupItemsNode
        );

        Predicate<TickContext> shouldRecover = ctx -> ctx.blackboard.get(DeathWatcher.DEATH_POSITION) != null;
        Predicate<TickContext> shouldNavigate = ctx ->
            ctx.blackboard.get(NavIntent.NAV_TARGET) != null && !Boolean.TRUE.equals(ctx.blackboard.get(NavIntent.NAV_ARRIVED));
        Predicate<TickContext> shouldFlee = ctx -> HandsEatNode.lowHealth(ctx) && InventoryController.hasFood(ctx.player);
        Predicate<TickContext> isPickupCommand = ctx -> Commands.has(ctx.commands, Command.Pickup.class);

        List<Edge<LegsState>> edges = List.of(
            // GO_TO_DEATH_POSITION/PICKUP_ITEMS outrank everything -- see
            // this class's own docstring. Dying again mid-recovery (e.g.
            // falling into the same lava that killed the bot the first
            // time) needs no special-cased self-loop/re-entry the way
            // KILL's own re-target does: DeathWatcher republishes a fresh
            // DEATH_POSITION on every new death, and both recovery nodes
            // already read it fresh every onTick (not cached once on
            // entry), so the SAME active node just starts walking toward
            // the new position next tick with no transition needed at
            // all -- PathTracker.maybeReplan already handles a moving
            // target the normal way.
            new Edge<>(LegsState.IDLE, LegsState.GO_TO_DEATH_POSITION, shouldRecover),
            new Edge<>(LegsState.NAVIGATE, LegsState.GO_TO_DEATH_POSITION, shouldRecover),
            new Edge<>(LegsState.FLEE, LegsState.GO_TO_DEATH_POSITION, shouldRecover),
            new Edge<>(LegsState.GO_TO_DEATH_POSITION, LegsState.PICKUP_ITEMS, ctx -> goToDeathPositionNode.isFinished()),
            new Edge<>(LegsState.PICKUP_ITEMS, LegsState.FLEE, ctx -> pickupItemsNode.isFinished() && shouldFlee.test(ctx)),
            new Edge<>(LegsState.PICKUP_ITEMS, LegsState.NAVIGATE, ctx -> pickupItemsNode.isFinished() && !shouldFlee.test(ctx) && shouldNavigate.test(ctx)),
            new Edge<>(LegsState.PICKUP_ITEMS, LegsState.IDLE, ctx -> pickupItemsNode.isFinished() && !shouldFlee.test(ctx) && !shouldNavigate.test(ctx)),

            new Edge<>(LegsState.IDLE, LegsState.FLEE, shouldFlee),
            new Edge<>(LegsState.NAVIGATE, LegsState.FLEE, shouldFlee),
            new Edge<>(LegsState.FLEE, LegsState.IDLE, ctx -> !shouldFlee.test(ctx) && !shouldNavigate.test(ctx)),
            new Edge<>(LegsState.FLEE, LegsState.NAVIGATE, ctx -> !shouldFlee.test(ctx) && shouldNavigate.test(ctx)),

            // !pickup -- see this class's own docstring for why this is
            // ranked here (below recovery/flee, above the plain
            // navigate/idle fallback) and deliberately not reachable from
            // FLEE.
            new Edge<>(LegsState.IDLE, LegsState.PICKUP_ITEMS, isPickupCommand),
            new Edge<>(LegsState.NAVIGATE, LegsState.PICKUP_ITEMS, isPickupCommand),

            new Edge<>(LegsState.IDLE, LegsState.NAVIGATE, shouldNavigate),
            new Edge<>(LegsState.NAVIGATE, LegsState.IDLE, ctx -> !shouldNavigate.test(ctx))
        );
        return new StateMachine<>("legs", LegsState.IDLE, nodes, edges);
    }
}
