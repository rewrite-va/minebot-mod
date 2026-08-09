package minebot.mod.statemachine.playerintention;

import minebot.mod.InventoryController;
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
 * SELECTED_WEAPON is computed ONCE per tick via InventoryController.
 * findBestWeapon(player, distanceToTarget) -- every consumer reads the
 * SAME published value rather than independently re-calling
 * findBestWeapon() itself. distanceToTarget is computed here (the real
 * live player<->target distance, same value used for the kiting decision
 * just below) and fed into findBestWeapon so it can drop any candidate
 * that can't actually reach the target from here and rank the survivors
 * by real damage -- see findBestWeapon's own docstring for why this
 * replaced the old "always prefer a bow with ammo" rule: at melee
 * distance, whichever weapon deals more damage should win, which is
 * usually the melee weapon. Deliberately NOT a bundled "CombatContext"
 * struct grouping target+weapon+everything -- considered and rejected:
 * every other cross-SM fact in this codebase is its own independently-
 * typed BlackboardKey, and a single aggregate object would be a new,
 * different data-modeling convention just to save one
 * InventoryController.findBestWeapon() call's worth of duplication.
 * Re-computed every tick (not cached once on entry) specifically so a
 * weapon breaking mid-fight -- or the target's distance simply changing
 * -- is picked up immediately: InventoryController.findBestWeapon() is a
 * pure, stateless read of current inventory + distance, so there's no
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
 * BOW KITING (same treatment for a crossbow -- both are Kind.BOW/
 * Kind.CROSSBOW under the single usingRanged flag below, see
 * InventoryController.findBestWeapon's own docstring for why they're a
 * single reach category): unchanged trigger (target has closed inside
 * melee reach while a ranged weapon is selected) but a real, modest
 * BOW_RETREAT_DISTANCE (5) instead of retreating all the way back to the
 * full BOW_RANGE (15) -- ranged range "can be at any distance" (a bow/
 * crossbow can still fire effectively
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

    // Raised from the original 16.0 per explicit direction (default/auto-
    // defend engagement was too short-ranged in practice) -- still well
    // under AbstractSkeleton/AbstractIllager's own much larger real
    // "nearest hostile" scan radius elsewhere in vanilla.
    public static final double SEARCH_RADIUS = 32.0;
    // BowItem.DEFAULT_RANGE == CrossbowItem.DEFAULT_RANGE (confirmed via
    // decompiled source, same value the old pre-deletion tickAttack's own
    // ATTACK_BOW_RANGE used) -- the real distance vanilla itself
    // considers either ranged weapon's effective range.
    private static final double BOW_RANGE = 15.0;
    // How far a bow/crossbow retreats to once the target's closed inside
    // melee reach -- NOT the full BOW_RANGE (see this class's own
    // docstring for why re-opening the whole 15 blocks every time isn't
    // necessary).
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

    /** InventoryController's own Choice for the CURRENT tick, or null if nothing beats bare hands -- see this class's own docstring for why this is computed once here and shared. */
    public static final BlackboardKey<InventoryController.Choice> SELECTED_WEAPON = new BlackboardKey<>("SELECTED_WEAPON");

    /**
     * True on exactly the one tick TARGET_ENTITY_ID transitions from
     * non-null to null (a fight just ended), false every other tick --
     * backs LegsStateMachine's own post-fight PICKUP_ITEMS trigger (see
     * its own docstring). Computed here, by tickEdgeDetection() below,
     * rather than as a plain Predicate closure living on Legs itself (an
     * earlier version did exactly that) -- confirmed that approach has a
     * real gap: Predicate.test() there only ever runs when Legs evaluates
     * an edge FROM its own current state, so it silently misses every
     * fight-ends-while-Legs-is-elsewhere case (FLEE, GO_TO_DEATH_POSITION,
     * PICKUP_ITEMS already fighting-adjacent) -- e.g. a fight ending while
     * Legs is fleeing on low health would never observe the transition,
     * leaving the "was fighting" bit stale for whatever real transition
     * happens to be checked next, misfiring or missing entirely. Fixed by
     * moving detection here and ticking it unconditionally every real
     * tick from MinebotMod's own top-level loop (same standalone-tick
     * precedent DeathWatcher already established for exactly this reason
     * -- see its own docstring), regardless of what any SM's current
     * state is.
     */
    public static final BlackboardKey<Boolean> FIGHT_JUST_ENDED = new BlackboardKey<>("FIGHT_JUST_ENDED");

    private static boolean wasFighting;

    /** Call once per real tick, unconditionally, after PlayerIntention's own StateMachine.tick() (so this observes that tick's freshly-published TARGET_ENTITY_ID) and before Legs' (so Legs sees FIGHT_JUST_ENDED the same tick it actually happens, not one tick late) -- see FIGHT_JUST_ENDED's own docstring for why this can't just live as a Predicate closure on Legs instead. */
    public static void tickEdgeDetection(final TickContext ctx) {
        boolean fightingNow = ctx.blackboard.get(TARGET_ENTITY_ID) != null;
        ctx.blackboard.put(FIGHT_JUST_ENDED, wasFighting && !fightingNow);
        wasFighting = fightingNow;
    }

    /** Publishes real, freshly-computed NAV_TARGET/NAV_ARRIVED/TARGET_ENTITY_ID/SELECTED_WEAPON for `target` -- call every tick (including the tick a fight is first entered, from onEnter, not just onTick -- see PlayerIntentionKillNode's own docstring for the live bug that skipping onEnter caused: a newly-entered node's onTick doesn't run until the NEXT tick, so waiting for it leaves stale data from whatever PlayerIntention state this interrupted visible for one real tick). */
    public static void publish(final TickContext ctx, final Entity target) {
        Vec3 targetPosition = target.position();
        double distanceToTarget = ctx.player.position().distanceTo(targetPosition);

        InventoryController.Choice weapon = InventoryController.findBestWeapon(ctx.player, distanceToTarget, target);
        ctx.blackboard.put(SELECTED_WEAPON, weapon);
        ctx.blackboard.put(TARGET_ENTITY_ID, target.getId());

        boolean usingRanged = weapon != null
            && (weapon.kind() == InventoryController.Kind.BOW || weapon.kind() == InventoryController.Kind.CROSSBOW);
        double meleeRange = ctx.player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
        double range = usingRanged ? BOW_RANGE : meleeRange;

        boolean shouldRetreat = usingRanged
            ? distanceToTarget < meleeRange
            : distanceToTarget <= meleeRange && ctx.player.getAttackStrengthScale(0.0f) < MELEE_RETREAT_CHARGE_THRESHOLD;

        Vec3 navPosition;
        boolean withinRange;
        if (shouldRetreat) {
            double retreatDistance = usingRanged ? BOW_RETREAT_DISTANCE : meleeRange;
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
