package minebot.mod.statemachine.head;

import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import net.minecraft.world.entity.player.Player;

/**
 * Looks at the closest nearby player's eye level, within RANGE blocks --
 * a bot with nothing else to look at glances at whoever's actually close
 * by, the way a real player naturally would. Ported from the old
 * standalone NearbyPlayerLookAt class (now deleted -- this IS its real
 * home now that Head SM owns look direction for real) with RANGE lowered
 * from 8 to 5 blocks per explicit direction.
 *
 * Unlike HeadNavigateNode (yaw only, deliberately -- see its own
 * docstring for why pitching while walking looked wrong), this DOES set
 * pitch: glancing at a specific person while otherwise idle is a
 * deliberate look, not an artifact of walking, so tilting to actually
 * meet their eye level is the natural behavior here.
 */
public final class HeadIdleNode implements StateNode<HeadState> {
    private static final double RANGE = 5.0;
    private static final double RANGE_SQUARED = RANGE * RANGE;

    @Override
    public void onTick(final TickContext ctx) {
        Player closest = null;
        double closestDistanceSquared = RANGE_SQUARED;

        for (Player other : ctx.level.players()) {
            if (other == ctx.player) {
                continue;
            }
            double distanceSquared = other.distanceToSqr(ctx.player);
            if (distanceSquared < closestDistanceSquared) {
                closest = other;
                closestDistanceSquared = distanceSquared;
            }
        }

        if (closest == null) {
            return; // nobody close enough -- leave yaw/pitch as they are
        }

        double dx = closest.getX() - ctx.player.getX();
        double dz = closest.getZ() - ctx.player.getZ();
        double dy = closest.getEyeY() - ctx.player.getEyeY();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        // Positive pitch = looking down (confirmed via decompiled
        // Entity.calculateViewVector), so a target above eye level
        // (dy > 0) needs a negative pitch -- the leading minus sign here
        // is deliberate, not a typo.
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontalDistance));
        ctx.player.setYRot(yaw);
        ctx.player.setXRot(pitch);
    }
}
