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
import minebot.mod.pathfinding.PathTracker;
import minebot.mod.statemachine.Blackboard;
import minebot.mod.statemachine.Command;
import minebot.mod.statemachine.CommandBus;
import minebot.mod.statemachine.StateMachine;
import minebot.mod.statemachine.TickContext;
import minebot.mod.statemachine.playerintention.CombatEngagement;
import minebot.mod.statemachine.playerintention.PlayerIntentionState;
import minebot.mod.statemachine.playerintention.PlayerIntentionStateMachine;
import minebot.mod.statemachine.playerintention.PlayerIntention;
import minebot.mod.statemachine.hands.HandsState;
import minebot.mod.statemachine.hands.HandsStateMachine;
import minebot.mod.statemachine.head.HeadState;
import minebot.mod.statemachine.head.HeadStateMachine;
import minebot.mod.statemachine.legs.LegsNavigateNode;
import minebot.mod.statemachine.legs.LegsState;
import minebot.mod.statemachine.legs.LegsStateMachine;
import minebot.mod.task.TaskController;
import minebot.mod.testsupport.TestWorldBootstrap;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
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

    // Set at the end of onInitializeClient, once this instance actually
    // exists -- needed so ItemBreakMixin (which has no other way to reach
    // a live MinebotMod instance; mixins target vanilla classes, not this
    // one) can call broadcastItemBrokenEvent. Fabric only ever constructs
    // one MinebotMod per client run, same one-instance assumption
    // ClientModInitializer entry points already rely on.
    private static MinebotMod instance;

    public static MinebotMod getInstance() {
        return instance;
    }

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
    private final InventoryReporter inventoryReporter = new InventoryReporter();
    private final ItemDropTracker itemDropTracker = new ItemDropTracker();
    // The peer-state-machine architecture described in STATE_MACHINE.md.
    // CommandBus is how a typed Command (see its own docstring) crosses
    // from dispatchMessage (WebSocket thread) to state machine edge
    // conditions (tick thread only) -- PlayerIntention and Legs are both built
    // against it, deliberately independent of ControlState's own
    // (largely retired) volatile-fields pattern. Hands/Head follow later,
    // per STATE_MACHINE.md's "Implementation order".
    private final Blackboard blackboard = new Blackboard();
    private final CommandBus commandBus = new CommandBus();
    // What the player actually asked for (IDLE/FOLLOW/DEFEND), constant
    // across incidental interruptions like KILL and, notably, death/
    // respawn (see DeathWatcher's own docstring for why death no longer
    // touches this axis at all) -- see PlayerIntention's own docstring.
    // Updated directly from dispatchMessage (WebSocket thread), read by
    // PlayerIntentionStateMachine's own resume edges (tick thread).
    private final PlayerIntention playerIntention = new PlayerIntention();
    private final StateMachine<PlayerIntentionState> playerIntentionStateMachine = PlayerIntentionStateMachine.create(playerIntention);
    // Ticked directly, not part of any peer StateMachine -- see its own
    // docstring for why.
    private final DeathWatcher deathWatcher = new DeathWatcher(this::broadcastDeathEvent, this::broadcastRespawnEvent);
    // Shared by every Legs node that actually walks (LegsNavigateNode,
    // LegsFleeNode's own delegation into it -- see TickContext's own
    // docstring for why this lives there, reachable via ctx.pathTracker,
    // rather than threaded through node constructors) -- constructed here
    // so this class can also hold the reference for PathVisualizer, and
    // passed into every TickContext built each tick (see its own build
    // site below).
    private final PathTracker legsPathTracker = new PathTracker();
    // Constructed directly (not inside LegsStateMachine.create()) so this
    // class can also hold the reference for PathVisualizer -- see
    // LegsStateMachine.create's own docstring.
    private final LegsNavigateNode legsNavigateNode = new LegsNavigateNode();
    private final StateMachine<LegsState> legsStateMachine = LegsStateMachine.create(legsNavigateNode);
    private final StateMachine<HeadState> headStateMachine = HeadStateMachine.create(legsStateMachine, playerIntentionStateMachine);
    private final StateMachine<HandsState> handsStateMachine = HandsStateMachine.create(legsStateMachine);
    // The generic Task queue described in prompt.txt/TaskController's own
    // docstring -- ticked directly, outside every peer StateMachine, same
    // precedent DeathWatcher/InventoryController's own tick() already
    // established. !give is its first real Task (GiveTask). busyReporter
    // is MinebotMod::sendChat -- see TaskController's own docstring for
    // why a real chat send, not a wire event, is the right channel for
    // its "can't start yet, busy" report.
    private final TaskController taskController = new TaskController(playerIntentionStateMachine, MinebotMod::sendChat);
    private ControlClient controlClient;
    private float lastReportedHealth = -1;

    @Override
    public void onInitializeClient() {
        instance = this;
        TestWorldBootstrap.registerIfRequested();
        // Port overridable via -Dminebot.controlPort=<port> -- needed by
        // the pytest integration driver (see minebot repo's
        // tests/integration/), which launches its own disposable client
        // and must not fight the always-running dev backend for
        // localhost:47893. Defaults to DEFAULT_PORT so normal play (and
        // every existing manual ./gradlew runClient invocation) is
        // completely unaffected -- this is purely additive.
        int controlPort = Integer.getInteger("minebot.controlPort", ControlClient.DEFAULT_PORT);
        controlClient = new ControlClient("localhost", controlPort, this::handleMessage, this::onControlChannelConnected);
        controlClient.start();
        new StatusHud(controlClient, List.of(playerIntentionStateMachine, legsStateMachine, headStateMachine, handsStateMachine), blackboard, taskController).register();
        new PathVisualizer(legsPathTracker).register();

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
        // Legs before Hands before Head, so each sees the previous one's
        // just-published state this same tick (WAYPOINT_COORDINATES/etc.
        // are written directly during Legs' own onTick, so they're fresh
        // same-tick regardless of ordering; HandsMineNode.
        // CURRENT_MINING_TARGET is likewise written directly during Hands'
        // own onTick, specifically so HeadMineNode can read this same
        // tick's real committed mining target rather than one tick stale --
        // see HandsMineNode/HeadMineNode's own docstrings for the live bug
        // this ordering fixes; LegsState/HandsState themselves are each
        // only published at the end of their own StateMachine.tick(), so
        // whichever peer runs after still sees this tick's real value).
        TickContext ctx = new TickContext(player, level, blackboard, commandBus.drain(), minebotInput, legsPathTracker);
        // Before every peer SM -- LegsStateMachine's own GO_TO_DEATH_POSITION
        // entry edge reads DEATH_POSITION this same tick, so a fresh death
        // observed just now must already be visible by the time Legs
        // evaluates its edges below (see DeathWatcher's own docstring).
        deathWatcher.tick(ctx);
        playerIntentionStateMachine.tick(ctx);
        // After PlayerIntention (reads its just-published TARGET_ENTITY_ID
        // this same tick) but before Legs (FIGHT_JUST_ENDED must already
        // be visible by the time Legs evaluates its own post-fight
        // PICKUP_ITEMS edge below) -- same standalone-tick precedent
        // deathWatcher.tick's own placement above establishes, see
        // CombatEngagement.FIGHT_JUST_ENDED's own docstring for why this
        // can't just be a Predicate closure living on Legs instead.
        CombatEngagement.tickEdgeDetection(ctx);
        // After PlayerIntention (isBusy() reads its just-published DEFEND
        // state this same tick) but before Legs (a task's own published
        // NAV_TARGET must already be visible by the time Legs evaluates
        // its own edges below -- same reasoning deathWatcher.tick's own
        // placement above documents for DEATH_POSITION).
        taskController.tick(ctx);
        legsStateMachine.tick(ctx);
        // Hands before Head -- see this method's own tick-order comment
        // above: HeadMineNode needs Hands' just-published
        // CURRENT_MINING_TARGET to aim at the real committed block this
        // same tick, not one tick late.
        handsStateMachine.tick(ctx);
        headStateMachine.tick(ctx);

        // Always-on, independent of every StateMachine above -- see its
        // own docstring for why this doesn't need a state of its own
        // (a single-tick container click, no multi-tick behavior to
        // coordinate with Legs/Head/PlayerIntention at all). Also backs
        // InventoryController.lastPickedUpItem() (bare "!give" with no
        // item argument) -- a pickup landing the same tick a fresh !give
        // arrives is visible to GiveTask.onEnter only next tick, the same
        // one-tick lag every other Blackboard-published fact already has.
        InventoryController.tick(player);

        maybeBroadcastPositionEvent(player);
        broadcastEntityEvents(player, level);
        inventoryReporter.maybeBroadcast(player.getInventory(), controlClient);
        itemDropTracker.tick(level, controlClient);

        float health = player.getHealth();
        if (health != lastReportedHealth) {
            boolean tookDamage = health < lastReportedHealth;
            lastReportedHealth = health;
            broadcastHealthEvent(health);
            // getLastDamageSource() is read the same tick as the health
            // drop it corresponds to, well inside the 40-tick (2s) window
            // LivingEntity self-clears it after -- see its own field doc.
            // Only meaningful on an actual drop: a rise (eating/regen)
            // never has a fresh DamageSource behind it, and reading a
            // stale one here would misattribute it to this tick's change.
            if (tookDamage) {
                broadcastDamageEvent(player.getLastDamageSource());
            }
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
     * Deliberately stripped down to only "follow"/"stop"/"kill"/"defend"/
     * "pickup"/"give"/"chat"/"sleep"/"goto" -- see STATE_MACHINE.md and
     * the conversation that produced this: every other command (dig_down/
     * collect/move_to_hotbar/equip/drop/find/find_chest/query/
     * debug_swap_test) was removed along with the ControlState.Mode-driven
     * machinery and standalone classes that only existed to support them,
     * rather than carrying old, not-yet-migrated behavior alongside the
     * new peer state-machine architecture. Each command gets reintroduced,
     * one at a time, once it's genuinely backed by a real SM node or Task
     * (or, for "chat", confirmed to genuinely need none at all) -- see git
     * history for the removed implementations if reintroducing one.
     * "kill" was the first one reintroduced this way (PlayerIntention:
     * KILL -- originally named COMBAT, renamed once "defend" needed the
     * exact same fighting mechanics with different re-entry semantics --
     * see CombatEngagement's own docstring), later converted to
     * TaskController's own KillTask (see its own docstring) for the same
     * reason "give"/"sleep" are Tasks rather than peer-SM axis values;
     * "defend" is PlayerIntention:DEFEND + the same Hands:MELEE_ATTACK/
     * DRAW_BOW + Head:AIM_AT_TARGET; "pickup" is Legs:PICKUP_ITEMS (see
     * Command.Pickup's own docstring); "goto" is Legs:GOTO (see
     * Command.Goto/LegsGotoNode's own docstrings -- built for the in-game
     * test harness's own first "bot walks A to B" slice, see TESTING.md);
     * "give"/"sleep"/"kill" are TaskController's own GiveTask/SleepTask/
     * KillTask (see TaskController's own docstring for why those are
     * queued Tasks rather than another peer-SM axis); "chat" is the one
     * exception with no SM node/Task behind it at all -- a real vanilla
     * chat send is an instant, stateless side effect (see its own case's
     * docstring below for why), never displaced by re-entering it.
     *
     * Also updates playerIntention here, alongside publishing the
     * Command itself, for "follow"/"stop"/"defend" -- this is the one
     * real place those messages arrive, so it's the natural place to
     * record "what did the player actually ask for" too (see
     * PlayerIntention's own docstring for why that's tracked separately
     * from Command/CommandBus: Command is a one-tick signal an edge
     * reacts to once, intention is a standing fact that survives across
     * many ticks/interruptions). "kill"/"pickup"/"give"/"sleep"
     * deliberately do NOT touch playerIntention -- all four are one-shot
     * triggers/queued Tasks, not standing goals (see PlayerIntention/
     * task/KillTask/Command.Pickup/Command.Give/Command.Sleep's own
     * docstrings).
     */
    private void dispatchMessage(final String type, final JsonObject json) {
        switch (type) {
            case "follow" -> {
                // "player_name" is the real player name -- Python sends
                // this unconditionally now, never a pre-resolved entity
                // id (see PlayerIntention.Snapshot/PlayerController's own
                // docstrings for why a name is the source of truth here:
                // it never goes stale the way an id/uuid resolved once
                // and never updated can, and PlayerController resolves
                // whatever's actually needed fresh every tick instead).
                String playerName = json.get("player_name").getAsString();
                double stopDistance = json.has("stop_distance") ? json.get("stop_distance").getAsDouble() : 2.0;
                playerIntention.follow(playerName);
                commandBus.publish(new Command.Follow(playerName, stopDistance));
            }
            case "stop" -> {
                playerIntention.stop();
                commandBus.publish(new Command.Stop());
            }
            case "kill" -> {
                // "entity_id"/"query" are both optional -- "entity_id"
                // (a player, resolved Python-side via EntityTracker, same
                // as !defend's own target) takes priority when present;
                // otherwise "query" is a raw entity-type string; both
                // absent/null means "nearest hostile mob" (see
                // Command.Kill/KillTask's own docstrings).
                // Deliberately does NOT touch playerIntention -- !kill is
                // read by TaskController.tick() to enqueue a fresh
                // KillTask (or re-target one already running), the same
                // queued-Task shape "give"/"sleep" use, not a peer-SM
                // concern at all (see task/KillTask's own docstring).
                Integer entityId = json.has("entity_id") && !json.get("entity_id").isJsonNull() ? json.get("entity_id").getAsInt() : null;
                String query = json.has("query") && !json.get("query").isJsonNull() ? json.get("query").getAsString() : null;
                commandBus.publish(new Command.Kill(entityId, query));
            }
            case "defend" -> {
                // "player_name" is optional -- absent/null means "defend
                // the bot itself" (see Command.Defend/PlayerIntention's
                // own docstrings). Same player-name-as-source-of-truth
                // shape "follow" now uses, when a real target is given.
                String defendTargetPlayerName = json.has("player_name") && !json.get("player_name").isJsonNull() ? json.get("player_name").getAsString() : null;
                playerIntention.defend(defendTargetPlayerName);
                commandBus.publish(new Command.Defend(defendTargetPlayerName));
            }
            case "pickup" -> {
                // Deliberately does NOT touch playerIntention -- like
                // "kill", this is a one-shot Legs-only reaction (see
                // Command.Pickup/LegsPickupItemsNode's own docstrings for
                // why item-recovery is purely a navigation concern, never
                // a PlayerIntentionState).
                commandBus.publish(new Command.Pickup());
            }
            case "give" -> {
                // Every field optional, per prompt.txt's own !give design:
                // absent/null "recipient_entity_id" means "give to the
                // caller" (drop at the bot's own feet); absent/null "item"
                // means "the last item picked up"; absent/zero "quantity"
                // means "the whole stack" -- see Command.Give/GiveTask's
                // own docstrings for exactly how each is resolved.
                // Deliberately does NOT touch playerIntention -- like
                // pickup/kill, this is a one-shot task, not a standing
                // goal, and isn't even a peer-SM concern at all (see
                // TaskController's own docstring for why give is
                // task-queue-driven instead).
                Integer recipientEntityId = json.has("recipient_entity_id") && !json.get("recipient_entity_id").isJsonNull() ? json.get("recipient_entity_id").getAsInt() : null;
                String item = json.has("item") && !json.get("item").isJsonNull() ? json.get("item").getAsString() : null;
                int quantity = json.has("quantity") && !json.get("quantity").isJsonNull() ? json.get("quantity").getAsInt() : 0;
                commandBus.publish(new Command.Give(recipientEntityId, item, quantity));
            }
            case "goto" -> {
                // "x"/"y"/"z" are required world coordinates -- see
                // Command.Goto/LegsGotoNode's own docstrings. Deliberately
                // does NOT touch playerIntention -- like pickup/kill, this
                // is a one-shot Legs-only reaction, not a standing goal.
                double x = json.get("x").getAsDouble();
                double y = json.get("y").getAsDouble();
                double z = json.get("z").getAsDouble();
                commandBus.publish(new Command.Goto(x, y, z));
            }
            case "sleep" -> {
                // No data at all -- "nearest bed" is the only meaningful
                // target (see Command.Sleep's own docstring). Deliberately
                // does NOT touch playerIntention -- read by TaskController.
                // tick() to enqueue a fresh SleepTask, the same queued-Task
                // shape "give" already uses, not a peer-SM concern at all
                // (see task/SleepTask's own docstring for why).
                commandBus.publish(new Command.Sleep());
            }
            case "chat" -> {
                // Re-added -- was one of the commands stripped down to
                // nothing during the peer-state-machine rewrite (see this
                // method's own docstring), but never actually needed a
                // real SM node behind it the way follow/kill/defend/pickup
                // did: this is a genuine vanilla chat SEND (real
                // ClientboundChat-triggering packet via the player's own
                // connection, visible to every other player, not just a
                // local-only broadcast), same mechanism a human typing in
                // chat uses. Confirmed live this gap meant every one of
                // Python's own chat replies (!follow's "ok, following X",
                // inventory-gain announcements, "I died", ...) arrived
                // back at the mod over the wire but was never actually
                // displayed anywhere a human could see -- reported live as
                // "the bot do not send any message in chat" -- since
                // dispatchMessage's switch had no case for "chat" at all
                // (each wire << {"type": "chat", ...} instead fell through
                // to the unknown-command-type warning). Not routed through
                // CommandBus/any StateMachine at all -- sending a chat
                // message is an instant, one-shot side effect with no
                // ongoing state, the same reasoning PlayerIntentionDeadNode's
                // own docstring gives for DEAD's direct respawn() call.
                if (json.has("text")) {
                    sendChat(json.get("text").getAsString());
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
        controlClient.sendEvent(event.toString());
    }

    /**
     * A genuine vanilla chat SEND, same mechanism/visibility as the "chat"
     * dispatch case above (see its own docstring) -- pulled out into its
     * own static method so TaskController's busyReporter can use it too
     * via a plain method reference, without needing a real dependency on
     * MinebotMod itself. Static (not instance) since it only ever needs
     * Minecraft.getInstance(), the same reasoning EntityFinder/
     * WaypointClassifier's own static-utility shape already established
     * for stateless real-game-state reads. Null-safe the same way the
     * original inline call was -- player can be null for the handful of
     * ticks before the world/player actually loads.
     *
     * Routes `/`-prefixed text through ClientPacketListener.sendCommand
     * (stripping the leading slash) instead of sendChat -- confirmed via
     * decompiled ChatScreen bytecode that this is exactly what vanilla's
     * own chat input box does (checks for a leading '/', calls sendCommand
     * with the slash stripped; sendChat otherwise), and confirmed live
     * this mod was NOT doing that: an in-game-test's own `/tp @s 0 -60 0`
     * sent via the "chat" wire command arrived at the server as a literal
     * CHAT message ("<Player> /tp @s 0 -60 0" visible in vanilla's own
     * chat log) rather than executing as a command at all -- the bot never
     * actually moved, and the caller's own arrival-polling loop timed out
     * with no error pointing at the real cause. Every existing caller of
     * this method (the "chat" wire case, TaskController's busyReporter)
     * already sends both plain chat AND real "/"-commands through the
     * same path, so fixing it here fixes both, not just the new /tp use.
     */
    static void sendChat(final String text) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        if (text.startsWith("/")) {
            client.player.connection.sendCommand(text.substring(1));
        } else {
            client.player.connection.sendChat(text);
        }
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
     * Fires once per real health drop (never on a rise), carrying WHAT hit
     * us -- something the plain health event can't express (it's just a
     * polled/diffed float, no cause). `source` can genuinely be null: the
     * game can dock health (or LivingEntity can fail to have recorded a
     * DamageSource in time -- see getLastDamageSource()'s own 40-tick
     * expiry) without one, so "hostile" only reports true when a source
     * was actually available AND matches a monster/player-attack damage
     * type; anything else (fall, fire, drown, unknown/null) reports false
     * rather than guessing. Python-side auto-defend deliberately only
     * reacts to hostile=true, so a bot that face-plants off a cliff
     * doesn't spuriously enter combat stance over fall damage.
     */
    private void broadcastDamageEvent(final DamageSource source) {
        boolean hostile = source != null && (
            source.is(DamageTypes.MOB_ATTACK)
                || source.is(DamageTypes.MOB_ATTACK_NO_AGGRO)
                || source.is(DamageTypes.PLAYER_ATTACK)
                || source.is(DamageTypes.MOB_PROJECTILE)
                || source.is(DamageTypes.ARROW)
                || source.is(DamageTypes.TRIDENT)
                || source.is(DamageTypes.STING)
                || source.is(DamageTypes.SPEAR)
                || source.is(DamageTypes.MACE_SMASH)
                || source.is(DamageTypes.THORNS)
        );

        JsonObject event = new JsonObject();
        event.addProperty("type", "damage");
        event.addProperty("hostile", hostile);
        event.addProperty("cause", source != null ? source.type().msgId() : null);
        Entity attacker = source != null ? source.getEntity() : null;
        event.addProperty("attacker", attacker != null ? attacker.getName().getString() : null);
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

    /**
     * Fires exactly once per item destroyed by durability loss -- see
     * ItemBreakMixin's own docstring for the vanilla hook this comes from.
     * `item`/`slot` are handed straight from that hook rather than read
     * back off the equipment slot, since the stack is already cleared by
     * the time onEquippedItemBroken runs.
     */
    public void broadcastItemBrokenEvent(final net.minecraft.world.item.Item item, final net.minecraft.world.entity.EquipmentSlot slot) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "item_broken");
        event.addProperty("item", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString());
        event.addProperty("slot", slot.getName());
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
