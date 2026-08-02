package minebot.mod;

import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * Replaces LocalPlayer.input (normally a KeyboardInput reading real
 * keyboard state -- see decompiled KeyboardInput.tick()) with a version
 * that lets the bot drive movement (from ControlState) *unless* the human
 * is actually pressing something, in which case the real keyboard wins --
 * manual override, not a takeover. LocalPlayer.aiStep() calls
 * this.input.tick() unconditionally every client tick, then reads
 * keyPresses/moveVector to move the real player through the real physics
 * pipeline (Entity.moveRelative -> real collision/gravity/friction) --
 * exactly the same code path a human pressing W/Space drives, whichever
 * source (bot or keyboard) ends up populating those fields this tick.
 *
 * Delegates to a real KeyboardInput internally (constructed from the
 * live Options, same as vanilla's own LocalPlayer construction) purely to
 * read actual key-bind state each tick -- this class never lets that
 * delegate's own tick() output reach the player directly except when it's
 * chosen as the winner below.
 */
public final class MinebotInput extends ClientInput {
    private final ControlState state;
    private final KeyboardInput keyboard;

    public MinebotInput(final ControlState state, final KeyboardInput keyboard) {
        this.state = state;
        this.keyboard = keyboard;
    }

    @Override
    public void tick() {
        keyboard.tick();
        Input realKeys = keyboard.keyPresses;
        boolean humanIsPressingSomething = realKeys.forward() || realKeys.backward() || realKeys.left()
            || realKeys.right() || realKeys.jump() || realKeys.shift() || realKeys.sprint();

        if (humanIsPressingSomething) {
            this.keyPresses = realKeys;
            this.moveVector = keyboard.getMoveVector();
            return;
        }

        this.keyPresses = new Input(
            state.forward, false, false, false, state.jump, false, state.sprint
        );
        // KeyboardInput's Vec2(left, forward) construction, mirrored exactly
        // (see decompiled KeyboardInput.tick()) -- forward-only input here
        // since minebot doesn't need strafing yet.
        this.moveVector = new Vec2(0.0F, state.forward ? 1.0F : 0.0F);
    }
}
