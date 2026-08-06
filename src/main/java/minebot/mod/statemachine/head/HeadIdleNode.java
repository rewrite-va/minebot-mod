package minebot.mod.statemachine.head;

import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;

/** Not aiming at anything -- doesn't touch yaw/pitch at all, leaving them at whatever they last were (no other system currently sets them either). */
public final class HeadIdleNode implements StateNode {
    @Override
    public void onTick(final TickContext ctx) {
    }
}
