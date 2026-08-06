package minebot.mod.statemachine.hands;

import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;

/** Not doing anything with its hands. */
public final class HandsIdleNode implements StateNode<HandsState> {
    @Override
    public void onTick(final TickContext ctx) {
    }
}
