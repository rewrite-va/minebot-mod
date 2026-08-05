package minebot.mod.statemachine;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

/**
 * The per-tick bag of everything a StateNode's onEnter/onTick/onExit or
 * an Edge's condition might need to read -- see STATE_MACHINE.md's
 * "Concrete Java shape" section. Rebuilt once per client tick by
 * MinebotMod (mutable, shared, no synchronization -- matches
 * ControlState's own documented reasoning: only ever touched from the
 * client tick thread, so a torn read is not a real concern here).
 *
 * Deliberately minimal for now -- grows as real nodes need more (nearby
 * entities, ControlState-equivalent command fields, etc.); see
 * STATE_MACHINE.md's open questions for why this stays mutable/shared
 * rather than an immutable snapshot.
 */
public final class TickContext {
    public final LocalPlayer player;
    public final ClientLevel level;
    public final Blackboard blackboard;

    public TickContext(final LocalPlayer player, final ClientLevel level, final Blackboard blackboard) {
        this.player = player;
        this.level = level;
        this.blackboard = blackboard;
    }
}
