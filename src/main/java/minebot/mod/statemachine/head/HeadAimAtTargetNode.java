package minebot.mod.statemachine.head;

import minebot.mod.WeaponSelector;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import minebot.mod.statemachine.playerintention.CombatEngagement;
import minebot.mod.statemachine.hands.HandsDrawBowNode;
import net.minecraft.world.entity.Entity;

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
 * (read fresh each tick, same as HandsDrawBowNode/HandsMeleeAttackNode
 * already do) rather than needing a separate HeadState -- a plain,
 * flat eye-level aim for melee (or anything that isn't a bow), or the
 * real arc-lifted ballistics aim (HandsDrawBowNode.ARC_LIFT_PER_BLOCK,
 * ported unchanged from BowShooter -- see its own docstring) once a bow
 * is selected. This automatically follows a weapon swap mid-fight (e.g.
 * a melee weapon breaking and a bow becoming the best choice) with no
 * new state transition needed, the same reasoning CombatEngagement's own
 * docstring gives for re-computing SELECTED_WEAPON every tick rather
 * than caching it once.
 */
public final class HeadAimAtTargetNode implements StateNode<HeadState> {
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

        WeaponSelector.Choice weapon = ctx.blackboard.get(CombatEngagement.SELECTED_WEAPON);
        boolean usingBow = weapon != null && weapon.kind() == WeaponSelector.Kind.BOW;

        double dx = target.getX() - ctx.player.getX();
        double dz = target.getZ() - ctx.player.getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        double dy;
        if (usingBow) {
            // Aim at roughly a third of the way up the target's body --
            // Entity.getY(fraction) is the exact same real convenience
            // AbstractSkeleton.performRangedAttack itself calls
            // (target.getY(0.3333)) to get this height, plus a fixed,
            // distance-proportional lift compensating for arrow flight
            // time -- both ported unchanged from BowShooter's own aimAt.
            dy = (target.getY(1.0 / 3.0) - ctx.player.getEyeY()) + horizontalDistance * HandsDrawBowNode.ARC_LIFT_PER_BLOCK;
        } else {
            dy = target.getEyeY() - ctx.player.getEyeY();
        }

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        // Positive pitch = looking down (same convention already
        // established in HeadIdleNode/LegsNavigateNode's own yaw
        // reasoning, confirmed via decompiled Entity.calculateViewVector).
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontalDistance));
        ctx.player.setYRot(yaw);
        ctx.player.setXRot(pitch);
    }
}
