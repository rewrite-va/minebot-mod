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
import minebot.mod.pathfinding.BlockFinder;
import minebot.mod.pathfinding.DoorOpener;
import minebot.mod.pathfinding.Move;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
    private static final int DEFAULT_FIND_RADIUS = 64;

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
    // DIG_DOWN/pathfinding-through-a-wall can each have a different
    // target block in play, so sharing one instance across modes would
    // make one mode's break silently reset another's in-progress
    // progress the moment they targeted different blocks. Each is
    // labeled (see BlockBreaker's own constructor) so its debug logging
    // identifies which one produced a given line.
    private final BlockBreaker digDownBreaker = new BlockBreaker("digDown");
    private final FoodEater foodEater = new FoodEater();
    private final InventoryReporter inventoryReporter = new InventoryReporter();
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
        new BlockTargetVisualizer(pathBlockBreaker, digDownBreaker).register();

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
        // progress (GOTO/FOLLOW/GIVE/DIG_DOWN all have a real goal,
        // whether or not it happens to be setting yaw via MovementIntent
        // this specific tick).
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
     * Shared entity-then-block resolution behind !find -- tries `query` as
     * an entity type first, falling back to a block type ("cow" has no
     * block type, "stone" has no entity type, so trying entity first and
     * falling back to block covers both without the caller needing to know
     * which kind of thing it's asking for).
     */
    private static NearestMatch resolveNearest(final ClientLevel level, final LocalPlayer player, final String query, final int radius) {
        Identifier id = Identifier.parse(query.contains(":") ? query : "minecraft:" + query);

        Entity entity = EntityFinder.findNearestEntity(level, player.position(), id.toString(), radius);
        if (entity != null) {
            return new NearestMatch("entity", entity.getX(), entity.getY(), entity.getZ(), entity, null);
        }

        BlockPos center = player.blockPosition();
        BlockPos block = BlockFinder.findNearestBlock(level, center, id.toString(), radius);
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
        // stale target once that happened, so currentTarget stayed frozen
        // forever, isBusy() stayed true forever (blocking all movement,
        // see resolveMovementIntent's own blockedByDig gating), and the
        // bot just stood there -- visibly "targeting" a block it had
        // already stopped actually trying to mine, with zero further log
        // output to explain why. This is the pathfinding-specific version
        // of the same "isBusy() can only ever be cleared by tryBreak, but
        // nothing keeps calling tryBreak" deadlock class already found
        // and fixed for !dig/!debug -- see BlockBreaker.hasActiveTarget's
        // own docstring.
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
            case "dig_down" -> controlState.setDigDown(json.get("count").getAsInt());
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
