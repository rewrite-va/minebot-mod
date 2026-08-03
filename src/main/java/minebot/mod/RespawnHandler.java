package minebot.mod;

import minebot.mod.util.EdgeTrigger;
import net.minecraft.client.player.LocalPlayer;

/**
 * Detects death and auto-respawns -- there's no client-side Fabric API
 * event for either (confirmed: fabric-entity-events-v1's
 * ServerPlayerEvents.AFTER_RESPAWN/JOIN/LEAVE/ALLOW_DEATH all take
 * ServerPlayer, server-side only, unusable here since minebot-mod isn't
 * the server; fabric-lifecycle-events-v1's ClientEntityEvents only covers
 * ENTITY_LOAD/UNLOAD), so this polls every tick the same way MinebotMod
 * already polls health for broadcastHealthEvent -- LivingEntity.
 * isDeadOrDying() is itself just `getHealth() <= 0` under the hood
 * (confirmed via decompiled bytecode), so this is really the same signal
 * already being read every tick, just interpreted as an edge instead of a
 * value-changed check.
 *
 * Without this, a dead bot just sits on the death screen forever -- the
 * game client doesn't respawn on its own, a human has to click the
 * Respawn button. LocalPlayer.respawn() sends the exact same
 * ServerboundClientCommandPacket(PERFORM_RESPAWN) that button's onPress
 * does (confirmed via decompiled bytecode), so calling it directly here
 * skips the screen/button entirely -- no GUI interaction needed.
 */
public final class RespawnHandler {
    private final EdgeTrigger dead = new EdgeTrigger();
    private final EdgeTrigger alive = new EdgeTrigger();

    // alive's trigger starts armed, so its very first fire(true) on tick
    // one (before any death has ever happened) would otherwise read as a
    // "respawn" -- this only lets onRespawn fire once a death has
    // actually been observed first.
    private boolean hasDied = false;

    public interface DeathListener {
        void onDeath();
    }

    public interface RespawnListener {
        void onRespawn();
    }

    private final DeathListener onDeath;
    private final RespawnListener onRespawn;

    public RespawnHandler(final DeathListener onDeath, final RespawnListener onRespawn) {
        this.onDeath = onDeath;
        this.onRespawn = onRespawn;
    }

    /** Safe to call every tick. */
    public void tick(final LocalPlayer player) {
        boolean isDead = player.isDeadOrDying();

        if (dead.fire(isDead)) {
            hasDied = true;
            onDeath.onDeath();
            player.respawn();
        }

        // alive's rising edge is the opposite of dead's: fires once when
        // isDeadOrDying() goes back to false (successfully respawned),
        // then stays quiet until the next death. A separate trigger (not
        // just inverting dead's own state) since each needs its own
        // independent "already fired this edge" bookkeeping.
        if (alive.fire(!isDead) && hasDied) {
            onRespawn.onRespawn();
        }
    }
}
