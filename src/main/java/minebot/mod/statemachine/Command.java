package minebot.mod.statemachine;

/**
 * A typed, parsed representation of a backend chat/control-channel
 * command -- the replacement, going forward, for state machines reading
 * ControlState's raw mutated fields directly (see STATE_MACHINE.md and
 * the conversation that produced this class: General SM's FOLLOW node is
 * the first thing built against this instead of ControlState.mode/
 * followEntityId, on purpose, to stop new SM work from growing a second
 * dependency on ControlState's shape).
 *
 * ControlState itself is untouched by this -- existing systems
 * (resolveMovementIntent, GIVE/COLLECT/ATTACK, ...) keep reading it
 * exactly as before. This is a parallel, independent path for whatever
 * SM work chooses to use it, not a replacement for ControlState as a
 * whole (that migration, if it ever happens, is Legs SM's job later --
 * see STATE_MACHINE.md's "Implementation order").
 */
public sealed interface Command {
    record Follow(int entityId, double stopDistance) implements Command {
    }

    /** !stop -- cancel whatever's currently going on. Deliberately carries no data (it's the same signal regardless of what's currently active). */
    record Stop() implements Command {
    }
}
