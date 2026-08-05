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
 * Also deliberately excludes yaw/pitch entirely -- look direction is
 * Head SM's exclusive concern (see STATE_MACHINE.md's axis split). The
 * bot will walk toward its target without visually turning to face it
 * until Head SM's first node exists; an accepted, documented interim
 * gap, not a bug.
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
            intent.forward = true;
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
}
