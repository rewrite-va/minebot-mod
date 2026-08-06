package minebot.mod.statemachine.playerintention;

import minebot.mod.WeaponSelector;
import minebot.mod.statemachine.BlackboardKey;
import minebot.mod.statemachine.TickContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * The real "given a live target entity, do the fighting bookkeeping"
 * logic -- shared by every PlayerIntention state that fights something
 * (PlayerIntentionKillNode for !kill's one-shot fight, PlayerIntentionDefendNode for
 * !defend's standing auto-retarget fight), pulled out here rather than
 * duplicated between them (per explicit direction, once !defend needed
 * the exact same target-tracking/kiting/weapon-publishing shape
 * PlayerIntentionKillNode -- then still named GeneralCombatNode -- already had).
 * A plain static-method utility, not a StateNode itself -- it has no
 * state/lifecycle of its own, callers own their own target-resolution
 * strategy and isFinished()/exit semantics entirely.
 *
 * TARGET_ENTITY_ID/SELECTED_WEAPON live here (not on PlayerIntentionKillNode)
 * since neither is KILL-specific anymore -- Hands (HandsMeleeAttackNode/
 * HandsDrawBowNode)/Head (HeadAimAtTargetNode) read whichever of KILL/
 * DEFEND most recently published them, with zero awareness of which one
 * that was.
 *
 * SELECTED_WEAPON is computed ONCE per tick via WeaponSelector.choose()
 * -- every consumer reads the SAME published value rather than
 * independently re-calling WeaponSelector.choose() itself. Deliberately
 * NOT a bundled "CombatContext" struct grouping target+weapon+everything
 * -- considered and rejected: every other cross-SM fact in this codebase
 * is its own independently-typed BlackboardKey, and a single aggregate
 * object would be a new, different data-modeling convention just to save
 * one WeaponSelector.choose() call's worth of duplication. Re-computed
 * every tick (not cached once on entry) specifically so a weapon
 * breaking mid-fight is picked up immediately -- WeaponSelector.choose()
 * is a pure, stateless read of current inventory, so there's no
 * staleness risk to guard against by caching it.
 *
 * Range is weapon-dependent and NOT range-managed by Hands at all --
 * Hands just acts "if there is vision of the target" (see
 * HandsMeleeAttackNode/HandsDrawBowNode's own docstrings), Legs is the
 * one that manages distance (a pure consumer of whatever NavIntent.
 * NAV_TARGET says, same as every other Legs walk), and PlayerIntention
 * (via this class) is the one that decides what distance Legs should
 * manage toward.
 *
 * MELEE engage range is Attributes.ENTITY_INTERACTION_RANGE -- the real,
 * live per-player attribute vanilla itself uses to decide how far a melee
 * attack/interaction can reach (default 3.0, confirmed via decompiled
 * Attributes source), NOT a hardcoded constant -- this automatically
 * stays correct for whatever's actually equipped (a future reach-
 * modifying weapon/effect included) without a hand-maintained per-item
 * table.
 *
 * MELEE KITING: once the target is within melee reach, this hits AND
 * retreats rather than standing still trading blows -- confirmed live
 * that standing still let a zombie close in and land free hits while the
 * bot's own attack was still charging. Retreat is gated on
 * Player.getAttackStrengthScale(0.0f): while LOW (just swung, recharging
 * -- see HandsMeleeAttackNode's own docstring for why Player.attack()
 * doesn't gate itself, so the ticker genuinely does climb back up between
 * real swings now that Hands only swings once fully charged), this
 * publishes a retreat point (distance = the player's OWN engage range,
 * so retreating always lands exactly back at melee range's edge, not an
 * arbitrary separate distance) instead of the target's position; once
 * the charge nears ready again, it switches back to approaching/holding
 * at melee range so the next swing can land the instant it's charged.
 *
 * BOW KITING: unchanged trigger (target has closed inside melee reach
 * while a bow is selected) but a real, modest BOW_RETREAT_DISTANCE (5)
 * instead of retreating all the way back to the full BOW_RANGE (15) --
 * bow range "can be at any distance" (a bow can still fire effectively
 * from just outside melee reach), so re-opening the full 15 blocks every
 * time a target gets close is unnecessary; 5 is enough to be safe from
 * melee reach again.
 *
 * NAV_ARRIVED correctness: this is computed against whatever
 * navPosition ACTUALLY is this tick (the retreat point while retreating,
 * the target's position otherwise) -- confirmed live this was a real,
 * separate bug: an earlier version always computed it against the
 * target's own position/the engage range, so while kiting away (bow: a
 * huge 15-block range, meaning "still within range" stayed true even
 * standing right next to the target) LegsStateMachine's own
 * NAVIGATE<->IDLE edges read NAV_ARRIVED==true and never actually
 * walked to the published retreat point at all -- Legs just stood still
 * instead of retreating. RETREAT_ARRIVAL_DISTANCE (0.2, shared across
 * bow/melee -- a purely mechanical "did we actually reach the retreat
 * spot" tolerance, not a weapon-specific concern, tight enough to avoid
 * PathTracker jitter at the destination without demanding exact-pixel
 * arrival) is used for NAV_ARRIVED specifically while retreating;
 * the real engage range is still used while approaching/holding, as
 * before.
 */
public final class CombatEngagement {
    private CombatEngagement() {
    }

    // AbstractSkeleton/AbstractIllager's own real search radius for
    // "nearest hostile" scans elsewhere in vanilla is much larger than
    // this, but a bare `!kill`/nearby-threat scan finding something 30+
    // blocks away and dragging the bot off toward it unprompted would be
    // surprising -- kept modest, matching the old EntityFinder-backed
    // !attack's own default before deletion.
    public static final double SEARCH_RADIUS = 16.0;
    // BowItem.DEFAULT_RANGE (confirmed via decompiled source, same value
    // the old pre-deletion tickAttack's own ATTACK_BOW_RANGE used) -- the
    // real distance vanilla itself considers a bow's effective range.
    private static final double BOW_RANGE = 15.0;
    // How far a bow retreats to once the target's closed inside melee
    // reach -- NOT the full BOW_RANGE (see this class's own docstring for
    // why re-opening the whole 15 blocks every time isn't necessary).
    private static final double BOW_RETREAT_DISTANCE = 5.0;
    // Charge threshold below which melee kiting retreats rather than
    // approaches/holds -- deliberately not 0.0 (retreat starts the
    // instant the swing lands, not one tick after) or 1.0 (would mean
    // retreating literally every tick that isn't the exact swing tick).
    private static final float MELEE_RETREAT_CHARGE_THRESHOLD = 0.5f;
    // Purely mechanical "did we actually reach the computed retreat
    // point" tolerance -- shared by bow/melee kiting alike, see this
    // class's own docstring for why this isn't weapon-specific the way
    // the retreat DISTANCE itself is.
    private static final double RETREAT_ARRIVAL_DISTANCE = 0.2;

    /** The live target's entity id, or null when nothing is currently being fought -- Hands needs the real Entity reference itself (to call gameMode.attack(player, entity)), not just a position, so a position-only publish the way FOLLOW does isn't enough here. */
    public static final BlackboardKey<Integer> TARGET_ENTITY_ID = new BlackboardKey<>("TARGET_ENTITY_ID");

    /** WeaponSelector's own Choice for the CURRENT tick, or null if nothing beats bare hands -- see this class's own docstring for why this is computed once here and shared. */
    public static final BlackboardKey<WeaponSelector.Choice> SELECTED_WEAPON = new BlackboardKey<>("SELECTED_WEAPON");

    /** Publishes real, freshly-computed NAV_TARGET/NAV_ARRIVED/TARGET_ENTITY_ID/SELECTED_WEAPON for `target` -- call every tick (including the tick a fight is first entered, from onEnter, not just onTick -- see PlayerIntentionKillNode's own docstring for the live bug that skipping onEnter caused: a newly-entered node's onTick doesn't run until the NEXT tick, so waiting for it leaves stale data from whatever PlayerIntention state this interrupted visible for one real tick). */
    public static void publish(final TickContext ctx, final Entity target) {
        WeaponSelector.Choice weapon = WeaponSelector.choose(ctx.player);
        ctx.blackboard.put(SELECTED_WEAPON, weapon);
        ctx.blackboard.put(TARGET_ENTITY_ID, target.getId());

        boolean usingBow = weapon != null && weapon.kind() == WeaponSelector.Kind.BOW;
        double meleeRange = ctx.player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
        double range = usingBow ? BOW_RANGE : meleeRange;
        Vec3 targetPosition = target.position();
        double distanceToTarget = ctx.player.position().distanceTo(targetPosition);

        boolean shouldRetreat = usingBow
            ? distanceToTarget < meleeRange
            : distanceToTarget <= meleeRange && ctx.player.getAttackStrengthScale(0.0f) < MELEE_RETREAT_CHARGE_THRESHOLD;

        Vec3 navPosition;
        boolean withinRange;
        if (shouldRetreat) {
            double retreatDistance = usingBow ? BOW_RETREAT_DISTANCE : meleeRange;
            navPosition = retreatPoint(ctx, targetPosition, retreatDistance);
            withinRange = ctx.player.position().distanceTo(navPosition) <= RETREAT_ARRIVAL_DISTANCE;
        } else {
            navPosition = targetPosition;
            withinRange = distanceToTarget <= range;
        }

        ctx.blackboard.put(NavIntent.NAV_TARGET, new NavIntent.Target(navPosition, shouldRetreat ? RETREAT_ARRIVAL_DISTANCE : range));
        ctx.blackboard.put(NavIntent.NAV_ARRIVED, withinRange);
    }

    /** Clears every fact this class publishes -- call from onExit whenever a fighting state is actually left (not on every "no target this tick" case -- see PlayerIntentionDefendNode's own docstring for why it distinguishes "temporarily no threat, still defending" from "genuinely leaving the fight"). */
    public static void clear(final TickContext ctx) {
        ctx.blackboard.put(NavIntent.NAV_TARGET, null);
        ctx.blackboard.put(NavIntent.NAV_ARRIVED, true);
        ctx.blackboard.put(TARGET_ENTITY_ID, null);
        ctx.blackboard.put(SELECTED_WEAPON, null);
    }

    /** A point `distance` blocks directly away from `targetPosition`, along the current player<->target separation vector -- same real formula the old pre-deletion tickAttack's own kiting logic used. Falls back to the player's current facing direction if the target is standing exactly on top of the bot (a zero-length separation vector has no defined "away"). */
    private static Vec3 retreatPoint(final TickContext ctx, final Vec3 targetPosition, final double distance) {
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
        return new Vec3(ctx.player.getX() + awayX * distance, ctx.player.getY(), ctx.player.getZ() + awayZ * distance);
    }
}
