package minebot.mod.statemachine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Bridges Commands from wherever they're produced (today: MinebotMod.
 * dispatchMessage, running on the WebSocket library's own thread -- see
 * its own docstring) to wherever they're consumed (state machine edge
 * conditions, evaluated only on the client tick thread -- every other
 * piece of the statemachine package, StateMachine/Blackboard included,
 * is explicitly documented as NOT thread-safe, unlike ControlState's own
 * volatile-fields approach). A real, if minimal, concurrent structure is
 * unavoidable here since this is genuine cross-thread handoff, not the
 * "torn read of a primitive is harmless" case ControlState's own plain
 * volatiles rely on -- Command objects are immutable records, so a
 * lock-free queue is enough; no broader synchronization needed.
 *
 * publish() is safe to call from any thread. drain() must only be called
 * from the client tick thread -- it's where Commands actually become
 * visible to state machine conditions.
 */
public final class CommandBus {
    private final ConcurrentLinkedQueue<Command> queue = new ConcurrentLinkedQueue<>();

    public void publish(final Command command) {
        queue.add(command);
    }

    /** Drains every Command published since the last drain(), in publish order. Call once per client tick. */
    public List<Command> drain() {
        List<Command> drained = new ArrayList<>();
        Command command;
        while ((command = queue.poll()) != null) {
            drained.add(command);
        }
        return drained;
    }
}
