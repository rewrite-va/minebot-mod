package minebot.mod.statemachine.general;

import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;

/** Does nothing yet -- the first real node in the General SM, kept intentionally empty until there's a second state to transition to/from. See STATE_MACHINE.md's "Implementation order". */
public final class GeneralIdleNode implements StateNode {
    @Override
    public void onTick(final TickContext ctx) {
    }
}
