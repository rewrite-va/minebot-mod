package minebot.mod.statemachine;

import minebot.mod.MinebotInput;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;

/**
 * The per-tick bag of everything a StateNode's onEnter/onTick/onExit or
 * an Edge's condition might need to read -- see STATE_MACHINE.md's
 * "Concrete Java shape" section. Rebuilt once per client tick by
 * MinebotMod (mutable, shared, no synchronization -- matches
 * ControlState's own documented reasoning: only ever touched from the
 * client tick thread, so a torn read is not a real concern here).
 *
 * `commands` is the CommandBus's drain() result for THIS tick only,
 * already off the WebSocket thread and safe to read directly -- see
 * CommandBus's own docstring for why the cross-thread handoff happens
 * there, not here.
 *
 * `input` is how a Legs node actually drives movement (forward/jump/
 * sprint) -- see MinebotInput's own docstring. Deliberately NOT how
 * look direction gets set: yaw/pitch are Head SM's concern exclusively
 * (see LegsNavigateNode's own docstring for why Legs never touches
 * rotation, even temporarily, per explicit direction), set via direct
 * player.setYRot/setXRot calls, not through this.
 *
 * Deliberately minimal for now -- grows as real nodes need more (nearby
 * entities, etc.); see STATE_MACHINE.md's open questions for why this
 * stays mutable/shared rather than an immutable snapshot.
 */
public final class TickContext {
    public final LocalPlayer player;
    public final ClientLevel level;
    public final Blackboard blackboard;
    public final List<Command> commands;
    public final MinebotInput input;

    public TickContext(final LocalPlayer player, final ClientLevel level, final Blackboard blackboard, final List<Command> commands, final MinebotInput input) {
        this.player = player;
        this.level = level;
        this.blackboard = blackboard;
        this.commands = commands;
        this.input = input;
    }
}
