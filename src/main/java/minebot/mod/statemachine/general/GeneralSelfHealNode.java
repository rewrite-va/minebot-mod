package minebot.mod.statemachine.general;

import minebot.mod.statemachine.BlackboardKey;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;

/**
 * Publishes NEEDS_HEAL for as long as this state is active -- never
 * touches inventory or keyUse itself. General only ever decides "should
 * the rest of the bot be doing something about this right now" (the same
 * role it already plays for FOLLOW, publishing TARGET_POSITION/
 * WITHIN_RANGE without touching pathfinding/MinebotInput itself) --
 * Hands:EAT is the one that reads NEEDS_HEAL and actually swaps to food/
 * holds keyUse/eats, matching Hands already owning every other real hand
 * interaction (OPEN_DOOR's useItemOn call).
 *
 * isFinished() reports true once health has recovered above
 * LOW_HEALTH_FRACTION again -- GeneralStateMachine's own SELF_HEAL-
 * >RESUME edge reads this to leave, and RESUME's own edges (see
 * ResumeNode) send it back to whatever state was actually running before
 * SELF_HEAL interrupted it (e.g. resuming an interrupted !follow, which
 * Command.Follow's own one-shot nature -- fires once, the tick the chat
 * command arrives, see Command's own docstring -- can't otherwise
 * re-trigger on its own).
 */
public final class GeneralSelfHealNode implements StateNode<GeneralState> {
    // Matches FoodEater's old LOW_HEALTH_FRACTION default.
    private static final float LOW_HEALTH_FRACTION = 0.80f;

    /** True for every tick this state is active -- Hands:EAT reads this to know whether it should be trying to eat right now. Always false/absent once this state exits (see onExit). */
    public static final BlackboardKey<Boolean> NEEDS_HEAL = new BlackboardKey<>();

    private boolean finished;

    @Override
    public void onEnter(final TickContext ctx, final GeneralState previousState) {
        finished = false;
    }

    @Override
    public void onTick(final TickContext ctx) {
        ctx.blackboard.put(NEEDS_HEAL, true);
        finished = ctx.player.getHealth() > ctx.player.getMaxHealth() * LOW_HEALTH_FRACTION;
    }

    @Override
    public void onExit(final TickContext ctx) {
        ctx.blackboard.put(NEEDS_HEAL, false);
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    /** How low health has to drop to enter this state in the first place -- GeneralStateMachine's own entry edges (from IDLE/FOLLOW) use this same threshold. */
    public static float lowHealthFraction() {
        return LOW_HEALTH_FRACTION;
    }
}
