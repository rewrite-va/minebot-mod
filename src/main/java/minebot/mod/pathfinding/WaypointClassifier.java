package minebot.mod.pathfinding;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Live classification of a single waypoint's real current block state --
 * separate from Movements/BlockInfo's OWN classification (which exists
 * purely to feed A*'s passability/cost decisions during planning, then is
 * discarded) because this needs to be re-checked fresh every tick a
 * waypoint is the bot's current target, by whichever SM cares (Legs:
 * don't jump onto farmland; a future Hands door node: open a closed door
 * ahead) -- not baked into the planned Move/path once and left stale if
 * the world changes between planning and actually arriving (a door
 * someone else closes, water freezing, etc.). See the conversation that
 * produced this class (and STATE_MACHINE.md) for the full reasoning.
 */
public record WaypointClassifier(boolean farmland, boolean closedDoor) {
    public static WaypointClassifier classify(final ClientLevel level, final int x, final int y, final int z) {
        BlockPos pos = new BlockPos(x, y, z);
        if (!level.isLoaded(pos)) {
            return new WaypointClassifier(false, false);
        }
        BlockState state = level.getBlockState(pos);
        boolean farmland = state.is(Blocks.FARMLAND);
        boolean isDoor = state.getBlock() instanceof DoorBlock && DoorBlock.isWoodenDoor(state);
        boolean closedDoor = isDoor && !state.getValue(DoorBlock.OPEN);
        return new WaypointClassifier(farmland, closedDoor);
    }
}
