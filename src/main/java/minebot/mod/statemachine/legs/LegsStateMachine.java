package minebot.mod.statemachine.legs;

import minebot.mod.DeathWatcher;
import minebot.mod.EntityFinder;
import minebot.mod.InventoryController;
import minebot.mod.statemachine.Command;
import minebot.mod.statemachine.Commands;
import minebot.mod.statemachine.Edge;
import minebot.mod.statemachine.StateMachine;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import minebot.mod.statemachine.playerintention.CombatEngagement;
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
 *
 * PICKUP_ITEMS is ALSO reachable from IDLE/NAVIGATE the instant a fight
 * ends with no hostile left in sight -- per explicit direction ("after a
 * fight and only when no visible monsters, switch to legs:pickup_items,
 * so the bot pickup drops and arrows"). Detected as an EDGE (fighting
 * last tick, not fighting this tick), not a level condition, via
 * CombatEngagement.FIGHT_JUST_ENDED -- reacting to "no fight right now" as
 * a standing condition would re-trigger a pickup sweep on every single
 * peaceful IDLE tick forever (nothing keeps it from re-arming the moment
 * PICKUP_ITEMS finishes and falls back to IDLE with the same
 * no-fight/no-hostile facts still true), turning this into a permanent
 * item-vacuum rather than a one-shot post-fight cleanup.
 * FIGHT_JUST_ENDED itself is computed centrally by CombatEngagement.
 * tickEdgeDetection (ticked unconditionally every real tick from
 * MinebotMod, regardless of Legs' own current state) rather than as a
 * Predicate closure living here -- see its own docstring for the real,
 * confirmed gap that fixed (Legs missing the transition entirely
 * whenever a fight starts/ends while Legs itself is in FLEE/
 * GO_TO_DEATH_POSITION/PICKUP_ITEMS, since edges are only ever evaluated
 * FROM Legs' own current state). True for BOTH !kill (KILL) and !defend
 * (DEFEND) fights alike, since both publish TARGET_ENTITY_ID through the
 * same CombatEngagement.publish/clear (or DEFEND's own equivalent inline
 * null-out). "No visible monsters" reuses
 * EntityFinder.findNearestVisibleHostile (not the plain
 * findNearestHostile KillTask's bare !kill fallback uses)
 * -- same real line-of-sight semantics PlayerIntentionDefendNode already
 * relies on for "is a threat actually engageable", so a hostile merely
 * heard/behind a wall doesn't block the sweep from starting. Ranked
 * BELOW GO_TO_DEATH_POSITION/FLEE (checked after them, same as the
 * !pickup edges) but does NOT need to rank against the !pickup edges
 * themselves -- both land on the exact same PICKUP_ITEMS state, so
 * whichever of the two edges happens to be declared/evaluated first on a
 * tick where both are true is immaterial. Deliberately NOT reachable
 * from FLEE, matching !pickup's own edges -- a fight ending WHILE still
 * fleeing (low health) shouldn't detour into item pickup ahead of
 * whatever FLEE's own exit edges would otherwise send Legs to.
 */
public final class LegsStateMachine {
    // Matches CombatEngagement.SEARCH_RADIUS -- the same "how far could a
    // fight plausibly have ranged" scope that governed the fight this is
    // reacting to.
    private static final double VISIBLE_HOSTILE_RADIUS = CombatEngagement.SEARCH_RADIUS;

    private LegsStateMachine() {
    }

    /** `navigateNode` is constructed by the caller (not internally) so it can also hold onto the reference directly -- e.g. MinebotMod wires PathVisualizer to navigateNode.pathTracker() for debug rendering. */
    public static StateMachine<LegsState> create(final LegsNavigateNode navigateNode) {
        LegsGoToDeathPositionNode goToDeathPositionNode = new LegsGoToDeathPositionNode();
        LegsPickupItemsNode pickupItemsNode = new LegsPickupItemsNode();
        LegsGotoNode gotoNode = new LegsGotoNode();

        Map<LegsState, StateNode<LegsState>> nodes = Map.of(
            LegsState.IDLE, new LegsIdleNode(),
            LegsState.NAVIGATE, navigateNode,
            LegsState.FLEE, new LegsFleeNode(),
            LegsState.GO_TO_DEATH_POSITION, goToDeathPositionNode,
            LegsState.PICKUP_ITEMS, pickupItemsNode,
            LegsState.GOTO, gotoNode
        );

        Predicate<TickContext> shouldRecover = ctx -> ctx.blackboard.get(DeathWatcher.DEATH_POSITION) != null;
        Predicate<TickContext> shouldNavigate = ctx ->
            ctx.blackboard.get(NavIntent.NAV_TARGET) != null && !Boolean.TRUE.equals(ctx.blackboard.get(NavIntent.NAV_ARRIVED));
        Predicate<TickContext> shouldFlee = ctx -> HandsEatNode.lowHealth(ctx) && InventoryController.hasFood(ctx.player);
        Predicate<TickContext> isPickupCommand = ctx -> Commands.has(ctx.commands, Command.Pickup.class);
        Predicate<TickContext> isGotoCommand = ctx -> Commands.has(ctx.commands, Command.Goto.class);
        // FIGHT_JUST_ENDED itself is a real edge (fighting last tick, not
        // fighting this tick) computed centrally by CombatEngagement.
        // tickEdgeDetection -- see its own docstring for why that can't
        // just be a Predicate closure living here. The "no visible
        // monsters" half is still checked locally, fresh, every time --
        // no need to also cache that centrally, it's a cheap read.
        Predicate<TickContext> fightJustEnded = ctx -> Boolean.TRUE.equals(ctx.blackboard.get(CombatEngagement.FIGHT_JUST_ENDED))
            && EntityFinder.findNearestVisibleHostile(ctx.level, ctx.player, VISIBLE_HOSTILE_RADIUS) == null;

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
            new Edge<>(LegsState.GOTO, LegsState.GO_TO_DEATH_POSITION, shouldRecover),
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

            // !goto -- same ranking as !pickup above (below recovery/
            // flee, above the plain navigate/idle fallback, not reachable
            // from FLEE) -- see LegsState/Command.Goto's own docstrings.
            new Edge<>(LegsState.IDLE, LegsState.GOTO, isGotoCommand),
            new Edge<>(LegsState.NAVIGATE, LegsState.GOTO, isGotoCommand),
            new Edge<>(LegsState.GOTO, LegsState.FLEE, ctx -> gotoNode.isFinished() && shouldFlee.test(ctx)),
            new Edge<>(LegsState.GOTO, LegsState.NAVIGATE, ctx -> gotoNode.isFinished() && !shouldFlee.test(ctx) && shouldNavigate.test(ctx)),
            new Edge<>(LegsState.GOTO, LegsState.IDLE, ctx -> gotoNode.isFinished() && !shouldFlee.test(ctx) && !shouldNavigate.test(ctx)),

            // Post-fight pickup -- see this class's own docstring for why
            // this is an edge-trigger (FightJustEnded), ranked alongside
            // !pickup above (below recovery/flee, above the plain
            // navigate/idle fallback, not reachable from FLEE).
            new Edge<>(LegsState.IDLE, LegsState.PICKUP_ITEMS, fightJustEnded),
            new Edge<>(LegsState.NAVIGATE, LegsState.PICKUP_ITEMS, fightJustEnded),

            new Edge<>(LegsState.IDLE, LegsState.NAVIGATE, shouldNavigate),
            new Edge<>(LegsState.NAVIGATE, LegsState.IDLE, ctx -> !shouldNavigate.test(ctx))
        );
        return new StateMachine<>("legs", LegsState.IDLE, nodes, edges);
    }
}
