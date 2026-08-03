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
import minebot.mod.pathfinding.DoorOpener;
import minebot.mod.pathfinding.Move;
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
 * (separate repo) runs its own WebSocket server, and this mod connects
 * out to it (ControlClient) instead of implementing the protocol itself --
 * Python sends high-level goals ("follow entity N", "goto x y z", "stop")
 * and receives game events (chat, position, entities, health) back, while
 * all actual movement runs through Minecraft's own real physics via
 * MinebotInput.
 *
 * The mod is the WebSocket *client*, not the server, specifically because
 * the Python backend commonly runs inside WSL2: WSL2's default networking
 * only forwards localhost connections from Windows into WSL2, not the
 * reverse, so a mod-side server was unreachable from Python no matter how
 * it was bound (confirmed live) -- see ControlClient's docstring for the
 * full story.
 *
 * See /home/colaila/git/minebot's `pure-protocol-backend` branch for the
 * previous from-scratch protocol implementation this replaces for
 * movement -- kept there in case this architecture is ever abandoned.
 */
public final class MinebotMod implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("minebot-mod");

    private static final double MAX_STEP_HEIGHT_TRIGGER = 0.1; // aim y this much above us before holding jump

    private final ControlState controlState = new ControlState();
    // Mutated from both the client tick thread (broadcastEntityEvents) and
    // the control channel's own WebSocket thread (onControlChannelConnected,
    // triggered by ControlClient's onOpen) -- needs real thread-safety, not
    // just "usually fine", since a race here previously caused every
    // freshly-restarted Python backend to never learn any already-seen
    // player's name (see onControlChannelConnected's docstring).
    private final Set<Integer> knownPlayerIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final DoorOpener doorOpener = new DoorOpener();
    private final FoodEater foodEater = new FoodEater();
    private final InventoryReporter inventoryReporter = new InventoryReporter();
    private ControlClient controlClient;
    private float lastReportedHealth = -1;

    @Override
    public void onInitializeClient() {
        controlClient = new ControlClient("localhost", ControlClient.DEFAULT_PORT, this::handleMessage, this::onControlChannelConnected);
        controlClient.start();
        new StatusHud(controlClient).register();

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

    /**
     * A fresh Python process has no memory of any entity we've already
     * reported "add" for in a previous connection -- its EntityTracker
     * starts empty every time. Without this reset, knownPlayerIds (which
     * outlives individual control-channel connections, since the game
     * client itself doesn't restart) would keep treating already-seen
     * players as already-known and only ever send "move" events for them,
     * so a freshly (re)started backend could never learn their name (found
     * live: !follow failed with "no known entity" because only "move"
     * events -- which carry no name -- had ever been sent for the
     * player).
     */
    private void onControlChannelConnected() {
        knownPlayerIds.clear();
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

        maybeCompleteGive(player, level);

        foodEater.maybeEat(player);

        broadcastPositionEvent(player);
        broadcastEntityEvents(player, level);
        inventoryReporter.maybeBroadcast(player.getInventory(), controlClient);

        float health = player.getHealth();
        if (health != lastReportedHealth) {
            lastReportedHealth = health;
            broadcastHealthEvent(health);
        }
    }

    /**
     * GIVE walks toward the recipient the same way FOLLOW does (see
     * resolveMovementIntent's GIVE branch); once within stopDistance of
     * them, this drops the requested slot/count and clears back to IDLE.
     * Checked every tick right after movement resolves, using the same
     * live entity lookup resolveMovementIntent uses -- if the recipient
     * has gone out of view (level.getEntity returns null, e.g. they
     * disconnected or moved out of render distance), the goal is
     * abandoned rather than left stuck forever with no target to walk
     * toward or measure distance to.
     */
    private void maybeCompleteGive(final LocalPlayer player, final ClientLevel level) {
        if (controlState.mode != ControlState.Mode.GIVE) {
            return;
        }
        Entity recipient = level.getEntity(controlState.followEntityId);
        if (recipient == null) {
            LOGGER.warn("give: recipient entity {} no longer visible, abandoning", controlState.followEntityId);
            controlState.clear();
            return;
        }
        double dx = recipient.getX() - player.getX();
        double dy = recipient.getY() - player.getY();
        double dz = recipient.getZ() - player.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance > controlState.stopDistance) {
            return;
        }
        InventoryActions.drop(player, controlState.giveSlot, controlState.giveCount);
        controlState.clear();
    }

    /**
     * Turns the current high-level goal (ControlState) into a concrete
     * per-tick movement input. Rather than walking straight at the goal's
     * raw (x, y, z) -- which only worked for small ledges, since it relied
     * on passive gravity/step-height to cover any vertical gap, and left
     * the bot stranded at the edge of a floor instead of finding a real
     * route down -- this plans (or reuses a still-fresh) A* path via
     * ControlState.pathTracker and aims at the next waypoint along it
     * instead. Falls back to the raw target position if pathfinding
     * couldn't find a route (e.g. unloaded chunks) so the bot still tries
     * to make progress rather than freezing.
     */
    private MovementIntent resolveMovementIntent(final LocalPlayer player, final ClientLevel level) {
        MovementIntent intent = new MovementIntent();

        Double[] target = switch (controlState.mode) {
            case IDLE -> null;
            case GOTO -> new Double[]{controlState.gotoX, controlState.gotoY, controlState.gotoZ};
            case FOLLOW, GIVE -> {
                Entity followed = level.getEntity(controlState.followEntityId);
                yield followed != null
                    ? new Double[]{followed.getX(), followed.getY(), followed.getZ()}
                    : null;
            }
        };

        if (target == null) {
            controlState.pathTracker.reset();
            return intent;
        }

        double selfX = player.getX();
        double selfY = player.getY();
        double selfZ = player.getZ();

        controlState.pathTracker.maybeReplan(
            level, selfX, selfY, selfZ, target[0], target[1], target[2], controlState.stopDistance
        );
        Move waypoint = controlState.pathTracker.nextWaypoint(selfX, selfY, selfZ);
        doorOpener.maybeOpenDoorNear(player, level, waypoint);

        // Aim at the next unreached waypoint's block center, or the raw
        // target if we have no plan (no path found / not yet computed).
        double aimX = waypoint != null ? waypoint.x + 0.5 : target[0];
        double aimY = waypoint != null ? waypoint.y : target[1];
        double aimZ = waypoint != null ? waypoint.z + 0.5 : target[2];

        double dx = aimX - selfX;
        double dz = aimZ - selfZ;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

        // Only the raw target (not a waypoint) should stop the bot when
        // close enough -- a waypoint just short of the goal must still be
        // walked through, not treated as "arrived".
        double distanceToStopAt = waypoint != null ? 0.0 : controlState.stopDistance;

        if (horizontalDistance > distanceToStopAt) {
            intent.forward = true;
            // Vanilla yaw convention: 0 = south/+z, matching Entity.getYRot()
            // and the atan2(-dx, dz) form used throughout the decompiled
            // source's own movement code.
            intent.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        }

        double dy = aimY - selfY;
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
            case "move_to_hotbar" -> withPlayer(player -> InventoryActions.moveToHotbar(
                player, json.get("slot").getAsInt(), json.get("hotbar_slot").getAsInt()
            ));
            case "equip" -> withPlayer(player -> InventoryActions.equip(player, json.get("slot").getAsInt()));
            case "drop" -> withPlayer(player -> InventoryActions.drop(
                player, json.get("slot").getAsInt(), json.get("count").getAsInt()
            ));
            case "give" -> controlState.setGive(
                json.get("entity_id").getAsInt(), json.get("slot").getAsInt(), json.get("count").getAsInt(),
                json.has("stop_distance") ? json.get("stop_distance").getAsDouble() : 2.0
            );
            default -> LOGGER.warn("control channel: unknown command type '{}'", type);
        }
    }

    /**
     * handleMessage runs on the WebSocket's own network thread (see
     * ControlClient), not the client tick thread -- unlike "chat" (which
     * only needs the connection, already null-checked inline) the new
     * inventory commands need the live LocalPlayer, which can be briefly
     * null between a disconnect and reconnect (same window the "chat"
     * case's own inline null check already guards against). Centralizes
     * that guard instead of repeating it in every new case.
     */
    private void withPlayer(final java.util.function.Consumer<LocalPlayer> action) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            action.accept(player);
        }
    }

    private void broadcastChatEvent(final String sender, final String text) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "chat");
        if (sender != null) {
            event.addProperty("sender", sender);
        }
        event.addProperty("text", text);
        controlClient.sendEvent(event.toString());
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
        controlClient.sendEvent(event.toString());
    }

    private void broadcastHealthEvent(final float health) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "health");
        event.addProperty("health", health);
        controlClient.sendEvent(event.toString());
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
        controlClient.sendEvent(event.toString());
    }
}
