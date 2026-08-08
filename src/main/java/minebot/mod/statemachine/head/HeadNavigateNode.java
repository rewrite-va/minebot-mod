package minebot.mod.statemachine.head;

import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import minebot.mod.statemachine.playerintention.NavIntent;
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
 * Briefly tried aiming one waypoint further out (ctx.pathTracker.
 * lookaheadWaypoint()) for a steadier, more anticipatory-looking head
 * turn -- reverted after it broke jumping: LegsNavigateNode's own
 * requiresJumpSafeToFire gates a requiresJump move's jump input on
 * facingWaypoint, which compares the CURRENT waypoint's own direction
 * against ctx.player.getYRot() (see its own docstring for exactly why).
 * That comparison implicitly assumes Head is aiming at the SAME waypoint
 * Legs is walking toward -- once Head aimed one waypoint further ahead
 * instead, the two could disagree by more than the ±67.5 degree cone on
 * any path with a real turn in it (confirmed live: a winding 21-waypoint
 * climb), so facingWaypoint stayed false forever, requiresJumpSafeToFire
 * never fired, and the bot was stuck bumping into the same block face
 * over and over -- the "moving back and forth, never actually
 * progressing" pattern this reverts. A correct lookahead-look feature
 * would need Legs' own jump gating to stop depending on Head's real yaw
 * at all (compare against the current waypoint's own direction directly
 * instead of ctx.player.getYRot()) -- not attempted here, since the look
 * smoothness this bought wasn't worth reintroducing that whole class of
 * bug for.
 *
 * Falls back to NavIntent.NAV_TARGET (the raw target whichever
 * PlayerIntention node is currently active published) whenever there's no real waypoint
 * to face -- e.g. no path found, or close enough that the planned path
 * is exhausted -- matching the old shared pipeline's own fallback shape
 * (aim at the waypoint if there is one, otherwise the raw target).
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
            NavIntent.Target target = ctx.blackboard.get(NavIntent.NAV_TARGET);
            aimPoint = target != null ? target.position() : null;
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
