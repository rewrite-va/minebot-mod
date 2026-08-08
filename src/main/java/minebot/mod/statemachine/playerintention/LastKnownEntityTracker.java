package minebot.mod.statemachine.playerintention;

import minebot.mod.PlayerController;
import minebot.mod.WaypointFinder;
import minebot.mod.statemachine.TickContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.UUID;

/**
 * Shared "keep tracking a specific PLAYER NAME's position, live or not"
 * logic -- extracted out of PlayerIntentionFollowNode (where this was
 * first built) so PlayerIntentionDefendNode's own defendAnchor could
 * reuse the exact same fallback chain instead of duplicating it, per
 * explicit direction ("is there any code we can encapsulate?, if I later
 * fix this it should affect defend") -- both FOLLOW and DEFEND need
 * identical "stay near/walk toward a specific player" tracking, and a
 * future fix to this logic (a better fallback, a smarter staleness
 * check, etc.) should only ever need to happen in one place.
 *
 * Keyed off a player NAME, not a pre-resolved entity id -- per explicit
 * direction ("python sends only playernames, we need to make all classes
 * to use playernames as the source of truth... each subsystem that needs
 * a different thing like UUID, we need a class like PlayerController...
 * that can do the conversion"). PlayerController is the one place that
 * actually resolves a name to whatever a given real Minecraft API needs
 * (see its own docstring for exactly which API backs which lookup) --
 * this class just calls it fresh every resolve(), so nothing here ever
 * holds a resolved id/uuid as standing state that could go stale; only
 * the name itself (immutable, never re-resolved) and a position snapshot
 * (explicitly allowed to be stale, see tier 3 below) persist across
 * calls.
 *
 * Four-tier fallback, resolved fresh every resolve() call:
 * 1. A real, currently-loaded Entity, via PlayerController.
 *    getEntityIdByName + ctx.level.getEntity -- the normal, fully-live
 *    case.
 * 2. WaypointFinder's own live Locator Bar data (see its own docstring),
 *    keyed off a UUID resolved THIS tick via PlayerController.
 *    getUuidByName -- covers a target that's out of entity range right
 *    now but still on the server (works even if this is the very FIRST
 *    tick this tracker has ever looked at this name, unlike a UUID
 *    captured only from a prior live sighting -- the tab list is a
 *    server-wide channel, not proximity-gated at all).
 * 3. A plain last-known POSITION snapshot from whenever this tracker last
 *    got a real answer from tier 1 or 2 -- the final fallback once
 *    NEITHER of the above has anything right now, still better than
 *    immediately giving up (e.g. the target just left the server
 *    entirely, so even the tab list has nothing, but they were
 *    physically standing somewhere a moment ago).
 * 4. Null -- this tracker has genuinely never resolved this name to
 *    anything at all.
 *
 * Each PlayerIntention node that needs this (currently
 * PlayerIntentionFollowNode/PlayerIntentionDefendNode) owns its OWN
 * instance -- this class holds no static/shared state, so FOLLOW and
 * DEFEND tracking a different (or even the same) player never interfere
 * with each other.
 */
public final class LastKnownEntityTracker {
    private Vec3 lastKnownPosition;
    private String trackedForName;

    /**
     * Must be called whenever the caller's own target name might have
     * changed (both onEnter and the top of onTick -- see
     * PlayerIntentionFollowNode/PlayerIntentionDefendNode's own call
     * sites for why both are needed: PlayerIntention can update its
     * current() target without a real state transition when the node was
     * already active). Clears all remembered state the instant `name`
     * differs from whatever this tracker last saw -- a stale position
     * from a PREVIOUS target must never leak into tracking a new one.
     */
    public void resetIfTargetChanged(final String name) {
        if (!Objects.equals(name, trackedForName)) {
            lastKnownPosition = null;
            trackedForName = name;
        }
    }

    /** Full reset, regardless of target name -- for a node's own onExit (see call sites). */
    public void reset() {
        lastKnownPosition = null;
        trackedForName = null;
    }

    /**
     * The best currently-available position for the player named `name`,
     * or null if this tracker has genuinely never resolved them to
     * anything (see class docstring for the full four-tier fallback
     * order). Also returns the resolved live Entity itself (via the
     * returned Resolution, not a separate call) for callers that need it
     * for something beyond position (e.g. PlayerIntentionDefendNode's own
     * findNearestAttackedByPlayer, which needs the real Player instance,
     * not just a Vec3 -- see its own call site for why THAT specific
     * lookup deliberately does NOT go through this tracker at all).
     */
    public Resolution resolve(final TickContext ctx, final String name) {
        Integer entityId = PlayerController.getEntityIdByName(ctx.level, name);
        Entity target = entityId != null ? ctx.level.getEntity(entityId) : null;
        if (target != null) {
            lastKnownPosition = target.position();
            return new Resolution(target, lastKnownPosition);
        }

        UUID uuid = PlayerController.getUuidByName(ctx.player, name);
        Vec3 waypointPosition = uuid != null ? WaypointFinder.findPosition(ctx.player, uuid) : null;
        if (waypointPosition != null) {
            lastKnownPosition = waypointPosition;
            return new Resolution(null, waypointPosition);
        }

        return new Resolution(null, lastKnownPosition);
    }

    /** result of a resolve() call -- entity() is non-null only in the fully-live case (tier 1); position() is null only when this tracker has nothing at all for the target yet. */
    public record Resolution(Entity entity, Vec3 position) {
    }
}
