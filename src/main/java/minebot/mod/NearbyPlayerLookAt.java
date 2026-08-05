package minebot.mod;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Looks at the closest nearby player's eye level -- a bot standing
 * genuinely idle glances at whoever's actually close by, the way a real
 * player naturally would.
 *
 * Currently applied unconditionally every tick (see MinebotMod.
 * onClientTick's call site) -- there's no other system contending for
 * look direction right now (ControlState.Mode is IDLE-only post
 * state-machine-architecture pivot, see STATE_MACHINE.md, and Head SM
 * doesn't exist yet), so nothing to gate this against. Once Head SM
 * exists, look-direction ownership/arbitration becomes its job, and this
 * class should be ported into a Head node rather than called directly
 * from onClientTick.
 */
public final class NearbyPlayerLookAt {
    private static final double RANGE = 8.0;
    private static final double RANGE_SQUARED = RANGE * RANGE;

    /**
     * Returns a MovementIntent with yaw+pitch aimed at the closest player
     * within RANGE blocks, or null if nobody's close enough -- callers
     * apply the returned intent's yaw/pitch themselves (this doesn't
     * touch forward/jump/sprint, which are movement's concern, not
     * looking's).
     */
    public MovementIntent resolve(final LocalPlayer self, final ClientLevel level) {
        Player closest = null;
        double closestDistanceSquared = RANGE_SQUARED;

        for (Player other : level.players()) {
            if (other == self) {
                continue;
            }
            double distanceSquared = other.distanceToSqr(self);
            if (distanceSquared < closestDistanceSquared) {
                closest = other;
                closestDistanceSquared = distanceSquared;
            }
        }

        if (closest == null) {
            return null;
        }

        double dx = closest.getX() - self.getX();
        double dz = closest.getZ() - self.getZ();
        double dy = closest.getEyeY() - self.getEyeY();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

        MovementIntent intent = new MovementIntent();
        intent.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        // Positive pitch = looking down (confirmed via decompiled
        // Entity.calculateViewVector), so a target above eye level
        // (dy > 0) needs a negative pitch -- the leading minus sign here
        // is deliberate, not a typo.
        intent.pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontalDistance));
        return intent;
    }
}
