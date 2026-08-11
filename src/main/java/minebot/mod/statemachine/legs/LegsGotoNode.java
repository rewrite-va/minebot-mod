package minebot.mod.statemachine.legs;

import minebot.mod.statemachine.Command;
import minebot.mod.statemachine.Commands;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import minebot.mod.statemachine.playerintention.NavIntent;
import net.minecraft.world.phys.Vec3;

/**
 * Walks the bot to a fixed world coordinate, once, then reports finished
 * -- backs !goto (Command.Goto), the first reintroduction of the "goto"
 * command stripped out during the peer-state-machine rewrite (see
 * MinebotMod.dispatchMessage's own docstring for the full removal/
 * reintroduction policy). Deliberately modeled on
 * LegsGoToDeathPositionNode (same "commit to one target position, walk
 * toward it via the shared NavIntent channel, report finished once
 * within range" shape) rather than PlayerIntentionFollowNode -- a !goto
 * target is a plain fixed Vec3, not a live entity/player that needs
 * re-resolving every tick, so there's nothing here that can go stale the
 * way a followed player's position can.
 *
 * The target coordinate is captured once, in onEnter, from the
 * Command.Goto that caused entry (via Commands.find -- see its own
 * docstring for why a plain has() isn't enough here, unlike Pickup/Stop)
 * -- NOT re-read every tick, since CommandBus only carries a Command for
 * the single tick it arrived on (see TickContext.commands's own
 * docstring); by the next tick it's already gone from the drained list.
 *
 * Uses its own dedicated ARRIVAL_DISTANCE, NOT NavIntent.
 * defaultStopDistance() (2.0 blocks) -- an earlier version reused the
 * default, the same "close enough" semantics Follow/GoToDeathPosition use
 * for "walked to a general area" (deliberately loose so the bot doesn't
 * crowd a followed player). !goto is a different kind of request though:
 * "go to THIS exact spot", not "stay near a moving target" -- confirmed
 * live/reported: a test polling for arrival within 0.5 blocks hung
 * forever because the bot's own NAV_ARRIVED flipped true (and Legs
 * stopped walking, falling back to IDLE) a full 2.0 blocks short of the
 * real target, nowhere near what a human would call "arrived" for a
 * fixed-coordinate goto. ARRIVAL_DISTANCE here matches
 * LegsPickupItemsNode's own tight tolerance shape (see its own docstring
 * for the same reasoning: a shared loose default doesn't work for every
 * publisher, see NavIntent's own docstring) rather than Follow's looser
 * one.
 */
public final class LegsGotoNode implements StateNode<LegsState> {
    // Tight enough to satisfy "walked to this specific spot" (matches the
    // precision a human would expect from typing !goto with real
    // coordinates), loose enough that normal per-tick movement overshoot
    // doesn't make arrival flicker in and out -- same reasoning
    // LegsPickupItemsNode's own ARRIVAL_DISTANCE (0.3) documents for why
    // it isn't exactly 0.0.
    private static final double ARRIVAL_DISTANCE = 0.5;

    // How much residual horizontal speed (blocks/tick) is "stopped enough"
    // to hand off to IDLE -- see isFinished's own arrival-check docstring
    // for the real bug this guards against (a jump's leftover slide
    // carrying the bot off a small landing platform the same tick GOTO
    // exits). Picked well below ordinary walking speed (~0.1-0.13
    // blocks/tick, see JumpPhysics' own docstring for the real vanilla
    // constants) so this only holds for the couple of ticks real ground
    // friction (0.6 * 0.91 per tick on a default block) takes to bleed
    // off jump-landing momentum specifically, not for the bot's entire
    // approach.
    private static final double ARRIVAL_MAX_HORIZONTAL_SPEED = 0.02;

    private Vec3 target;
    private boolean finished;

    @Override
    public void onEnter(final TickContext ctx, final LegsState previousState) {
        finished = false;
        // Confirmed live as a real bug: neither of these was ever reset
        // on GOTO's own entry (unlike LegsNavigateNode's own onEnter,
        // which does both -- see its own docstring) -- a fresh !goto
        // could inherit a stale cached A* plan (ctx.pathTracker, shared
        // across every Legs node that walks) or a stale
        // SPRINT_RUNUP_TICKS counter from whatever walk happened to run
        // immediately before this one (a DIFFERENT !goto, or an earlier
        // !follow/NAVIGATE session). Observed live as a perfectly
        // alternating FAIL/PASS/FAIL/PASS pattern across repeated runs of
        // the exact same tight-runway jump scenario -- a run that ended
        // mid-flight (cancelled by the outer test timeout before arrival)
        // left this state different from a run that completed cleanly,
        // and that leftover state fed directly into whether the NEXT
        // run's own jump could build enough runup before reaching the
        // platform edge.
        ctx.pathTracker.reset();
        ctx.blackboard.put(LegsNavigateNode.SPRINT_RUNUP_TICKS, 0);
        Command.Goto command = Commands.find(ctx.commands, Command.Goto.class);
        // Defensive only -- LegsStateMachine only ever enters GOTO off a
        // fresh Command.Goto being present this tick (see its own edge
        // table), so this should always resolve. Null only if that
        // invariant is ever broken elsewhere; report finished rather than
        // walk toward a stale/absent target.
        target = command != null ? new Vec3(command.x(), command.y(), command.z()) : null;
        onTick(ctx);
    }

    @Override
    public void onTick(final TickContext ctx) {
        if (target == null) {
            ctx.blackboard.put(NavIntent.NAV_TARGET, null);
            ctx.blackboard.put(NavIntent.NAV_ARRIVED, true);
            finished = true;
            return;
        }

        ctx.blackboard.put(NavIntent.NAV_TARGET, new NavIntent.Target(target, ARRIVAL_DISTANCE));
        LegsNavigateNode.walkTowardNavTarget(ctx, false, false);
        double distance = ctx.player.position().distanceTo(target);
        // Only count arrival while ON GROUND -- confirmed live as a real
        // bug: a jump's own airborne arc can pass within ARRIVAL_DISTANCE
        // of the target well before actually landing on it (e.g. flying
        // directly over/through the target's own column mid-jump), which
        // satisfied this check and exited GOTO back to IDLE while still
        // mid-air. IDLE applies no more movement intent at all, so
        // whatever momentum the bot had at that exact instant became the
        // only thing determining where it actually landed -- observed
        // live as a perpetual timeout loop: GOTO exited a bare ~1 second
        // after entering (well before the jump's own arc had time to
        // land), the bot fell uncontrolled the rest of the way down past
        // the target platform, and the test's own separate arrival check
        // (which DOES require being actually on the ground near the
        // target, not just having passed near it in the air) never saw a
        // real landing, timing out every single attempt.
        //
        // Also requires near-zero horizontal velocity -- confirmed live
        // as a SECOND, distinct real bug once the onGround fix above was
        // in place: the bot could correctly land ON GROUND, ON TARGET
        // (onGround=true, distance well under ARRIVAL_DISTANCE), but
        // still carrying real residual horizontal momentum from the jump
        // that hadn't bled off via ground friction yet. GOTO exited to
        // IDLE the very same tick, which applies NO movement intent at
        // all -- with nothing left to counteract it, that one tick of
        // leftover slide was enough to carry the bot straight off a small
        // (1-block-deep) landing platform's own edge, falling all the way
        // to bedrock. Waiting for horizontal speed to drop below
        // ARRIVAL_MAX_HORIZONTAL_SPEED (vanilla ground friction bleeds
        // this off within 1-2 ticks, an imperceptible extra delay) means
        // control is only ever handed to IDLE once the bot has actually
        // stopped, not merely arrived-but-still-sliding.
        Vec3 horizontalVelocity = new Vec3(ctx.player.getDeltaMovement().x, 0.0, ctx.player.getDeltaMovement().z);
        boolean withinRange = distance <= ARRIVAL_DISTANCE && ctx.player.onGround()
            && horizontalVelocity.length() <= ARRIVAL_MAX_HORIZONTAL_SPEED;
        ctx.blackboard.put(NavIntent.NAV_ARRIVED, withinRange);
        // Give up (report finished) once pathfinding has genuinely
        // determined there's no route at all, not just "haven't arrived
        // yet" -- see PathTracker.lastSearchFoundNoPath's own docstring,
        // and walkTowardNavTarget's own early-return that now stops
        // movement on NO_PATH instead of walking blindly at the raw
        // target. Confirmed live as a real, newly-exposed bug: blocking
        // movement without ALSO giving this node a way to exit meant a
        // genuinely unreachable !goto target left the bot stuck in
        // LegsState.GOTO forever -- motionless (correctly, no longer
        // walking off ledges/into hazards) but never transitioning back
        // to IDLE either, since withinRange alone was the only exit
        // condition and a blocked target can never satisfy it. A human
        // (or a test harness) had no way to recover except an explicit
        // !stop.
        finished = withinRange || ctx.pathTracker.lastSearchFoundNoPath();
    }

    @Override
    public void onExit(final TickContext ctx) {
        ctx.blackboard.put(NavIntent.NAV_TARGET, null);
        ctx.blackboard.put(NavIntent.NAV_ARRIVED, true);
        ctx.blackboard.put(LegsNavigateNode.WAYPOINT_COORDINATES, null);
        // Matches LegsNavigateNode's own onExit -- see onEnter's own
        // docstring for the real bug leaving this stale caused. Belt and
        // suspenders alongside onEnter's own reset: whichever node enters
        // NEXT (this one again, or NAVIGATE) starts from a guaranteed-zero
        // counter either way, not just "usually zero because onEnter
        // happened to reset it."
        ctx.blackboard.put(LegsNavigateNode.SPRINT_RUNUP_TICKS, 0);
        target = null;
    }

    @Override
    public boolean isFinished() {
        return finished;
    }
}
