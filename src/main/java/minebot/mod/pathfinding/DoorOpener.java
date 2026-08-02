package minebot.mod.pathfinding;

import minebot.mod.MinebotMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Right-clicks a closed hand-openable door open, the same way a real player
 * would -- movements.js/mineflayer-pathfinder don't cost doors specially in
 * A* either; mineflayer's own door-handling plugin opens them on approach,
 * which is the same shape of fix applied here (see Movements.getBlock's
 * closedDoor handling: the pathfinder treats a closed door as passable
 * space, and this class is what actually makes that true by opening it).
 *
 * Sends a real interaction packet via MultiPlayerGameMode.useItemOn, exactly
 * like every other server-visible action this mod performs -- there's no
 * direct world-state mutation available (or wanted) here, since the mod
 * doesn't implement its own protocol.
 */
public final class DoorOpener {
    // How close (in blocks, each axis) the bot must be to a door before
    // bothering to open it -- matches roughly a real player's short
    // interaction reach, and avoids opening a door while still several
    // blocks of walking away from it.
    private static final double INTERACT_RANGE = 3.0;

    // Long.MIN_VALUE as a sentinel "never interacted yet" caused
    // `tickCounter - lastInteractTick` to overflow on the very first
    // check (wrapping around to another huge negative number, which is
    // still < 10), so the throttle silently blocked every single
    // interaction attempt forever -- found live: the bot stood right next
    // to a closed door for 17+ seconds, well within range, and never once
    // logged an open attempt. -1000 is just "far enough in the past that
    // the first real check always passes" without any overflow risk.
    private long lastInteractTick = -1000;
    private long tickCounter;

    /**
     * Looks for a closed door at or adjacent to the given waypoint (the
     * pathfinder's own coordinates only identify a passable column, not
     * which exact block was the door), and opens it if the bot is close
     * enough and hasn't just tried. Safe to call every tick -- most ticks
     * there's nothing to do.
     */
    public void maybeOpenDoorNear(final LocalPlayer player, final ClientLevel level, final Move waypoint) {
        tickCounter++;
        if (waypoint == null) {
            return;
        }

        BlockPos doorPos = findClosedDoorAt(level, waypoint.x, waypoint.y, waypoint.z);
        if (doorPos == null) {
            return;
        }
        double dx = (doorPos.getX() + 0.5) - player.getX();
        double dy = (doorPos.getY() + 0.5) - player.getY();
        double dz = (doorPos.getZ() + 0.5) - player.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (distance > INTERACT_RANGE) {
            return;
        }

        // Don't spam interactions every tick while walking up to/through
        // it -- one attempt per ~10 ticks (half a second) is plenty, and
        // repeated right-clicks on the same door would just toggle it
        // shut again right after opening it.
        if (tickCounter - lastInteractTick < 10) {
            return;
        }
        lastInteractTick = tickCounter;

        Vec3 hitLocation = Vec3.atCenterOf(doorPos);
        BlockHitResult hitResult = new BlockHitResult(hitLocation, Direction.NORTH, doorPos, false);
        Minecraft.getInstance().gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
        player.swing(InteractionHand.MAIN_HAND);
        MinebotMod.LOGGER.debug("pathfinding: attempted to open door at {} (distance={})", doorPos, distance);
    }

    private static BlockPos findClosedDoorAt(final ClientLevel level, final int x, final int y, final int z) {
        // The waypoint itself, plus one block below (a drop/step move can
        // land the waypoint one block above a door's actual position) --
        // covers the two ways Movements' cost model ever passes through a
        // door column.
        for (int dy = 0; dy >= -1; dy--) {
            BlockPos pos = new BlockPos(x, y + dy, z);
            if (!level.isLoaded(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof DoorBlock && DoorBlock.isWoodenDoor(state) && !state.getValue(DoorBlock.OPEN)) {
                return pos;
            }
        }
        return null;
    }
}
