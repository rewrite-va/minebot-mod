package minebot.mod.statemachine.playerintention;

/**
 * What the player last told the bot to do, via chat/control-channel
 * command -- distinct from PlayerIntentionState (what the bot is ACTUALLY doing
 * right now, which can differ from intention while KILL is interrupting
 * it). Deliberately event-driven, not tick-derived: this only changes the
 * moment a real Command.Follow/Command.Defend/Command.Stop arrives (see
 * MinebotMod.dispatchMessage, the one place this is written), and stays
 * exactly as-is through any number of ticks/interruptions in between --
 * there's no "intention expires" or "intention re-derived from current
 * state" concept. Notably, this includes death/respawn -- see
 * DeathWatcher's own docstring for why dying no longer touches this at
 * all (an earlier version routed death through this axis and a stale,
 * unrelated bug exploited the resulting brief window to silently
 * overwrite the resumed intention -- confirmed live).
 *
 * KILL (Command.Kill/!kill) is deliberately NOT a PlayerIntention value
 * -- it's a one-shot TRIGGER that interrupts PlayerIntention into KILL the same
 * way low health used to trigger the now-deleted SELF_HEAL, not a
 * standing goal the player is describing. The underlying intention (IDLE/
 * FOLLOW/DEFEND) never changes just because a kill was issued; once the
 * target's dead (or the fight's abandoned), KILL resumes straight back to
 * whatever intention already said -- see KillTask's own
 * docstring.
 *
 * DEFEND (Command.Defend/!defend), unlike KILL, IS a real standing
 * PlayerIntention value -- "protect this entity from hostiles" is a
 * goal the player is describing, exactly like "follow this player" is,
 * not an incidental interruption. See PlayerIntentionDefendNode's own docstring
 * for how it auto-triggers fighting (reusing CombatEngagement, the same
 * shared engagement logic KILL uses) without ever leaving DEFEND itself.
 *
 * This is what KILL's own exit edge resumes TO, instead of a node-tracked
 * one-hop-back stateToResume -- see KillTask's own docstring for the
 * multi-hop bug an earlier per-node stateToResume approach had (dying
 * mid-fight would lose the real FOLLOW/DEFEND target through a multi-hop
 * interruption chain -- moot now that death isn't even one of the hops
 * anymore, but the same resume-to-intention shape is still what KILL
 * uses). The player's intention is constant ("follow that player",
 * "defend that player") regardless of how many times something
 * incidental (a kill trigger, or now-invisible-to-this-axis death)
 * interrupts it -- only another real command changes it, matching how a
 * human would describe their own goal ("I told it to defend me, dying
 * doesn't change that").
 *
 * A single volatile reference to an immutable record -- same reasoning
 * ControlState's own plain volatiles already rely on (a torn read of a
 * reference assignment is not a real concern on real JVMs, and this is
 * one field swapping atomically, not several fields that need to change
 * together) -- written from the WebSocket thread (dispatchMessage),
 * read from the client tick thread (PlayerIntentionStateMachine's edges). No
 * queueing/draining needed the way CommandBus needs for Commands:
 * intention has no "since last tick" batching concept, only a current
 * value.
 */
public final class PlayerIntention {
    /**
     * state is IDLE, FOLLOW, or DEFEND -- never KILL, which is always
     * incidental, never player-intended. followPlayerName is only
     * meaningful when state == FOLLOW. defendTargetPlayerName is only
     * meaningful when state == DEFEND -- null means "defend the bot
     * itself" (no argument given to !defend), matching Command.Defend's
     * own shape.
     *
     * Carries the target's real player NAME, not a pre-resolved entity id
     * or UUID -- per explicit direction ("python sends only playernames,
     * we need to make all classes to use playernames as the source of
     * truth"): a name is stable for the bot's entire session and never
     * "goes stale" the way a resolved id/uuid/position snapshot can.
     * PlayerIntentionFollowNode/PlayerIntentionDefendNode re-resolve
     * whatever they actually need (a live entity id, or a UUID for
     * WaypointFinder) FRESH every tick via PlayerController (see its own
     * docstring for exactly which real Minecraft API backs each kind of
     * lookup, and why no single API answers all of them) -- this record
     * itself never holds an id/uuid at all, so there's nothing here that
     * can ever go stale between commands.
     */
    public record Snapshot(PlayerIntentionState state, String followPlayerName, String defendTargetPlayerName) {
        public static final Snapshot IDLE = new Snapshot(PlayerIntentionState.IDLE, null, null);
    }

    private volatile Snapshot current = Snapshot.IDLE;

    public void follow(final String playerName) {
        current = new Snapshot(PlayerIntentionState.FOLLOW, playerName, null);
    }

    /** `defendTargetPlayerName` null means "defend the bot itself" -- see Snapshot's own docstring. */
    public void defend(final String defendTargetPlayerName) {
        current = new Snapshot(PlayerIntentionState.DEFEND, null, defendTargetPlayerName);
    }

    public void stop() {
        current = Snapshot.IDLE;
    }

    public Snapshot current() {
        return current;
    }
}
