package minebot.mod.task;

import minebot.mod.pathfinding.BlockFinder;
import minebot.mod.statemachine.TickContext;
import minebot.mod.statemachine.playerintention.NavIntent;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * !sleep -- finds the nearest bed (BlockFinder.findNearestBed, the first
 * real block-scan helper in the mod, built for exactly this), walks to it
 * (publishing NavIntent.NAV_TARGET/NAV_ARRIVED the same shared channel
 * GiveTask already uses -- Legs is a pure consumer, no awareness a Task
 * rather than a peer SM is asking), then right-clicks it once in range,
 * the same real useItemOn interaction HandsOpenDoorNode uses for doors.
 *
 * A queued Task, not a PlayerIntentionState -- an earlier version made
 * this a peer-SM state (PlayerIntentionState.SLEEP, modeled on KILL's
 * one-shot-trigger shape), removed per explicit direction ("I dont like
 * playerintention:sleep... implement it more like !give, which is a task
 * in a queue"): "walk to a bed and sleep" is a queued unit of work with a
 * genuine start/finish, exactly Task's own docstring description of the
 * GiveTask/StateNode distinction -- it never needs to interrupt or be
 * resumed by IDLE/FOLLOW/DEFEND the way a real standing intention would,
 * it just runs once queued and is done. TaskController.isBusy() already
 * holds off dequeuing it during real DEFEND combat, the same protection
 * SLEEP's old KILL-interrupt edges existed to provide.
 *
 * isFinished() once the actual useItemOn call resolves (whether or not
 * vanilla accepted it -- a bed can reject sleep for real in-game reasons
 * a client-side mod can't fully predict ahead of time: it's daytime, a
 * monster is nearby, the bed's obstructed, this isn't the bot's actual
 * spawn-eligible bed, etc. -- see tick()'s own comment), or once no bed
 * can be found/reached at all within TIMEOUT_TICKS. Deliberately does NOT
 * try to verify the bot ended up actually asleep (Player.isSleeping())
 * before finishing -- vanilla's own sleep failure is communicated back to
 * the player via a real chat system message the same way a human's
 * failed sleep attempt would be, which the bot already relays like any
 * other chat line (see MinebotMod's own chat-event broadcast), so there's
 * no separate success/failure signal this task needs to invent.
 */
public final class SleepTask implements Task {
    // Matches EntityFinder's own general search scope -- "nearest bed"
    // should mean "reasonably nearby", not a world-wide scan.
    private static final double SEARCH_RADIUS = 32.0;
    // Matches HandsOpenDoorNode's own INTERACT_RANGE -- a real player's
    // short interaction reach. Used directly as NAV_TARGET's own
    // stopDistance() too (not a separate, tighter tolerance the way
    // LegsPickupItemsNode's ARRIVAL_DISTANCE is) -- unlike walking onto an
    // item's real sub-block pickup AABB, a bed interaction just needs
    // real useItemOn range, so "arrived" and "close enough to interact"
    // are the same distance here.
    private static final double INTERACT_RANGE = 3.0;
    // No bed found/reachable at all -- give up rather than stand near a
    // stale target forever. Shorter than LegsPickupItemsNode's 600 (30s)
    // since a bed is a single fixed point, not a moving/re-scanned
    // target -- if it hasn't been reached by then it's not going to be.
    private static final int TIMEOUT_TICKS = 200; // ~10 seconds

    private BlockPos bedPos;
    private boolean finished;
    private int ticksElapsed;
    private boolean started;

    @Override
    public void onEnter(final TickContext ctx) {
        bedPos = BlockFinder.findNearestBed(ctx.level, ctx.player.position(), SEARCH_RADIUS);
        tick(ctx);
    }

    @Override
    public void tick(final TickContext ctx) {
        if (finished) {
            return;
        }
        // onEnter already calls tick() once (same convention GiveTask
        // uses) -- only count ticks from the SECOND call onward, so a
        // fresh task doesn't burn a tick off TIMEOUT_TICKS before it's
        // had a real tick of its own (same reasoning
        // LegsPickupItemsNode's own docstring gives for not delegating
        // its onEnter straight to onTick).
        if (started) {
            ticksElapsed++;
        }
        started = true;

        if (bedPos == null) {
            finished = true;
            return;
        }

        Vec3 bedCenter = Vec3.atCenterOf(bedPos);
        ctx.blackboard.put(NavIntent.NAV_TARGET, new NavIntent.Target(bedCenter, INTERACT_RANGE));
        double distance = ctx.player.position().distanceTo(bedCenter);
        boolean arrived = distance <= INTERACT_RANGE;
        ctx.blackboard.put(NavIntent.NAV_ARRIVED, arrived);

        if (ticksElapsed >= TIMEOUT_TICKS) {
            finished = true;
            return;
        }

        if (arrived) {
            useBed(ctx, bedPos);
            finished = true;
        }
    }

    /** Right-clicks the bed, the same real useItemOn+swing interaction HandsOpenDoorNode uses for doors -- vanilla's own BedBlock.useWithoutItem/Player.startSleepInBed handles everything else (actually entering the bed, or rejecting with a real chat system message if sleep isn't currently valid). */
    private static void useBed(final TickContext ctx, final BlockPos bedPos) {
        Vec3 hitLocation = Vec3.atCenterOf(bedPos);
        BlockHitResult hitResult = new BlockHitResult(hitLocation, Direction.UP, bedPos, false);
        InteractionResult result = Minecraft.getInstance().gameMode.useItemOn(ctx.player, InteractionHand.MAIN_HAND, hitResult);
        if (result.consumesAction()) {
            ctx.player.swing(InteractionHand.MAIN_HAND);
        }
    }

    @Override
    public void onExit(final TickContext ctx) {
        ctx.blackboard.put(NavIntent.NAV_TARGET, null);
        ctx.blackboard.put(NavIntent.NAV_ARRIVED, true);
    }

    @Override
    public boolean isFinished() {
        return finished;
    }
}
