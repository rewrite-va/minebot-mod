package minebot.mod.statemachine;

import java.util.List;

/** Small shared helper for Edge conditions that just need to know "did a Command of this type arrive this tick" -- used identically by PlayerIntention/Legs's own edge tables. */
public final class Commands {
    private Commands() {
    }

    public static boolean has(final List<Command> commands, final Class<? extends Command> type) {
        for (Command command : commands) {
            if (type.isInstance(command)) {
                return true;
            }
        }
        return false;
    }

    /** Like has(), but returns the matching Command itself (the most recent one this tick, if somehow more than one arrived) -- needed by one-shot commands that carry data an edge/node has to read, not just react to the presence of (e.g. Goto's own x/y/z), the same way Pickup/Stop only ever needed has()'s plain boolean. */
    public static <T extends Command> T find(final List<Command> commands, final Class<T> type) {
        T found = null;
        for (Command command : commands) {
            if (type.isInstance(command)) {
                found = type.cast(command);
            }
        }
        return found;
    }
}
