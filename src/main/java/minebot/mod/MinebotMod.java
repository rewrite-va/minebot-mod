package minebot.mod;

import com.google.gson.JsonArray;
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
import minebot.mod.pathfinding.BlockFinder;
import minebot.mod.pathfinding.DoorOpener;
import minebot.mod.pathfinding.Move;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
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

    private static final double MAX_STEP_HEIGHT_TRIGGER = 0.1; // aim y this much above us before holding jump
    private static final int DEFAULT_FIND_RADIUS = 64;

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

    private final ControlState controlState = new ControlState();
    // Mutated from both the client tick thread (broadcastEntityEvents) and
    // the control channel's own WebSocket thread (onControlChannelConnected,
    // triggered by ControlClient's onOpen) -- needs real thread-safety, not
    // just "usually fine", since a race here previously caused every
    // freshly-restarted Python backend to never learn any already-seen
    // player's name (see onControlChannelConnected's docstring).
    private final Set<Integer> knownPlayerIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final DoorOpener doorOpener = new DoorOpener();
    private final BlockBreaker pathBlockBreaker = new BlockBreaker("pathfinding");
    // Separate BlockBreaker instances per mode -- each tracks its own
    // single in-progress break (see BlockBreaker's own docstring), and
    // DIG_DOWN/COLLECT/pathfinding-through-a-wall can each have a
    // different target block in play, so sharing one instance across
    // modes would make one mode's break silently reset another's
    // in-progress progress the moment they targeted different blocks.
    // Each is labeled (see BlockBreaker's own constructor) so its debug
    // logging identifies which one produced a given line -- added
    // chasing a live report that looked like two different breakers were
    // fighting over the same block during !collect.
    private final BlockBreaker digDownBreaker = new BlockBreaker("digDown");
    private final BlockBreaker collectBreaker = new BlockBreaker("collect");
    private final FoodEater foodEater = new FoodEater();
    private final InventoryReporter inventoryReporter = new InventoryReporter();
    private final ItemDropTracker itemDropTracker = new ItemDropTracker();
    private final RespawnHandler respawnHandler = new RespawnHandler(this::broadcastDeathEvent, this::broadcastRespawnEvent);
    private final NearbyPlayerLookAt nearbyPlayerLookAt = new NearbyPlayerLookAt();
    private ControlClient controlClient;
    private float lastReportedHealth = -1;

    @Override
    public void onInitializeClient() {
        controlClient = new ControlClient("localhost", ControlClient.DEFAULT_PORT, this::handleMessage, this::onControlChannelConnected);
        controlClient.start();
        new StatusHud(controlClient).register();
        new PathVisualizer(controlState.pathTracker).register();
        new BlockTargetVisualizer(pathBlockBreaker, digDownBreaker, collectBreaker).register();

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

        MovementIntent intent = resolveMovementIntent(player, level);
        if (!(player.input instanceof MinebotInput)) {
            player.input = new MinebotInput(new KeyboardInput(client.options));
        }
        ((MinebotInput) player.input).setIntent(intent);

        // Only look at a nearby player while genuinely idle (no goal at
        // all) -- checking intent.yaw == null alone isn't enough:
        // BlockBreaker.aimAt sets yaw/pitch *directly* on the player
        // (not through MovementIntent) while mining, so resolveMovementIntent
        // still reports yaw == null on every tick spent breaking a block,
        // and this override was undoing aimAt's work the instant a
        // player got close enough to trigger it -- reported live: mining
        // visibly slowed down/stopped progressing whenever a nearby
        // player approached, since the bot kept glancing at them instead
        // of the block, and BlockState.getDestroyProgress only counts
        // ticks actually spent looking at (and swinging at) the target.
        // ControlState.mode is the actual "is there a current goal"
        // signal -- IDLE is the only mode with nothing at all in
        // progress (GOTO/FOLLOW/GIVE/DIG_DOWN/COLLECT/ATTACK all have a
        // real goal, whether or not it happens to be setting yaw via
        // MovementIntent this specific tick).
        if (intent.yaw == null && controlState.mode == ControlState.Mode.IDLE) {
            MovementIntent lookIntent = nearbyPlayerLookAt.resolve(player, level);
            if (lookIntent != null) {
                intent = lookIntent;
            }
        }

        if (intent.yaw != null) {
            player.setYRot(intent.yaw);
        }
        if (intent.pitch != null) {
            player.setXRot(intent.pitch);
        }

        maybeCompleteGive(player, level);
        tickDigDown(player, level);
        tickCollect(player, level);
        tickAttack(player, level);

        respawnHandler.tick(player);
        if (player.isDeadOrDying()) {
            // FoodEater isn't ticked while dead (no point trying to eat at
            // 0 health), but it may have left the real `keyUse` keybind
            // held down from the moment before death -- release it here
            // so it doesn't stay stuck through death/respawn (which would
            // either resume eating immediately regardless of the fresh
            // post-respawn state, or leave a human retaking manual
            // control later finding right-click stuck held).
            foodEater.releaseUseKeyIfHeld();
            // Same reasoning, for BlockBreaker's keyAttack hold (see its
            // own docstring for why it holds the real keybind now instead
            // of calling continueDestroyBlock directly) -- a break in
            // progress at the moment of death would otherwise leave attack
            // stuck held through death/respawn.
            BlockBreaker.releaseAttackKeyIfHeld();
        } else {
            foodEater.maybeEat(player);
        }

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
     * !digDown: breaks the block directly below the player, repeatedly,
     * `digDownRemaining` times -- stops early (and reports why via
     * dig_down_result) if the next block down is lava/water, or if
     * there's no solid floor within a safety margin below the block just
     * broken (a "big drop", per PENDING.md's explicit ask mirroring
     * mindcraft's skills.digDown). No pathfinding/A* involved at all --
     * this is a stationary loop, not a movement goal (see
     * resolveMovementIntent's DIG_DOWN case, which handles the one real
     * movement need -- centering over the current column, see
     * centerDigDownIntent -- separately from this method's own breaking
     * logic).
     */
    private static final int DIG_DOWN_DROP_SAFETY_MARGIN = 3;

    private void tickDigDown(final LocalPlayer player, final ClientLevel level) {
        if (controlState.mode != ControlState.Mode.DIG_DOWN) {
            return;
        }

        if (!isCenteredForDig(player)) {
            // resolveMovementIntent's DIG_DOWN case is walking the bot to
            // center this same tick -- don't start (or continue aiming/
            // swinging at) a break until it gets there, or the target
            // column computed here could point at the wrong block
            // relative to where the bot ends up standing.
            return;
        }

        // tickSettle every tick regardless of what runs below, so a settle
        // window started by the previous block's break actually counts
        // down -- see BlockBreaker.isBusy's own docstring for why the next
        // block shouldn't start until the last one has actually had time
        // to settle, not just appear locally gone.
        digDownBreaker.tickSettle(level);
        // Only skip calling tryBreak while purely settling (no active
        // target) -- gating this on isBusy() alone (the old check here)
        // deadlocked forever the moment a tool switch made tryBreak
        // return false without completing anything (see BlockBreaker.
        // hasActiveTarget's own docstring for the live repro that found
        // this): isBusy() was already true from that point on, so this
        // would never call tryBreak again and the dig could never
        // progress past the very first block that needed a tool switch.
        if (!digDownBreaker.hasActiveTarget() && digDownBreaker.isBusy()) {
            return;
        }

        BlockPos below = player.blockPosition().below();
        BlockState belowState = level.getBlockState(below);
        if (!belowState.getFluidState().isEmpty()) {
            int broken = controlState.digDownTotal - controlState.digDownRemaining;
            digDownBreaker.stopBreaking();
            controlState.clear();
            broadcastDigDownResultEvent(broken, "hit lava/water");
            return;
        }

        boolean brokeThisTick = digDownBreaker.tryBreak(player, level, below);
        if (!brokeThisTick) {
            return; // still breaking, out of reach, or (see BlockBreaker.tryBreak) below was already air with nothing this call actually broke
        }

        controlState.digDownRemaining--;

        // Check what's below what we just broke -- a genuine "big drop"
        // (open air all the way down past the safety margin, no floor to
        // catch the fall) should stop here rather than keep digging into
        // a fall the bot has no way to recover from mid-loop.
        BlockPos scan = below.below();
        boolean foundFloorOrLiquid = false;
        for (int i = 0; i < DIG_DOWN_DROP_SAFETY_MARGIN; i++) {
            BlockState scanState = level.getBlockState(scan);
            if (!scanState.getFluidState().isEmpty() || !scanState.isAir()) {
                foundFloorOrLiquid = true;
                break;
            }
            scan = scan.below();
        }
        if (!foundFloorOrLiquid) {
            int broken = controlState.digDownTotal - controlState.digDownRemaining;
            digDownBreaker.stopBreaking();
            controlState.clear();
            broadcastDigDownResultEvent(broken, "big drop ahead");
            return;
        }

        if (controlState.digDownRemaining <= 0) {
            int broken = controlState.digDownTotal;
            digDownBreaker.stopBreaking();
            controlState.clear();
            broadcastDigDownResultEvent(broken, null);
        }
    }

    /**
     * !collect: a single attempt to find the nearest match for
     * collectQuery (entity type tried first, block type as fallback --
     * resolveNearest, same order !find uses), walk to it via the normal
     * GOTO-style pathfinding (resolveMovementIntent's COLLECT case reads
     * gotoX/Y/Z or followEntityId depending on collectTargetIsEntity),
     * then either mine it (BlockBreaker) or fight it (repeated
     * MultiPlayerGameMode.attack calls) once in range -- one item, then
     * back to IDLE, reporting collect_result either way. Deliberately not
     * a counted loop anymore (it used to repeat internally against a
     * requested count) -- collecting N is now the Python backend's job:
     * MiningController.collect sends N separate one-item `collect`
     * commands, watching real inventory-gain events between each to
     * confirm one actually landed before asking for the next. Moving
     * that bookkeeping to Python is what makes each individual collect
     * attempt small and atomic enough to interrupt cleanly (a new chat
     * command can supersede "walking to this one block" far more
     * responsively than it could ever interrupt "the 6th of 10 total").
     */
    private static final double COLLECT_MELEE_RANGE = 3.0;
    // How many ticks (10s at 20 ticks/sec) a single target gets before
    // tickCollect gives up on it and tries the next-nearest match
    // instead -- generous enough that legitimately walking there (even
    // across a large map via pathfinding) never trips it, but bounded so
    // a target that can never actually be reached (see BlockBreaker's
    // line-of-sight check -- a block found by BlockFinder's pure
    // distance scan can be on the far side of a wall from wherever the
    // bot ends up standing, with no angle that ever makes it visible)
    // doesn't strand this attempt forever on one impossible candidate.
    private static final int COLLECT_TARGET_TIMEOUT_TICKS = 200;
    // How many ticks the pickup-walk phase gets before giving up and
    // completing the attempt anyway -- see ControlState.
    // collectPickupStuckTicks's own docstring for why this exists
    // (bounding a drop that rolled somewhere genuinely unreachable) and
    // why "give up" here still means "report success", not failure: the
    // block/entity really was destroyed/killed either way, this phase
    // only ever tries to *also* physically collect the result, it was
    // never the thing that decided success/failure in the first place
    // (Python's own drop-confirmation, watching real inventory counts,
    // already owns that decision -- see MiningController.collect).
    // Shorter than COLLECT_TARGET_TIMEOUT_TICKS (10s) since a real drop
    // sitting in melee range should be reachable in well under that.
    private static final int COLLECT_PICKUP_TIMEOUT_TICKS = 100;
    // How far (blocks) to search for the dropped item(s) DropTable says
    // this collect's target should have produced -- generous enough to
    // cover a drop that rolled/bounced a little from where the block/
    // entity was, tight enough that this doesn't accidentally walk the
    // bot toward an unrelated item of the same type sitting somewhere
    // else on the map.
    private static final double COLLECT_PICKUP_SEARCH_RADIUS = 6.0;

    private void tickCollect(final LocalPlayer player, final ClientLevel level) {
        if (controlState.mode != ControlState.Mode.COLLECT) {
            return;
        }

        if (controlState.collectPickingUp) {
            tickCollectPickup(player, level);
            return;
        }

        if (!controlState.collectHasTarget) {
            NearestMatch match = resolveNearest(
                level, player, controlState.collectQuery, controlState.collectRadius, controlState.collectExcludedPositions
            );
            if (match == null) {
                String query = controlState.collectQuery;
                controlState.clear();
                broadcastCollectResultEvent(false, query, "no more " + query + " found nearby");
                return;
            }
            controlState.collectTargetIsEntity = match.entity != null;
            if (match.entity != null) {
                controlState.followEntityId = match.entity.getId();
            } else {
                controlState.gotoX = match.x;
                controlState.gotoY = match.y;
                controlState.gotoZ = match.z;
            }
            controlState.collectHasTarget = true;
            controlState.collectTargetStuckTicks = 0;
            controlState.pathTracker.reset();
            LOGGER.info(
                "collect: new target -- kind={} at ({}, {}, {}), excluded so far: {}",
                match.kind, match.x, match.y, match.z, controlState.collectExcludedPositions
            );
            return; // resolveMovementIntent picks up the fresh target next tick
        }

        BlockPos blockTargetPos = controlState.collectTargetIsEntity ? null : new BlockPos(
            (int) Math.floor(controlState.gotoX), (int) Math.floor(controlState.gotoY), (int) Math.floor(controlState.gotoZ)
        );

        boolean collectedThisTick;
        if (controlState.collectTargetIsEntity) {
            collectedThisTick = tickCollectEntity(player, level);
        } else {
            // tickSettle every tick regardless of which branch below
            // actually runs, so a settle window started by an earlier
            // tick's break actually counts down. While still settling,
            // deliberately skip tickCollectBlock's own live-block-state
            // check entirely -- checking justFinishedSettling(pos)
            // instead of re-deriving completion from level.getBlockState
            // is what lets this tell "my own break just finished
            // settling" apart from "something else made this position
            // air" once the window elapses; re-entering tickCollectBlock
            // itself right as/after settling would otherwise see the
            // (correctly, now-air) target and take its "something else
            // must have removed this" abandonment branch instead --
            // misclassifying a real success as an abandoned target,
            // excluding its own just-broken position, and never reporting
            // completion at all. See BlockBreaker.isBusy's own docstring
            // for why treating a break's client-predicted completion as
            // immediately final (the old behavior here, before any settle
            // window existed) is unsafe in the first place: !collect was
            // reporting collect_result success and clearing back to IDLE
            // the instant a block visually disappeared, well before any
            // server-side confirmation or drop could plausibly have
            // landed.
            collectBreaker.tickSettle(level);
            // Only skip calling tickCollectBlock (which calls tryBreak)
            // while purely settling with no active target -- gating this
            // on isBusy() alone (the old check here) deadlocked forever
            // the moment a tool switch made tryBreak return false without
            // completing anything (see BlockBreaker.hasActiveTarget's own
            // docstring for the live repro that found this class of bug,
            // in !debug and !dig): isBusy() was already true from that
            // point on, so this would never call tryBreak again and the
            // target could never actually be mined.
            if (!collectBreaker.hasActiveTarget() && collectBreaker.isBusy()) {
                collectedThisTick = false;
            } else if (collectBreaker.justFinishedSettling(blockTargetPos)) {
                collectedThisTick = true;
            } else {
                collectedThisTick = tickCollectBlock(player, level);
            }
        }

        if (!collectedThisTick) {
            // Still gated behind collectHasTarget -- tickCollectBlock's
            // own already-air guard already clears that flag and returns
            // early in the "target vanished" case, so this only counts
            // real "tried and made no progress" ticks (out of range,
            // obstructed, still walking there, mid-break, ...).
            if (controlState.collectHasTarget && ++controlState.collectTargetStuckTicks > COLLECT_TARGET_TIMEOUT_TICKS) {
                LOGGER.warn(
                    "collect: giving up on unreachable target after {} ticks (query={})",
                    controlState.collectTargetStuckTicks, controlState.collectQuery
                );
                collectBreaker.stopBreaking();
                if (!controlState.collectTargetIsEntity) {
                    // Entities move, so excluding a position for them
                    // doesn't mean anything -- only block targets are
                    // fixed enough for "don't find this exact position
                    // again" to be the right exclusion.
                    controlState.collectExcludedPositions.add(new BlockPos(
                        (int) Math.floor(controlState.gotoX), (int) Math.floor(controlState.gotoY), (int) Math.floor(controlState.gotoZ)
                    ));
                }
                controlState.collectHasTarget = false;
                // Deliberately not clearing back to IDLE here -- the
                // next tick's !controlState.collectHasTarget branch
                // above will search again (now with this position
                // excluded) and try the next-nearest candidate, same
                // "one attempt can retry several candidates" behavior
                // this had before, just without a count driving it.
            }
            return;
        }

        String kind = controlState.collectTargetIsEntity ? "entity" : "block";
        Vec3 destroyedAt = controlState.collectTargetIsEntity
            ? player.position() // the entity is gone by now -- search from the bot's own position instead
            : new Vec3(controlState.gotoX, controlState.gotoY, controlState.gotoZ);
        collectBreaker.stopBreaking();
        LOGGER.info(
            "collect: destroyed/killed the target (kind={}) -- note this fires the instant that happens, NOT once any drop is actually in inventory",
            kind
        );

        // Look for a real dropped item before declaring this attempt
        // done -- see ControlState.collectPickingUp's own docstring for
        // why (the old behavior, reporting collect_result and clearing
        // to IDLE right here, relied entirely on incidental proximity
        // from mining to actually collect the drop, with zero explicit
        // retrieval effort).
        ItemEntity drop = findNearestMatchingDrop(level, destroyedAt, controlState.collectQuery);
        if (drop == null) {
            // No real drop entity anywhere nearby -- either it genuinely
            // dropped nothing (real vanilla randomness) or it hasn't
            // spawned this exact tick yet. Not worth a dedicated "wait a
            // moment and check again" sub-phase here: Python's own
            // drop-confirmation (MiningController.collect, watching real
            // InventoryTracker counts with its own 5s window) already
            // handles "destroyed/killed but produced nothing" correctly
            // via its empty-drop retry counter -- this mod reporting
            // success immediately when there's visibly nothing to walk
            // to is consistent with that, not a regression from it.
            finishCollectAttempt(true, null);
            return;
        }

        LOGGER.info("collect: found dropped {} at ({}, {}, {}) -- walking to pick it up", drop.getItem(), drop.getX(), drop.getY(), drop.getZ());
        controlState.collectPickingUp = true;
        controlState.collectPickupStuckTicks = 0;
        controlState.gotoX = drop.getX();
        controlState.gotoY = drop.getY();
        controlState.gotoZ = drop.getZ();
        controlState.collectHasTarget = false;
        controlState.stopDistance = 0.5; // real vanilla pickup radius is small -- walk genuinely close, not just "in melee range" the way mining/attacking needs
        controlState.pathTracker.reset();
    }

    /**
     * The brief "walk to the dropped item" phase after a target's been
     * destroyed/killed -- see ControlState.collectPickingUp's own
     * docstring for why this exists. Completes (reports collect_result)
     * once the item's gone (picked up, by us or otherwise, or a natural
     * despawn) or the pickup-timeout expires, whichever comes first --
     * either way the block/entity was already genuinely
     * destroyed/killed, so this always reports success regardless of
     * whether the walk-over actually landed the item in inventory;
     * Python's own drop-confirmation is still the real authority on
     * whether to count this as a real gain (see MiningController.collect).
     */
    private void tickCollectPickup(final LocalPlayer player, final ClientLevel level) {
        ItemEntity drop = findNearestMatchingDrop(level, new Vec3(controlState.gotoX, controlState.gotoY, controlState.gotoZ), controlState.collectQuery);
        if (drop == null) {
            LOGGER.info("collect: dropped item is gone (picked up or despawned) -- pickup phase done");
            finishCollectAttempt(true, null);
            return;
        }

        // Re-aim at the item's live position every tick -- it can still
        // be settling (falling, sliding) for a moment after spawning.
        controlState.gotoX = drop.getX();
        controlState.gotoY = drop.getY();
        controlState.gotoZ = drop.getZ();

        if (++controlState.collectPickupStuckTicks > COLLECT_PICKUP_TIMEOUT_TICKS) {
            LOGGER.warn("collect: gave up walking to the dropped item after {} ticks -- completing anyway", controlState.collectPickupStuckTicks);
            finishCollectAttempt(true, null);
        }
    }

    /**
     * Finds the nearest real ItemEntity within COLLECT_PICKUP_SEARCH_RADIUS
     * of `origin` whose item is one of DropTable.dropsFrom(query) -- null
     * if none.
     *
     * DropTable's own maps are keyed/valued in bare ids ("cobblestone",
     * not "minecraft:cobblestone" -- see its own class docstring), but
     * BuiltInRegistries.ITEM.getKey(...) returns a full ResourceLocation
     * whose toString() always includes the "minecraft:" namespace. Found
     * live: the pickup-walk phase never once triggered across many real
     * completions with a real matching drop sitting right next to the bot
     * (confirmed via item_drop wire events landing right before "destroyed/
     * killed the target") -- expectedDrops.contains(itemId) was
     * structurally guaranteed to always be false, comparing "cobblestone"
     * against "minecraft:cobblestone" every time, so every attempt fell
     * straight through to finishCollectAttempt(true, null) instead of ever
     * finding the drop it was looking for. The exact same namespace-
     * mismatch bug class already found and fixed once on the Python side
     * (see MiningController.collect's own expected_drops normalization),
     * just never applied here.
     */
    private static ItemEntity findNearestMatchingDrop(final ClientLevel level, final Vec3 origin, final String query) {
        List<String> expectedDrops = DropTable.dropsFrom(query);
        ItemEntity nearest = null;
        double nearestDistanceSq = COLLECT_PICKUP_SEARCH_RADIUS * COLLECT_PICKUP_SEARCH_RADIUS;
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof ItemEntity itemEntity)) {
                continue;
            }
            String itemId = BuiltInRegistries.ITEM.getKey(itemEntity.getItem().getItem()).getPath();
            if (!expectedDrops.contains(itemId)) {
                continue;
            }
            double distanceSq = itemEntity.position().distanceToSqr(origin);
            if (distanceSq <= nearestDistanceSq) {
                nearest = itemEntity;
                nearestDistanceSq = distanceSq;
            }
        }
        return nearest;
    }

    /** Reports collect_result and clears back to IDLE -- the shared completion path for both a real pickup and a pickup-phase give-up. */
    private void finishCollectAttempt(final boolean success, final String reason) {
        String query = controlState.collectQuery;
        controlState.clear();
        broadcastCollectResultEvent(success, query, reason);
    }

    private boolean tickCollectBlock(final LocalPlayer player, final ClientLevel level) {
        BlockPos pos = new BlockPos(
            (int) Math.floor(controlState.gotoX), (int) Math.floor(controlState.gotoY), (int) Math.floor(controlState.gotoZ)
        );
        if (level.getBlockState(pos).isAir()) {
            // Something else removed this block between the search that
            // found it and now (another player mined it, gravity/a
            // falling block cleared it, our own still-held attack key
            // finally landing a break that started against a *previous*
            // target at the same position, ...) -- BlockBreaker.tryBreak
            // correctly reports false for an already-air target (see its
            // own docstring on why "already gone" must never count as a
            // fresh success), so without this the collect loop would sit
            // here forever waiting for a break that already happened
            // through means other than this run. Abandon this target and
            // let the next tickCollect call search for a fresh one.
            //
            // Also exclude it (not just abandon it) -- reported live:
            // without this, resolveNearest's very next search could (and
            // did) immediately re-find this exact same now-air position
            // again as "solid" (a genuine TOCTOU race between the search
            // reading live block state and this check a tick or more
            // later, once BlockBreaker's keyAttack-hold approach -- see
            // its own docstring -- means a break can land slightly after
            // the tick that found it), producing an infinite two-position
            // oscillation that never actually made progress or hit the
            // stuck-target timeout (each cycle "succeeded" in finding a
            // fresh target in under a tick, so collectTargetStuckTicks
            // never had a chance to accumulate). Same exclusion set the
            // stuck-timeout path below already uses, for the same reason:
            // findClosestMatch is fully deterministic for a fixed
            // center/predicate, so re-searching without excluding a
            // known-bad position just finds it again.
            LOGGER.info("collect: target {} already air at start of tickCollectBlock -- abandoning and excluding, will re-search", pos);
            controlState.collectExcludedPositions.add(pos);
            controlState.collectHasTarget = false;
            // This abandonment path never reaches BlockBreaker.tryBreak
            // (short-circuited above), so none of *its* own key-release
            // paths run either -- without this, a keyAttack hold left over
            // from whatever this collectBreaker was actually mid-break on
            // stays held indefinitely (Options.keyAttack.setDown(true) is
            // sticky until explicitly released), silently continuing to
            // mine whatever the crosshair happens to land on for however
            // many ticks pass before the next real tryBreak call
            // overwrites it. stopBreaking() is the same cleanup
            // stopBreaking()'s own callers already rely on.
            collectBreaker.stopBreaking();
            return false;
        }
        return collectBreaker.tryBreak(player, level, pos);
    }

    private boolean tickCollectEntity(final LocalPlayer player, final ClientLevel level) {
        Entity target = level.getEntity(controlState.followEntityId);
        if (target == null || target.isRemoved()) {
            return target == null; // gone without us landing the kill (disconnected/despawned) -- treat as done, move on
        }
        double dx = target.getX() - player.getX();
        double dy = target.getY() - player.getY();
        double dz = target.getZ() - player.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance > COLLECT_MELEE_RANGE) {
            return false; // still walking there -- resolveMovementIntent's COLLECT case is driving that
        }
        Minecraft.getInstance().gameMode.attack(player, target);
        player.swing(InteractionHand.MAIN_HAND);
        return target.isRemoved();
    }

    /**
     * !attack / !kill: finds a target (nearest real hostile -- see
     * EntityFinder.findNearestHostile -- if attackQuery is null, or the
     * nearest entity of a specific type otherwise, same resolveNearest
     * entity-lookup !find/!collect already use), walks to melee range via
     * the normal GOTO-style pathfinding (resolveMovementIntent's ATTACK
     * case, mirroring COLLECT's entity case exactly), and repeatedly
     * calls the same real MultiPlayerGameMode.attack used by
     * tickCollectEntity until the target dies or the attempt is
     * abandoned. Reports attack_result exactly once, either way.
     *
     * Two ways to abandon an in-progress attack, per the explicit design
     * ask that this be more than "chase and melee until dead":
     * - ATTACK_LOW_HEALTH_FRACTION: the bot's own health drops to/below
     *   this fraction of max -- self-preservation takes priority over
     *   finishing a fight FoodEater's own auto-eat may not win in time
     *   (e.g. no food carried, or hunger already full so eating is
     *   gated off -- see FoodEater's own docstring). Checked every tick
     *   this method runs, not just once, so a fight that starts safe but
     *   turns bad partway through still aborts promptly.
     * - ATTACK_TARGET_TIMEOUT_TICKS: same give-up-on-unreachable shape
     *   COLLECT_TARGET_TIMEOUT_TICKS already established -- a target
     *   that's fled out of pathfinding's reach, or is stuck behind
     *   geometry the bot can't path around, shouldn't strand this
     *   command forever.
     */
    private static final double ATTACK_MELEE_RANGE = 3.0;
    private static final float ATTACK_LOW_HEALTH_FRACTION = 0.25f;
    private static final int ATTACK_TARGET_TIMEOUT_TICKS = 200;

    private void tickAttack(final LocalPlayer player, final ClientLevel level) {
        if (controlState.mode != ControlState.Mode.ATTACK) {
            return;
        }

        if (player.getHealth() <= player.getMaxHealth() * ATTACK_LOW_HEALTH_FRACTION) {
            LOGGER.warn("attack: own health too low ({}/{}), abandoning to self-preserve", player.getHealth(), player.getMaxHealth());
            String query = controlState.attackQuery;
            controlState.clear();
            broadcastAttackResultEvent(false, query, "had to retreat, health too low");
            return;
        }

        if (!controlState.attackHasTarget) {
            Entity target = controlState.attackQuery == null
                ? EntityFinder.findNearestHostile(level, player.position(), controlState.attackRadius)
                : resolveNearestEntityOnly(level, player, controlState.attackQuery, controlState.attackRadius);
            if (target == null) {
                String query = controlState.attackQuery;
                controlState.clear();
                broadcastAttackResultEvent(false, query, query == null ? "no hostiles found nearby" : "no more " + query + " found nearby");
                return;
            }
            controlState.followEntityId = target.getId();
            controlState.attackHasTarget = true;
            controlState.attackTargetStuckTicks = 0;
            controlState.pathTracker.reset();
            LOGGER.info("attack: new target -- entity {} ({})", target.getId(), target.getType());
            return; // resolveMovementIntent picks up the fresh target next tick
        }

        Entity target = level.getEntity(controlState.followEntityId);
        if (target == null || target.isRemoved()) {
            // Gone without us landing the kill (disconnected/despawned)
            // -- still report success, same as tickCollectEntity: the
            // fight is over either way, just not from our own blow.
            String query = controlState.attackQuery;
            controlState.clear();
            broadcastAttackResultEvent(true, query, null);
            return;
        }

        double dx = target.getX() - player.getX();
        double dy = target.getY() - player.getY();
        double dz = target.getZ() - player.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance > ATTACK_MELEE_RANGE) {
            // Still walking there -- resolveMovementIntent's ATTACK case
            // is driving that. Only counts as "stuck" (not just "still
            // approaching") once this stays true for a long while, same
            // as COLLECT's own give-up timeout.
            if (++controlState.attackTargetStuckTicks > ATTACK_TARGET_TIMEOUT_TICKS) {
                LOGGER.warn("attack: giving up, target unreachable after {} ticks", controlState.attackTargetStuckTicks);
                String query = controlState.attackQuery;
                controlState.clear();
                broadcastAttackResultEvent(false, query, "couldn't reach the target");
            }
            return;
        }

        controlState.attackTargetStuckTicks = 0;
        Minecraft.getInstance().gameMode.attack(player, target);
        player.swing(InteractionHand.MAIN_HAND);
        if (target.isRemoved()) {
            String query = controlState.attackQuery;
            controlState.clear();
            broadcastAttackResultEvent(true, query, null);
        }
    }

    /** Entity-only lookup (no block fallback) -- !attack <type> only ever means "fight that entity", never "mine that block". */
    private static Entity resolveNearestEntityOnly(final ClientLevel level, final LocalPlayer player, final String query, final int radius) {
        Identifier id = Identifier.parse(query.contains(":") ? query : "minecraft:" + query);
        return EntityFinder.findNearestEntity(level, player.position(), id.toString(), radius);
    }

    /**
     * Generic query/query_result mechanism -- currently only backs
     * DropTable's drops_from/source_for lookups (see its own class
     * docstring for the real-loot-table-access gap this is a stopgap
     * for), but deliberately shaped as a general "ask the mod something,
     * get a correlated answer back later" pattern rather than one-off
     * wire messages per question, since more mod-side queries are likely
     * to want the same shape later. Pure in-memory lookup (DropTable is
     * a static map, no world-state access at all), so unlike handleFind
     * this needs no thread-hop to the client tick thread -- answered
     * synchronously, right here on the control channel's own thread.
     */
    private void handleQuery(final JsonObject json) {
        String subType = json.get("sub_type").getAsString();
        String key = json.get("key").getAsString();
        JsonArray arguments = json.getAsJsonArray("arguments");
        String argument = arguments.get(0).getAsString();

        List<String> result = switch (subType) {
            case "drops_from" -> DropTable.dropsFrom(argument);
            case "source_for" -> List.of(DropTable.sourceFor(argument));
            default -> {
                LOGGER.warn("control channel: unknown query sub_type '{}'", subType);
                yield List.of();
            }
        };
        broadcastQueryResultEvent(key, result);
    }

    /**
     * !find resolves `query` as an entity type first, falling back to a
     * block type if no entity type by that name matched (e.g. "cow" is an
     * entity, not a block, so it resolves as one; "stone" has no entity
     * type, so it falls through to the block lookup) -- this mirrors the
     * user's stated intent for a single unified !find rather than separate
     * !searchForBlock/!searchForEntity commands. Runs synchronously on the
     * control channel's network thread rather than the tick thread, same
     * as every other handleMessage case; BlockFinder/EntityFinder only
     * read world state (no player-null guard needed beyond the existing
     * withPlayer-style null checks below since level can be null between
     * a disconnect/reconnect same as player).
     */
    private void handleFind(final String query, final int radius) {
        // handleMessage (and so this) runs on the WebSocket library's own
        // thread, not the render/tick thread -- unlike withPlayer's simple
        // inventory mutations, BlockFinder/EntityFinder iterate live chunk
        // and entity collections, which is genuinely unsafe to do off the
        // main thread (concurrent modification from the tick thread is a
        // real hazard, not just a style concern). Minecraft.execute queues
        // the actual lookup to run on the main thread instead, same as
        // vanilla/Fabric code scheduling cross-thread work back onto it.
        Minecraft.getInstance().execute(() -> runFind(query, radius));
    }

    /**
     * !save chest <name>: finds the chest block a given *tracked* player
     * (not necessarily the bot itself) is currently looking at -- backs
     * "!save chest a" meaning "remember the chest I (the caller) am
     * looking at", not whatever chest the bot happens to be near. See
     * LookingAt's own docstring for why raycasting from another tracked
     * player's eyes (not just the bot's own) is a real, correct operation
     * here, not a hack.
     *
     * Same thread-hop as handleFind -- iterating live entities/chunks off
     * the tick thread is unsafe.
     */
    private void handleFindChest(final int entityId) {
        Minecraft.getInstance().execute(() -> runFindChest(entityId));
    }

    // Matches BlockBreaker/DoorOpener's own real-interaction-reach
    // convention -- a chest the caller is looking at from further away
    // than a real player could actually interact with isn't a sensible
    // "the chest I'm looking at" answer.
    private static final double FIND_CHEST_MAX_DISTANCE = 4.5;

    private void runFindChest(final int entityId) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            broadcastFindChestResultEvent(false, 0, 0, 0);
            return;
        }
        Entity looker = level.getEntity(entityId);
        if (looker == null) {
            LOGGER.warn("find_chest: entity {} not currently visible", entityId);
            broadcastFindChestResultEvent(false, 0, 0, 0);
            return;
        }

        BlockPos pos = LookingAt.blockPos(looker, level, FIND_CHEST_MAX_DISTANCE);
        if (pos == null || !(level.getBlockState(pos).getBlock() instanceof ChestBlock)) {
            broadcastFindChestResultEvent(false, 0, 0, 0);
            return;
        }

        broadcastFindChestResultEvent(true, pos.getX(), pos.getY(), pos.getZ());
    }

    private void runFind(final String query, final int radius) {
        LocalPlayer player = Minecraft.getInstance().player;
        ClientLevel level = Minecraft.getInstance().level;
        if (player == null || level == null) {
            broadcastFindResultEvent(query, false, false, "entity", 0, 0, 0);
            return;
        }

        NearestMatch match = resolveNearest(level, player, query, radius);
        if (match == null) {
            boolean recognized = isRecognizedType(query);
            broadcastFindResultEvent(query, false, recognized, "entity", 0, 0, 0);
            return;
        }
        broadcastFindResultEvent(query, true, true, match.kind, match.x, match.y, match.z);
    }

    /** entity type first, block type as fallback -- same registry-membership check as !find (see isRecognizedType). */
    private record NearestMatch(String kind, double x, double y, double z, Entity entity, BlockPos block) {
    }

    private static boolean isRecognizedType(final String query) {
        // BLOCK/ENTITY_TYPE are DefaultedRegistry -- looking up an unknown
        // key silently falls back to a default (air / pig) instead of
        // failing, so a genuinely unrecognized query (a typo, or a word
        // that isn't a block or entity at all) would otherwise report the
        // exact same "not found nearby" as a real type that's just out of
        // range -- found live: a player asked for the error message to
        // distinguish those two cases ("!find aaa" vs "!find allay" with
        // no allay around). containsKey checks real registry membership,
        // independent of the defaulting behavior.
        Identifier id = Identifier.parse(query.contains(":") ? query : "minecraft:" + query);
        return BuiltInRegistries.ENTITY_TYPE.containsKey(id) || BuiltInRegistries.BLOCK.containsKey(id);
    }

    /**
     * Shared entity-then-block resolution behind both !find and !collect's
     * per-item search step -- tries `query` as an entity type first,
     * falling back to a block type (see class-level !find docs for why:
     * "cow" has no block type, "stone" has no entity type, so trying
     * entity first and falling back to block covers both without the
     * caller needing to know which kind of thing it's asking for).
     */
    private static NearestMatch resolveNearest(final ClientLevel level, final LocalPlayer player, final String query, final int radius) {
        return resolveNearest(level, player, query, radius, Collections.emptySet());
    }

    /** Same as the four-arg overload, but skips any block position in `excludedBlocks` -- see BlockFinder's own excluded-set overload for why (backs !collect's give-up-and-retry). */
    private static NearestMatch resolveNearest(
        final ClientLevel level, final LocalPlayer player, final String query, final int radius, final Set<BlockPos> excludedBlocks
    ) {
        Identifier id = Identifier.parse(query.contains(":") ? query : "minecraft:" + query);

        Entity entity = EntityFinder.findNearestEntity(level, player.position(), id.toString(), radius);
        if (entity != null) {
            return new NearestMatch("entity", entity.getX(), entity.getY(), entity.getZ(), entity, null);
        }

        BlockPos center = player.blockPosition();
        BlockPos block = BlockFinder.findNearestBlock(level, center, id.toString(), radius, excludedBlocks);
        if (block != null) {
            return new NearestMatch("block", block.getX() + 0.5, block.getY(), block.getZ() + 0.5, null, block);
        }

        return null;
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

        if (controlState.mode == ControlState.Mode.DIG_DOWN) {
            // DIG_DOWN needs no A*/waypoints (it's a straight-down loop,
            // not real navigation) but it does need the bot standing
            // reasonably centered over its own column before digging --
            // reported live: without this, digging while straddling two
            // columns (feet partially over the target block, partially
            // over its still-solid neighbor) let the bot avoid ever
            // actually falling into the hole it just dug, so the *next*
            // tick's "block below me" was still the same already-broken
            // (now air) block -- BlockBreaker correctly reports that as
            // "nothing to break" now (see its docstring), but before that
            // fix the loop just kept reporting fresh "successes" for a
            // single real break, undercounting real progress while
            // overcounting reported progress (5 requested, only 1 block
            // actually gone). centerDigDownIntent (below) walks the bot
            // to its own column's center first; tickDigDown only starts
            // breaking once close enough.
            return centerDigDownIntent(player);
        }

        Double[] target = switch (controlState.mode) {
            case IDLE, DIG_DOWN -> null; // DIG_DOWN already returned above -- unreachable here, kept only for switch exhaustiveness
            case GOTO -> new Double[]{controlState.gotoX, controlState.gotoY, controlState.gotoZ};
            case FOLLOW, GIVE -> {
                Entity followed = level.getEntity(controlState.followEntityId);
                yield followed != null
                    ? new Double[]{followed.getX(), followed.getY(), followed.getZ()}
                    : null;
            }
            case ATTACK -> {
                if (!controlState.attackHasTarget) {
                    yield null; // between targets -- tickAttack (called below) is what searches for one
                }
                Entity target1 = level.getEntity(controlState.followEntityId);
                yield target1 != null
                    ? new Double[]{target1.getX(), target1.getY(), target1.getZ()}
                    : null;
            }
            case COLLECT -> {
                if (controlState.collectPickingUp) {
                    // Walking to the dropped item -- see tickCollect's own
                    // pickup-phase docstring. Reuses gotoX/Y/Z the exact
                    // same way the mining phase does, just re-pointed at
                    // the item's position instead of the block/entity
                    // that was just destroyed/killed.
                    yield new Double[]{controlState.gotoX, controlState.gotoY, controlState.gotoZ};
                }
                if (!controlState.collectHasTarget) {
                    yield null; // between items -- tickCollect (called below) is what searches for the next one
                }
                if (controlState.collectTargetIsEntity) {
                    Entity target1 = level.getEntity(controlState.followEntityId);
                    yield target1 != null
                        ? new Double[]{target1.getX(), target1.getY(), target1.getZ()}
                        : null;
                }
                yield new Double[]{controlState.gotoX, controlState.gotoY, controlState.gotoZ};
            }
        };

        if (target == null) {
            controlState.pathTracker.reset();
            controlState.gotoArrived.reset();
            return intent;
        }

        double selfX = player.getX();
        double selfY = player.getY();
        double selfZ = player.getZ();

        controlState.pathTracker.maybeReplan(
            level, player, selfX, selfY, selfZ, target[0], target[1], target[2], controlState.stopDistance
        );
        Move waypoint = controlState.pathTracker.nextWaypoint(selfX, selfY, selfZ, player.onGround());
        doorOpener.maybeOpenDoorNear(player, level, waypoint);
        boolean blockedByDig = maybeBreakBlocksNear(player, level, waypoint);

        // COLLECT's own mining phase holds collectBreaker's keyAttack (see
        // tickCollect, called later this same tick) completely independently
        // of pathBlockBreaker/maybeBreakBlocksNear above -- which only ever
        // reflects pathfinding's own dig-through-obstacles breaker. Without
        // this, blockedByDig stayed false the entire time collectBreaker was
        // actively mining a block reachable within BlockBreaker.INTERACT_RANGE
        // (4.5) but outside stopDistance (2.5, see ControlState.setCollect),
        // so `walking` below stayed true every tick: the bot kept walking
        // forward (and jumping, once close enough to trip the step-height
        // check) straight at the block it was simultaneously holding
        // keyAttack against, constantly changing position/rotation out from
        // under the in-progress break. Reported live: !collect held a real,
        // steady non-zero getDestroyProgress against a target for the full
        // 200-tick give-up window without ever completing, every single
        // attempt, despite no tool switch and a clean line of sight -- the
        // same "fighting aim/movement" class of bug already fixed once for
        // pathfinding's own mid-dig walking (see the `walking` field's own
        // comment below), just never wired up for collectBreaker.
        boolean blockedByCollectMining = controlState.mode == ControlState.Mode.COLLECT
            && !controlState.collectPickingUp
            && !controlState.collectTargetIsEntity
            && collectBreaker.hasActiveTarget();
        blockedByDig = blockedByDig || blockedByCollectMining;

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

        boolean arrivedNow = controlState.mode == ControlState.Mode.GOTO && waypoint == null && horizontalDistance <= distanceToStopAt;
        if (controlState.gotoArrived.fire(arrivedNow)) {
            broadcastArrivedEvent();
        }

        // While a waypoint still has blocks left to dig through, hold off
        // walking forward into it -- BlockBreaker is already aiming/
        // swinging at the target this same tick (see
        // maybeBreakBlocksNear), and walking into a still-solid block
        // achieves nothing but bumping into a wall. Collision would mostly
        // prevent this anyway, but an explicit hold keeps yaw/forward
        // intent from fighting the aim BlockBreaker just set. Reported
        // live: this hold used to only cover forward/yaw/pitch, not jump
        // (see below) -- the bot kept spamming jump in place while stuck
        // mid-dig on a waypoint that happened to require a step up,
        // pointlessly hopping instead of just standing still and finishing
        // the break.
        boolean walking = horizontalDistance > distanceToStopAt && !blockedByDig;
        if (walking) {
            intent.forward = true;
            // Vanilla yaw convention: 0 = south/+z, matching Entity.getYRot()
            // and the atan2(-dx, dz) form used throughout the decompiled
            // source's own movement code.
            intent.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            // pitch is set below (looking at the waypoint), not here --
            // see that block's own comment for why a blanket level-horizon
            // pitch used to fight BlockBreaker.aimAt.
        }

        // Only jump while actually walking toward the waypoint -- gated on
        // the same `walking` condition as forward/yaw above, not just
        // `dy > MAX_STEP_HEIGHT_TRIGGER` alone (see this method's own
        // docstring update above for the live bug this fixes: jumping in
        // place, pointlessly, while stuck mid-dig on a step-up waypoint).
        //
        // Also gated on not currently standing over farmland -- real
        // vanilla (FarmBlock.fallOn) rolls a chance to trample farmland
        // back to plain dirt on landing, scaling with fall distance;
        // repeated real jumps each roll that chance independently, so
        // even a low per-jump probability becomes near-certain over many
        // landings. Reported live: pathfinding to a crop (!collect
        // carrot) walks straight across the farm plot to reach it, and
        // any step-up jump taken while still over farmland risked
        // trampling the very crop being walked toward, or its neighbors.
        // A plain walk across farmland doesn't trigger this at all (the
        // chance is fall-distance-gated) -- only suppressing the jump
        // itself, not all movement, actually needed.
        boolean standingOnFarmland = level.getBlockState(player.blockPosition().below()).is(Blocks.FARMLAND);
        double dy = aimY - selfY;
        if (walking && dy > MAX_STEP_HEIGHT_TRIGGER && !standingOnFarmland) {
            intent.jump = true;
            // Sprint into any jump, not just a plain walking hop --
            // MovementIntent.sprint existed but nothing ever set it, so
            // every jump this whole mod has ever executed used vanilla's
            // plain walking-jump distance (~0.6 blocks with no forward
            // speed built up), never the meaningfully longer real
            // distance a sprint-jump covers. Reported live: a jump across
            // a real gap (a raised platform reachable only via a running
            // leap, not a plain step-up) consistently came up short and
            // fell -- every single waypoint in this A* graph is a single
            // adjacent-cell step (see Movements.getMoveJumpUp/
            // getMoveDiagonal, both dx/dz in {-1,0,1} only), planned
            // assuming a real player's jump reach, not the shorter
            // walking-jump reach this mod was actually executing.
            // Sprinting into every jump waypoint (not just ones the
            // planner flags as tight, since there's no such flag and a
            // sprint-jump is never actually harmful for a jump that would
            // have succeeded anyway) closes that gap.
            intent.sprint = true;
        }

        // Look at the next waypoint while genuinely walking toward it.
        // Reported live: a blanket "level the pitch while moving" (this
        // used to unconditionally set intent.pitch = 0f here) fought
        // BlockBreaker.aimAt's own direct player.setXRot call the moment
        // mining resumed after a waypoint's dig finished -- aimAt runs
        // earlier this same tick (inside maybeBreakBlocksNear, above) and
        // sets pitch straight at the block being mined, but the very next
        // tick this code unconditionally reset it back to level before the
        // bot had actually finished looking at (and making progress on)
        // the target, undoing aimAt's work every other tick. Gated on
        // `walking` (mutually exclusive with blockedByDig, see above) so
        // this never runs on a tick BlockBreaker already owns pitch for --
        // mining always looks at the target block (aimAt), walking always
        // looks at the next waypoint (this), and neither overwrites the
        // other's work as a side effect.
        if (walking) {
            double eyeDy = aimY - player.getEyeY();
            intent.pitch = (float) -Math.toDegrees(Math.atan2(eyeDy, horizontalDistance));
        }

        return intent;
    }

    // How close (horizontally, blocks) the bot's feet must be to its own
    // column's center before tickDigDown will start breaking -- tight
    // enough that gravity reliably drops the bot straight into the hole
    // once the block below is gone (the actual bug this exists to fix:
    // digging while straddling two columns left the bot balanced on the
    // still-solid neighbor instead of falling), loose enough that real
    // physics jitter (the bot is never perfectly motionless) doesn't
    // thrash between "centered" and "not centered" every tick.
    private static final double DIG_DOWN_CENTER_TOLERANCE = 0.15;

    /**
     * Walks the bot to the horizontal center of its own current block --
     * DIG_DOWN's only movement need, no A-star or waypoints involved (see
     * resolveMovementIntent's DIG_DOWN case for why). Returns an empty
     * intent once already centered within DIG_DOWN_CENTER_TOLERANCE, at
     * which point tickDigDown (ticked separately, see onClientTick) takes
     * over and starts breaking.
     */
    private MovementIntent centerDigDownIntent(final LocalPlayer player) {
        MovementIntent intent = new MovementIntent();

        double centerX = Math.floor(player.getX()) + 0.5;
        double centerZ = Math.floor(player.getZ()) + 0.5;
        double dx = centerX - player.getX();
        double dz = centerZ - player.getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

        if (horizontalDistance > DIG_DOWN_CENTER_TOLERANCE) {
            intent.forward = true;
            intent.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            intent.pitch = 90f; // look straight down -- centering is purely horizontal, and this doubles as visual feedback that a dig is in progress
        }

        return intent;
    }

    /** True once the bot's feet are close enough to its own column's center for tickDigDown to safely start breaking (see DIG_DOWN_CENTER_TOLERANCE). */
    private static boolean isCenteredForDig(final LocalPlayer player) {
        double dx = (Math.floor(player.getX()) + 0.5) - player.getX();
        double dz = (Math.floor(player.getZ()) + 0.5) - player.getZ();
        return Math.sqrt(dx * dx + dz * dz) <= DIG_DOWN_CENTER_TOLERANCE;
    }

    /**
     * Breaks through a waypoint's toBreak list (see Movements.java's
     * dig-cost port) as the bot approaches it -- called every tick from
     * resolveMovementIntent, same "does nothing most ticks" shape as
     * doorOpener.maybeOpenDoorNear. Returns true while a required block is
     * still standing OR pathBlockBreaker is still settling after a
     * just-completed break (caller should hold off walking/jumping either
     * way -- see BlockBreaker.isBusy's own docstring for why resuming
     * movement the instant a break's client-predicted completion is seen
     * is unsafe), false once every block in the list is gone and any
     * settle window has elapsed.
     *
     * Only ever targets the *first* still-solid block in the list at a
     * time -- BlockBreaker itself only tracks one in-progress break, and
     * toBreak's own order (movements.js's own toBreak.push order,
     * preserved through the port) is already the order a move's blocks
     * need clearing in for that move to actually be walkable.
     */
    private boolean maybeBreakBlocksNear(final LocalPlayer player, final ClientLevel level, final Move waypoint) {
        pathBlockBreaker.tickSettle(level);

        // If pathBlockBreaker still has an active target, but that exact
        // position is no longer anywhere in the *current* waypoint's
        // toBreak list, abandon it explicitly instead of just falling
        // through to isBusy() below. Reported live: a replanned path (or
        // simply reaching a new waypoint) can leave the current
        // waypoint/toBreak completely different from what
        // pathBlockBreaker was last actually digging -- nothing in this
        // method ever called tryBreak or stopBreaking() again for the
        // stale target once that happened, so currentTarget (and its
        // gizmo highlight, see BlockTargetVisualizer) stayed frozen
        // forever, isBusy() stayed true forever (blocking all movement,
        // see resolveMovementIntent's own blockedByDig gating), and the
        // bot just stood there -- visibly "targeting" a block it had
        // already stopped actually trying to mine, with zero further log
        // output to explain why. This is the pathfinding-specific version
        // of the same "isBusy() can only ever be cleared by tryBreak, but
        // nothing keeps calling tryBreak" deadlock class already found
        // and fixed for !collect/!dig/!debug -- see BlockBreaker.
        // hasActiveTarget's own docstring.
        BlockPos activeTarget = pathBlockBreaker.currentTarget();
        if (activeTarget != null && (waypoint == null || !waypoint.toBreak.contains(activeTarget))) {
            LOGGER.info(
                "mining[pathfinding]: abandoning stale target {} -- no longer in the current waypoint's toBreak list (waypoint={})",
                activeTarget, waypoint
            );
            pathBlockBreaker.stopBreaking();
        }

        if (waypoint == null || waypoint.toBreak.isEmpty()) {
            return pathBlockBreaker.isBusy();
        }
        for (BlockPos pos : waypoint.toBreak) {
            if (!level.getBlockState(pos).isAir()) {
                pathBlockBreaker.tryBreak(player, level, pos);
                return true;
            }
        }
        return pathBlockBreaker.isBusy();
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

    private void dispatchMessage(final String type, final JsonObject json) {
        switch (type) {
            case "goto" -> controlState.setGoto(
                json.get("x").getAsDouble(), json.get("y").getAsDouble(), json.get("z").getAsDouble(),
                json.has("stop_distance") ? json.get("stop_distance").getAsDouble() : 2.0
            );
            case "follow" -> controlState.setFollow(
                json.get("entity_id").getAsInt(),
                json.has("stop_distance") ? json.get("stop_distance").getAsDouble() : 2.0
            );
            case "stop" -> {
                controlState.clear();
                // Also aborts any in-progress block break -- without this,
                // !stop mid-dig left the mining animation/progress stuck
                // active even though the goal that started it was cleared
                // (same class of bug FoodEater's stuck-keyUse-through-
                // death fix addressed for a held key instead of a break).
                pathBlockBreaker.stopBreaking();
                digDownBreaker.stopBreaking();
                collectBreaker.stopBreaking();
            }
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
            case "find" -> handleFind(
                json.get("query").getAsString(),
                json.has("radius") ? json.get("radius").getAsInt() : DEFAULT_FIND_RADIUS
            );
            case "find_chest" -> handleFindChest(json.get("entity_id").getAsInt());
            case "dig_down" -> controlState.setDigDown(json.get("count").getAsInt());
            case "debug_swap_test" -> withPlayer(this::runDebugSwapTest);
            case "collect" -> controlState.setCollect(
                json.get("query").getAsString(),
                json.has("radius") ? json.get("radius").getAsInt() : DEFAULT_FIND_RADIUS
            );
            case "attack" -> controlState.setAttack(
                json.has("query") && !json.get("query").isJsonNull() ? json.get("query").getAsString() : null,
                json.has("radius") ? json.get("radius").getAsInt() : DEFAULT_FIND_RADIUS
            );
            case "query" -> handleQuery(json);
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

    /**
     * Temporary !debug command handler -- tests whether InventoryActions.
     * moveToHotbar's local-only swap (see its own docstring) is itself
     * the cause of a live-reported client/server held-item desync (see
     * FINDINGS.md's "InventoryActions.moveToHotbar's local-only swap
     * genuinely desyncing the server's view of the held item" section),
     * isolated from mining/BlockBreaker entirely.
     *
     * Sequence: find the diamond pickaxe wherever it currently is; if
     * it's in the hotbar, shift-click it into main storage first (a real
     * container click, so it starts from a known-clean, server-confirmed
     * position -- see InventoryActions.moveToMainStorage); then call
     * moveToHotbar to bring it into hotbar slot 0 and select it -- the
     * exact mechanism under test. Logs every step's local state; the
     * actual test is comparing what a human observer sees the bot
     * holding afterward against these log lines, live, not something
     * this method can verify on its own (there's no way for the mod to
     * observe another client's rendered state of the bot).
     */
    private void runDebugSwapTest(final LocalPlayer player) {
        Inventory inventory = player.getInventory();
        int pickaxeSlot = -1;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            if (inventory.getItem(slot).is(Items.DIAMOND_PICKAXE)) {
                pickaxeSlot = slot;
                break;
            }
        }
        if (pickaxeSlot < 0) {
            LOGGER.info("debug_swap_test: no diamond pickaxe found anywhere in inventory -- aborting");
            return;
        }
        LOGGER.info("debug_swap_test: found diamond pickaxe in slot {} (hotbar={})", pickaxeSlot, Inventory.isHotbarSlot(pickaxeSlot));

        if (Inventory.isHotbarSlot(pickaxeSlot)) {
            LOGGER.info("debug_swap_test: shift-clicking slot {} out of the hotbar into main storage first", pickaxeSlot);
            InventoryActions.moveToMainStorage(player, pickaxeSlot);
            // The shift-click is a real server round trip -- log where the
            // client's own local prediction now thinks the pickaxe landed,
            // for comparison against the next tick, but the actual
            // moveToHotbar call below runs off this same local state
            // regardless (same as every other caller of moveToHotbar
            // today), which is exactly the scenario under test.
            for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
                if (inventory.getItem(slot).is(Items.DIAMOND_PICKAXE)) {
                    LOGGER.info("debug_swap_test: after shift-click, local state shows pickaxe in slot {}", slot);
                    pickaxeSlot = slot;
                    break;
                }
            }
        }

        LOGGER.info(
            "debug_swap_test: calling moveToHotbar({}, 0) -- was mainHand={}, selectedSlot={}",
            pickaxeSlot, player.getMainHandItem(), inventory.getSelectedSlot()
        );
        InventoryActions.moveToHotbar(player, pickaxeSlot, 0);
        LOGGER.info(
            "debug_swap_test: done -- mainHand={}, selectedSlot={} (compare this against what other clients report seeing the bot hold)",
            player.getMainHandItem(), inventory.getSelectedSlot()
        );
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
     * Fires exactly once when a GOTO goal's distance-to-target first drops
     * under stopDistance (see ControlState.gotoArrived / resolveMovementIntent)
     * -- FOLLOW/GIVE never fire this, since neither has a single "arrival"
     * moment (FOLLOW tracks a moving target indefinitely; GIVE's completion
     * is reported by maybeCompleteGive dropping the item, a different
     * concept). General-purpose, not !find-specific: any command that
     * issues a `goto` can react to this the same way.
     */
    private void broadcastArrivedEvent() {
        JsonObject event = new JsonObject();
        event.addProperty("type", "arrived");
        controlClient.sendEvent(event.toString());
    }

    /**
     * Reports the outcome of a `find_chest` command -- x/y/z (the
     * chest's real BlockPos, not a hit-location fraction) are only
     * meaningful when found=true. Fire-and-forget, same one-in-flight-
     * at-a-time reasoning as find_result (!save is a chat command,
     * dispatched one at a time).
     */
    private void broadcastFindChestResultEvent(final boolean found, final int x, final int y, final int z) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "find_chest_result");
        event.addProperty("found", found);
        if (found) {
            event.addProperty("x", x);
            event.addProperty("y", y);
            event.addProperty("z", z);
        }
        controlClient.sendEvent(event.toString());
    }

    /**
     * Reports the outcome of a `find` command -- x/y/z/kind are only
     * meaningful when found=true. Fire-and-forget like every other event
     * here (no request id): only one !find is ever in flight at a time
     * (chat commands are dispatched one at a time), so the Python side can
     * simply await the next find_result it receives.
     */
    private void broadcastFindResultEvent(
        final String query, final boolean found, final boolean recognized,
        final String kind, final double x, final double y, final double z
    ) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "find_result");
        event.addProperty("query", query);
        event.addProperty("found", found);
        event.addProperty("recognized", recognized);
        if (found) {
            event.addProperty("kind", kind);
            event.addProperty("x", x);
            event.addProperty("y", y);
            event.addProperty("z", z);
        }
        controlClient.sendEvent(event.toString());
    }

    /**
     * Reports how a !digDown run ended -- `broken` is how many blocks were
     * actually dug (may be less than requested on an early stop). `reason`
     * is omitted on full completion (broken == the original request),
     * present ("hit lava/water" / "big drop ahead") on an early abort.
     */
    private void broadcastDigDownResultEvent(final int broken, final String reason) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "dig_down_result");
        event.addProperty("broken", broken);
        if (reason != null) {
            event.addProperty("reason", reason);
        }
        controlClient.sendEvent(event.toString());
    }

    /**
     * Reports the outcome of a single !collect attempt -- `success` is
     * true once the target was actually destroyed/killed (note: *not*
     * once any drop is confirmed in inventory -- Python's own
     * InventoryTracker is the source of truth for that, watching real
     * inventory-gain events independently; this event only ever means
     * "the block/entity is gone now"). `reason` is present on failure
     * (e.g. "no more stone found nearby" when every candidate within
     * radius was either not found at all or excluded as unreachable).
     * No collected/requested counts anymore -- see tickCollect's own
     * docstring for why counting moved to the Python side entirely.
     */
    private void broadcastCollectResultEvent(final boolean success, final String query, final String reason) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "collect_result");
        event.addProperty("success", success);
        event.addProperty("query", query);
        if (reason != null) {
            event.addProperty("reason", reason);
        }
        controlClient.sendEvent(event.toString());
    }

    /**
     * Reports the outcome of a !attack/!kill run -- `success` is true
     * once the target is actually dead (or gone without us landing the
     * kill -- see tickAttack's own docstring for why that still counts).
     * `reason` is present on failure (no target found, couldn't reach
     * it, or had to retreat on low health).
     */
    private void broadcastAttackResultEvent(final boolean success, final String query, final String reason) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "attack_result");
        event.addProperty("success", success);
        if (query != null) {
            event.addProperty("query", query);
        }
        if (reason != null) {
            event.addProperty("reason", reason);
        }
        controlClient.sendEvent(event.toString());
    }

    /**
     * Reply to a `query` command -- `key` is whatever correlation key
     * the request carried (Python generates a fresh one per query, e.g.
     * a uuid, since answers can arrive out of order relative to other
     * traffic and there's no other way to match a reply back to its
     * request the way find_result/collect_result can get away with
     * "only one ever in flight" -- queries could plausibly overlap).
     */
    private void broadcastQueryResultEvent(final String key, final List<String> result) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "query_result");
        event.addProperty("key", key);
        JsonArray resultArray = new JsonArray();
        for (String value : result) {
            resultArray.add(value);
        }
        event.add("result", resultArray);
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
