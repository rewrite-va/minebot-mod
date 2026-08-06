package minebot.mod.statemachine.playerintention;

import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;

/** Does nothing -- the "nothing is going on" state. See STATE_MACHINE.md's "Implementation order". */
public final class PlayerIntentionIdleNode implements StateNode<PlayerIntentionState> {
    @Override
    public void onTick(final TickContext ctx) {
    }
}
