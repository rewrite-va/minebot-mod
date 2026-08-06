package minebot.mod.statemachine.playerintention;

/**
 * The bot's overall behavioral intent -- one of the peer axes described
 * in STATE_MACHINE.md (no hierarchy over Legs/Hands/Head; this is not a
 * "parent" state machine). Renamed from GeneralState (see git history)
 * once "General" as a name started inviting unrelated concerns to be
 * dumped into this axis just because they didn't obviously belong
 * anywhere else -- PlayerIntentionState is what this axis actually
 * tracks: the player's own standing goal (IDLE/FOLLOW/DEFEND), plus KILL
 * as the one incidental trigger that still lives here. Built node by
 * node, not all at once (see STATE_MACHINE.md's "Implementation order");
 * more states (FARMING, BUILDING, ...) get added as real behaviors that
 * need them are ported in.
 *
 * DEAD/GO_TO_DEATH_POSITION/PICKUP_ITEMS are deliberately NOT states on
 * this axis (an earlier version had all three here -- see git history) --
 * moved to DeathWatcher (death/respawn itself, ticked standalone outside
 * every peer SM) and LegsState (GO_TO_DEATH_POSITION/PICKUP_ITEMS, real
 * navigation concerns) respectively, after a real live bug this caused:
 * the old DEAD->GO_TO_DEATH_POSITION->PICKUP_ITEMS chain DISPLACED
 * PlayerIntention's own current state for the whole recovery walk,
 * resuming FOLLOW/DEFEND/IDLE afterward via intention.current().state()
 * -- correct in principle, but it meant PlayerIntention's real value was
 * briefly unobservable/interruptible during recovery, and a stale,
 * unrelated Python-side bug (a name-tracked auto-re-follow that never
 * got cleared when !defend superseded an earlier !follow) exploited
 * exactly that window, silently overwriting the resumed intention back
 * to FOLLOW the moment respawn caused the followed player's entity to
 * reappear -- confirmed live ("after respawn, it goes back to follow,
 * instead of defend"). Per explicit direction: "player intention is the
 * SM that tracks the player intention, after death, the player intention
 * should stay on DEFEND" -- death/respawn now touches ONLY
 * DeathWatcher.DEATH_POSITION/DEATH_SEQUENCE; PlayerIntention itself
 * never transitions because of dying, so there's nothing left for
 * anything to accidentally clobber.
 *
 * KILL is the one remaining INCIDENTAL state -- distinct from
 * PlayerIntention (see its own docstring), which is the player's
 * constant, event-driven goal (IDLE/FOLLOW/DEFEND) that only changes on a
 * real command. KILL transitions back to whatever PlayerIntention
 * currently says once its own isFinished() reports true.
 *
 * KILL (!kill) is reachable from IDLE/FOLLOW/DEFEND, resolves its target
 * from whichever Command.Kill triggered entry (see
 * PlayerIntentionKillNode's own docstring), and exits straight back to
 * intention once the target dies/can't be resolved -- a genuine one-shot
 * task, not an ongoing "keep fighting" mode: a self-loop lets a fresh
 * !kill re-target while already fighting, but nothing auto-re-engages
 * once a kill finishes.
 *
 * DEFEND (!defend), unlike KILL, IS a real standing PlayerIntention value
 * -- "protect this entity from hostiles" (or the bot itself, if no
 * argument) is a goal the player is describing, not an incidental
 * interruption. PlayerIntentionDefendNode auto-fights the nearest
 * hostile to the defend target and never leaves DEFEND to do it (see its
 * own docstring -- it reuses the same CombatEngagement logic KILL uses,
 * just without a separate state transition), re-arming for the next
 * threat the instant the current one is gone. Only !stop (or !follow/
 * !kill superseding it) ends DEFEND -- notably, dying does NOT end it
 * either (see above).
 *
 * KILL/DEFEND both only ever publish the fight target's position/whether
 * it's in range (via NavIntent, same shape FOLLOW already uses) plus
 * which weapon is currently best (CombatEngagement.SELECTED_WEAPON) --
 * Hands:MELEE_ATTACK/DRAW_BOW are what actually act on that, matching
 * the established "this axis decides, Hands acts" split.
 *
 * NOTE: there is deliberately no SELF_HEAL state on this axis (an
 * earlier version had one, removed -- see git history). Low-health
 * eating is Hands:EAT's own concern, entered directly whenever health is
 * low AND hunger allows eating AND Legs is far enough from any threat
 * (all checked in HandsStateMachine's own entry edge) -- this axis
 * previously owned "should something be eating right now" and could get
 * GENUINELY STUCK: its own isFinished() only ever checked whether health
 * had recovered, never whether Hands could actually make progress, so a
 * bot at low health with hunger already full (blocking all
 * non-canAlwaysEat food) sat in SELF_HEAL indefinitely waiting for a
 * health recovery that could only happen through an eat that was
 * structurally impossible right now. Making EAT a Hands-only concern
 * means it naturally gives up (falls back to IDLE inside Hands' own
 * state graph) the instant eating stops being possible, with zero
 * coupling to this axis at all. Legs:FLEE is the other half of this (see
 * its own docstring) -- reacting to the SAME low-health fact,
 * independent of whatever this axis is doing.
 */
public enum PlayerIntentionState {
    IDLE,
    FOLLOW,
    DEFEND,
    KILL
}
