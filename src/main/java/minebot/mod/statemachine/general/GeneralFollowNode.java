package minebot.mod.statemachine.general;

import minebot.mod.statemachine.BlackboardKey;
import minebot.mod.statemachine.Command;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Tracks the currently-followed entity and publishes its live position +
 * whether it's within FOLLOW_STOP_DISTANCE, once per tick, for Legs/Head
 * (or anything else) to react to -- General owns "am I close enough to
 * the thing I'm following" rather than Legs/Head each independently
 * tracking the followed entity id and re-deriving the same distance
 * check, per explicit direction: General:FOLLOW is the one thing that
 * isn't idle while actively following, so it's the natural place to
 * decide "should the rest of the bot currently be doing anything about
 * this" -- Legs/Head become pure consumers of what's published here, no
 * target-tracking logic duplicated in either. This is also what lets
 * Legs/Head both fall back to a real IDLE the moment the target is close
 * enough, instead of getting stuck in NAVIGATE forever once first
 * reached (the bug this was built to fix -- confirmed live).
 */
public final class GeneralFollowNode implements StateNode<GeneralState> {
    // Matches LegsNavigateNode's old STOP_DISTANCE default (itself
    // matching ControlState.setFollow's old default) -- moved here now
    // that General, not Legs, owns the "close enough" decision.
    private static final double STOP_DISTANCE = 2.0;

    /** The followed entity's live position this tick, or null if it's no longer following anything / the entity is gone. */
    public static final BlackboardKey<Vec3> TARGET_POSITION = new BlackboardKey<>();

    /** True if within STOP_DISTANCE of the followed entity (or nothing to follow) -- Legs/Head use this to know whether to actually be navigating/looking right now. */
    public static final BlackboardKey<Boolean> WITHIN_RANGE = new BlackboardKey<>();

    private int followEntityId = -1;

    /** How close is "close enough" -- LegsNavigateNode reads this for its own PathTracker.maybeReplan/stop-at-waypoint calls, so the two stay in sync with WITHIN_RANGE's own threshold. */
    public static double stopDistance() {
        return STOP_DISTANCE;
    }

    @Override
    public void onEnter(final TickContext ctx, final GeneralState previousState) {
        for (Command command : ctx.commands) {
            if (command instanceof Command.Follow follow) {
                followEntityId = follow.entityId();
            }
        }
    }

    @Override
    public void onTick(final TickContext ctx) {
        for (Command command : ctx.commands) {
            if (command instanceof Command.Follow follow) {
                followEntityId = follow.entityId();
            }
        }

        Entity target = ctx.level.getEntity(followEntityId);
        if (target == null) {
            ctx.blackboard.put(TARGET_POSITION, null);
            ctx.blackboard.put(WITHIN_RANGE, true); // nothing to walk toward -- "close enough" (i.e. don't navigate) by default
            return;
        }

        Vec3 targetPosition = target.position();
        ctx.blackboard.put(TARGET_POSITION, targetPosition);
        double distance = ctx.player.position().distanceTo(targetPosition);
        ctx.blackboard.put(WITHIN_RANGE, distance <= STOP_DISTANCE);
    }

    @Override
    public void onExit(final TickContext ctx) {
        ctx.blackboard.put(TARGET_POSITION, null);
        ctx.blackboard.put(WITHIN_RANGE, true);
        followEntityId = -1;
    }
}
