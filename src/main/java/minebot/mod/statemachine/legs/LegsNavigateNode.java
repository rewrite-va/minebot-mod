package minebot.mod.statemachine.legs;

import minebot.mod.MovementIntent;
import minebot.mod.pathfinding.Move;
import minebot.mod.pathfinding.PathTracker;
import minebot.mod.statemachine.Command;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import net.minecraft.world.entity.Entity;

/**
 * Walks toward a live entity's position -- the first real Legs behavior,
 * ported from MinebotMod.resolveMovementIntent's old FOLLOW case + its
 * shared pathfinding pipeline (see STATE_MACHINE.md's git history / the
 * conversation that produced this class for the full step-by-step
 * mapping). ControlState.Mode.FOLLOW/setFollow are gone -- this node,
 * driven by Command.Follow (see Command's own docstring), is now the
 * only thing that walks the bot toward a followed entity.
 *
 * Deliberately excludes, as real known gaps for future Hands SM nodes
 * (NOT ported here, unlike the rest of the old pipeline):
 * - Door-opening while walking (was DoorOpener.maybeOpenDoorNear, called
 *   unconditionally inside the old shared pipeline).
 * - Digging through obstacle blocks while walking (was
 *   MinebotMod.maybeBreakBlocksNear / pathBlockBreaker).
 * Both are real hand interactions (a useItemOn call, a keyAttack hold)
 * that happened to live inside movement code only because nothing else
 * separated "decide where to walk" from "do the interactions needed to
 * get there" -- per explicit direction, they belong to Hands SM later
 * (e.g. hands:open_door), not duplicated into Legs now. Until then,
 * !follow simply cannot walk through a closed door or a blocked path --
 * an honest regression from the old behavior, not an oversight.
 *
 * Deliberately never WRITES yaw/pitch -- look direction is Head SM's
 * exclusive concern (see STATE_MACHINE.md's axis split) -- but DOES
 * READ the player's current yaw, to compute forward/backward/left/right
 * relative to whatever direction the player currently happens to be
 * facing (Minecraft's own movement model is inherently yaw-relative --
 * "forward" always means "whichever way the entity is currently facing",
 * confirmed live: an earlier version of this node only ever set
 * `forward`, with nothing left to set yaw at all once it stopped doing
 * so directly, and the bot walked in a fixed, arbitrary direction --
 * whichever way it happened to be facing when !follow started --
 * completely independent of the actual target's real position). Reading
 * yaw without writing it is what actually keeps this decoupled from
 * Head SM: real kiting (backing/strafing away from a target while
 * FACING it) needs exactly this -- Legs moving in an arbitrary absolute
 * direction while some other axis (eventually Head) points the camera
 * wherever it wants, independent of travel direction. Until Head SM
 * exists to deliberately set yaw (e.g. aiming at a combat target), the
 * player's yaw is left at whatever it last was (nothing currently
 * writes it at all), so the bot visibly strafes/walks backward toward
 * its target rather than facing it -- an honest, visible interim state,
 * but real, correct absolute-direction movement, unlike the walks-in-a-
 * fixed-direction bug this replaces.
 */
public final class LegsNavigateNode implements StateNode {
    private static final double STOP_DISTANCE = 2.0; // matches ControlState.setFollow's old default

    // Node-local state -- NOT ControlState fields. Which entity to follow
    // comes from the most recent Command.Follow seen (see onTick below);
    // pathTracker is this node's own instance, not shared with any other
    // node/mode (unlike the old single ControlState.pathTracker every
    // mode aliased).
    private final PathTracker pathTracker = new PathTracker();
    private int followEntityId = -1;

    @Override
    public void onEnter(final TickContext ctx) {
        pathTracker.reset();
        for (Command command : ctx.commands) {
            if (command instanceof Command.Follow follow) {
                followEntityId = follow.entityId();
            }
        }
    }

    @Override
    public void onTick(final TickContext ctx) {
        for (Command command : ctx.commands) {
            if (command instanceof Command.Follow follow) {
                // A fresh !follow for a different entity while already
                // navigating -- re-aim at the new target, same as the old
                // ControlState.setFollow's pathTracker.reset() did.
                if (follow.entityId() != followEntityId) {
                    pathTracker.reset();
                }
                followEntityId = follow.entityId();
            }
        }

        Entity target = ctx.level.getEntity(followEntityId);
        if (target == null) {
            ctx.input.setIntent(new MovementIntent());
            return;
        }

        double selfX = ctx.player.getX();
        double selfY = ctx.player.getY();
        double selfZ = ctx.player.getZ();
        double targetX = target.getX();
        double targetY = target.getY();
        double targetZ = target.getZ();

        pathTracker.maybeReplan(ctx.level, ctx.player, selfX, selfY, selfZ, targetX, targetY, targetZ, STOP_DISTANCE);
        Move waypoint = pathTracker.nextWaypoint(selfX, selfY, selfZ, ctx.player.onGround());

        // Aim at the next unreached waypoint's block center, or the raw
        // target if we have no plan (no path found / not yet computed) --
        // same as the old shared pipeline.
        double aimX = waypoint != null ? waypoint.x + 0.5 : targetX;
        double aimY = waypoint != null ? waypoint.y : targetY;
        double aimZ = waypoint != null ? waypoint.z + 0.5 : targetZ;

        double dx = aimX - selfX;
        double dz = aimZ - selfZ;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

        // Only the raw target (not a waypoint) should stop the bot when
        // close enough -- a waypoint just short of the goal must still be
        // walked through, not treated as "arrived".
        double distanceToStopAt = waypoint != null ? 0.0 : STOP_DISTANCE;

        MovementIntent intent = new MovementIntent();
        boolean walking = horizontalDistance > distanceToStopAt;
        if (walking) {
            // World-space angle to the aim point (same atan2(-dx, dz)
            // convention as the old pipeline/BowShooter/BlockBreaker,
            // confirmed via decompiled Entity.calculateViewVector: yaw 0
            // = south/+z), minus the player's CURRENT yaw (read, never
            // written here -- see this class's own docstring) gives the
            // direction to walk RELATIVE to whichever way the player
            // happens to be facing right now. Necessary because
            // Minecraft's movement is inherently yaw-relative -- there is
            // no "walk toward absolute world direction X" primitive,
            // only forward/backward/left/right relative to facing.
            double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
            double relativeYaw = normalizeDegrees(targetYaw - ctx.player.getYRot());
            setDirectionalKeys(intent, relativeYaw);
        }

        // Same MAX_STEP_HEIGHT_TRIGGER/farmland-avoidance/always-sprint
        // reasoning as the old shared pipeline (see its own extensive
        // comment history in MinebotMod's git log) -- ported unchanged.
        boolean standingOnFarmland = ctx.level.getBlockState(ctx.player.blockPosition().below()).is(net.minecraft.world.level.block.Blocks.FARMLAND);
        double dy = aimY - selfY;
        if (walking && dy > 0.1 && !standingOnFarmland) {
            intent.jump = true;
            intent.sprint = true;
        }

        ctx.input.setIntent(intent);
    }

    @Override
    public void onExit(final TickContext ctx) {
        ctx.input.setIntent(new MovementIntent());
        followEntityId = -1;
    }

    /**
     * Sets forward/backward/left/right on `intent` for `relativeYaw`
     * degrees (0 = straight ahead, +90 = the player's own right,
     * -90 = left, +-180 = straight behind -- confirmed live, see
     * setDirectionalKeys' own comment for why this isn't the sign one
     * might expect from this codebase's usual facing-yaw convention) --
     * an 8-way (octant) approximation of a continuous direction, the same
     * granularity real discrete WASD keys are limited to (a real player
     * can't press "43% forward, 57% left" either, only combinations of
     * whole keys). Combines two keys for a diagonal (e.g. forward+left)
     * exactly like a real player would to walk at an angle.
     */
    private static void setDirectionalKeys(final MovementIntent intent, final double relativeYaw) {
        // Forward/backward: within 67.5 degrees of straight ahead/behind.
        if (relativeYaw > -67.5 && relativeYaw < 67.5) {
            intent.forward = true;
        } else if (relativeYaw > 112.5 || relativeYaw < -112.5) {
            intent.backward = true;
        }
        // Left/right: within 67.5 degrees of straight left/right.
        // Confirmed live (a first attempt using the opposite sign made
        // the bot strafe right when the target was to its left) --
        // positive relativeYaw is toward the player's own RIGHT, not
        // left, despite the seemingly-analogous atan2(-dx, dz) facing
        // convention used elsewhere in this codebase; movement's own
        // left/right input axis evidently isn't mirrored the same way.
        if (relativeYaw < -22.5 && relativeYaw > -157.5) {
            intent.left = true;
        } else if (relativeYaw > 22.5 && relativeYaw < 157.5) {
            intent.right = true;
        }
    }

    /** Wraps `degrees` into (-180, 180], the same range Entity.getYRot()/setYRot() themselves use. */
    private static double normalizeDegrees(double degrees) {
        degrees %= 360.0;
        if (degrees <= -180.0) {
            degrees += 360.0;
        } else if (degrees > 180.0) {
            degrees -= 360.0;
        }
        return degrees;
    }
}
