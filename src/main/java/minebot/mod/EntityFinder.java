package minebot.mod;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.entity.animal.polarbear.PolarBear;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Finds the nearest entity of a given registry type ("minecraft:cow",
 * "minecraft:zombie", ...) within a search radius -- restored (see git
 * history for the original, deleted during the mass command-removal
 * along with everything else not yet backed by a real SM) to back
 * PlayerIntention:KILL's own target resolution for !kill (and PlayerIntention:DEFEND's
 * own nearest-hostile threat scan), the same real client-side "scan all
 * loaded entities, pick nearest match" approach Command.Kill's own
 * docstring calls for (Python has no non-player entity tracking to
 * resolve a mob-type query itself, unlike Follow's player-name lookup
 * via EntityTracker).
 *
 * Not under pathfinding/ -- unlike BlockFinder, this isn't a movement-
 * cost concern, just a target-resolution query.
 */
public final class EntityFinder {
    private EntityFinder() {
    }

    /**
     * Returns the nearest entity matching `entityType` (its full
     * registry id, e.g. "minecraft:cow") within `radius` blocks of
     * `center`, or null if none is found.
     *
     * getEntitiesOfClass's AABB parameter is a cube, not a sphere -- a
     * radius-N box has corner points further than N away in true
     * (Euclidean) distance, so results are filtered by real
     * distanceToSqr against radius^2 as well, not just trusted from the
     * box scan, to match "search radius" meaning an actual sphere.
     */
    public static Entity findNearestEntity(final ClientLevel level, final Vec3 center, final String entityType, final double radius) {
        AABB searchBox = AABB.ofSize(center, radius * 2, radius * 2, radius * 2);
        double radiusSquared = radius * radius;

        Entity nearest = null;
        double nearestDistanceSquared = Double.MAX_VALUE;

        for (Entity entity : level.getEntitiesOfClass(Entity.class, searchBox, e -> matchesType(e, entityType))) {
            double distanceSquared = entity.distanceToSqr(center);
            if (distanceSquared > radiusSquared) {
                continue;
            }
            if (distanceSquared < nearestDistanceSquared) {
                nearest = entity;
                nearestDistanceSquared = distanceSquared;
            }
        }

        return nearest;
    }

    private static boolean matchesType(final Entity entity, final String entityType) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals(entityType);
    }

    /**
     * Returns the nearest real hostile entity within `radius` blocks of
     * `center` -- backs a bare `!kill` with no query (PlayerIntention:KILL's
     * own "attack the nearest hostile mob" ask), and PlayerIntention:DEFEND's
     * own continuous threat scan around the defend target. Same
     * real-sphere-vs-AABB-box distance filtering as findNearestEntity
     * above. "Hostile" is isHostileNow(entity) below, not just
     * `instanceof Enemy` -- see its own docstring for why a live-anger
     * check on top of the marker interface is needed (a peaceful wolf/
     * bear/bee isn't a threat, but an angered one is, and neither
     * implements Enemy at all in vanilla).
     */
    public static Entity findNearestHostile(final ClientLevel level, final Vec3 center, final double radius) {
        AABB searchBox = AABB.ofSize(center, radius * 2, radius * 2, radius * 2);
        double radiusSquared = radius * radius;

        Entity nearest = null;
        double nearestDistanceSquared = Double.MAX_VALUE;

        for (Entity entity : level.getEntitiesOfClass(Entity.class, searchBox, EntityFinder::isHostileNow)) {
            double distanceSquared = entity.distanceToSqr(center);
            if (distanceSquared > radiusSquared) {
                continue;
            }
            if (distanceSquared < nearestDistanceSquared) {
                nearest = entity;
                nearestDistanceSquared = distanceSquared;
            }
        }

        return nearest;
    }

    /**
     * Same as findNearestHostile, but additionally requires a clear
     * eye-to-eye line of sight from `player` -- added specifically for
     * PlayerIntentionDefendNode (see its own docstring), which was
     * locking onto and pathing toward hostiles it had no real line to
     * (an underground/behind-a-wall zombie, for instance), sending Legs
     * toward a target it could never actually reach in a straight line.
     * A blocked-but-closer hostile is simply skipped, not preferred over
     * a farther visible one -- "nearest AMONG VISIBLE", never "nearest
     * overall, visibility be damned". Deliberately NOT applied to
     * findNearestHostile's other two callers (PlayerIntentionKillNode's
     * bare `!kill` fallback, TaskController's busy-threat interrupt) --
     * per explicit direction, this is a DEFEND-specific fix, and an
     * explicit `!kill` with no query is allowed to path toward a
     * heard-but-not-yet-seen mob same as before.
     */
    public static Entity findNearestVisibleHostile(final ClientLevel level, final LocalPlayer player, final double radius) {
        Vec3 center = player.position();
        AABB searchBox = AABB.ofSize(center, radius * 2, radius * 2, radius * 2);
        double radiusSquared = radius * radius;

        Entity nearest = null;
        double nearestDistanceSquared = Double.MAX_VALUE;

        for (Entity entity : level.getEntitiesOfClass(Entity.class, searchBox, EntityFinder::isHostileNow)) {
            double distanceSquared = entity.distanceToSqr(center);
            if (distanceSquared > radiusSquared) {
                continue;
            }
            if (distanceSquared < nearestDistanceSquared && hasLineOfSight(level, player, entity)) {
                nearest = entity;
                nearestDistanceSquared = distanceSquared;
            }
        }

        return nearest;
    }

    /** Real eye-to-eye raycast against solid blocks (ClipContext.Block.COLLIDER, matching what actually stops a real attack/arrow) -- MISS means a clear line of sight. Fluids are deliberately not checked (ClipContext.Fluid.NONE) -- water/lava don't block sight the way a solid block does. Same real formula HandsDrawBowNode/HandsDrawCrossbowNode's own hasLineOfSight already use for firing -- copied, not shared, matching this codebase's own established precedent of keeping each short raycast local rather than a shared utility for a two-line check. */
    public static boolean hasLineOfSight(final ClientLevel level, final LocalPlayer player, final Entity target) {
        Vec3 from = player.getEyePosition();
        Vec3 to = target.getEyePosition();
        ClipContext clipContext = new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player);
        BlockHitResult hit = level.clip(clipContext);
        return hit.getType() == HitResult.Type.MISS;
    }

    // How close a wild adult PolarBear needs a baby PolarBear to be
    // before it turns hostile toward nearby players -- matches vanilla's
    // own PolarBearAttackPlayersGoal.canUse() trigger box exactly
    // (getBoundingBox().inflate(8.0, 4.0, 8.0), confirmed via decompiled
    // source), a pure proximity condition that's fully client-computable
    // without needing PolarBear's own (server-only, unsynced) anger
    // field at all.
    private static final double POLAR_BEAR_CUB_ALERT_RADIUS = 8.0;

    /**
     * True for guaranteed-hostile mobs (the vanilla `Enemy` marker
     * interface -- confirmed via decompiled source this is the correct
     * general check, not `instanceof Monster`: most hostiles extend the
     * abstract `Monster` class, which itself implements `Enemy`, but at
     * least one real vanilla hostile (EnderDragon) is an `Enemy` without
     * extending `Monster` at all) OR a normally-neutral mob that's
     * CURRENTLY hostile -- per explicit direction: "some non-hostile can
     * turn hostiles like bears that are near to a baby bear, or wolves
     * when attacked... check if the non-hostiles are actually in hostile
     * mode".
     *
     * Vanilla's own NeutralMob.isAngry() would be the exact, authoritative
     * check, but confirmed via decompiled source that its backing anger
     * field is only client-synced (SynchedEntityData) for Wolf and Bee --
     * PolarBear/IronGolem/ZombifiedPiglin/EnderMan store it as a plain
     * server-only field with no client-visible equivalent, and there's no
     * other generic "has a target"/"is attacking" signal on Mob/
     * LivingEntity to fall back on for those. So this only widens
     * detection for the two mobs where a real client-side signal exists
     * (Wolf, Bee, both via isAngry()) plus PolarBear via the specific
     * client-computable proximity trigger described above (not its real
     * anger state, which stays unreadable) -- IronGolem/ZombifiedPiglin/
     * EnderMan are left as `Enemy`-only for now, same as before this
     * change, since there's genuinely no way to detect their live
     * hostility from the client.
     */
    /**
     * Returns the nearest LivingEntity within `radius` of `center` whose
     * most recent damage was dealt directly by `attacker` within the last
     * ~2 seconds, or null if none -- backs PlayerIntentionDefendNode's
     * "the defended player is attacking something, help them" trigger.
     * Deliberately entity-type-agnostic (no `Enemy`/isHostileNow check at
     * all): per explicit direction, this is NOT about whether vanilla
     * would consider the target hostile (a pig never would, a provoked
     * polar bear only sometimes/unreliably would via isHostileNow's own
     * species-specific heuristics) -- it's purely "did the player we're
     * defending just hit this thing", which the bot should back up
     * regardless of species.
     *
     * LivingEntity.getLastDamageSource() is the real client-synced signal
     * this relies on: confirmed via decompiled source that
     * LivingEntity.handleDamageEvent(DamageSource) -- invoked client-side
     * off the server's damage-event packet, not a server-only field -- sets
     * lastDamageSource/lastDamageStamp, and getLastDamageSource() itself
     * self-expires the value (returns null) once game time - lastDamageStamp
     * exceeds 40 ticks (2s), so no separate staleness tracking is needed
     * here. DamageSource.getEntity() is the "causing entity" -- for a bare-
     * handed/weapon melee hit this is the attacking player directly.
     */
    public static Entity findNearestAttackedByPlayer(final ClientLevel level, final Vec3 center, final Player attacker, final double radius) {
        AABB searchBox = AABB.ofSize(center, radius * 2, radius * 2, radius * 2);
        double radiusSquared = radius * radius;

        Entity nearest = null;
        double nearestDistanceSquared = Double.MAX_VALUE;

        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, searchBox, e -> wasJustAttackedBy(e, attacker))) {
            double distanceSquared = entity.distanceToSqr(center);
            if (distanceSquared > radiusSquared) {
                continue;
            }
            if (distanceSquared < nearestDistanceSquared) {
                nearest = entity;
                nearestDistanceSquared = distanceSquared;
            }
        }

        return nearest;
    }

    private static boolean wasJustAttackedBy(final LivingEntity entity, final Player attacker) {
        if (entity == attacker) {
            return false;
        }
        DamageSource lastDamage = entity.getLastDamageSource();
        return lastDamage != null && lastDamage.getEntity() == attacker;
    }

    private static boolean isHostileNow(final Entity entity) {
        if (entity instanceof Enemy) {
            return true;
        }
        if (entity instanceof Wolf wolf) {
            return wolf.isAngry();
        }
        if (entity instanceof Bee bee) {
            return bee.isAngry();
        }
        if (entity instanceof PolarBear bear && !bear.isBaby()) {
            AABB cubAlertBox = bear.getBoundingBox().inflate(POLAR_BEAR_CUB_ALERT_RADIUS, POLAR_BEAR_CUB_ALERT_RADIUS / 2, POLAR_BEAR_CUB_ALERT_RADIUS);
            return !level(entity).getEntitiesOfClass(PolarBear.class, cubAlertBox, PolarBear::isBaby).isEmpty();
        }
        return false;
    }

    private static ClientLevel level(final Entity entity) {
        return (ClientLevel) entity.level();
    }
}
