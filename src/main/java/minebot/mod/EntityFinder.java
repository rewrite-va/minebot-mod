package minebot.mod;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Finds the nearest entity of a given registry type ("minecraft:cow",
 * "minecraft:zombie", ...) within a search radius -- backs the !find/
 * !searchForEntity command. Unlike MinebotMod.broadcastEntityEvents
 * (which continuously tracks *players only*, for !follow's sake), this
 * is a one-shot on-demand lookup over ALL entity types, only run in
 * response to an explicit command -- not something that needs
 * per-tick broadcast/diffing the way the player list does.
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
     * Returns the nearest real hostile entity (implements the vanilla
     * `Enemy` marker interface -- confirmed via decompiled source this is
     * the correct general check, not `instanceof Monster`: most hostiles
     * extend the abstract `Monster` class, which itself implements
     * `Enemy`, but at least one real vanilla hostile (EnderDragon) is an
     * `Enemy` without extending `Monster` at all, so checking the
     * interface directly covers both without needing a second case)
     * within `radius` blocks of `center` -- backs a bare `!attack` with
     * no query, per PENDING.md's "!attack with no argument attacks the
     * nearest hostile mob" ask. Same real-sphere-vs-AABB-box distance
     * filtering as findNearestEntity above.
     */
    public static Entity findNearestHostile(final ClientLevel level, final Vec3 center, final double radius) {
        AABB searchBox = AABB.ofSize(center, radius * 2, radius * 2, radius * 2);
        double radiusSquared = radius * radius;

        Entity nearest = null;
        double nearestDistanceSquared = Double.MAX_VALUE;

        for (Entity entity : level.getEntitiesOfClass(Entity.class, searchBox, e -> e instanceof Enemy)) {
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
}
