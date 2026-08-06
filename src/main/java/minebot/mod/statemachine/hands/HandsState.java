package minebot.mod.statemachine.hands;

/**
 * What the main/off hand is doing -- one of the peer axes described in
 * STATE_MACHINE.md (no hierarchy over General/Legs/Head). Built node by
 * node, not all at once (see STATE_MACHINE.md's "Implementation order").
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
 * EAT eats food while General:SELF_HEAL is active (its published
 * NEEDS_HEAL fact) -- ported from the old standalone FoodEater class.
 * Exits directly back to IDLE once finished (see HandsEatNode's own
 * isFinished()), no interrupt/resume memory needed the way General's own
 * SELF_HEAL/RESUME pair has: Hands' other states (e.g. a future combat
 * node) each independently re-evaluate their own entry condition against
 * General's CURRENT published state every tick, so the right Hands state
 * naturally re-activates on its own the instant General moves on from
 * SELF_HEAL back to whatever it actually resumes (e.g. COMBAT) -- Hands
 * never needs to remember what it was doing before EAT interrupted it.
 *
 * More states (drawing a bow, mining, ...) get added later, per
 * STATE_MACHINE.md's "Implementation order".
 */
public enum HandsState {
    IDLE,
    OPEN_DOOR,
    EAT
}
