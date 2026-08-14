package minebot.mod.replay;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import minebot.mod.pathfinding.Move;
import minebot.mod.pathfinding.PathTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.client.player.LocalPlayer;

/**
 * Builds one `replay_frame` wire event per client tick for the test-replay
 * recorder (see minebot's own testing/replay.py for the consumer). Stateless
 * on purpose -- unlike maybeBroadcastPositionEvent this never dedups, since
 * a replay scrubber needs one frame per tick regardless of whether anything
 * actually changed.
 */
public final class ReplayFrameBuilder {
    private ReplayFrameBuilder() {
    }

    public static JsonObject build(final LocalPlayer player, final PathTracker pathTracker) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "replay_frame");
        event.addProperty("x", player.getX());
        event.addProperty("y", player.getY());
        event.addProperty("z", player.getZ());
        event.addProperty("yaw", player.getYRot());
        event.addProperty("pitch", player.getXRot());
        event.addProperty("on_ground", player.onGround());
        event.addProperty("velocity_x", player.getDeltaMovement().x);
        event.addProperty("velocity_y", player.getDeltaMovement().y);
        event.addProperty("velocity_z", player.getDeltaMovement().z);
        event.addProperty("sprinting", player.isSprinting());

        JsonArray path = new JsonArray();
        for (Move move : pathTracker.waypoints()) {
            JsonObject waypoint = new JsonObject();
            waypoint.addProperty("x", move.x);
            waypoint.addProperty("y", move.y);
            waypoint.addProperty("z", move.z);
            waypoint.addProperty("requires_jump", move.requiresJump);
            JsonArray toBreak = new JsonArray();
            for (BlockPos pos : move.toBreak) {
                JsonObject block = new JsonObject();
                block.addProperty("x", pos.getX());
                block.addProperty("y", pos.getY());
                block.addProperty("z", pos.getZ());
                toBreak.add(block);
            }
            waypoint.add("to_break", toBreak);
            path.add(waypoint);
        }
        event.add("path", path);

        return event;
    }
}
