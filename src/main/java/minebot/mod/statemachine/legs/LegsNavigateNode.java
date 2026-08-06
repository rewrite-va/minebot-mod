package minebot.mod.statemachine.legs;

import minebot.mod.MovementIntent;
import minebot.mod.pathfinding.Move;
import minebot.mod.pathfinding.WaypointClassifier;
import minebot.mod.statemachine.BlackboardKey;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import minebot.mod.statemachine.playerintention.NavIntent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Walks toward whatever position PlayerIntention is currently publishing via
 * NavIntent.NAV_TARGET -- the first real Legs behavior, ported from
 * MinebotMod.resolveMovementIntent's old FOLLOW case + its shared
 * pathfinding pipeline (see STATE_MACHINE.md's git history / the
 * conversation that produced this class for the full step-by-step
 * mapping).
 *
 * No longer tracks followEntityId or does its own stop-distance check --
 * whichever PlayerIntention node is currently active owns both (see NavIntent's
 * own docstring for why this is a shared channel, not FOLLOW-specific
 * anymore: GO_TO_DEATH_POSITION/PICKUP_ITEMS need the exact same "walk
 * toward this point, stop when close enough" shape for their own
 * targets). LegsStateMachine's own NAVIGATE<->IDLE edges already gate on
 * a NAV_TARGET existing and not yet being within range, so by the time
 * this node's onTick runs, "should Legs be walking at all" is already
 * answered -- this node only ever needs to answer "walk toward this
 * specific position," with zero awareness of WHY (which PlayerIntention state
 * asked, or what it's for).
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
 * exclusive concern (see STATE_MACHINE.md's axis split, and HeadState/
 * HeadNavigateNode, which now actually owns it) -- but DOES READ the
 * player's current yaw, to compute forward/backward/left/right relative
 * to whatever direction the player currently happens to be facing
 * (Minecraft's own movement model is inherently yaw-relative -- "forward"
 * always means "whichever way the entity is currently facing", confirmed
 * live: an earlier version of this node only ever set `forward` with
 * nothing anywhere setting yaw at all, and the bot walked in a fixed,
 * arbitrary direction -- whichever way it happened to be facing when
 * !follow started -- completely independent of the actual target's real
 * position). Reading yaw without writing it is what actually keeps this
 * decoupled from Head SM: real kiting (backing/strafing away from a
 * target while FACING it) needs exactly this -- Legs moving in an
 * arbitrary absolute direction while Head points the camera wherever it
 * wants, independent of travel direction.
 *
 * Uses ctx.pathTracker -- a single PathTracker shared across every Legs
 * node that walks (this one, and LegsFleeNode via its own delegation into
 * walkTowardNavTarget below), living on TickContext itself (see its own
 * docstring) rather than owned privately per-node or threaded through
 * node constructors -- per explicit direction, nodes reach shared engine
 * state the same way they already reach everything else per-tick
 * (ctx.blackboard, ctx.input, ...), not via ad hoc references passed
 * around outside that channel. NAVIGATE/FLEE are never active at the same
 * time (see LegsStateMachine's own edges), and PathVisualizer draws
 * whichever plan is current regardless of which state produced it, so one
 * shared instance is exactly right here.
 */
public final class LegsNavigateNode implements StateNode<LegsState> {
    /**
     * The next unreached waypoint's raw block position, or null when
     * there's no real planned waypoint (no path found/needed -- walking
     * straight at the raw target) or this node isn't active at all.
     * Published every tick this node is active -- read by HeadNavigateNode
     * (which derives its own fractional aim point from it) and
     * HandsOpenDoorNode (which checks the actual block there for a closed
     * door), without either needing a direct reference to this node (see
     * STATE_MACHINE.md's "The blackboard"). Deliberately the raw integer
     * block position, not a pre-offset Vec3 -- a fractional aim point is
     * only meaningful to a consumer that wants to aim at it (Head); a
     * consumer that wants to know what block this actually is (Hands)
     * needs the real coordinates, not an already-offset point that could
     * floor() back to the wrong block in edge cases (see the conversation
     * that produced this field for the full reasoning).
     */
    public static final BlackboardKey<BlockPos> WAYPOINT_COORDINATES = new BlackboardKey<>("WAYPOINT_COORDINATES");

    @Override
    public void onEnter(final TickContext ctx, final LegsState previousState) {
        ctx.pathTracker.reset();
    }

    @Override
    public void onTick(final TickContext ctx) {
        walkTowardNavTarget(ctx);
    }

    @Override
    public void onExit(final TickContext ctx) {
        ctx.input.setIntent(new MovementIntent());
        ctx.blackboard.put(WAYPOINT_COORDINATES, null);
    }

    /**
     * The actual "walk toward whatever NavIntent.NAV_TARGET currently
     * says, using ctx.pathTracker's own plan" logic -- shared by this
     * node's own onTick and by LegsFleeNode's (see its own docstring for
     * why FLEE delegates its actual movement here after publishing its
     * own computed retreat point as NAV_TARGET, rather than duplicating
     * this walk logic or driving movement itself): both ultimately reduce
     * to the same "walk toward whatever position NAV_TARGET holds right
     * now" problem, and NAVIGATE/FLEE are never active at the same time
     * (see LegsStateMachine's own edges), so there's no real reason for
     * two separate implementations of it.
     */
    static void walkTowardNavTarget(final TickContext ctx) {
        NavIntent.Target target = ctx.blackboard.get(NavIntent.NAV_TARGET);
        if (target == null) {
            ctx.input.setIntent(new MovementIntent());
            ctx.blackboard.put(WAYPOINT_COORDINATES, null);
            return;
        }
        Vec3 targetPosition = target.position();
        double stopDistanceValue = target.stopDistance();

        double selfX = ctx.player.getX();
        double selfY = ctx.player.getY();
        double selfZ = ctx.player.getZ();
        double targetX = targetPosition.x();
        double targetY = targetPosition.y();
        double targetZ = targetPosition.z();

        ctx.pathTracker.maybeReplan(ctx.level, ctx.player, selfX, selfY, selfZ, targetX, targetY, targetZ, stopDistanceValue);
        Move waypoint = ctx.pathTracker.nextWaypoint(selfX, selfY, selfZ, ctx.player.onGround());
        ctx.blackboard.put(WAYPOINT_COORDINATES, waypoint != null ? new BlockPos(waypoint.x, waypoint.y, waypoint.z) : null);

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
        double distanceToStopAt = waypoint != null ? 0.0 : stopDistanceValue;

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

        // Same MAX_STEP_HEIGHT_TRIGGER/always-sprint reasoning as the old
        // shared pipeline (see its own extensive comment history in
        // MinebotMod's git log). Farmland check corrected from the old
        // pipeline's own version, per explicit direction: jumping itself
        // (e.g. over/from a farmland tile to reach some other block) is
        // fine -- it's specifically LANDING on farmland that tramples
        // real vanilla FarmBlock.fallOn mechanics, so this checks the
        // waypoint being jumped TO, not whichever block the bot currently
        // happens to be standing on.
        boolean landingOnFarmland = waypoint != null && WaypointClassifier.classify(ctx.level, waypoint.x, waypoint.y, waypoint.z).farmland();
        double dy = aimY - selfY;
        if (walking && dy > 0.1 && !landingOnFarmland) {
            intent.jump = true;
            intent.sprint = true;
        }

        ctx.input.setIntent(intent);
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
