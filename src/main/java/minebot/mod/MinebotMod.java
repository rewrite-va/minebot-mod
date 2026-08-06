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
import minebot.mod.pathfinding.BlockBreaker;
import minebot.mod.statemachine.Blackboard;
import minebot.mod.statemachine.Command;
import minebot.mod.statemachine.CommandBus;
import minebot.mod.statemachine.StateMachine;
import minebot.mod.statemachine.TickContext;
import minebot.mod.statemachine.general.GeneralState;
import minebot.mod.statemachine.general.GeneralStateMachine;
import minebot.mod.statemachine.head.HeadState;
import minebot.mod.statemachine.head.HeadStateMachine;
import minebot.mod.statemachine.legs.LegsNavigateNode;
import minebot.mod.statemachine.legs.LegsState;
import minebot.mod.statemachine.legs.LegsStateMachine;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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

    /**
     * A player's exact position/orientation -- used to decide whether
     * `position` (self) and `entity` "move" (other players) events are
     * actually worth broadcasting, the same change-only-broadcast shape
     * InventoryReporter already established for inventory (see its own
     * docstring for why: a JSON-string comparison was real, unnecessary
     * overhead once already tried elsewhere -- this is a plain value
     * comparison from the start). A buffer of exactly 1 (compared only
     * against the immediately-previous broadcast, not any tolerance
     * band) -- normal physics jitter is real movement and should still
     * get its own event; this only suppresses sending the literal same
     * values again in a row (confirmed live: many consecutive ticks were
     * broadcasting bit-identical position/move data, e.g. while another
     * player stood still, flooding minebot-frontend's live wire viewer
     * with genuinely redundant messages -- not jitter, exact repeats).
     */
    private record PositionSnapshot(double x, double y, double z, float yaw, float pitch) {
    }

    private PositionSnapshot lastBroadcastSelfPosition;
    // Per-entity, since each tracked player's own movement is independent
    // -- player A standing still while player B walks should still only
    // broadcast a "move" for B, not suppress/force one for A too.
    private final Map<Integer, PositionSnapshot> lastBroadcastEntityPosition = new HashMap<>();

    // Mutated from both the client tick thread (broadcastEntityEvents) and
    // the control channel's own WebSocket thread (onControlChannelConnected,
    // triggered by ControlClient's onOpen) -- needs real thread-safety, not
    // just "usually fine", since a race here previously caused every
    // freshly-restarted Python backend to never learn any already-seen
    // player's name (see onControlChannelConnected's docstring).
    private final Set<Integer> knownPlayerIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final FoodEater foodEater = new FoodEater();
    private final InventoryReporter inventoryReporter = new InventoryReporter();
    private final ItemDropTracker itemDropTracker = new ItemDropTracker();
    private final RespawnHandler respawnHandler = new RespawnHandler(this::broadcastDeathEvent, this::broadcastRespawnEvent);
    // The peer-state-machine architecture described in STATE_MACHINE.md.
    // CommandBus is how a typed Command (see its own docstring) crosses
    // from dispatchMessage (WebSocket thread) to state machine edge
    // conditions (tick thread only) -- General and Legs are both built
    // against it, deliberately independent of ControlState's own
    // (largely retired) volatile-fields pattern. Hands/Head follow later,
    // per STATE_MACHINE.md's "Implementation order".
    private final Blackboard blackboard = new Blackboard();
    private final CommandBus commandBus = new CommandBus();
    private final StateMachine<GeneralState> generalStateMachine = GeneralStateMachine.create();
    // Constructed directly (not inside LegsStateMachine.create()) so this
    // class can also hold the reference for PathVisualizer -- see
    // LegsStateMachine.create's own docstring.
    private final LegsNavigateNode legsNavigateNode = new LegsNavigateNode();
    private final StateMachine<LegsState> legsStateMachine = LegsStateMachine.create(legsNavigateNode, generalStateMachine);
    private final StateMachine<HeadState> headStateMachine = HeadStateMachine.create(legsStateMachine);
    private ControlClient controlClient;
    private float lastReportedHealth = -1;

    @Override
    public void onInitializeClient() {
        controlClient = new ControlClient("localhost", ControlClient.DEFAULT_PORT, this::handleMessage, this::onControlChannelConnected);
        controlClient.start();
        new StatusHud(controlClient, List.of(generalStateMachine, legsStateMachine, headStateMachine)).register();
        new PathVisualizer(legsNavigateNode.pathTracker()).register();

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
        inventoryReporter.forceNextBroadcast();
        broadcastHelloEvent();
    }

    /**
     * Reports exactly what code this running mod instance actually is
     * (git commit + build time -- see BuildInfo), the moment the control
     * channel connects. The backend logs this loudly so a stale deployed-
     * but-not-yet-restarted client is obvious from the log instead of
     * looking like a fix that "doesn't work" (found live -- see
     * AGENTS.md's documented deploy trap).
     */
    private void broadcastHelloEvent() {
        JsonObject event = new JsonObject();
        event.addProperty("type", "hello");
        event.addProperty("commit", BuildInfo.COMMIT);
        event.addProperty("built_at", BuildInfo.BUILT_AT);
        controlClient.sendEvent(event.toString());
    }

    private void onClientTick(final Minecraft client) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            knownPlayerIds.clear();
            lastReportedHealth = -1;
            // A fresh connection (this backend restarting, or the mod's
            // own control-channel reconnecting) has no memory of
            // whatever position was last reported to a previous
            // connection -- forcing a fresh comparison baseline here
            // means the very next real tick always broadcasts at least
            // once, the same "don't silently suppress the first report
            // to a new listener" reasoning InventoryReporter's own
            // forceNextBroadcast already established for inventory.
            lastBroadcastSelfPosition = null;
            lastBroadcastEntityPosition.clear();
            return;
        }

        // MinebotInput must be attached before any StateMachine ticks --
        // LegsNavigateNode drives movement by calling ctx.input directly
        // (see TickContext's own docstring for why yaw/pitch are
        // deliberately NOT part of this -- Head SM's exclusive concern).
        if (!(player.input instanceof MinebotInput)) {
            player.input = new MinebotInput(new KeyboardInput(client.options));
        }
        MinebotInput minebotInput = (MinebotInput) player.input;

        // See STATE_MACHINE.md's "Tick order". commandBus.drain() must
        // happen exactly once per tick, here, so every Command published
        // since the last tick (from dispatchMessage, a different thread
        // -- see CommandBus's own docstring) is visible to exactly one
        // tick's worth of edge conditions, never dropped/double-counted.
        // Legs before Head so Head's edges (reading Legs' just-published
        // LegsState) and HeadNavigateNode (reading Legs' AIM_POINT,
        // written directly during Legs' own onTick, so it's actually
        // fresh same-tick regardless of ordering) both see this tick's
        // real data. Hands still to come -- see STATE_MACHINE.md's
        // "Implementation order".
        TickContext ctx = new TickContext(player, level, blackboard, commandBus.drain(), minebotInput);
        generalStateMachine.tick(ctx);
        legsStateMachine.tick(ctx);
        headStateMachine.tick(ctx);

        // TEMPORARY: FoodEater/RespawnHandler still removed from the tick
        // loop -- per explicit direction, to isolate live testing to
        // ONLY what the state-machine architecture itself is driving.
        // FoodEater -> a future Hands SM node; RespawnHandler -> arguably
        // General SM's DEAD state. See git history for the removed call
        // sites if restoring one.

        maybeBroadcastPositionEvent(player);
        broadcastEntityEvents(player, level);
        inventoryReporter.maybeBroadcast(player.getInventory(), controlClient);
        itemDropTracker.tick(level, controlClient);

        float health = player.getHealth();
        if (health != lastReportedHealth) {
            lastReportedHealth = health;
            broadcastHealthEvent(health);
        }
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

        try {
            dispatchMessage(type, json);
        } catch (RuntimeException e) {
            // handleMessage runs on the WebSocket library's own thread
            // (ControlClient.Client.onMessage), not ours -- an exception
            // thrown here is caught by that library's internals, not
            // logged anywhere by us, so a bug in any command handler
            // previously vanished with zero trace in either log (found
            // live debugging !find: a world-access bug produced no
            // response and no error, anywhere).
            LOGGER.warn("control channel: command '{}' failed: {}", type, e.toString(), e);
        }
    }

    /**
     * Deliberately stripped down to ONLY "follow"/"stop" -- see
     * STATE_MACHINE.md and the conversation that produced this: every
     * other command (goto/give/dig_down/collect/attack/chat/
     * move_to_hotbar/equip/drop/find/find_chest/query/debug_swap_test)
     * was removed along with the ControlState.Mode-driven machinery and
     * standalone classes that only existed to support them, rather than
     * carrying old, not-yet-migrated behavior alongside the new peer
     * state-machine architecture. Each command gets reintroduced, one at
     * a time, once it's genuinely backed by a real SM node -- see git
     * history for the removed implementations if reintroducing one.
     */
    private void dispatchMessage(final String type, final JsonObject json) {
        switch (type) {
            case "follow" -> commandBus.publish(new Command.Follow(
                json.get("entity_id").getAsInt(),
                json.has("stop_distance") ? json.get("stop_distance").getAsDouble() : 2.0
            ));
            case "stop" -> commandBus.publish(new Command.Stop());
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
        controlClient.sendEvent(event.toString());
    }

    /**
     * Broadcasts `position` only when it actually differs from the last
     * one sent -- see PositionSnapshot's own docstring: this is a plain
     * exact-duplicate dedup (buffer of 1), not a jitter tolerance, so
     * real movement (however small) always still gets its own event.
     * `on_ground` is deliberately not part of the comparison snapshot --
     * it's a boolean already, so it can't produce duplicate-value spam
     * the way repeated identical position readings can, but including it
     * here would trigger its own broadcast every time it flips fully
     * independent of any position/orientation change (e.g. brief
     * ground-contact flicker while standing still on stairs/slabs);
     * Python has no code path today that reads `on_ground` at all, so
     * there's no consumer this would risk staling.
     */
    private void maybeBroadcastPositionEvent(final LocalPlayer player) {
        PositionSnapshot snapshot = new PositionSnapshot(
            player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()
        );
        if (snapshot.equals(lastBroadcastSelfPosition)) {
            return;
        }
        lastBroadcastSelfPosition = snapshot;

        JsonObject event = new JsonObject();
        event.addProperty("type", "position");
        event.addProperty("name", player.getScoreboardName());
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
     * A real "we died" signal, distinct from the health event's
     * `health <= 0.0`: health can legitimately read exactly 0 only
     * transiently or under other edge cases, whereas this only fires once
     * per RespawnHandler-observed death, right as it also triggers the
     * auto-respawn -- Python can rely on this firing exactly once per
     * death instead of re-deriving "did we just die" from watching health
     * values itself.
     */
    private void broadcastDeathEvent() {
        JsonObject event = new JsonObject();
        event.addProperty("type", "death");
        controlClient.sendEvent(event.toString());
    }

    private void broadcastRespawnEvent() {
        JsonObject event = new JsonObject();
        event.addProperty("type", "respawn");
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
                lastBroadcastEntityPosition.put(id, entityPositionSnapshot(player));
            } else {
                maybeBroadcastEntityMove(id, currentById.get(id));
            }
        }
        for (Integer id : knownPlayerIds) {
            if (!currentIds.contains(id)) {
                broadcastEntityEvent("remove", id, null, null);
                lastBroadcastEntityPosition.remove(id);
            }
        }

        knownPlayerIds.clear();
        knownPlayerIds.addAll(currentIds);
    }

    /** Position-only comparison (no yaw/pitch -- the entity event's own wire shape never carries orientation, only x/y/z) -- see PositionSnapshot's own docstring for why this is an exact-duplicate dedup, not a jitter tolerance. */
    private static PositionSnapshot entityPositionSnapshot(final Entity entity) {
        return new PositionSnapshot(entity.getX(), entity.getY(), entity.getZ(), 0, 0);
    }

    /**
     * Broadcasts an entity "move" only when that specific player's
     * position actually differs from the last one sent for them -- same
     * reasoning/shape as maybeBroadcastPositionEvent, just per-tracked-
     * entity instead of a single self snapshot (see
     * lastBroadcastEntityPosition's own field comment for why a map, not
     * one shared snapshot: each tracked player's movement is
     * independent).
     */
    private void maybeBroadcastEntityMove(final int id, final Player entity) {
        PositionSnapshot snapshot = entityPositionSnapshot(entity);
        if (snapshot.equals(lastBroadcastEntityPosition.get(id))) {
            return;
        }
        lastBroadcastEntityPosition.put(id, snapshot);
        broadcastEntityEvent("move", id, null, entity);
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
