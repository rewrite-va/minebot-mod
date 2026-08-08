package minebot.mod.statemachine.hands;

import minebot.mod.MinebotMod;
import minebot.mod.pathfinding.BlockBreaker;
import minebot.mod.statemachine.BlackboardKey;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import minebot.mod.statemachine.legs.LegsNavigateNode;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Digs through whatever block(s) the current pathfinding waypoint's own
 * planned Move requires (LegsNavigateNode.WAYPOINT_TO_BREAK -- see its own
 * docstring for why this reads the REAL planned toBreak list rather than
 * re-deriving "what's blocking the path" from the waypoint's block state)
 * -- restores the "digging through obstacle blocks while walking" gap
 * LegsNavigateNode's own class docstring explicitly called out as cut
 * during the state-machine port (was MinebotMod.maybeBreakBlocksNear /
 * pathBlockBreaker in the old shared pipeline), now as a real Hands SM
 * state per that docstring's own "belongs to Hands SM later (e.g.
 * hands:mine)" note.
 *
 * Reuses the existing BlockBreaker engine (see its own extensive class
 * docstring for the real keyAttack-hold mechanics this depends on) rather
 * than reimplementing block-breaking -- BlockBreaker already handles tool
 * switching, line-of-sight, settle timing, and stuck-target giveup
 * correctly; this node's only job is deciding WHICH position(s) to feed it
 * and WHEN (whenever Legs' current waypoint actually needs one broken).
 *
 * One block at a time, COMMITTED to until it's actually gone -- a jump
 * move's toBreak can legitimately list more than one position (e.g. a
 * 2-tall headroom clearance both needing digging), and BlockBreaker
 * itself only tracks one currentTarget at a time. Reported live: an
 * earlier version blindly re-read toBreak.get(0) fresh every single
 * tick, with no memory of which position it had already started
 * breaking -- harmless as long as toBreak's own first entry stayed
 * stable, but PathTracker replans the WHOLE path fresh every tick a
 * waypoint hasn't been reached yet (see its own maybeReplan docstring),
 * and A* has no guarantee of returning toBreak entries in the same order
 * across replans of the exact same jump move. Confirmed live via
 * BlockBreaker's own reflection diagnostic: destroyBlockPos alternated
 * y=93/y=92 every few ticks, real destroyProgress climbing to ~0.4 each
 * time before resetting to 0.0 -- exactly BlockBreaker's own documented
 * "any mid-break target switch resets real server-side destroy progress"
 * behavior (see its class docstring), just triggered by THIS node
 * switching targets instead of a hotbar swap. Neither leaf block ever
 * actually finished breaking, so the jump kept failing forever, which
 * kept the bot's own y oscillating (jump attempted, blocked, fell back
 * down), which kept perturbing which toBreak entry ended up first on the
 * next replan -- a real self-sustaining stuck loop. currentMiningTarget
 * (below) fixes this the same "keep working toward THIS SAME thing"
 * way LegsPickupItemsNode/PlayerIntentionDefendNode's own targetEntityId
 * already do for their own target selection: once set, only re-evaluated
 * (picking a fresh entry, preferring one still actually in toBreak) once
 * the current target is confirmed gone via BlockBreaker's own
 * justFinishedSettling, never merely because toBreak's list order shifted.
 *
 * Deliberately does NOT pause Legs' own walking -- unlike a held keyUse
 * interaction that would conflict with movement, walking into a still-
 * solid block simply has no effect (the collision box blocks forward
 * progress on its own, exactly like a real player bumping into a wall
 * while mining through it), so Legs and Hands can run concurrently here
 * the same way HandsOpenDoorNode already does for door interactions.
 */
public final class HandsMineNode implements StateNode<HandsState> {
    /**
     * The block this node is actively committed to breaking right now, or
     * null if none -- published so HeadMineNode (which runs AFTER Hands in
     * MinebotMod's own tick order, specifically so it can read this same-
     * tick -- see MinebotMod's own tick-order comment) knows exactly what
     * to aim at, without duplicating this node's own commit-until-done
     * target-selection logic (see this class's own docstring for why
     * toBreak.get(0) can't just be re-read fresh every tick). Aiming used
     * to happen inside BlockBreaker.aimAt itself, called from tryBreak --
     * moved out to Head entirely (see HeadMineNode's own docstring) so
     * there's exactly one writer of yaw/pitch per tick again, matching
     * every other HeadState's own exclusive ownership (see HeadState's own
     * class docstring: "Owns yaw/pitch exclusively"). Null whenever this
     * node has nothing committed (mirrors currentMiningTarget's own
     * lifecycle exactly -- this field only exists to make that same value
     * visible outside the node).
     */
    public static final BlackboardKey<BlockPos> CURRENT_MINING_TARGET = new BlackboardKey<>("hands.currentMiningTarget");

    // How close (real Euclidean distance) the bot's actual live position
    // must be to WAYPOINT_DIG_STANCE before mining is attempted at all --
    // see this class's own docstring for why this can't just be
    // BlockBreaker's own, much more permissive INTERACT_RANGE (4.5).
    // Reported live: HandsMineNode tried mining a diagonal move's own
    // obstruction while Legs was still short of the actual stance that
    // move's own toBreak/hasDigLineOfSight check (Movements.java,
    // planning-time) was computed from -- well within INTERACT_RANGE, so
    // BlockBreaker happily attempted the swing, but from a real position
    // Movements never actually verified visibility from, and the real
    // per-tick raycast consistently missed ("no line of sight" logged
    // every tick, forever, confirmed live). A first attempt at this gate
    // compared against WAYPOINT_COORDINATES (the move's own DESTINATION)
    // instead of WAYPOINT_DIG_STANCE (the move's own ORIGIN, see Move's
    // own docstring) -- a real bug, since the bot can never actually
    // reach the destination until digging is done in the first place (a
    // chicken-and-egg deadlock). 1.0 block is generous slop for real
    // walking jitter/pathfinding waypoint-reached tolerance (matches
    // PathTracker.nextWaypoint's own onGround yTolerance) while still
    // being tight enough that the bot is genuinely standing at (not just
    // near) the stance planning assumed.
    private static final double STANCE_ARRIVAL_TOLERANCE = 1.0;

    private final BlockBreaker breaker = new BlockBreaker("pathfinding");

    // The single block this node has committed to breaking, or null if it
    // needs to pick one -- see this class's own docstring for why this
    // can't just be toBreak.get(0) re-read every tick.
    private BlockPos currentMiningTarget;

    @Override
    public void onTick(final TickContext ctx) {
        List<BlockPos> toBreak = ctx.blackboard.get(LegsNavigateNode.WAYPOINT_TO_BREAK);
        if (toBreak == null || toBreak.isEmpty()) {
            currentMiningTarget = null;
            breaker.tickSettle(ctx.level);
            ctx.blackboard.put(CURRENT_MINING_TARGET, null);
            return;
        }

        BlockPos stance = ctx.blackboard.get(LegsNavigateNode.WAYPOINT_DIG_STANCE);
        if (stance != null && !isAtStance(ctx, stance)) {
            // Not actually there yet -- keep settling any in-progress
            // break (a target committed to from a PREVIOUS, already-
            // reached stance) but don't start a fresh attempt from a
            // position the plan never verified visibility from. Nothing
            // published here (null): no tryBreak call is about to happen
            // this tick, so there's nothing for HeadMineNode to usefully
            // aim at yet either.
            breaker.tickSettle(ctx.level);
            ctx.blackboard.put(CURRENT_MINING_TARGET, null);
            return;
        }

        if (currentMiningTarget != null && breaker.justFinishedSettling(currentMiningTarget)) {
            // The block we were committed to is confirmed actually gone
            // (not just "toBreak no longer lists it", which could also
            // mean a replan dropped it for some unrelated reason) --
            // free to pick a new target below.
            currentMiningTarget = null;
        }

        if (currentMiningTarget != null && breaker.justAbandoned(currentMiningTarget)) {
            // BlockBreaker gave up (STUCK_TICKS_LIMIT -- either never
            // completing or never getting a real line of sight) --
            // NOT a success, so justFinishedSettling above never catches
            // this. Reported live: a waypoint's own toBreak listed a block
            // permanently obstructed from the real dig stance (never
            // actually visible from there, no matter how long BlockBreaker
            // held keyAttack waiting) -- without this branch,
            // currentMiningTarget stayed set to the exact same abandoned
            // position, toBreak.contains(currentMiningTarget) below stayed
            // true forever (nothing about a give-up removes it from the
            // plan), and the block below just re-selected the identical
            // unreachable target next tick, restarting the same
            // STUCK_TICKS_LIMIT cycle forever with zero real progress.
            // Resetting the shared PathTracker forces a full fresh replan
            // next tick -- A* has no per-position exclusion list to steer
            // around this exact block (a real gap, see this method's own
            // git history), but a fresh plan at least stops the immediate
            // infinite retry and gives self-drift/target-movement a chance
            // to eventually produce a genuinely different route.
            MinebotMod.LOGGER.warn(
                "mining[pathfinding]: abandoning waypoint after giving up on {} -- forcing a fresh path replan",
                currentMiningTarget
            );
            currentMiningTarget = null;
            ctx.pathTracker.reset();
        }

        if (currentMiningTarget == null || !toBreak.contains(currentMiningTarget)) {
            // Either nothing committed yet, or the previous target is no
            // longer part of the plan at all (a real replan genuinely
            // moved on, not just reordered the same entries) -- pick
            // toBreak's own first entry fresh. This is the only place
            // toBreak's list order is ever consulted, and only when
            // there's no real in-progress commitment to preserve.
            currentMiningTarget = toBreak.get(0);
        }

        // Published BEFORE tryBreak, not after -- MinebotMod's own tick
        // order runs Hands before Head specifically so this same-tick
        // write is what HeadMineNode reads (see MinebotMod's own tick-order
        // comment), which in turn sets the real yaw/pitch tryBreak's own
        // hasLineOfSight/hitResult checks below depend on. Order within
        // this method doesn't affect that (both run inside this same
        // onTick, before Head ever sees it), but publishing right after
        // the target is finalized, not buried after tryBreak, keeps this
        // node's own "decide target, then announce it, then act on it"
        // shape readable.
        ctx.blackboard.put(CURRENT_MINING_TARGET, currentMiningTarget);

        breaker.tryBreak(ctx.player, ctx.level, currentMiningTarget);
    }

    @Override
    public void onExit(final TickContext ctx) {
        breaker.stopBreaking();
        currentMiningTarget = null;
        ctx.blackboard.put(CURRENT_MINING_TARGET, null);
    }

    /** Real Euclidean distance from the bot's own live position to `stance`'s block center -- same "+0.5 horizontal center" convention Movements.hasDigLineOfSight assumes when predicting visibility from a planned stance. */
    private static double distanceTo(final TickContext ctx, final BlockPos stance) {
        double dx = (stance.getX() + 0.5) - ctx.player.getX();
        double dy = stance.getY() - ctx.player.getY();
        double dz = (stance.getZ() + 0.5) - ctx.player.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** True once the bot's real live position is within STANCE_ARRIVAL_TOLERANCE of `stance` -- shared by onTick's own gate and hasBlockToMine below, so both agree on exactly the same "actually arrived" definition. */
    private static boolean isAtStance(final TickContext ctx, final BlockPos stance) {
        return distanceTo(ctx, stance) <= STANCE_ARRIVAL_TOLERANCE;
    }

    /**
     * True while there's a real block the current waypoint's planned Move
     * needs dug through AND the bot has actually arrived at the stance
     * that toBreak was planned from -- used by HandsStateMachine's own
     * blockedByObstacle edge. Deliberately checks stance arrival here too,
     * not just toBreak non-emptiness: reported live, HandsState toggled
     * IDLE<->MINE (and, via CURRENT_MINING_TARGET, HeadState NAVIGATE<->
     * MINE right along with it) every single tick while Legs was still
     * walking toward the stance -- onTick's own stance gate already
     * stopped it from ever actually swinging at the block from too far
     * away, but the state machine itself still flickered between MINE and
     * IDLE purely from ordinary walking jitter crossing the tolerance
     * boundary back and forth, with zero real tryBreak attempts ever
     * logged during the whole episode. Gating entry into MINE on the same
     * arrival check onTick already uses keeps Hands (and Head, which
     * mirrors it) in a single stable state -- IDLE/NAVIGATE -- for the
     * entire walk toward the stance, only flipping to MINE once actually
     * standing there.
     */
    public static boolean hasBlockToMine(final TickContext ctx) {
        List<BlockPos> toBreak = ctx.blackboard.get(LegsNavigateNode.WAYPOINT_TO_BREAK);
        if (toBreak == null || toBreak.isEmpty()) {
            return false;
        }
        BlockPos stance = ctx.blackboard.get(LegsNavigateNode.WAYPOINT_DIG_STANCE);
        return stance == null || isAtStance(ctx, stance);
    }
}
