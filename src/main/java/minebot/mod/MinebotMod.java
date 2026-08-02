package minebot.mod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the client-mod half of minebot's architecture pivot: the
 * mod runs inside a real Minecraft client logged into the bot's account
 * (normal Microsoft/Mojang auth, nothing custom), and is the *only* thing
 * that actually talks to the Minecraft server. The Python backend
 * (separate repo) connects to this mod's local WebSocket control channel
 * (ControlServer) instead of implementing the protocol itself -- it sends
 * high-level commands ("follow", "goto", "stop") and receives game events
 * (chat, position, entities, health) back, while all actual movement runs
 * through Minecraft's own real physics via MinebotInput/ControlState.
 *
 * See /home/colaila/git/minebot's `pure-protocol-backend` branch for the
 * previous from-scratch protocol implementation this replaces for
 * movement -- kept there in case this architecture is ever abandoned.
 */
public final class MinebotMod implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("minebot-mod");

    private final ControlState controlState = new ControlState();
    private ControlServer controlServer;

    @Override
    public void onInitializeClient() {
        controlServer = new ControlServer(ControlServer.DEFAULT_PORT, this::handleMessage);
        controlServer.start();

        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);

        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            String senderName = sender != null ? sender.name() : null;
            broadcastChatEvent(senderName, message.getString());
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                broadcastChatEvent(null, message.getString());
            }
        });
    }

    private void onClientTick(final Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }

        if (!(player.input instanceof MinebotInput)) {
            player.input = new MinebotInput(controlState, new KeyboardInput(client.options));
        }

        Float targetYaw = controlState.targetYaw;
        if (targetYaw != null) {
            player.setYRot(targetYaw);
        }
        Float targetPitch = controlState.targetPitch;
        if (targetPitch != null) {
            player.setXRot(targetPitch);
        }

        broadcastPositionEvent(player);
    }

    private void handleMessage(final String rawJson) {
        JsonObject json;
        try {
            json = JsonParser.parseString(rawJson).getAsJsonObject();
        } catch (RuntimeException e) {
            LOGGER.warn("control channel: ignoring malformed message: {}", rawJson);
            return;
        }

        String type = json.has("type") ? json.get("type").getAsString() : null;
        if (type == null) {
            return;
        }

        switch (type) {
            case "walk" -> {
                controlState.forward = json.has("forward") && json.get("forward").getAsBoolean();
                controlState.jump = json.has("jump") && json.get("jump").getAsBoolean();
                controlState.sprint = json.has("sprint") && json.get("sprint").getAsBoolean();
                if (json.has("yaw")) {
                    controlState.targetYaw = json.get("yaw").getAsFloat();
                }
                if (json.has("pitch")) {
                    controlState.targetPitch = json.get("pitch").getAsFloat();
                }
            }
            case "stop" -> controlState.clear();
            case "chat" -> {
                Minecraft client = Minecraft.getInstance();
                if (client.player != null && json.has("text")) {
                    client.player.connection.sendChat(json.get("text").getAsString());
                }
            }
            default -> LOGGER.warn("control channel: unknown command type '{}'", type);
        }
    }

    private void broadcastChatEvent(final String sender, final String text) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "chat");
        if (sender != null) {
            event.addProperty("sender", sender);
        }
        event.addProperty("text", text);
        controlServer.broadcastEvent(event.toString());
    }

    private void broadcastPositionEvent(final LocalPlayer player) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "position");
        event.addProperty("x", player.getX());
        event.addProperty("y", player.getY());
        event.addProperty("z", player.getZ());
        event.addProperty("yaw", player.getYRot());
        event.addProperty("pitch", player.getXRot());
        event.addProperty("on_ground", player.onGround());
        controlServer.broadcastEvent(event.toString());
    }
}
