package minebot.mod.statemachine.head;

import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import minebot.mod.statemachine.general.GeneralFollowNode;
import minebot.mod.statemachine.legs.LegsNavigateNode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Faces (yaw only -- see below) whatever point Legs is currently walking
 * toward -- reads LegsNavigateNode.WAYPOINT_COORDINATES off the shared
 * Blackboard rather than a direct reference to the Legs node (see
 * STATE_MACHINE.md's "The blackboard" -- this is the actual point of
 * that indirection: Head has no import/reference to any specific Legs
 * node, just shared, identity-keyed data slots), deriving its own
 * horizontal-center aim point from the raw block coordinates (the
 * published value is deliberately the raw block position, not a
 * pre-offset point -- see WAYPOINT_COORDINATES' own docstring).
 *
 * Falls back to GeneralFollowNode.TARGET_POSITION (the raw followed-
 * entity position) whenever there's no real waypoint to face -- e.g. no
 * path found, or close enough that the planned path is exhausted --
 * matching the old shared pipeline's own fallback shape (aim at the
 * waypoint if there is one, otherwise the raw target).
 *
 * Pitch is pinned level (0 -- looking straight at the horizon), not
 * aimed at the waypoint's exact height -- confirmed live: pitching to
 * look at the waypoint's exact height while walking (matching the old
 * shared pipeline's behavior, which combined both since it computed
 * movement and look direction in one pass) reads as the bot awkwardly
 * tilting its view up/down at every step change, not a natural "walking
 * and looking where you're going" motion. Real players walk level;
 * pitch tracking a specific point is reserved for a future deliberate
 * look target (aiming at a combat target, etc.), not tied to ordinary
 * navigation.
 */
public final class HeadNavigateNode implements StateNode<HeadState> {
    @Override
    public void onTick(final TickContext ctx) {
        BlockPos waypoint = ctx.blackboard.get(LegsNavigateNode.WAYPOINT_COORDINATES);
        Vec3 aimPoint;
        if (waypoint != null) {
            aimPoint = new Vec3(waypoint.getX() + 0.5, waypoint.getY(), waypoint.getZ() + 0.5);
        } else {
            aimPoint = ctx.blackboard.get(GeneralFollowNode.TARGET_POSITION);
        }
        if (aimPoint == null) {
            return; // nothing real to face right now -- leave yaw as it is
        }

        double dx = aimPoint.x() - ctx.player.getX();
        double dz = aimPoint.z() - ctx.player.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        ctx.player.setYRot(yaw);
        ctx.player.setXRot(0f);
    }
}
