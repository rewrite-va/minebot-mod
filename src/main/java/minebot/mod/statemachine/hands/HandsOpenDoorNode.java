package minebot.mod.statemachine.hands;

import minebot.mod.pathfinding.WaypointClassifier;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import minebot.mod.statemachine.legs.LegsNavigateNode;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Right-clicks a closed door open, the same way a real player would --
 * ported from the old standalone DoorOpener class (see git history),
 * which used to run unconditionally inside the shared movement pipeline
 * for every mode. Reads LegsNavigateNode.WAYPOINT_COORDINATES directly
 * (the exact block Legs' own pathfinder is about to walk through) rather
 * than DoorOpener's old dy-in-{0,-1} search -- that search existed to
 * cover ambiguity in which of two adjacent cells a waypoint's own
 * coordinates might refer to; reading the real published waypoint block
 * directly doesn't have that ambiguity.
 *
 * A one-shot interaction (useItemOn + swing, throttled), not a held
 * keybind/resource -- no SharedResourceArbiter involvement needed (see
 * STATE_MACHINE.md's arbiter design, reserved for genuinely exclusive,
 * multi-tick-held resources like keyUse/keyAttack).
 */
public final class HandsOpenDoorNode implements StateNode<HandsState> {
    // Matches DoorOpener's old INTERACT_RANGE -- roughly a real player's
    // short interaction reach.
    private static final double INTERACT_RANGE = 3.0;
    // Matches DoorOpener's old throttle -- don't spam interactions every
    // tick while walking up to/through it; repeated right-clicks on the
    // same door would just toggle it shut again right after opening it.
    private static final int RETRY_TICKS = 10;

    private int ticksSinceLastAttempt = RETRY_TICKS;

    @Override
    public void onEnter(final TickContext ctx, final HandsState previousState) {
        ticksSinceLastAttempt = RETRY_TICKS; // always allowed to try immediately on entry
    }

    @Override
    public void onTick(final TickContext ctx) {
        ticksSinceLastAttempt++;

        BlockPos doorPos = ctx.blackboard.get(LegsNavigateNode.WAYPOINT_COORDINATES);
        if (doorPos == null) {
            return;
        }

        double dx = (doorPos.getX() + 0.5) - ctx.player.getX();
        double dy = (doorPos.getY() + 0.5) - ctx.player.getY();
        double dz = (doorPos.getZ() + 0.5) - ctx.player.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance > INTERACT_RANGE) {
            return;
        }

        if (ticksSinceLastAttempt < RETRY_TICKS) {
            return;
        }
        ticksSinceLastAttempt = 0;

        Vec3 hitLocation = Vec3.atCenterOf(doorPos);
        BlockHitResult hitResult = new BlockHitResult(hitLocation, Direction.NORTH, doorPos, false);
        Minecraft.getInstance().gameMode.useItemOn(ctx.player, InteractionHand.MAIN_HAND, hitResult);
        ctx.player.swing(InteractionHand.MAIN_HAND);
    }

    /** True if `pos` (Legs' currently published waypoint) is a real closed door worth entering this state for -- used by HandsStateMachine's own edges. */
    public static boolean isClosedDoor(final TickContext ctx, final BlockPos pos) {
        if (pos == null) {
            return false;
        }
        return WaypointClassifier.classify(ctx.level, pos.getX(), pos.getY(), pos.getZ()).closedDoor();
    }
}
