package minebot.mod.statemachine.legs;

/**
 * The feet -- one of the peer axes described in STATE_MACHINE.md. Owns
 * ONLY movement (forward/jump/sprint, via MinebotInput) -- never yaw/
 * pitch, which is Head SM's exclusive concern (see LegsNavigateNode's
 * own docstring). Also never door-opening or dig-through-obstacles,
 * despite those historically living inside the same shared pathfinding
 * pipeline this replaces -- both are real hand interactions and belong
 * to future Hands SM nodes (e.g. hands:open_door), not here. This is a
 * deliberate, documented gap in this first version, not an oversight.
 *
 * FLEE continuously repositions away from the current combat target
 * while health is low AND there's still a way to heal (HandsEatNode.
 * lowHealth() && hasFood()) -- reads those facts directly, entirely
 * independent of whatever PlayerIntention OR Hands is currently doing,
 * per explicit direction: there is no PlayerIntention:SELF_HEAL/FLEE
 * state coordinating this (see PlayerIntentionState's own docstring for
 * why PlayerIntention:SELF_HEAL was removed) -- Legs and Hands react to
 * the same low-level facts each in their own lane, without PlayerIntention
 * arbitrating between them at all (see LegsFleeNode/LegsStateMachine's
 * own docstrings for why Legs specifically reacts to lowHealth()/
 * hasFood() directly rather than to Hands:EAT's own NEEDS_HEAL, avoiding
 * a start-up deadlock between the two, and for why hasFood() is required
 * too: fleeing with no way to ever heal just delays an inevitable death,
 * so FLEE exits once eating genuinely can't help). See LegsFleeNode's own
 * docstring for why it never falls back to LegsNavigateNode's own "walk
 * straight at the raw target if no plan" behavior -- that fallback
 * bypasses real pathfinding's own hazard avoidance (lava, unsafe falls),
 * which is exactly the wrong risk to take while already low on health.
 * Also see LegsFleeNode's own docstring for why "safe" is a
 * continuously-reevaluated condition here, not a single destination
 * reached once and then held.
 *
 * GO_TO_DEATH_POSITION/PICKUP_ITEMS handle post-death recovery -- moved
 * here from the old PlayerIntention:DEAD's own downstream states (see
 * PlayerIntentionState's own docstring for the full story of why, and
 * DeathWatcher's own docstring for the live bug this fixes) -- reachable
 * off a fresh DeathWatcher.DEATH_SEQUENCE, entirely independent of
 * whatever PlayerIntention is doing: walking back to a death spot and
 * recovering dropped items is purely a Legs/navigation concern, and
 * PlayerIntention (IDLE/FOLLOW/DEFEND/KILL) never needs to be interrupted
 * or resumed to make it happen. Outranks even FLEE (checked first in
 * LegsStateMachine's own edge table) -- a fresh death is the single most
 * overriding event Legs can react to, though in practice this rarely
 * matters since health is full immediately post-respawn, so FLEE's own
 * lowHealth() trigger wouldn't be competing for the same tick anyway.
 * GO_TO_DEATH_POSITION always goes to PICKUP_ITEMS next (never straight
 * back to NAVIGATE/IDLE) -- walks generally back to the death spot, then
 * PICKUP_ITEMS does its own tighter per-item walk-onto approach; once
 * PICKUP_ITEMS itself finishes (or times out), LegsStateMachine's normal
 * edges (shouldNavigate/shouldFlee) take back over exactly as if nothing
 * had interrupted them, since neither node ever touched PlayerIntention's
 * own state to begin with.
 */
public enum LegsState {
    IDLE,
    NAVIGATE,
    FLEE,
    GO_TO_DEATH_POSITION,
    PICKUP_ITEMS
}
