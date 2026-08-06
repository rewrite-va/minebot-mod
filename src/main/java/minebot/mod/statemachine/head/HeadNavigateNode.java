package minebot.mod.statemachine.head;

import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import minebot.mod.statemachine.legs.LegsNavigateNode;
import net.minecraft.world.phys.Vec3;

/**
 * Faces whatever point Legs is currently walking toward -- reads
 * LegsNavigateNode.AIM_POINT off the shared Blackboard rather than a
 * direct reference to the Legs node (see STATE_MACHINE.md's "The
 * blackboard" -- this is the actual point of that indirection: Head has
 * no import/reference to any specific Legs node, just a shared,
 * identity-keyed data slot). Same yaw/pitch formulas the old shared
 * pipeline used (atan2(-dx, dz) for yaw, matching the convention already
 * established in BowShooter/BlockBreaker/NearbyPlayerLookAt; pitch from
 * the eye-relative angle) -- this class is what that old pipeline's
 * "look at the next waypoint while walking" fragment becomes now that
 * look direction has its own real owner instead of being computed
 * inline alongside movement.
 */
public final class HeadNavigateNode implements StateNode {
    @Override
    public void onTick(final TickContext ctx) {
        Vec3 aimPoint = ctx.blackboard.get(LegsNavigateNode.AIM_POINT);
        if (aimPoint == null) {
            return; // Legs has nothing to walk toward right now -- leave yaw/pitch as they are
        }

        double dx = aimPoint.x() - ctx.player.getX();
        double dz = aimPoint.z() - ctx.player.getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        double eyeDy = aimPoint.y() - ctx.player.getEyeY();
        float pitch = (float) -Math.toDegrees(Math.atan2(eyeDy, horizontalDistance));

        ctx.player.setYRot(yaw);
        ctx.player.setXRot(pitch);
    }
}
