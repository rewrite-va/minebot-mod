package minebot.mod.statemachine.legs;

import minebot.mod.MovementIntent;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;

/** Not walking anywhere -- releases any held forward/jump/sprint on entry, in case NAVIGATE left them set. */
public final class LegsIdleNode implements StateNode<LegsState> {
    @Override
    public void onEnter(final TickContext ctx, final LegsState previousState) {
        ctx.input.setIntent(new MovementIntent()); // all-false/empty -- stop moving
    }

    @Override
    public void onTick(final TickContext ctx) {
    }
}
