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
 * LOW_HEALTH_FRACTION again. Remembers which state to resume (via
 * onEnter's own previousState -- see StateNode's own docstring) itself,
 * rather than relying on any shared engine-level "resume" mechanism --
 * found live that a generic version of this (a shared ResumeNode reading
 * a single engine-tracked "one step back" field) breaks the moment
 * there's a real multi-hop chain: entering a THIRD state (the generic
 * RESUME waypoint) after SELF_HEAL overwrote the engine's own memory
 * with SELF_HEAL itself, losing the real answer (FOLLOW). Tracking it
 * directly here, and transitioning straight to the real destination (no
 * intermediate hop), sidesteps that whole class of bug.
 */
public final class GeneralSelfHealNode implements StateNode<GeneralState> {
    // Matches FoodEater's old LOW_HEALTH_FRACTION default.
    private static final float LOW_HEALTH_FRACTION = 0.80f;

    /** True for every tick this state is active -- Hands:EAT reads this to know whether it should be trying to eat right now. Always false/absent once this state exits (see onExit). */
    public static final BlackboardKey<Boolean> NEEDS_HEAL = new BlackboardKey<>();

    private boolean finished;
    private GeneralState stateToResume = GeneralState.IDLE;

    @Override
    public void onEnter(final TickContext ctx, final GeneralState previousState) {
        finished = false;
        // previousState is only null on the machine's very first-ever
        // entry, which can never be SELF_HEAL itself (that's never the
        // initial state) -- so this is always a real interrupted state
        // in practice, but falls back to IDLE defensively rather than
        // risk resuming into a null state some future change might
        // introduce.
        stateToResume = previousState != null ? previousState : GeneralState.IDLE;
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

    /** Which state to transition straight back to once finished -- read by GeneralStateMachine's own SELF_HEAL exit edges (one per real destination state). */
    public GeneralState stateToResume() {
        return stateToResume;
    }

    /** How low health has to drop to enter this state in the first place -- GeneralStateMachine's own entry edges (from IDLE/FOLLOW) use this same threshold. */
    public static float lowHealthFraction() {
        return LOW_HEALTH_FRACTION;
    }
}
