package minebot.mod.statemachine.legs;

import minebot.mod.MovementIntent;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import minebot.mod.statemachine.playerintention.CombatEngagement;
import minebot.mod.statemachine.playerintention.NavIntent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Continuously repositions away from the current combat target while
 * health is low (HandsEatNode.lowHealth()) -- reacts directly to that
 * fact, completely independent of whatever PlayerIntention OR Hands is
 * doing (IDLE/FOLLOW/DEFEND/KILL; EAT active or not), per explicit
 * direction: "independent SMs work together and independent without
 * general". Only meaningful when there IS a live combat target
 * (CombatEngagement.TARGET_ENTITY_ID) -- outside a fight (e.g. low health
 * from fall damage while just following) there's nothing to flee FROM, so
 * this node has nothing to do and Legs stands still.
 *
 * "Safe" is a DYNAMIC, ongoing condition here, not a one-time destination
 * -- per explicit direction: "flee should be a state of constant
 * reposition, not just a simple go to position A, then stop and maybe
 * reevaluate... since is dynamic, then we dont need the concept of
 * arrival". This node computes a fresh retreat point AWAY FROM THE
 * TARGET'S CURRENT POSITION every tick the target is still within
 * FLEE_DISTANCE and publishes it as NavIntent.NAV_TARGET, same shape as
 * CombatEngagement's own melee-kiting retreat (see its own docstring)
 * rather than PlayerIntentionPickupItemsNode-style one-time destination
 * commitment.
 *
 * Publishes through the SAME NavIntent.NAV_TARGET/LegsNavigateNode.
 * WAYPOINT_COORDINATES channels every other Legs-moving publisher uses
 * (CombatEngagement, PlayerIntentionFollowNode, etc.) and then delegates
 * the actual walk to LegsNavigateNode's own walkTowardNavTarget (shared
 * ctx.pathTracker -- see TickContext's own docstring for why this is a
 * single instance reachable by every Legs node, not owned per-node) --
 * per explicit direction: no separate private PathTracker here, so
 * PathVisualizer's gizmos show the real, live navigation at all times
 * regardless of which Legs state is currently driving it. LegsState.FLEE
 * still exists as its own distinct state (unlike, say, folding this into
 * NAVIGATE) so HeadStateMachine can key its own look behavior off it
 * directly -- HeadStateMachine maps HeadState.FLEE to the SAME
 * HeadNavigateNode instance HeadState.NAVIGATE uses (see its own
 * docstring), since publishing through the identical
 * WAYPOINT_COORDINATES/NAV_TARGET channel this node uses means Head's
 * existing "look at whatever Legs is walking toward" logic already does
 * exactly the right thing here with zero new code -- fleeing needs the
 * bot looking toward its travel direction rather than at the threat,
 * since backing away/strafing while staring at a target is real-vanilla
 * slower than sprinting forward, and that's a Head-only decision this
 * node has no reason to also need to make.
 *
 * This does NOT reintroduce the original chasing-treadmill bug (see git
 * history: recomputing a point N blocks from a live-chasing target's
 * CURRENT position every tick, while actively pathing MANY blocks toward
 * it, made the goalpost recede roughly as fast as the bot closed on it,
 * so real distance gained stayed near zero) -- PathTracker.maybeReplan's
 * own REPLAN_DISTANCE already throttles actually re-running A* to only
 * when the aim point has moved meaningfully, and FLEE_DISTANCE here is
 * deliberately much smaller than the old single-commit version's 16 (see
 * below) specifically so each retreat step is one the bot can actually
 * close within a tick or two, the same reason CombatEngagement's own
 * melee retreat distance is the player's real (short) engage range rather
 * than something large -- a short, continuously-refreshed retreat step
 * reliably gains real ground against a chasing target; a long one
 * recomputed continuously does not.
 *
 * Hands:EAT decides for itself when it's safe enough to eat (distance to
 * the live target vs. its own threshold) rather than this node reporting
 * an "arrived"/"safe" signal -- per explicit direction: "we just need 2
 * conditions to eat, legs:flee and distance to hostiles more than X...
 * this is something eats decide" (see HandsEatNode/HandsStateMachine's
 * own docstrings).
 */
public final class LegsFleeNode implements StateNode<LegsState> {
    // How far away from the target's CURRENT position each retreat step
    // aims for -- deliberately short (unlike an earlier single-commit
    // version's 16) since this is recomputed continuously against the
    // target's live position; see this class's own docstring for why a
    // short, continuously-refreshed step reliably gains ground against a
    // chasing target where a long one recomputed every tick would not.
    private static final double FLEE_DISTANCE = 6.0;

    // Purely mechanical arrival tolerance for the retreat point itself --
    // small, since the point is recomputed fresh next tick anyway; this
    // only affects when NavIntent.NAV_ARRIVED flips true for a single
    // tick's aim, not any real "done fleeing" concept (there isn't one --
    // see this class's own docstring).
    private static final double ARRIVAL_DISTANCE = 0.2;

    @Override
    public void onEnter(final TickContext ctx, final LegsState previousState) {
        ctx.pathTracker.reset();
    }

    @Override
    public void onTick(final TickContext ctx) {
        Vec3 retreatPoint = computeRetreatPoint(ctx);
        if (retreatPoint == null) {
            ctx.blackboard.put(NavIntent.NAV_TARGET, null);
            ctx.blackboard.put(NavIntent.NAV_ARRIVED, true);
            ctx.input.setIntent(new MovementIntent());
            return;
        }
        ctx.blackboard.put(NavIntent.NAV_TARGET, new NavIntent.Target(retreatPoint, ARRIVAL_DISTANCE));
        LegsNavigateNode.walkTowardNavTarget(ctx);
    }

    @Override
    public void onExit(final TickContext ctx) {
        ctx.input.setIntent(new MovementIntent());
        ctx.blackboard.put(NavIntent.NAV_TARGET, null);
        ctx.blackboard.put(NavIntent.NAV_ARRIVED, true);
        ctx.blackboard.put(LegsNavigateNode.WAYPOINT_COORDINATES, null);
    }

    /** A point FLEE_DISTANCE blocks directly away from the live combat target's CURRENT position -- recomputed fresh every tick (see this class's own docstring for why that's safe here), along the current player<->target separation vector, falling back to the player's current facing direction if the target is standing exactly on top of the bot. Null if there's no live combat target to flee from at all. */
    private static Vec3 computeRetreatPoint(final TickContext ctx) {
        Integer targetEntityId = ctx.blackboard.get(CombatEngagement.TARGET_ENTITY_ID);
        Entity target = targetEntityId != null ? ctx.level.getEntity(targetEntityId) : null;
        if (target == null) {
            return null;
        }
        Vec3 targetPosition = target.position();

        double dx = ctx.player.getX() - targetPosition.x();
        double dz = ctx.player.getZ() - targetPosition.z();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        double awayX;
        double awayZ;
        if (horizontalDistance > 1.0e-3) {
            awayX = dx / horizontalDistance;
            awayZ = dz / horizontalDistance;
        } else {
            double yawRad = Math.toRadians(ctx.player.getYRot());
            awayX = Math.sin(yawRad);
            awayZ = -Math.cos(yawRad);
        }
        return new Vec3(ctx.player.getX() + awayX * FLEE_DISTANCE, ctx.player.getY(), ctx.player.getZ() + awayZ * FLEE_DISTANCE);
    }
}
