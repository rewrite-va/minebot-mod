package minebot.mod.statemachine.head;

import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import minebot.mod.statemachine.legs.LegsNavigateNode;
import net.minecraft.world.phys.Vec3;

/**
 * Faces (yaw only -- see below) whatever point Legs is currently walking
 * toward -- reads LegsNavigateNode.AIM_POINT off the shared Blackboard
 * rather than a direct reference to the Legs node (see STATE_MACHINE.md's
 * "The blackboard" -- this is the actual point of that indirection: Head
 * has no import/reference to any specific Legs node, just a shared,
 * identity-keyed data slot).
 *
 * Yaw only, deliberately -- confirmed live: pitching to look at the
 * waypoint's exact height while walking (matching the old shared
 * pipeline's behavior, which combined both since it computed movement
 * and look direction in one pass) reads as the bot awkwardly tilting its
 * view up/down at every step change, not a natural "walking and looking
 * where you're going" motion. Real players walk level; pitch is reserved
 * for a future deliberate look target (aiming at a combat target, etc.),
 * not tied to ordinary navigation.
 */
public final class HeadNavigateNode implements StateNode {
    @Override
    public void onTick(final TickContext ctx) {
        Vec3 aimPoint = ctx.blackboard.get(LegsNavigateNode.AIM_POINT);
        if (aimPoint == null) {
            return; // Legs has nothing to walk toward right now -- leave yaw as it is
        }

        double dx = aimPoint.x() - ctx.player.getX();
        double dz = aimPoint.z() - ctx.player.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        ctx.player.setYRot(yaw);
    }
}
