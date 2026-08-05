package minebot.mod.statemachine.general;

import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;

/**
 * Does nothing yet -- see GeneralState.FOLLOW's own docstring for why
 * this node is intentionally a label only right now. Real FOLLOW
 * movement is still entirely owned by ControlState/resolveMovementIntent
 * (see MinebotMod), which this doesn't touch or duplicate.
 */
public final class GeneralFollowNode implements StateNode {
    @Override
    public void onTick(final TickContext ctx) {
    }
}
