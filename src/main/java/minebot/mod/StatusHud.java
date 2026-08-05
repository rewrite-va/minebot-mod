package minebot.mod;

import minebot.mod.statemachine.StateMachine;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Small always-on HUD line showing whether the mod's control channel is
 * currently connected to the Python backend -- added after live testing
 * made clear that "is anything actually happening" was otherwise only
 * visible by digging through the game log. Green/connected or
 * red/disconnected, top-left corner, drop-shadowed for readability
 * against any background.
 *
 * Includes BuildInfo.BUILT_AT (reformatted the same readable way as
 * minebot's own mod_version._format_built_at, in Pacific time to match
 * the user's own local clock -- confirmed live that a first attempt at
 * this rendered in UTC read as "incorrect" at a glance) so a
 * stale-deployed-but-not-yet-restarted client is visible at a glance in
 * this same on-screen line, not just in the backend's own log --
 * reported live repeatedly that this line still just said "minebot:
 * connected" with no version of any kind after the backend-log-side
 * format was changed, since that was a completely separate string from
 * this one.
 *
 * Also renders one line per registered StateMachine (see
 * STATE_MACHINE.md) showing its current state -- e.g. "general: FOLLOW"
 * -- directly below the connection line, so a state machine's real live
 * behavior is visible at a glance the same way connection status
 * already is, without needing to read the log. `stateMachines` is a
 * fixed list handed in at construction (today: just General) -- grows
 * as Legs/Hands/Head get built.
 */
public final class StatusHud {
    private static final int COLOR_CONNECTED = 0xFF55FF55;
    private static final int COLOR_DISCONNECTED = 0xFFFF5555;
    private static final int COLOR_STATE = 0xFFAAAAFF;
    private static final int LINE_HEIGHT = 10;
    private static final DateTimeFormatter BUILT_AT_FORMAT =
        DateTimeFormatter.ofPattern("'v'yyyyMMdd HH.mm.ss").withZone(ZoneId.of("America/Los_Angeles"));

    private final ControlClient controlClient;
    private final List<StateMachine<?>> stateMachines;

    public StatusHud(final ControlClient controlClient, final List<StateMachine<?>> stateMachines) {
        this.controlClient = controlClient;
        this.stateMachines = stateMachines;
    }

    public void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("minebot-mod", "status"), this::extractRenderState);
    }

    private void extractRenderState(final GuiGraphicsExtractor guiGraphics, final DeltaTracker deltaTracker) {
        boolean connected = controlClient.isOpen();
        String message = "backend: " + (connected ? "connected" : "disconnected") + " (" + formatBuiltAt(BuildInfo.BUILT_AT) + ")";
        int color = connected ? COLOR_CONNECTED : COLOR_DISCONNECTED;
        guiGraphics.text(Minecraft.getInstance().font, message, 4, 4, color, true);

        int y = 4 + LINE_HEIGHT;
        for (StateMachine<?> stateMachine : stateMachines) {
            guiGraphics.text(Minecraft.getInstance().font, stateMachine.name() + ": " + stateMachine.currentState(), 4, y, COLOR_STATE, true);
            y += LINE_HEIGHT;
        }
    }

    private static String formatBuiltAt(final String builtAt) {
        try {
            return BUILT_AT_FORMAT.format(Instant.parse(builtAt));
        } catch (DateTimeParseException e) {
            return builtAt;
        }
    }
}
