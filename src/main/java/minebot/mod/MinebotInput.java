package minebot.mod;

import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * Replaces LocalPlayer.input (normally a KeyboardInput reading real
 * keyboard state -- see decompiled KeyboardInput.tick()) with a version
 * driven by ControlState instead. LocalPlayer.aiStep() calls
 * this.input.tick() unconditionally every client tick, then reads
 * keyPresses/moveVector to move the real player through the real physics
 * pipeline (Entity.moveRelative -> real collision/gravity/friction) --
 * exactly the same code path a human pressing W/Space drives, just fed
 * from ControlState instead of Options key-bind state.
 */
public final class MinebotInput extends ClientInput {
    private final ControlState state;

    public MinebotInput(final ControlState state) {
        this.state = state;
    }

    @Override
    public void tick() {
        this.keyPresses = new Input(
            state.forward, false, false, false, state.jump, false, state.sprint
        );
        // KeyboardInput's Vec2(left, forward) construction, mirrored exactly
        // (see decompiled KeyboardInput.tick()) -- forward-only input here
        // since minebot doesn't need strafing yet.
        this.moveVector = new Vec2(0.0F, state.forward ? 1.0F : 0.0F);
    }
}
