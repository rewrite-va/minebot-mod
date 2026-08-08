package minebot.mod;

import com.mojang.datafixers.util.Either;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.waypoints.ClientWaypointManager;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.waypoints.TrackedWaypoint;

import java.lang.reflect.Field;
import java.util.UUID;

/**
 * Reads a specific player's live position out of the client's own
 * ClientWaypointManager -- the same real, server-streamed data vanilla's
 * Locator Bar HUD element (net.minecraft.client.gui.contextualbar.
 * LocatorBarRenderer, confirmed via decompiled source) uses to point an
 * arrow at another player who's outside real entity/simulation range.
 * Added per explicit direction ("there is a player compass in the
 * experience bar that does point to another player even if it is far
 * away, could the position be streamed to the client") to back
 * PlayerIntentionFollowNode's own last-known-position fallback with real,
 * continuously-updated data instead of a single stale snapshot from the
 * last tick the target was actually loaded.
 *
 * ClientWaypointManager (via LocalPlayer.connection.getWaypointManager(),
 * confirmed public) is a genuinely separate sync channel from normal
 * entity tracking -- a ClientboundTrackedWaypointPacket keeps updating a
 * player's own TrackedWaypoint entry independently of whether that player
 * is currently a loaded Entity at all, which is exactly what lets a real
 * client's Locator Bar keep pointing at someone who ran out of render
 * distance. Whether a given server actually sends these at all is a
 * server-side opt-in (a real vanilla feature, not something this mod
 * enables) -- findPosition returning null for every player is the
 * expected result on a server that doesn't use it, not a bug in this
 * class.
 *
 * The manager's own public API (WaypointManager/ClientWaypointManager,
 * confirmed via decompiled source) only exposes trackWaypoint/
 * updateWaypoint/untrackWaypoint (mutators) and forEachWaypoint/
 * hasWaypoints -- there is no real "get waypoint by id" lookup, so
 * findPosition has to scan via forEachWaypoint and match TrackedWaypoint.
 * id() (an Either<UUID, String> -- Either.left(UUID) for a real player,
 * confirmed via TrackedWaypoint.setPosition/Vec3iWaypoint's own
 * constructor) against the target's UUID itself.
 *
 * Once matched, the real block position is STILL not reachable through
 * any public method -- TrackedWaypoint.Vec3iWaypoint's own `vector` field
 * (a player waypoint's real coordinates) and TrackedWaypoint.ChunkWaypoint's
 * own `chunkPos` (a coarser, chunk-level variant some servers may send
 * instead) are both private, with no getter; every public method on
 * TrackedWaypoint only computes camera-relative angles for HUD rendering
 * (yawAngleToCamera/pitchDirectionToCamera), never a raw Vec3. Reflection
 * is the only way to actually read it -- same established pattern
 * BlockBreaker.dumpRealDestroyState already uses elsewhere in this
 * codebase for other private-with-no-public-equivalent Mojang fields, per
 * this codebase's own documented precedent (see AGENTS.md's mixin/
 * section -- reflection is used here rather than a Mixin accessor since
 * this is a one-shot read, not a hot per-tick path needing invoker-call
 * performance).
 *
 * A THIRD real waypoint shape, AzimuthWaypoint, carries no position at
 * all -- just a `float angle` (a real world-space yaw in radians,
 * confirmed via decompiled source: yawAngleToCamera converts it with
 * `angle * (180/pi)` before comparing against the camera's own yaw) --
 * confirmed live this is what a server sends for a player it hasn't
 * tracked closely enough to know an exact position for (e.g. never seen
 * this session at all), exactly matching what the Locator Bar HUD itself
 * shows in that case: a direction arrow, no distance. findPosition
 * projects this into a real, plannable Vec3 (see PROJECTION_DISTANCE's
 * own docstring) rather than returning null outright -- per explicit
 * direction ("implement azimuth projection to a far (but still inside
 * simulation so A* can compute a path) point"), since a direction-only
 * signal is still real, useful information (the same information a human
 * reading their own compass would act on), not nothing.
 */
public final class WaypointFinder {
    private WaypointFinder() {
    }

    // How far to project a direction-only AzimuthWaypoint's own angle
    // into a real, plannable Vec3 -- deliberately NOT the real (unknown)
    // distance to the target, just far enough to make meaningful walking
    // progress per replan while staying safely within normal loaded-chunk
    // range regardless of server view-distance settings (Movements.
    // getBlock treats any unloaded block as BLOCKED/unknown, so
    // projecting further than what's actually loaded would just make A*
    // fail or path toward the edge of loaded terrain instead of the real
    // target direction). Matches CombatEngagement.SEARCH_RADIUS's own
    // existing "far but safely local" convention rather than inventing a
    // new tunable. Re-projected fresh every findPosition call as the
    // real azimuth keeps updating live while the bot walks, so this
    // self-corrects tick by tick rather than committing to one static
    // point -- and stops being used at all the moment the server upgrades
    // this player to a real Vec3iWaypoint (a closer, more precisely
    // tracked case), since findPosition always prefers a real position
    // over a projected one when one is available.
    private static final double PROJECTION_DISTANCE = 32.0;

    private static Field vec3iVectorField;
    private static Field chunkPosField;
    private static Field azimuthAngleField;
    private static boolean reflectionFailed;

    /**
     * The real (or, for an AzimuthWaypoint, projected -- see class
     * docstring) position of `targetUuid`'s own tracked waypoint, or null
     * if the server isn't streaming one for them right now at all (never
     * tracked in any form, or this server doesn't use the Locator Bar
     * feature). A ChunkWaypoint (some servers send only chunk-granularity,
     * not exact position) resolves to that chunk's own center at the
     * bot's current Y -- coarser than a real Vec3iWaypoint, but still far
     * better than nothing for "which general direction is this person"
     * purposes.
     */
    public static Vec3 findPosition(final LocalPlayer player, final UUID targetUuid) {
        if (reflectionFailed) {
            return null;
        }
        ClientWaypointManager manager = player.connection.getWaypointManager();
        TrackedWaypoint[] match = new TrackedWaypoint[1];
        manager.forEachWaypoint(player, waypoint -> {
            if (match[0] == null && matchesUuid(waypoint, targetUuid)) {
                match[0] = waypoint;
            }
        });
        if (match[0] == null) {
            return null;
        }
        return extractPosition(match[0], player);
    }

    private static boolean matchesUuid(final TrackedWaypoint waypoint, final UUID targetUuid) {
        return waypoint.id().left().map(targetUuid::equals).orElse(false);
    }

    private static Vec3 extractPosition(final TrackedWaypoint waypoint, final LocalPlayer player) {
        try {
            String className = waypoint.getClass().getSimpleName();
            if (className.equals("Vec3iWaypoint")) {
                if (vec3iVectorField == null) {
                    vec3iVectorField = waypoint.getClass().getDeclaredField("vector");
                    vec3iVectorField.setAccessible(true);
                }
                Vec3i vector = (Vec3i) vec3iVectorField.get(waypoint);
                return Vec3.atCenterOf(vector);
            }
            if (className.equals("ChunkWaypoint")) {
                if (chunkPosField == null) {
                    chunkPosField = waypoint.getClass().getDeclaredField("chunkPos");
                    chunkPosField.setAccessible(true);
                }
                net.minecraft.world.level.ChunkPos chunkPos = (net.minecraft.world.level.ChunkPos) chunkPosField.get(waypoint);
                return Vec3.atCenterOf(chunkPos.getMiddleBlockPosition(player.getBlockY()));
            }
            if (className.equals("AzimuthWaypoint")) {
                if (azimuthAngleField == null) {
                    azimuthAngleField = waypoint.getClass().getDeclaredField("angle");
                    azimuthAngleField.setAccessible(true);
                }
                float angleRadians = (float) azimuthAngleField.get(waypoint);
                return projectAlongAzimuth(player, angleRadians);
            }
            // EmptyWaypoint -- genuinely nothing to extract.
            return null;
        } catch (ReflectiveOperationException | ClassCastException e) {
            reflectionFailed = true;
            MinebotMod.LOGGER.warn("waypoint: extractPosition reflection failed, disabling further attempts", e);
            return null;
        }
    }

    /**
     * Projects `angleRadians` (a real world-space yaw, see class
     * docstring) into a Vec3 PROJECTION_DISTANCE blocks from the bot's
     * OWN current position -- same yaw convention this codebase's own
     * atan2(-dx, dz) formula (LegsNavigateNode/BlockBreaker/etc.) uses
     * throughout (confirmed via decompiled Entity.calculateViewVector:
     * yaw 0 = south/+z), so this is the exact inverse of that formula
     * (dx = -sin(yaw), dz = cos(yaw)) rather than a different convention
     * that would send the bot the wrong way.
     */
    private static Vec3 projectAlongAzimuth(final LocalPlayer player, final float angleRadians) {
        double dx = -Math.sin(angleRadians) * PROJECTION_DISTANCE;
        double dz = Math.cos(angleRadians) * PROJECTION_DISTANCE;
        return new Vec3(player.getX() + dx, player.getY(), player.getZ() + dz);
    }
}
