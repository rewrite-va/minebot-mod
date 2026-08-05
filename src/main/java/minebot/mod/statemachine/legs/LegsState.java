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
 */
public enum LegsState {
    IDLE,
    NAVIGATE
}
