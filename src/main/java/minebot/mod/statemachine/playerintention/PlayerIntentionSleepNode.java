package minebot.mod.statemachine.playerintention;

import minebot.mod.pathfinding.BlockFinder;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * !sleep -- finds the nearest bed (BlockFinder.findNearestBed, the first
 * real block-scan helper in the mod, built for exactly this), walks to
 * it (publishing NavIntent.NAV_TARGET/NAV_ARRIVED the same way every
 * other PlayerIntention node does -- Legs/Head are pure consumers, same
 * split PlayerIntentionFollowNode/PlayerIntentionKillNode already
 * established), then right-clicks it once in range, the same real
 * useItemOn interaction HandsOpenDoorNode uses for doors -- ported
 * inline here rather than as a separate Hands node, since (unlike
 * OPEN_DOOR, which can fire repeatedly for many different doors along a
 * walk) this is a single one-shot action tied 1:1 to this node's own
 * bed target and lifecycle, not an ongoing per-tick condition Hands would
 * need to keep re-evaluating independently.
 *
 * SLEEP is a one-shot TRIGGER, not a standing PlayerIntention value --
 * same reasoning as KILL (see PlayerIntentionState's own docstring):
 * "walk to a bed and sleep" is a task that finishes (bed reached and
 * used, or gives up) on its own, not an ongoing goal the player is
 * describing. Modeled directly on PlayerIntentionKillNode's own
 * onEnter/onTick/isFinished shape.
 *
 * isFinished() once the actual useItemOn call resolves (whether or not
 * vanilla accepted it -- a bed can reject sleep for real in-game reasons
 * a client-side mod can't fully predict ahead of time: it's daytime, a
 * monster is nearby, the bed's obstructed, this isn't the bot's actual
 * spawn-eligible bed, etc. -- see onTick's own comment), or once no bed
 * can be found/reached at all within TIMEOUT_TICKS. Deliberately does
 * NOT try to verify the bot ended up actually asleep (Player.isSleeping())
 * before finishing -- vanilla's own sleep failure is communicated back to
 * the player via a real chat system message the same way a human's
 * failed sleep attempt would be, which the bot already relays like any
 * other chat line (see MinebotMod's own chat-event broadcast), so there's
 * no separate success/failure signal this node needs to invent.
 */
public final class PlayerIntentionSleepNode implements StateNode<PlayerIntentionState> {
    // Matches PlayerIntentionKillNode/EntityFinder's own general search
    // scope -- "nearest bed" should mean "reasonably nearby", not a
    // world-wide scan.
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

    @Override
    public void onEnter(final TickContext ctx, final PlayerIntentionState previousState) {
        finished = false;
        ticksElapsed = 0;
        bedPos = BlockFinder.findNearestBed(ctx.level, ctx.player.position(), SEARCH_RADIUS);
        publishOrFinish(ctx);
    }

    @Override
    public void onTick(final TickContext ctx) {
        ticksElapsed++;
        publishOrFinish(ctx);
    }

    private void publishOrFinish(final TickContext ctx) {
        if (bedPos == null) {
            ctx.blackboard.put(NavIntent.NAV_TARGET, null);
            ctx.blackboard.put(NavIntent.NAV_ARRIVED, true);
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
        bedPos = null;
        ticksElapsed = 0;
    }

    @Override
    public boolean isFinished() {
        return finished;
    }
}
