package minebot.mod.task;

import minebot.mod.EntityFinder;
import minebot.mod.MinebotMod;
import minebot.mod.statemachine.Command;
import minebot.mod.statemachine.StateMachine;
import minebot.mod.statemachine.TickContext;
import minebot.mod.statemachine.playerintention.CombatEngagement;
import minebot.mod.statemachine.playerintention.PlayerIntentionState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

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
 * only once the current one finishes (isFinished()) AND busyThreat() says
 * the bot isn't otherwise occupied (see tick()'s own dequeue check).
 * Nothing here preempts an in-progress Task the way e.g. KILL preempts
 * PlayerIntention -- per prompt.txt's own design, a Task, once current,
 * runs to completion before anything else is dequeued.
 *
 * `busyReporter` sends one real chat line the first tick a freshly-
 * enqueued task can't actually start (busyThreat() returns non-null) --
 * added after a live !sleep sat silently queued behind an active DEFEND-
 * with-nearby-hostile: Python's own immediate "ok, looking for a bed"
 * reply is sent at dispatch time, before this class has any chance to
 * know whether the task can actually run, so it can't reflect this on
 * its own. A real chat send (not a wire event Python would need to
 * relay) since this is closer to vanilla's own in-game system messages --
 * the same reasoning SleepTask's own docstring gives for leaning on real
 * sleep-rejection messages instead of inventing a Python-side signal.
 * Deliberately fires only ONCE per stuck task (see reportedBusyForFront
 * below), not every tick it stays queued -- busyThreat() can hold for a
 * long, ongoing fight; repeating the same line every tick would spam
 * chat the whole time. Names WHAT the threat is and how far away (see
 * describeThreat()) -- added after a live report of "it works but what
 * threat? I see nothing": findNearestHostile has no line-of-sight check
 * and a 32-block radius, so the blocking hostile is very often not
 * anywhere near visible on screen, and a bare "a nearby threat" gave no
 * way to check that against reality.
 */
public final class TaskController {
    // How close a hostile has to be, while DEFEND is the live PlayerIntention
    // state, for isBusy() to hold off dequeuing -- reuses CombatEngagement's
    // own SEARCH_RADIUS (the same "is there realistically a threat nearby
    // at all" scale already used for defend's own threat scan), rather
    // than inventing a second unrelated radius constant.
    private static final double BUSY_THREAT_RADIUS = CombatEngagement.SEARCH_RADIUS;

    private final StateMachine<PlayerIntentionState> playerIntentionStateMachine;
    private final Consumer<String> busyReporter;
    private final Deque<Task> queue = new ArrayDeque<>();
    private Task currentTask;
    // True once busyReporter has already fired for the task currently at
    // the front of the queue -- reset whenever the queue's front changes
    // (a new task arrives, or the stuck one is finally dequeued), so each
    // distinct stuck task gets exactly one report, not zero or many.
    private boolean reportedBusyForFront;

    public TaskController(final StateMachine<PlayerIntentionState> playerIntentionStateMachine, final Consumer<String> busyReporter) {
        this.playerIntentionStateMachine = playerIntentionStateMachine;
        this.busyReporter = busyReporter;
    }

    /** Adds `task` to the back of the queue -- runs once every task ahead of it (if any) has finished and the bot isn't busy. */
    public void enqueue(final Task task) {
        if (queue.isEmpty() && currentTask == null) {
            reportedBusyForFront = false;
        }
        queue.add(task);
    }

    /** Total tasks not yet finished -- the queue itself plus the current task, if any (0 when nothing at all is queued/running). Read by StatusHud so queue depth is visible on the HUD the same way SM state already is, instead of only inferable from the game log. */
    public int pendingTaskCount() {
        return queue.size() + (currentTask != null ? 1 : 0);
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
                reportedBusyForFront = false;
            }
            return;
        }

        if (queue.isEmpty()) {
            return;
        }
        Entity blockingThreat = busyThreat(ctx);
        if (blockingThreat != null) {
            if (!reportedBusyForFront) {
                reportedBusyForFront = true;
                String description = describeThreat(ctx, blockingThreat);
                MinebotMod.LOGGER.info("task queue: held off dequeuing, busy -- {}", description);
                busyReporter.accept("busy defending against " + description + " -- queued task will run once that's clear");
            }
            return;
        }
        currentTask = queue.poll();
        reportedBusyForFront = false;
        currentTask.onEnter(ctx);
    }

    /**
     * The hostile currently making the player "busy" (see this method's
     * own name/return-null-if-not-busy shape), or null if nothing's
     * holding the queue off right now -- currently: real DEFEND combat, a
     * hostile actually nearby (per explicit direction: "returns true if
     * there are monsters nearby and we are in defend mode"). Standing in
     * DEFEND with nothing actually threatening nearby is NOT busy -- a
     * queued give should still run rather than waiting on a defend mode
     * that has nothing to do right now.
     *
     * Returns the real Entity (not just a boolean) so tick() can report
     * WHAT it found and HOW FAR away, not just "something" -- added after
     * a live report of "it works but what threat? I see nothing": this
     * uses EntityFinder.findNearestHostile, which (unlike
     * findNearestVisibleHostile, DEFEND's own actual combat-targeting
     * scan) has no line-of-sight check at all, and BUSY_THREAT_RADIUS is
     * 32 blocks -- a hostile well behind a wall, underground, or just far
     * across open terrain can hold the queue off with nothing visibly
     * wrong on screen. That's intentional (per the same explicit
     * direction: a real threat should still block a queued give/sleep
     * even if the bot hasn't turned to look at it yet), but it needs to
     * be a diagnosable "why" instead of a silent one.
     */
    private Entity busyThreat(final TickContext ctx) {
        if (ctx.blackboard.get(playerIntentionStateMachine) != PlayerIntentionState.DEFEND) {
            return null;
        }
        return EntityFinder.findNearestHostile(ctx.level, ctx.player.position(), BUSY_THREAT_RADIUS);
    }

    /** "a zombie 18 blocks away" -- mob registry type name (matching EntityFinder.findNearestEntity's own type-string shape) plus real distance rounded to the nearest block, so the busy report/log line says something a player looking at their own surroundings can actually check against. */
    private static String describeThreat(final TickContext ctx, final Entity threat) {
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(threat.getType()).getPath();
        long distance = Math.round(ctx.player.position().distanceTo(threat.position()));
        return "a " + type + " " + distance + " blocks away";
    }
}
