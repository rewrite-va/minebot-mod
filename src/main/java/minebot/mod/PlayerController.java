package minebot.mod;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * Resolves a player NAME to whatever a given subsystem actually needs
 * (a live entity id, or a UUID), on demand -- the single place this
 * resolution happens, per explicit direction ("python sends only
 * playernames, we need to make all classes to use playernames as the
 * source of truth, each subsystem that needs a different thing like
 * UUID, we need a class like PlayerController static methods that can do
 * the conversion"). A player name is stable for the whole session (it
 * never "goes stale" the way a resolved entity id/position snapshot
 * can), so it's the one thing worth carrying as standing state
 * (PlayerIntention.Snapshot's own followPlayerName/defendTargetPlayerName)
 * -- entity ids and UUIDs are cheap to re-derive fresh every tick from a
 * name, and re-deriving them fresh is exactly what lets a target that's
 * currently out of entity range (but still resolvable via the tab list)
 * keep working, instead of being stuck with whatever id/uuid happened to
 * be resolved once and never updated.
 *
 * Two genuinely different real-API constraints back the two methods here
 * (see each one's own docstring) -- there is no single Minecraft API that
 * answers "give me X for this player name" for everything at once:
 * - getEntityIdByName needs a real, currently-LOADED Entity (Level's own
 *   entity table is keyed by int id, not name or UUID -- there's no
 *   getEntityByUuid/getEntityByName at all) -- only ever answerable for a
 *   player within normal entity/simulation range right now.
 * - getUuidByName needs the tab list (ClientPacketListener.getPlayerInfo,
 *   confirmed public via decompiled source) -- server-wide, works for
 *   ANY player currently on the server regardless of distance, but only
 *   ever gives a UUID, never a live Entity to interact with.
 */
public final class PlayerController {
    private PlayerController() {
    }

    /**
     * The entity id of the currently-loaded Player named `name`, or null
     * if no such player is currently loaded (out of simulation/render
     * range, or never joined at all) -- scans level.players() by
     * Player.getScoreboardName() (== GameProfile.name(), confirmed via
     * decompiled source), the exact same real display name
     * MinebotMod.broadcastEntityEvents already uses to identify a
     * tracked player over the wire, so this matches whatever name Python's
     * own EntityTracker would have reported too.
     */
    public static Integer getEntityIdByName(final ClientLevel level, final String name) {
        for (Player candidate : level.players()) {
            if (name.equals(candidate.getScoreboardName())) {
                return candidate.getId();
            }
        }
        return null;
    }

    /**
     * The real UUID of the online player named `name`, or null if no such
     * player is currently on the server at all -- via the client's own
     * tab-list sync (ClientPacketListener.getPlayerInfo), a genuinely
     * separate, server-wide channel from normal entity tracking (see this
     * class's own docstring): populated the moment a player joins the
     * server, independent of simulation/render distance entirely. This is
     * what makes "!follow someone never actually seen as a loaded entity
     * this session" resolvable at all -- WaypointFinder.findPosition (a
     * fourth, still-separate channel -- the Locator Bar's own live
     * position stream) needs exactly this UUID as its lookup key.
     */
    public static UUID getUuidByName(final LocalPlayer player, final String name) {
        PlayerInfo info = player.connection.getPlayerInfo(name);
        return info != null ? info.getProfile().id() : null;
    }
}
