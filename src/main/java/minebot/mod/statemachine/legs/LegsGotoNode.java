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

    private Vec3 target;
    private boolean finished;

    @Override
    public void onEnter(final TickContext ctx, final LegsState previousState) {
        finished = false;
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
        boolean withinRange = distance <= ARRIVAL_DISTANCE;
        ctx.blackboard.put(NavIntent.NAV_ARRIVED, withinRange);
        finished = withinRange;
    }

    @Override
    public void onExit(final TickContext ctx) {
        ctx.blackboard.put(NavIntent.NAV_TARGET, null);
        ctx.blackboard.put(NavIntent.NAV_ARRIVED, true);
        ctx.blackboard.put(LegsNavigateNode.WAYPOINT_COORDINATES, null);
        target = null;
    }

    @Override
    public boolean isFinished() {
        return finished;
    }
}
