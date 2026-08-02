package minebot.mod;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * Small always-on HUD line showing whether the mod's control channel is
 * currently connected to the Python backend -- added after live testing
 * made clear that "is anything actually happening" was otherwise only
 * visible by digging through the game log. Green/connected or
 * red/disconnected, top-left corner, drop-shadowed for readability
 * against any background.
 */
public final class StatusHud {
    private static final int COLOR_CONNECTED = 0xFF55FF55;
    private static final int COLOR_DISCONNECTED = 0xFFFF5555;

    private final ControlClient controlClient;

    public StatusHud(final ControlClient controlClient) {
        this.controlClient = controlClient;
    }

    public void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("minebot-mod", "status"), this::extractRenderState);
    }

    private void extractRenderState(final GuiGraphicsExtractor guiGraphics, final DeltaTracker deltaTracker) {
        boolean connected = controlClient.isOpen();
        String message = "minebot: " + (connected ? "connected" : "disconnected");
        int color = connected ? COLOR_CONNECTED : COLOR_DISCONNECTED;
        guiGraphics.text(Minecraft.getInstance().font, message, 4, 4, color, true);
    }
}
