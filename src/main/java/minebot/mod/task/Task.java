package minebot.mod.task;

import minebot.mod.statemachine.TickContext;

/**
 * One entry in TaskController's queue -- see its own docstring for the
 * overall queue/current-task shape. Distinct from the peer-SM StateNode
 * interface (statemachine/StateNode.java) despite the similar onEnter/
 * tick/onExit shape: a Task is a one-shot, queued unit of work with a
 * genuine start/finish (GiveTask walks somewhere, drops an item, then is
 * done forever), not a node in a standing graph of states another Edge
 * can transition back into -- TaskController itself has no notion of
 * "edges" at all, just "is there a current task, and is it finished yet".
 */
public interface Task {
    /** Called once, the tick TaskController dequeues this task and makes it current. */
    default void onEnter(final TickContext ctx) {
    }

    /** Called every tick this task is TaskController's current task (including the tick it was entered, right after onEnter). */
    void tick(TickContext ctx);

    /** Called once, the tick TaskController notices isFinished() and removes this task as current. */
    default void onExit(final TickContext ctx) {
    }

    /** Whether this task has completed its own work -- a plain fact the task itself tracks and exposes, the same convention StateNode.isFinished() already established for peer-SM nodes. TaskController polls this every tick to know when to retire the current task. */
    boolean isFinished();
}
