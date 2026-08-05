package minebot.mod;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Small always-on HUD line showing whether the mod's control channel is
 * currently connected to the Python backend -- added after live testing
 * made clear that "is anything actually happening" was otherwise only
 * visible by digging through the game log. Green/connected or
 * red/disconnected, top-left corner, drop-shadowed for readability
 * against any background.
 *
 * Includes BuildInfo.BUILT_AT (reformatted the same readable way as
 * minebot's own mod_version._format_built_at) so a stale-deployed-but-
 * not-yet-restarted client is visible at a glance in this same on-screen
 * line, not just in the backend's own log -- reported live repeatedly
 * that this line still just said "minebot: connected" with no version
 * of any kind after the backend-log-side format was changed, since that
 * was a completely separate string from this one.
 */
public final class StatusHud {
    private static final int COLOR_CONNECTED = 0xFF55FF55;
    private static final int COLOR_DISCONNECTED = 0xFFFF5555;
    private static final DateTimeFormatter BUILT_AT_FORMAT = DateTimeFormatter.ofPattern("'v'yyyyMMdd HH.mm.ss").withZone(ZoneOffset.UTC);

    private final ControlClient controlClient;

    public StatusHud(final ControlClient controlClient) {
        this.controlClient = controlClient;
    }

    public void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("minebot-mod", "status"), this::extractRenderState);
    }

    private void extractRenderState(final GuiGraphicsExtractor guiGraphics, final DeltaTracker deltaTracker) {
        boolean connected = controlClient.isOpen();
        String message = "backend: " + (connected ? "connected" : "disconnected") + " (" + formatBuiltAt(BuildInfo.BUILT_AT) + ")";
        int color = connected ? COLOR_CONNECTED : COLOR_DISCONNECTED;
        guiGraphics.text(Minecraft.getInstance().font, message, 4, 4, color, true);
    }

    private static String formatBuiltAt(final String builtAt) {
        try {
            return BUILT_AT_FORMAT.format(Instant.parse(builtAt));
        } catch (DateTimeParseException e) {
            return builtAt;
        }
    }
}
