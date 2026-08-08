package minebot.mod.statemachine.hands;

/**
 * What the main/off hand is doing -- one of the peer axes described in
 * STATE_MACHINE.md (no hierarchy over PlayerIntention/Legs/Head). Built
 * node by node, not all at once (see STATE_MACHINE.md's "Implementation
 * order").
 *
 * OPEN_DOOR opens a closed door Legs is currently walking toward (its
 * published WAYPOINT_COORDINATES classifies as a closed door, within
 * interaction range) -- ported from the old DoorOpener class, which used
 * to run unconditionally inside the shared movement pipeline (see
 * LegsNavigateNode's own docstring for why that was a known gap this
 * closes). Deliberately never touches yaw/pitch (Head SM's exclusive
 * concern) -- opening a door is a hand interaction, not a look decision,
 * even though the bot happens to already be facing the door most of the
 * time as a side effect of Head:NAVIGATE aiming at the same waypoint.
 *
 * EAT eats food whenever health is low AND eating is actually possible
 * right now (own gating, no PlayerIntention involvement at all -- see
 * HandsEatNode/PlayerIntentionState's own docstrings for why the old
 * General:SELF_HEAL state -- see git history -- was removed entirely).
 * Exits directly back to
 * IDLE once finished, no interrupt/resume memory needed: Hands' other
 * states (e.g. MELEE_ATTACK/DRAW_BOW) each independently re-evaluate
 * their own entry condition every tick, so the right Hands state
 * naturally re-activates on its own the instant EAT's own condition
 * stops holding -- Hands never needs to remember what it was doing
 * before EAT interrupted it.
 *
 * MELEE_ATTACK/DRAW_BOW/DRAW_CROSSBOW are mutually exclusive, all
 * reachable whenever PlayerIntention==KILL or PlayerIntention==DEFEND
 * (either real fighting state -- see PlayerIntentionState's own
 * docstring), split by CombatEngagement.SELECTED_WEAPON's own kind (see
 * HandsStateMachine's own docstring for the exact entry conditions) --
 * PlayerIntention/Legs already decided range/positioning per weapon (see
 * CombatEngagement's own docstring for its
 * kiting logic), Hands here just acts once positioned: MELEE_ATTACK
 * swings with whatever InventoryController picked once within melee range
 * (its published NAV_ARRIVED), ported from the old shared
 * tickAttack's melee branch. DRAW_BOW draws and fires a real bow once it
 * has actual line of sight to the target (see its own docstring) -- NOT
 * range-gated the way MELEE_ATTACK is, since a bow's whole point is
 * acting from range; ported from the deleted BowShooter class (git
 * history has its full docstring on the real mechanics involved).
 * DRAW_CROSSBOW is the crossbow counterpart (see HandsDrawCrossbowNode's
 * own docstring for why it's a genuinely separate state rather than
 * folded into DRAW_BOW -- a crossbow's real charge/fire mechanics are
 * shaped nothing like a bow's hold-and-auto-release), same
 * line-of-sight-only gating, no range gate either.
 *
 * More states (mining, ...) get added later, per STATE_MACHINE.md's
 * "Implementation order".
 */
public enum HandsState {
    IDLE,
    OPEN_DOOR,
    EAT,
    MELEE_ATTACK,
    DRAW_BOW,
    DRAW_CROSSBOW
}
