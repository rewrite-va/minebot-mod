package minebot.mod.task;

import minebot.mod.EntityFinder;
import minebot.mod.statemachine.Command;
import minebot.mod.statemachine.StateMachine;
import minebot.mod.statemachine.TickContext;
import minebot.mod.statemachine.playerintention.CombatEngagement;
import minebot.mod.statemachine.playerintention.PlayerIntentionState;
import net.minecraft.world.entity.Entity;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A generic, ticked-directly (outside every peer StateMachine, same
 * precedent DeathWatcher/InventoryController's own tick() established for
 * "a cross-cutting concern that isn't itself a graph of states") FIFO
 * queue of one-shot Task work -- see prompt.txt/the conversation that
 * produced this for the full design. !give is the first real Task
 * (GiveTask); !sleep (SleepTask) is the second -- moved here from an
 * earlier PlayerIntentionState.SLEEP peer-SM node per explicit direction
 * ("implement it more like !give, which is a task in a queue"), since
 * "walk to a bed and sleep" is exactly the queued-unit-of-work shape a
 * Task already exists for, not a standing intention. This class itself
 * has no give/sleep-specific knowledge at all.
 *
 * Deliberately NOT a peer StateMachine<S>: a Task has no "which state am I
 * in" concept, no Edge table, no notion of ever being re-entered once
 * finished -- it's a queued unit of work with a single onEnter/tick.../
 * onExit lifecycle, then it's gone for good (see Task's own docstring for
 * the full contrast with StateNode).
 *
 * Only one Task is ever "current" at a time -- a fresh Task is dequeued
 * only once the current one finishes (isFinished()) AND isBusy() says the
 * bot isn't otherwise occupied (see canDequeueTask()). Nothing here
 * preempts an in-progress Task the way e.g. KILL preempts PlayerIntention
 * -- per prompt.txt's own design, a Task, once current, runs to
 * completion before anything else is dequeued.
 */
public final class TaskController {
    // How close a hostile has to be, while DEFEND is the live PlayerIntention
    // state, for isBusy() to hold off dequeuing -- reuses CombatEngagement's
    // own SEARCH_RADIUS (the same "is there realistically a threat nearby
    // at all" scale already used for defend's own threat scan), rather
    // than inventing a second unrelated radius constant.
    private static final double BUSY_THREAT_RADIUS = CombatEngagement.SEARCH_RADIUS;

    private final StateMachine<PlayerIntentionState> playerIntentionStateMachine;
    private final Deque<Task> queue = new ArrayDeque<>();
    private Task currentTask;

    public TaskController(final StateMachine<PlayerIntentionState> playerIntentionStateMachine) {
        this.playerIntentionStateMachine = playerIntentionStateMachine;
    }

    /** Adds `task` to the back of the queue -- runs once every task ahead of it (if any) has finished and the bot isn't busy. */
    public void enqueue(final Task task) {
        queue.add(task);
    }

    /** Call once per client tick. First enqueues a fresh GiveTask/SleepTask for every Command.Give/Command.Sleep seen this tick (the cross-thread handoff CommandBus exists for -- see Command.Give/Command.Sleep's own docstrings), then ticks the current task if there is one (retiring it via onExit the moment it reports isFinished()), otherwise dequeues a fresh one if canDequeueTask() allows it. */
    public void tick(final TickContext ctx) {
        for (Command command : ctx.commands) {
            if (command instanceof Command.Give give) {
                enqueue(new GiveTask(give.recipientEntityId(), give.item(), give.quantity()));
            }
            if (command instanceof Command.Sleep) {
                enqueue(new SleepTask());
            }
        }

        if (currentTask != null) {
            currentTask.tick(ctx);
            if (currentTask.isFinished()) {
                currentTask.onExit(ctx);
                currentTask = null;
            }
            return;
        }

        if (!canDequeueTask(ctx)) {
            return;
        }
        currentTask = queue.poll();
        currentTask.onEnter(ctx);
    }

    /** False if the queue is empty, a task is already current (only reached here when it's null, so this is really just documenting the invariant tick() already enforces), or isBusy() says the bot is occupied with something outside the task system entirely. */
    private boolean canDequeueTask(final TickContext ctx) {
        return !queue.isEmpty() && currentTask == null && !isBusy(ctx);
    }

    /**
     * True if the player is busy with something outside the task system
     * that a Task shouldn't interrupt -- currently: real DEFEND combat, a
     * hostile actually nearby (per explicit direction: "returns true if
     * there are monsters nearby and we are in defend mode"). Standing in
     * DEFEND with nothing actually threatening nearby is NOT busy -- a
     * queued give should still run rather than waiting on a defend mode
     * that has nothing to do right now.
     */
    private boolean isBusy(final TickContext ctx) {
        if (ctx.blackboard.get(playerIntentionStateMachine) != PlayerIntentionState.DEFEND) {
            return false;
        }
        Entity nearestHostile = EntityFinder.findNearestHostile(ctx.level, ctx.player.position(), BUSY_THREAT_RADIUS);
        return nearestHostile != null;
    }
}
