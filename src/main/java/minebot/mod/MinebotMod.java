package minebot.mod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Entry point for the client-mod half of minebot's architecture pivot: the
 * mod runs inside a real Minecraft client logged into the bot's account
 * (normal Microsoft/Mojang auth, nothing custom), and is the *only* thing
 * that actually talks to the Minecraft server. The Python backend
 * (separate repo) connects to this mod's local WebSocket control channel
 * (ControlServer) instead of implementing the protocol itself -- it sends
 * high-level goals ("follow entity N", "goto x y z", "stop") and receives
 * game events (chat, position, entities, health) back, while all actual
 * movement runs through Minecraft's own real physics via MinebotInput.
 *
 * See /home/colaila/git/minebot's `pure-protocol-backend` branch for the
 * previous from-scratch protocol implementation this replaces for
 * movement -- kept there in case this architecture is ever abandoned.
 */
public final class MinebotMod implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("minebot-mod");

    private static final double MAX_STEP_HEIGHT_TRIGGER = 0.1; // aim y this much above us before holding jump

    private final ControlState controlState = new ControlState();
    private final Set<Integer> knownPlayerIds = new HashSet<>();
    private ControlServer controlServer;
    private float lastReportedHealth = -1;

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
        ClientLevel level = client.level;
        if (player == null || level == null) {
            knownPlayerIds.clear();
            lastReportedHealth = -1;
            return;
        }

        MovementIntent intent = resolveMovementIntent(player, level);
        if (!(player.input instanceof MinebotInput)) {
            player.input = new MinebotInput(new KeyboardInput(client.options));
        }
        ((MinebotInput) player.input).setIntent(intent);
        if (intent.yaw != null) {
            player.setYRot(intent.yaw);
        }

        broadcastPositionEvent(player);
        broadcastEntityEvents(player, level);

        float health = player.getHealth();
        if (health != lastReportedHealth) {
            lastReportedHealth = health;
            broadcastHealthEvent(health);
        }
    }

    /**
     * Turns the current high-level goal (ControlState) into a concrete
     * per-tick movement input: aim yaw at the goal's target position, hold
     * forward while still farther than stop_distance, hold jump whenever
     * the target sits meaningfully above us (matches how a real player
     * reaches a ledge one step higher than automatic step-height climbing
     * alone covers -- see minebot's earlier pure-Python physics port,
     * FINDINGS.md, for why this specific heuristic was chosen there;
     * reused here since it's the same real-client movement problem).
     */
    private MovementIntent resolveMovementIntent(final LocalPlayer player, final ClientLevel level) {
        MovementIntent intent = new MovementIntent();

        Double[] target = switch (controlState.mode) {
            case IDLE -> null;
            case GOTO -> new Double[]{controlState.gotoX, controlState.gotoY, controlState.gotoZ};
            case FOLLOW -> {
                Entity followed = level.getEntity(controlState.followEntityId);
                yield followed != null
                    ? new Double[]{followed.getX(), followed.getY(), followed.getZ()}
                    : null;
            }
        };

        if (target == null) {
            return intent;
        }

        double dx = target[0] - player.getX();
        double dz = target[2] - player.getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

        if (horizontalDistance > controlState.stopDistance) {
            intent.forward = true;
            // Vanilla yaw convention: 0 = south/+z, matching Entity.getYRot()
            // and the atan2(-dx, dz) form used throughout the decompiled
            // source's own movement code.
            intent.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        }

        double dy = target[1] - player.getY();
        if (dy > MAX_STEP_HEIGHT_TRIGGER) {
            intent.jump = true;
        }

        return intent;
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
            case "goto" -> controlState.setGoto(
                json.get("x").getAsDouble(), json.get("y").getAsDouble(), json.get("z").getAsDouble(),
                json.has("stop_distance") ? json.get("stop_distance").getAsDouble() : 2.0
            );
            case "follow" -> controlState.setFollow(
                json.get("entity_id").getAsInt(),
                json.has("stop_distance") ? json.get("stop_distance").getAsDouble() : 2.0
            );
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

    private void broadcastHealthEvent(final float health) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "health");
        event.addProperty("health", health);
        controlServer.broadcastEvent(event.toString());
    }

    /**
     * Diffs the client's currently-loaded player list against what we
     * reported last tick and broadcasts add/remove/move events -- this is
     * how the Python side resolves a chat-typed name ("!follow Steve") to
     * an entity id for the `follow` command, and how it knows a followed
     * entity's position without re-querying every tick itself. Only
     * players are tracked (not every entity type): the only thing minebot
     * currently needs to path toward is another player.
     */
    private void broadcastEntityEvents(final LocalPlayer self, final ClientLevel level) {
        Set<Integer> currentIds = new HashSet<>();
        Map<Integer, Player> currentById = new HashMap<>();
        for (Player other : level.players()) {
            if (other == self) {
                continue;
            }
            currentIds.add(other.getId());
            currentById.put(other.getId(), other);
        }

        for (Integer id : currentIds) {
            if (!knownPlayerIds.contains(id)) {
                Player player = currentById.get(id);
                broadcastEntityEvent("add", id, player.getScoreboardName(), player);
            } else {
                broadcastEntityEvent("move", id, null, currentById.get(id));
            }
        }
        for (Integer id : knownPlayerIds) {
            if (!currentIds.contains(id)) {
                broadcastEntityEvent("remove", id, null, null);
            }
        }

        knownPlayerIds.clear();
        knownPlayerIds.addAll(currentIds);
    }

    private void broadcastEntityEvent(final String action, final int id, final String name, final Entity entity) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "entity");
        event.addProperty("action", action);
        event.addProperty("id", id);
        if (name != null) {
            event.addProperty("name", name);
        }
        if (entity != null) {
            event.addProperty("x", entity.getX());
            event.addProperty("y", entity.getY());
            event.addProperty("z", entity.getZ());
        }
        controlServer.broadcastEvent(event.toString());
    }
}
