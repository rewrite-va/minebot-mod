package minebot.mod;

import minebot.mod.statemachine.BlackboardKey;
import minebot.mod.statemachine.TickContext;
import net.minecraft.world.phys.Vec3;

/**
 * Handles death/respawn directly, outside every peer StateMachine --
 * ticked straight from MinebotMod, not part of PlayerIntention/Legs/Head/
 * Hands at all. Reuses LocalPlayer.isDeadOrDying()/player.respawn() the
 * same way the old always-on RespawnHandler (pre-SM) and, later,
 * PlayerIntentionDeadNode (see git history for both) did -- no client-side
 * Fabric event covers death/respawn, and isDeadOrDying() is itself just
 * getHealth() <= 0 under the hood.
 *
 * Demoted from a real PlayerIntentionState (DEAD, with GO_TO_DEATH_POSITION/
 * PICKUP_ITEMS chained after it) to this standalone watcher per explicit
 * direction, after a real live bug: PlayerIntention:DEAD/GO_TO_DEATH_POSITION/
 * PICKUP_ITEMS used to DISPLACE PlayerIntention's own current state
 * (FOLLOW/DEFEND) for the whole recovery walk, resuming it afterward via
 * intention.current().state() -- correct in principle, but fragile in
 * practice: a stale, unrelated bug on the Python side (MovementController.
 * _following_name never cleared when !defend superseded an earlier
 * !follow) re-issued a stale `follow` command the moment respawn caused
 * the followed player's entity to reappear, silently overwriting the
 * PlayerIntention snapshot back to FOLLOW mid-recovery -- confirmed live
 * ("after respawn, it goes back to follow, instead of defend"). The
 * actual root cause was cross-language and arguably Python's to fix, but
 * it exposed a real architectural smell here too: PlayerIntention's own
 * state should never have needed to be interrupted by death at all --
 * "the player intention should stay on DEFEND" the whole time, since
 * recovering dropped items is a Legs/navigation concern (walking
 * somewhere), not a change in what the player is standing there wanting.
 * Death/respawn now touches ONLY DEATH_POSITION (below); PlayerIntention
 * (IDLE/FOLLOW/DEFEND/KILL) never transitions because of it, so there is
 * nothing for anything to accidentally clobber anymore -- see
 * LegsGoToDeathPositionNode/LegsPickupItemsNode's own docstrings for the
 * Legs-owned recovery chain that replaces DEAD's old downstream states.
 */
public final class DeathWatcher {
    /**
     * Where the player died, captured the instant death was observed
     * (before respawn() moves them) -- non-null is ITSELF the "a fresh
     * death needs reacting to" signal LegsStateMachine's own edge into
     * GO_TO_DEATH_POSITION reads (no separate sequence counter needed):
     * published here on every new death, and explicitly cleared back to
     * null by LegsPickupItemsNode's own onExit once the WHOLE
     * GO_TO_DEATH_POSITION->PICKUP_ITEMS recovery chain is actually done
     * (not by GO_TO_DEATH_POSITION's own onExit -- PICKUP_ITEMS still
     * needs this value to scope its own item search radius, so it has to
     * outlive GO_TO_DEATH_POSITION itself). Read by
     * LegsGoToDeathPositionNode/LegsPickupItemsNode.
     */
    public static final BlackboardKey<Vec3> DEATH_POSITION = new BlackboardKey<>("DEATH_POSITION");

    /** Fires exactly once, the tick death is first observed. */
    public interface DeathListener {
        void onDeath();
    }

    /** Fires exactly once, the tick isDeadOrDying() goes back false (i.e. respawn actually took effect). */
    public interface RespawnListener {
        void onRespawn();
    }

    private final DeathListener onDeath;
    private final RespawnListener onRespawn;

    private boolean wasDead;

    public DeathWatcher(final DeathListener onDeath, final RespawnListener onRespawn) {
        this.onDeath = onDeath;
        this.onRespawn = onRespawn;
    }

    /** Call once per client tick, same as every StateMachine -- not itself a StateMachine (see this class's own docstring for why), so MinebotMod calls this directly rather than through the peer-SM tick loop. */
    public void tick(final TickContext ctx) {
        boolean isDead = ctx.player.isDeadOrDying();
        if (isDead && !wasDead) {
            ctx.blackboard.put(DEATH_POSITION, ctx.player.position());
            onDeath.onDeath();
            ctx.player.respawn();
        } else if (!isDead && wasDead) {
            onRespawn.onRespawn();
        }
        wasDead = isDead;
    }
}
