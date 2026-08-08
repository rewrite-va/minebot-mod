package minebot.mod.statemachine.head;

import minebot.mod.InventoryController;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import minebot.mod.statemachine.playerintention.CombatEngagement;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Faces whatever CombatEngagement.TARGET_ENTITY_ID currently points at
 * every tick -- shared by PlayerIntention:KILL and PlayerIntention:DEFEND (whichever's
 * currently fighting something -- see CombatEngagement's own docstring)
 * -- ported from the old shared pipeline's own aimAtEntity/
 * BowShooter.aimAt (see git history: MinebotMod.aimAtEntity, called
 * every tick from tickAttack; BowShooter's own aimAt, which used to
 * write yaw/pitch directly from inside Hands territory -- both aim
 * formulas now live here instead, since aiming is exclusively Head's
 * concern). Reads the real Entity itself (not just NavIntent.
 * NAV_TARGET's position) rather than the fixed-height/arc-lifted aim
 * point HeadNavigateNode derives from a static waypoint -- a real combat
 * target moves and has its own eye height.
 *
 * Branches its own aim formula by CombatEngagement.SELECTED_WEAPON
 * (read fresh each tick, same as HandsDrawBowNode/HandsDrawCrossbowNode/
 * HandsMeleeAttackNode already do) rather than needing a separate
 * HeadState -- a flat aim at the target's real hitbox center for melee
 * (or anything that isn't a ranged weapon), or the real arc-lifted,
 * velocity-led ballistics aim (see below) once a bow OR crossbow is
 * selected (both are real projectile weapons with the same arc, see
 * InventoryController.findBestWeapon's own docstring for why they're a
 * single reach/Kind category here). This automatically follows a weapon
 * swap mid-fight (e.g. a melee weapon breaking and a bow becoming the
 * best choice) with no new state transition needed, the same reasoning
 * CombatEngagement's own docstring gives for re-computing
 * SELECTED_WEAPON every tick rather than caching it once.
 *
 * Aims at the target's real hitbox CENTER (Entity.getBoundingBox().
 * getCenter()) rather than a fixed height fraction of its feet position
 * -- per explicit direction ("some hostiles are small and bow always
 * misses it because the bot is not considering the hitbox"), a fixed
 * "1/3 up the body"/"at eye height" guess is tuned for a roughly
 * humanoid skeleton shape and misses badly on anything shorter/taller/
 * oddly-proportioned (a baby zombie, a spider, a silverfish) whose real
 * hitbox center sits somewhere else entirely.
 *
 * RANGED SHOTS ALSO LEAD THE TARGET: per explicit direction ("some
 * hostiles move fast making the bot fail all shots... aim where its
 * target will be considering the target's velocity vector"), the aim
 * point for a bow/crossbow is the hitbox center projected forward by
 * Entity.getDeltaMovement() (the target's own real live per-tick motion
 * vector) over an estimated arrow travel time (distance / ARROW_SPEED --
 * see that constant's own comment) -- see leadForVelocity's own
 * docstring. A fast-moving target is long gone from its CURRENT position
 * by the time a 3-blocks/tick arrow actually covers real combat
 * distance, so aiming at "now" rather than "on arrival" was missing
 * consistently against anything that wasn't standing still. Melee does
 * NOT lead -- a melee swing/attack is effectively instant compared to
 * the target's own movement per tick, so there's no meaningful travel
 * time to compensate for.
 *
 * hitboxCenter/leadForVelocity/arcLift are three DELIBERATELY SEPARATE
 * compensation steps (per explicit direction, to let each be tested/
 * toggled independently rather than as one fused formula, which is
 * exactly what let a real bug get isolated live: hitboxCenter alone
 * confirmed correct, then arcLift alone -- with vanilla's own unscaled
 * mob constant -- confirmed shots landing consistently too high, see
 * arcLift's own docstring for the real root cause and fix) -- onTick
 * composes them in order (center, then lead, then arc lift) rather than
 * inlining the math.
 */
public final class HeadAimAtTargetNode implements StateNode<HeadState> {
    // BowItem.releaseUsing's own real launch speed at full draw (power
    // 1.0 * 3.0F, confirmed via decompiled source -- the same constant
    // BowItem itself uses to build the arrow's initial motion vector via
    // Projectile.shootFromRotation). Used here only for a straight-line
    // time-of-flight ESTIMATE (arrowDistance / ARROW_SPEED) to lead a
    // moving target -- real arrow flight also decelerates slightly from
    // drag and drops from gravity, but this is the same level of
    // approximation ARC_LIFT_PER_BLOCK already uses for the vertical
    // arc below, not a full physics simulation.
    private static final double ARROW_SPEED = 3.0;

    @Override
    public void onTick(final TickContext ctx) {
        Integer targetEntityId = ctx.blackboard.get(CombatEngagement.TARGET_ENTITY_ID);
        if (targetEntityId == null) {
            return; // nothing real to face right now -- leave yaw/pitch as they are
        }
        Entity target = ctx.level.getEntity(targetEntityId);
        if (target == null) {
            return;
        }

        InventoryController.Choice weapon = ctx.blackboard.get(CombatEngagement.SELECTED_WEAPON);
        boolean usingRanged = weapon != null
            && (weapon.kind() == InventoryController.Kind.BOW || weapon.kind() == InventoryController.Kind.CROSSBOW);

        Vec3 aimPoint = hitboxCenter(target);

        if (usingRanged) {
            double distanceHint = ctx.player.position().distanceTo(aimPoint);
            aimPoint = leadForVelocity(aimPoint, target, distanceHint);
            aimPoint = arcLift(ctx, aimPoint);
        }

        double dx = aimPoint.x() - ctx.player.getX();
        double dz = aimPoint.z() - ctx.player.getZ();
        double dy = aimPoint.y() - ctx.player.getEyeY();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        aim(ctx, dx, dz, dy, horizontalDistance);
    }

    /**
     * The real hitbox center (Entity.getBoundingBox().getCenter()) --
     * confirmed live (per explicit direction: "is aiming at the center")
     * that this alone, with no lead/arc compensation, really does land on
     * the true center -- see this class's own docstring for why this
     * replaced the old fixed-height-fraction guess.
     */
    private static Vec3 hitboxCenter(final Entity target) {
        return target.getBoundingBox().getCenter();
    }

    /**
     * Predicts where `point` will actually BE once a real arrow arrives,
     * using the target's own live velocity (Entity.getDeltaMovement(),
     * the same real per-tick motion vector the game itself advances the
     * entity by) -- per explicit direction ("aim where its target will be
     * considering the target's velocity vector"), a fast mover is long
     * gone from its CURRENT position by the time a slow-traveling arrow
     * (3 blocks/tick) covers real combat distance. `distanceHint` (the
     * distance to `point` BEFORE leading) is used for the travel-time
     * estimate rather than the true leaded distance -- close enough for
     * this level of approximation, and avoids a circular "leaded position
     * depends on travel time depends on leaded position" solve.
     */
    private static Vec3 leadForVelocity(final Vec3 point, final Entity target, final double distanceHint) {
        Vec3 velocity = target.getDeltaMovement();
        double travelTime = distanceHint / ARROW_SPEED;
        return point.add(velocity.scale(travelTime));
    }

    /**
     * Fixed, distance-proportional lift compensating for real arrow drop
     * over its flight -- the aimed direction's un-normalized Y component
     * gets `horizontalDistance * ARC_LIFT_PER_BLOCK` added before the
     * game normalizes it into a unit direction and scales by the arrow's
     * actual launch speed (confirmed via decompiled Projectile.
     * getMovementToShoot -- normalize() happens AFTER this addition, so
     * atan2(dy, horizontalDistance) recovers the identical angle whether
     * computed before or after that scaling, which is why doing the
     * addition directly in world-space Y here, then re-deriving dy/pitch
     * from it in onTick, is equivalent).
     *
     * ARC_LIFT_PER_BLOCK is NOT vanilla's own AbstractSkeleton.
     * performRangedAttack constant (0.2) unchanged -- that value is
     * tuned for a MOB's shot, which launches at velocity 1.6 blocks/tick
     * (confirmed via decompiled AbstractSkeleton.performRangedAttack,
     * the literal 1.6f passed into spawnProjectileUsingShoot), not a
     * player's fully-drawn bow (velocity 3.0, confirmed via decompiled
     * BowItem.releaseUsing's power*3.0F). Confirmed live (per explicit
     * direction: "arrows landed consistently too HIGH") that reusing
     * 0.2 unchanged overshoots badly -- the needed vertical launch
     * compensation for a fixed gravity (AbstractArrow.getDefaultGravity
     * == 0.05 blocks/tick^2, confirmed via decompiled source) to hit a
     * level target at distance D scales as roughly D / velocity^2 (the
     * standard small-angle "half the total drop as initial upward
     * velocity" projectile-launch approximation), so the correct
     * constant for a DIFFERENT velocity scales by (oldVelocity /
     * newVelocity)^2: 0.2 * (1.6/3.0)^2 ~= 0.057. Still a small-angle,
     * straight-line-lift APPROXIMATION (same category as
     * ARROW_SPEED's own straight-line travel-time estimate above), not a
     * full parabolic trajectory solve -- worth re-tuning further via live
     * testing if shots are still landing off.
     */
    private static final double ARC_LIFT_PER_BLOCK = 0.2 * (1.6 / 3.0) * (1.6 / 3.0);

    private static Vec3 arcLift(final TickContext ctx, final Vec3 point) {
        double horizontalDistance = Math.sqrt(
            Math.pow(point.x() - ctx.player.getX(), 2) + Math.pow(point.z() - ctx.player.getZ(), 2)
        );
        return point.add(0, horizontalDistance * ARC_LIFT_PER_BLOCK, 0);
    }

    private static void aim(final TickContext ctx, final double dx, final double dz, final double dy, final double horizontalDistance) {
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        // Positive pitch = looking down (same convention already
        // established in HeadIdleNode/LegsNavigateNode's own yaw
        // reasoning, confirmed via decompiled Entity.calculateViewVector).
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontalDistance));
        ctx.player.setYRot(yaw);
        ctx.player.setXRot(pitch);
    }
}
