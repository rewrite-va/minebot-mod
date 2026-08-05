package minebot.mod.statemachine;

import java.util.List;

/** Small shared helper for Edge conditions that just need to know "did a Command of this type arrive this tick" -- used identically by General/Legs's own edge tables. */
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
}
