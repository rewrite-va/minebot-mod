package minebot.mod.pathfinding;

import minebot.mod.InventoryActions;
import minebot.mod.MinebotMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;

/**
 * Drives a real block break by holding the real `keyAttack` keybind down --
 * the block-breaking sibling of FoodEater's keyUse-hold discovery, and for
 * the exact same underlying reason. This class originally called
 * MultiPlayerGameMode.startDestroyBlock/continueDestroyBlock directly every
 * tick (the same real multi-tick sequence a human's held left-click drives,
 * confirmed via decompiled source, and functionally correct-looking on
 * paper: the block visibly disappeared, tryBreak correctly reported success,
 * BlockBreaker's own tool-switch/line-of-sight logic all worked exactly as
 * intended). But reported live: mining stone with a real pickaxe in hand
 * consistently broke the block -- confirmed by a player watching, who saw
 * it disappear, then *reappear*, then get "broken" again -- while never
 * actually producing a cobblestone drop, not once, across many repeated
 * attempts. Wire-level logging (ControlClient) confirmed the mod itself
 * never observed any inventory-count change and no `item_drop` ground-item
 * sighting near the bot either -- not a lost event or a tracking bug, the
 * server genuinely never rolled a drop for these breaks at all. The
 * player's own "it reappears" observation is exactly what a server
 * rejecting/reverting a client-predicted break looks like from outside
 * (destroyBlock's real removal is client-side prediction via
 * BlockStatePredictionHandler -- see MultiPlayerGameMode's decompiled
 * source -- authoritative removal, and the loot-table roll that produces a
 * drop, only happen server-side once it processes the corresponding
 * ServerboundPlayerActionPacket sequence).
 *
 * Exactly the same shape of mystery FoodEater's useItem() investigation hit
 * (every individually-checked mechanism looked correct, yet a live A/B test
 * against genuine human input showed a real difference) -- rather than
 * continuing to chase why the direct API sequence differs from a real
 * click, this switches to the same fix that worked there: hold the real
 * `keyAttack` keybind (Options.keyAttack.setDown(true)) instead of calling
 * startDestroyBlock/continueDestroyBlock ourselves. Vanilla's own
 * Minecraft.handleKeybinds() (called every client tick regardless of this
 * mod, from Minecraft.tick()) then drives the exact real
 * startAttack()/continueAttack() sequence a human's held left-click would,
 * including whatever server-round-trip nuance a direct API call was
 * apparently missing. This relies on Minecraft's own per-frame crosshair
 * raycast (`hitResult`, recomputed every frame from the real player yaw/
 * pitch via Player.raycastHitResult) actually landing on the target block --
 * satisfied here because aimAt (below) already sets real yaw/pitch at the
 * target before the key is held, the same way a human turning to look at a
 * block before clicking would.
 *
 * Called every tick from MinebotMod.resolveMovementIntent while a
 * pathfinding waypoint's toBreak list or a dig_down/collect goal has a
 * target block, the same "does nothing most ticks" shape DoorOpener
 * already established for its own real interaction.
 *
 * Callers must check isBusy() (not just tryBreak's own return value)
 * before treating a break as fully finished and resuming movement/
 * advancing to whatever comes next -- see isBusy's own docstring and
 * SETTLE_TICKS for why a break isn't safely "done" the instant
 * tryBreak's local block-state check shows air.
 */
public final class BlockBreaker {
    // Matches DoorOpener's own interaction-reach constant -- how close (in
    // blocks) the bot must be before it can start swinging at a block.
    private static final double INTERACT_RANGE = 4.5;

    // How many ticks to keep holding movement/jump still after a break
    // completes client-side, before actually handing control back to
    // walking -- reported live: the bot resumed walking/jumping toward
    // the next waypoint the instant tryBreak's local block-state check
    // showed air, which is only ever a client *prediction* (see this
    // class's own docstring on destroyBlock's real client-side-prediction/
    // server-authoritative-completion split). Moving immediately risked
    // interrupting whatever was still settling server-side before the
    // break was actually confirmed (a real, load-bearing tool-switch mid-
    // break was already found and fixed as one concrete way this mod
    // itself could reset the server's own destroy-progress tracking --
    // see maybeSwitchToBestTool's docstring; this settle window is a
    // second, complementary safeguard against any other still-unproven
    // interruption this mod's own immediate next action could cause,
    // e.g. jumping/moving right as the sequence concludes). 6 ticks
    // (0.3s) is a deliberately generous margin, not tuned against a
    // measured round-trip time -- cheap to hold a little longer than
    // strictly needed since nothing else can happen productively during
    // a break anyway.
    private static final int SETTLE_TICKS = 6;

    // Which caller/purpose this instance belongs to (e.g. "collect",
    // "digDown", "pathfinding") -- purely for log attribution. MinebotMod
    // owns three separate BlockBreaker instances (see its own docstring
    // on why sharing one would corrupt progress across goals); without a
    // tag, a debugging session can't tell which one produced a given log
    // line, which matters a lot when chasing a report of two different
    // BlockBreakers seemingly fighting over the same block.
    // How many ticks tryBreak will hold keyAttack against the same target
    // without a completion before giving up on it entirely (releasing the
    // key, clearing currentTarget) -- reported live: a pathfinding dig-
    // through-a-wall held keyAttack against one grass block for 601
    // ticks (30s) straight, real getDestroyProgress accumulating every
    // single tick, in range, with line of sight, yet never completing --
    // the bot had fallen ~2 blocks partway through (real gravity, not
    // this mod's own movement logic) and landed somewhere the real
    // per-tick destroy sequence apparently never actually finishes
    // against, for a reason not fully root-caused (plausibly an aim/
    // hitResult interpolation edge case while the bot was still settling
    // from the fall -- see FINDINGS.md). Unlike !collect/!dig, which
    // already had their own give-up-after-N-ticks logic
    // (ControlState.collectTargetStuckTicks, mirrored for digDown),
    // pathfinding's own maybeBreakBlocksNear had no equivalent at all --
    // it could hold a stuck target forever. Moving the timeout in here,
    // not into any one caller, means every caller (including future
    // ones) gets this safety net automatically. ~50 real seconds (1000
    // ticks) -- deliberately much longer than the 601-tick failure this
    // is responding to, generous enough that no legitimately slow break
    // (a very hard block with a weak tool) should ever trip it, but
    // bounded so a genuinely stuck target can't hold keyAttack forever.
    private static final int STUCK_TICKS_LIMIT = 1000;

    private final String label;
    private BlockPos currentTarget;
    private int stuckTicks;
    private int settleTicksRemaining;
    // The position tryBreak most recently reported a genuine solid->air
    // completion for -- distinct from currentTarget (cleared back to null
    // the instant a break completes) specifically so a caller can still
    // tell "this exact break just finished" apart from "some other,
    // unrelated cause made this position air" once isBusy() finally
    // returns false (see MinebotMod.tickCollect's own use of this: without
    // it, the tick a settle window elapses has no way to distinguish its
    // own just-completed break from a target that vanished some other
    // way, and would misclassify a real success as an abandoned target).
    private BlockPos lastCompletedTarget;

    public BlockBreaker(final String label) {
        this.label = label;
    }

    /**
     * True while this instance is either actively breaking something or
     * still settling after a just-completed break (see SETTLE_TICKS) --
     * callers that decide whether to resume walking/jumping should check
     * this, not just tryBreak's own return value, so movement doesn't
     * resume the instant a break's client-predicted completion is seen,
     * before it's actually had time to be confirmed.
     */
    public boolean isBusy() {
        return currentTarget != null || settleTicksRemaining > 0;
    }

    /**
     * True while there's an active target still being worked (currentTarget
     * != null) -- as opposed to isBusy(), which is also true purely from
     * settling with no active target at all. Callers must keep calling
     * tryBreak every tick this is true, or a break can never progress or
     * complete: tryBreak is the only thing that ever advances a break
     * (aims, holds keyAttack, and -- via its own isAir branch -- notices a
     * genuine one-tick-late completion) or clears currentTarget. Reported
     * live, twice, in two different callers (!debug and !dig): gating
     * entry to tryBreak behind isBusy() alone deadlocked forever the
     * moment a tool switch made tryBreak return false without completing
     * anything (see tryBreak's own "switched tool this tick" branch) --
     * isBusy() was already true from that point on, so a caller that
     * skipped calling tryBreak whenever isBusy() was true never called it
     * again, and the break could never actually proceed. Settling alone
     * (no active target) is the only state where skipping tryBreak is
     * actually safe -- tickSettle already handles that case on its own.
     */
    public boolean hasActiveTarget() {
        return currentTarget != null;
    }

    /** The position currently being actively mined, or null if none (see hasActiveTarget). */
    public BlockPos currentTarget() {
        return currentTarget;
    }

    /**
     * True once isBusy() has settled back to false, if the break that just
     * finished settling was for exactly `pos` -- lets a caller confirm "my
     * own just-completed break is done settling" instead of re-deriving
     * completion from live block state, which can't distinguish a real
     * just-broken block from some unrelated cause (see lastCompletedTarget's
     * own field docstring).
     */
    public boolean justFinishedSettling(final BlockPos pos) {
        return !isBusy() && pos.equals(lastCompletedTarget);
    }

    /**
     * Ticks the settle countdown down, and -- critically -- also checks
     * whether a still-in-progress break (currentTarget != null) has
     * become air since the last check, treating that the same way
     * tryBreak's own isAir branch does (a genuine completion, since this
     * instance was the one actively holding keyAttack against it).
     *
     * This must run every tick regardless of whether callers go on to
     * call tryBreak again that same tick -- found live (see FINDINGS.md):
     * every caller's own gating skips calling tryBreak again once a
     * target looks done (pathfinding's toBreak loop stops finding a
     * still-solid block to pass in; !collect/!dig both gate entry behind
     * isBusy()) -- but tryBreak was the *only* thing that ever cleared
     * currentTarget, creating a real deadlock: isBusy() stays true
     * forever because nothing ever calls the one method that would
     * notice the break actually finished and clear it. Calling this
     * unconditionally every tick (already required for the settle
     * countdown itself) closes that gap -- a completion gets attributed
     * and currentTarget cleared here even on a tick no caller happens to
     * invoke tryBreak directly.
     */
    public void tickSettle(final ClientLevel level) {
        if (settleTicksRemaining > 0) {
            settleTicksRemaining--;
        }
        if (currentTarget != null && level.getBlockState(currentTarget).isAir()) {
            BlockPos completed = currentTarget;
            currentTarget = null;
            settleTicksRemaining = SETTLE_TICKS;
            lastCompletedTarget = completed;
            releaseAttackKey();
            MinebotMod.LOGGER.info(
                "mining[{}]: tickSettle({}) -- broke it (observed via settle check, not a fresh tryBreak call), settling for {} ticks before resuming movement",
                label, completed, SETTLE_TICKS
            );
        }
    }

    /**
     * Attempts to break the block at `pos`. Returns true on the call that
     * observes it going from solid to air -- either directly (the old
     * direct-API-call model, where this method drove
     * MultiPlayerGameMode.continueDestroyBlock itself and could observe
     * the exact tick of transition) or, now that this holds keyAttack
     * instead (see class docstring), via a genuine "was I actively
     * targeting this exact position last tick, and is it air now"
     * inference -- see the isAir branch below for why the direct
     * observation case structurally can't happen with the keyAttack-hold
     * mechanism. False while still in progress, out of range, not
     * actually visible (see hasLineOfSight below), unbreakable, or if
     * `pos` was air *before* this ever started targeting it (a target
     * genuinely vanishing for some unrelated reason) -- callers that
     * count "how many did I break" must not treat that case as a fresh
     * success, or a caller re-checking the same already-broken position
     * on a later tick would silently double-count one real break as
     * several. Safe to call every tick with the same `pos` -- continues
     * the same in-progress break rather than restarting it.
     */
    public boolean tryBreak(final LocalPlayer player, final ClientLevel level, final BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            // Whether this is a real completion this call is observing,
            // or the target was simply already gone for some unrelated
            // reason, hinges on whether *this instance* was actively
            // holding keyAttack against exactly `pos` as of the last call
            // -- see class docstring for the live investigation that
            // found this. With the keyAttack-hold mechanism, a real
            // completion's actual solid->air transition happens inside
            // vanilla's own Minecraft.handleKeybinds() (MultiPlayerGameMode.
            // continueDestroyBlock's internal destroyBlock() call),
            // which always runs *before* this mod's own tick hook
            // (ClientTickEvents.END_CLIENT_TICK fires at the end of the
            // tick) -- so by the time tryBreak ever gets called again on
            // the completing tick, the transition has *already* happened,
            // and the old "return true from the !stillThere check further
            // down this method" branch can structurally never fire for a
            // real completion anymore; every genuine success shows up
            // here instead, one call late. Confirmed live via !debug
            // (see FINDINGS.md): real, steady getDestroyProgress
            // accumulation for several ticks, immediately followed by
            // this exact branch, on a target this instance was
            // definitely still actively mining -- with the old logic that
            // was silently discarded as "something else must have
            // removed it" and re-targeted from scratch, forever, despite
            // the break being completely genuine each time (confirmed:
            // real item_drop events landed during this exact loop).
            boolean wasCompletingOurOwnBreak = pos.equals(currentTarget);
            if (wasCompletingOurOwnBreak) {
                currentTarget = null;
                settleTicksRemaining = SETTLE_TICKS;
                lastCompletedTarget = pos;
                releaseAttackKey();
                MinebotMod.LOGGER.info(
                    "mining[{}]: tryBreak({}) -- broke it (observed one tick late, via keyAttack-hold -- see class docstring), settling for {} ticks before resuming movement",
                    label, pos, SETTLE_TICKS
                );
                return true;
            }
            if (currentTarget != null) {
                MinebotMod.LOGGER.info("mining[{}]: tryBreak({}) -- already air on arrival (was targeting {})", label, pos, currentTarget);
            }
            currentTarget = null;
            releaseAttackKey();
            return false; // nothing to break here -- already gone before this call, and we weren't the one targeting it
        }

        double dx = (pos.getX() + 0.5) - player.getX();
        double dy = (pos.getY() + 0.5) - player.getEyeY();
        double dz = (pos.getZ() + 0.5) - player.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance > INTERACT_RANGE) {
            MinebotMod.LOGGER.info("mining[{}]: tryBreak({}) -- out of range (distance={})", label, pos, distance);
            releaseAttackKey(); // not close enough to actually be mining -- don't hold attack while just walking over
            return false; // not close enough yet -- caller should keep walking toward it
        }

        if (!hasLineOfSight(player, level, pos)) {
            // Reported live: !collect picked a block reachable only by
            // distance (e.g. across/through a wall from the bot's actual
            // standing position) and got stuck there forever, since a
            // real player can never actually mine something they can't
            // see -- BlockFinder's search has no line-of-sight awareness
            // at all, it's a pure distance scan. Refusing to mine here
            // (rather than swinging at empty air toward an obstructed
            // target) matches what a real client would do: the crosshair
            // simply can't land on a block hidden behind another one.
            // The caller (tickCollectBlock) is responsible for eventually
            // giving up on a target that never becomes visible -- see its
            // own docstring.
            MinebotMod.LOGGER.info("mining[{}]: tryBreak({}) -- no line of sight", label, pos);
            releaseAttackKey();
            return false;
        }

        boolean isNewTarget = !pos.equals(currentTarget);
        if (isNewTarget) {
            MinebotMod.LOGGER.info(
                "mining[{}]: tryBreak({}) -- new target (was {}), state={}, destroySpeed={}",
                label, pos, currentTarget, state.getBlock(), state.getDestroySpeed(level, pos)
            );
            // Only re-evaluate/switch tools when actually starting a fresh
            // target, not every tick of an already-in-progress break --
            // this used to run unconditionally every tick specifically so
            // FoodEater stealing the hotbar selection mid-break (to eat)
            // would get corrected on the very next tick instead of mining
            // the rest of that block with whatever was left selected. But
            // a real vanilla break is only considered continuous
            // (MultiPlayerGameMode.sameDestroyTarget, confirmed via
            // decompiled source) if the held ItemStack stays *exactly* the
            // same, by full component comparison, for the whole sequence
            // -- any hotbar swap mid-break, even reselecting the identical
            // logical item, changes the stack instance/components enough
            // to reset the server's own destroy-progress tracking back to
            // zero. Reported live: mined stone with a real pickaxe
            // consistently appeared to break (client-predicted removal
            // visibly happened, sometimes even reappearing and needing a
            // second "break"), but never actually produced a drop -- exactly
            // what a client-predicted completion getting silently reverted
            // once the server's own, still-in-progress destroy-progress
            // disagreed would look like. Not re-touching tool selection
            // after a break has already started removes one concrete way
            // this mod itself could have been causing that reset --
            // FoodEater stealing the selection mid-break can still corrupt
            // a break same as before, but that's a real, separate,
            // pre-existing tradeoff (see FoodEater's own docstring),
            // narrower than unconditionally re-checking on every tick.
            boolean switchedTool = maybeSwitchToBestTool(player, state);
            currentTarget = pos;
            stuckTicks = 0;
            if (switchedTool) {
                // Skip holding keyAttack for this exact tick -- reported
                // live: the server-side loot-table roll for a completed
                // break used whichever item was selected *before* this
                // tick's hotbar swap, not the pickaxe the client/log
                // showed as selected, even though the local swap and its
                // ServerboundSetCarriedItemPacket (via
                // MultiPlayerGameMode.ensureHasSentCarriedItem, confirmed
                // via decompiled source) both happen immediately.
                // moveToHotbar's swap is a local Inventory mutation, not
                // itself packet-synced -- ensureHasSentCarriedItem only
                // fires the sync packet, it doesn't wait for the server to
                // acknowledge it before whatever destroy-related packet
                // goes out next. Immediately holding keyAttack the same
                // tick as the swap sent both the carried-item sync and the
                // first destroy packet back to back, with no guarantee the
                // server had actually processed the former before the
                // latter -- exactly matching a live report of the bot
                // visibly holding (and the log confirming) a diamond
                // pickaxe while mining stone, yet the stone dropped
                // nothing, the same way breaking it bare-handed would.
                // Waiting one tick before ever holding keyAttack gives the
                // carried-item packet a full tick's head start to land
                // server-side first.
                MinebotMod.LOGGER.info(
                    "mining[{}]: tryBreak({}) -- switched tool this tick, waiting one tick before holding keyAttack so the carried-item sync lands server-side first",
                    label, pos
                );
                return false;
            }
        }

        if (++stuckTicks > STUCK_TICKS_LIMIT) {
            MinebotMod.LOGGER.warn(
                "mining[{}]: tryBreak({}) -- giving up after {} ticks with no completion (in range, line of sight, real progress accumulating -- see STUCK_TICKS_LIMIT's own docstring for the live repro this guards against)",
                label, pos, stuckTicks
            );
            currentTarget = null;
            stuckTicks = 0;
            releaseAttackKey();
            Minecraft.getInstance().gameMode.stopDestroyBlock();
            return false;
        }

        aimAt(player, pos);

        // Hold the real keyAttack keybind rather than calling
        // startDestroyBlock/continueDestroyBlock ourselves -- see this
        // class's own docstring for why (a direct API call visibly broke
        // blocks without ever producing a real drop; holding the actual
        // keybind and letting vanilla's own Minecraft.handleKeybinds()
        // drive the interaction, the same fix FoodEater's keyUse-hold
        // already established for eating, is confirmed to work where the
        // direct call didn't). Idempotent to call every tick while a break
        // is still in progress -- setDown(true) on an already-down key is
        // a no-op. Relies on aimAt (just above) having set real yaw/pitch
        // at this target -- Minecraft's own per-frame crosshair raycast
        // needs to actually be landing on `pos` for handleKeybinds' held-key
        // handling to do anything.
        holdAttackKey();

        // Debug-only per-tick diagnostic (kept cheap/always-on at info,
        // same reasoning as every other log line in this class -- this
        // client's default log4j config filters debug output entirely) --
        // added chasing a live report that a held keyAttack never
        // completes a break even with the bot's window genuinely focused.
        // getDestroyProgress is the real per-tick progress rate
        // MultiPlayerGameMode.continueDestroyBlock itself accumulates
        // internally (see this class's own docstring) -- logging it every
        // tick makes it directly observable whether real progress is
        // accumulating at all, or something is silently keeping it at (or
        // resetting it to) a rate that never reaches completion.
        MinebotMod.LOGGER.info(
            "mining[{}]: tryBreak({}) -- holding keyAttack, mainHand={}, getDestroyProgress this tick={}, real internal state: {}",
            label, pos, player.getMainHandItem(), state.getDestroyProgress(player, level, pos), dumpRealDestroyState()
        );

        // Defensive fallback only -- confirmed live this branch does not
        // actually fire for a real completion under the keyAttack-hold
        // mechanism (see the isAir branch above for why: the real
        // transition happens inside vanilla's own handleKeybinds, which
        // always runs before this mod's own tick hook, so by the time
        // this line runs on the completing tick the block is already air
        // -- caught by the isAir branch at the top of this method on the
        // *next* call, not here). Kept in case that ordering assumption
        // is ever wrong for some tick, rather than deleting the ability
        // to observe a same-call transition entirely.
        boolean stillThere = !level.getBlockState(pos).isAir();
        if (!stillThere) {
            currentTarget = null;
            settleTicksRemaining = SETTLE_TICKS;
            lastCompletedTarget = pos;
            releaseAttackKey();
            ItemStack heldWhenBroken = player.getMainHandItem();
            MinebotMod.LOGGER.info(
                "mining[{}]: tryBreak({}) -- broke it (observed same-call), was holding {} (destroySpeed was {}), settling for {} ticks before resuming movement",
                label, pos, heldWhenBroken.getItem(), heldWhenBroken.getDestroySpeed(state), SETTLE_TICKS
            );
        }
        return !stillThere;
    }

    /**
     * Holds the real keyAttack keybind down -- Minecraft.handleKeybinds()
     * (called every client tick regardless of this mod) reads this on its
     * own and drives the actual startAttack()/continueAttack() interaction
     * from it. Idempotent to call every tick while breaking should
     * continue, same shape as FoodEater.holdUseKey.
     *
     * Also force-grabs the mouse every call -- root-caused live via a
     * reflection diagnostic on MultiPlayerGameMode's real private state
     * (see dumpRealDestroyState): isDestroying stayed false and
     * destroyBlockPos stayed the {-1,-1,-1} sentinel on literally every
     * single tick of a run where getDestroyProgress logged real, steady,
     * vanilla-formula-correct progress (0.178/tick on stone with a diamond
     * pickaxe) continuously for 200+ ticks -- meaning a real destroy
     * sequence was never actually starting server-side at all, despite
     * keyAttack being held the whole time and the crosshair/aim being
     * correct. Traced to decompiled Minecraft.handleKeybinds/continueAttack:
     * `continueAttack`'s own `down` argument (the thing that decides
     * whether to call MultiPlayerGameMode.continueDestroyBlock at all, vs.
     * calling stopDestroyBlock() instead) is gated on
     * `this.mouseHandler.isMouseGrabbed()`, not merely keyAttack.isDown() --
     * unlike keyUse (FoodEater's own hold target), which handleKeybinds
     * drives with no such gate. Without real mouse capture, keyAttack.
     * consumeClick() can still fire startAttack() once (briefly setting
     * isDestroying=true), but continueAttack(false) immediately calls
     * stopDestroyBlock() on the very next tick (mouseGrabbed still false),
     * resetting straight back to isDestroying=false forever -- exactly the
     * observed symptom. MouseHandler.grabMouse() itself no-ops unless
     * Minecraft.isWindowActive() (real OS window focus) is also true, so
     * this can't force progress through a genuinely unfocused/backgrounded
     * window, but it does recover the common case of a focused window
     * whose mouse simply isn't captured (e.g. after any GUI screen/chat
     * box briefly took it). Safe to call every tick -- grabMouse() itself
     * is already a no-op once already grabbed.
     */
    private static void holdAttackKey() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.keyAttack.setDown(true);
        // continueAttack's real gate is `this.screen == null && ... &&
        // this.mouseHandler.isMouseGrabbed()` (see this method's own
        // docstring) -- grabMouse() alone isn't sufficient, since
        // grabMouse() itself is a no-op unless Minecraft.isWindowActive()
        // is true *at the exact moment it's called*, and mouseGrabbed is
        // just a sticky boolean that doesn't automatically clear when
        // focus is lost again afterward. Reported live (second repro,
        // same underlying issue): a session logged isMouseGrabbed=true for
        // 1475 consecutive ticks (grabMouse() clearly succeeded at some
        // point) while isDestroying stayed false the entire time regardless
        // -- the missing piece was `this.screen`: losing real OS focus
        // typically auto-opens Minecraft's own pause screen, and
        // `continueAttack` bails out on any non-null screen independent of
        // mouse-grab state. grabMouse() itself already does `setScreen(null)`
        // internally, but only inside its own isWindowActive() guard --
        // useless on a tick where focus is still lost. Clearing the screen
        // unconditionally here (whenever this mod is actively trying to
        // hold a break) directly removes that second, independent
        // blocker rather than hoping grabMouse() clears it as a side
        // effect.
        mc.setScreen(null);
        mc.mouseHandler.grabMouse();
    }

    /** Releases the attack key -- must be called once a break is no longer wanted (finished, aborted, or target unreachable), or a human retaking real control would find it stuck held. */
    private static void releaseAttackKey() {
        Minecraft.getInstance().options.keyAttack.setDown(false);
    }

    // Reflection field handles for MultiPlayerGameMode's real internal
    // destroy-progress state (destroyProgress/destroyTicks/isDestroying/
    // destroyBlockPos/destroyingItem) -- none of these are public (only
    // isDestroying() exists, with no way to read the running progress sum
    // itself). Added purely as a one-time diagnostic to chase a live
    // report of real, steady, vanilla-formula-consistent getDestroyProgress
    // (0.178/tick on stone with a diamond pickaxe, mathematically ~6 ticks
    // to complete) held continuously against a stationary, correctly-aimed
    // target for 200+ ticks without ever completing -- every already-fixed
    // cause (tool switch mid-break, aim/movement fighting the break,
    // isBusy()-deadlock) was ruled out for this exact repro, so the next
    // live run needs to see the real internal accumulator directly rather
    // than inferring it from the client-local per-tick rate alone (see
    // BlockBreaker's own class docstring and STUCK_TICKS_LIMIT's docstring
    // for the full history of this still-not-fully-root-caused symptom).
    // Cached once (fields are stable across the JVM's lifetime) rather
    // than re-resolved every tick.
    private static Field destroyProgressField;
    private static Field destroyTicksField;
    private static Field destroyBlockPosField;
    private static Field destroyingItemField;
    private static boolean reflectionFailed;

    private static String dumpRealDestroyState() {
        if (reflectionFailed) {
            return "reflection unavailable";
        }
        try {
            MultiPlayerGameMode gameMode = Minecraft.getInstance().gameMode;
            if (destroyProgressField == null) {
                destroyProgressField = MultiPlayerGameMode.class.getDeclaredField("destroyProgress");
                destroyProgressField.setAccessible(true);
                destroyTicksField = MultiPlayerGameMode.class.getDeclaredField("destroyTicks");
                destroyTicksField.setAccessible(true);
                destroyBlockPosField = MultiPlayerGameMode.class.getDeclaredField("destroyBlockPos");
                destroyBlockPosField.setAccessible(true);
                destroyingItemField = MultiPlayerGameMode.class.getDeclaredField("destroyingItem");
                destroyingItemField.setAccessible(true);
            }
            Minecraft mc = Minecraft.getInstance();
            return String.format(
                "isDestroying=%s, destroyBlockPos=%s, destroyingItem=%s, destroyProgress(cumulative)=%s, destroyTicks=%s, "
                    + "isWindowActive=%s, isMouseGrabbed=%s, screen=%s, hitResult=%s",
                gameMode.isDestroying(), destroyBlockPosField.get(gameMode), destroyingItemField.get(gameMode),
                destroyProgressField.get(gameMode), destroyTicksField.get(gameMode),
                mc.isWindowActive(), mc.mouseHandler.isMouseGrabbed(), mc.screen, mc.hitResult
            );
        } catch (ReflectiveOperationException e) {
            reflectionFailed = true;
            MinebotMod.LOGGER.warn("mining: dumpRealDestroyState reflection failed, disabling further attempts", e);
            return "reflection failed: " + e;
        }
    }

    /**
     * Releases the real keyAttack keybind unconditionally, regardless of
     * which BlockBreaker instance (if any) most recently held it down --
     * static and stateless since the keybind itself is a single piece of
     * global input state, not owned by any one instance. Called from
     * MinebotMod's death-tick handling (mirroring FoodEater.
     * releaseUseKeyIfHeld) so a break in progress at the moment of death
     * doesn't leave attack stuck held through death/respawn.
     */
    public static void releaseAttackKeyIfHeld() {
        releaseAttackKey();
    }

    /** Cleanly aborts any in-progress break -- called when a dig goal is cancelled (!stop) or superseded by a new target. */
    public void stopBreaking() {
        if (currentTarget != null) {
            MinebotMod.LOGGER.info("mining[{}]: stopBreaking() -- aborting break of {}", label, currentTarget);
            releaseAttackKey();
            Minecraft.getInstance().gameMode.stopDestroyBlock();
            currentTarget = null;
        }
    }

    // Below this horizontal offset (blocks) from the target block's own
    // center, atan2(dy, horizontalDistance)/atan2(-dx, dz) both approach a
    // numerically degenerate near-vertical singularity -- yaw becomes
    // effectively arbitrary (tiny floating-point noise in dx/dz swings it
    // wildly) right as pitch approaches +90, exactly the geometry of
    // digging a block directly underfoot (pathfinding's own dig-through-
    // obstacles case, not !collect's roughly-eye-level targets). Reported
    // live via the reflection diagnostic (dumpRealDestroyState):
    // isDestroying stayed false forever (destroyBlockPos stuck at the
    // {-1,-1,-1} "nothing targeted" sentinel, meaning vanilla's real
    // raycast was outright MISSING, not just landing on a neighboring
    // block) for a target only ~0.08 blocks of horizontal offset away,
    // pitch ~87.8 degrees -- confirmed isWindowActive=true,
    // isMouseGrabbed=true at the time, ruling out the separate mouse-grab
    // cause entirely for this specific failure. Below this threshold,
    // aim at a point offset toward one corner of the target's top face
    // instead of its exact center -- still guaranteed to land on the
    // target itself (the offset stays within the block's own footprint),
    // but restores a well-defined, non-degenerate horizontal direction
    // for both yaw and pitch to point toward.
    //
    // A first attempt at this used a much smaller offset (0.3) and only
    // brought pitch down a few degrees (e.g. 86.6 -> 83.6 for a ~2-block
    // vertical drop) -- confirmed live via the diagnostic that this
    // wasn't nearly enough: destroyBlockPos still locked onto the wrong
    // (one-block-off) neighbor at that reduced-but-still-steep angle.
    // 0.5 blocks is the largest offset from center that still guarantees
    // staying within the target block's own footprint on both axes
    // (center 0.5 +/- 0.5 spans the full [0,1) range) -- using the
    // largest safe value, not a token nudge, maximizes how much this can
    // actually reduce pitch for any given vertical drop.
    private static final double NEAR_VERTICAL_HORIZONTAL_THRESHOLD = 0.5;
    private static final double NEAR_VERTICAL_AIM_OFFSET = 0.5 - 0.02;

    private static void aimAt(final LocalPlayer player, final BlockPos pos) {
        double targetX = pos.getX() + 0.5;
        double targetZ = pos.getZ() + 0.5;
        double roughDx = targetX - player.getX();
        double roughDz = targetZ - player.getZ();
        if (Math.sqrt(roughDx * roughDx + roughDz * roughDz) < NEAR_VERTICAL_HORIZONTAL_THRESHOLD) {
            targetX = pos.getX() + NEAR_VERTICAL_AIM_OFFSET;
            targetZ = pos.getZ() + NEAR_VERTICAL_AIM_OFFSET;
        }

        double dx = targetX - player.getX();
        double dz = targetZ - player.getZ();
        double dy = (pos.getY() + 0.5) - player.getEyeY();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        // Positive pitch = looking down (same convention confirmed in
        // NearbyPlayerLookAt's own docstring via decompiled
        // Entity.calculateViewVector).
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontalDistance));
        player.setYRot(yaw);
        player.setXRot(pitch);

        // Also snap yRotO/xRotO (the *previous*-tick rotation, used to
        // interpolate the actual rendered/raycast camera angle across
        // partial ticks -- see Entity.getViewXRot/getViewYRot, and
        // LocalPlayer.raycastHitResult's own use of
        // cameraEntity.getEyePosition(partialTicks)/cameraEntity.pick,
        // both partial-tick-interpolated) to the same value, not just the
        // current-tick rotation -- confirmed root cause via the reflection
        // diagnostic (dumpRealDestroyState): a real break's destroyBlockPos
        // consistently locked onto a *different*, adjacent block than the
        // one tryBreak was actually aiming at and holding keyAttack toward,
        // specifically for steep-pitch targets (pathfinding digging the
        // block directly below/beside the bot's own feet, ~70-86 degrees
        // pitch) -- isDestroying stayed false forever despite real,
        // steady, correct-formula getDestroyProgress logging every tick,
        // because vanilla's own hitResult (computed from the interpolated,
        // partial-tick camera angle, not the raw discrete player rotation
        // this method sets) never actually agreed with what this class
        // thought it was aiming at. A single-tick rotation snap from
        // whatever the bot was previously looking at (e.g. level, mid-walk)
        // to a steep mining pitch leaves several rendered frames still
        // easing from the *old* angle (xRotO) toward the new one, long
        // enough for the real per-frame raycast to clip a neighboring
        // block instead -- exactly the observed one-block-off mismatch.
        // Setting xRotO/yRotO immediately removes that lag the same way
        // Entity's own moveTo(...) does when teleporting (confirmed via
        // decompiled source) -- there's no legitimate reason for this
        // mod's own aim to ever be smoothly interpolated toward, unlike a
        // human's actual mouse movement.
        player.yRotO = yaw;
        player.xRotO = pitch;
    }

    // Sample points on the target block, tried in order until one has
    // clear line of sight -- the exact center alone is too strict: a
    // real player mining a block adjacent to (not just behind) their own
    // standing block naturally has a clean angle to *some* point on its
    // near face even when a straight ray to the dead center clips the
    // corner of their own ground block first (confirmed live: the bot
    // got permanently stuck "looking down" at stone directly below/beside
    // it -- exactly the geometry where a center-only ray grazes the
    // block the bot is standing on). Offsets are within the block's own
    // 1x1x1 volume, not on its surface, so a hit at any of these still
    // has to be the target itself, never a false positive from clipping
    // through it into whatever's beyond.
    private static final double[][] SIGHT_SAMPLE_OFFSETS = {
        {0.5, 0.5, 0.5}, // center
        {0.5, 0.9, 0.5}, // near top face -- often clearer when the obstruction is a block below/beside
        {0.5, 0.1, 0.5}, // near bottom face
        {0.1, 0.5, 0.5},
        {0.9, 0.5, 0.5},
        {0.5, 0.5, 0.1},
        {0.5, 0.5, 0.9},
    };

    /**
     * True if any point on `pos` is actually visible from the player's
     * eye -- a real client's crosshair can never land on a block hidden
     * behind another one, but the distance-only check above says nothing
     * about what's *between* the player and the target. Uses the same
     * real raycast primitive vanilla itself uses for the crosshair pick
     * (Level.clip(ClipContext), ClipContext.Block.OUTLINE -- confirmed
     * via decompiled source, e.g. Entity.pick's own use of this exact
     * shape) rather than a hand-rolled line/voxel walk, tried against
     * several points on the target (see SIGHT_SAMPLE_OFFSETS) rather
     * than only its exact center, since a real player has the same
     * freedom to aim at any visible point on a block's face, not just
     * its middle.
     */
    private static boolean hasLineOfSight(final LocalPlayer player, final ClientLevel level, final BlockPos pos) {
        Vec3 from = player.getEyePosition();
        for (double[] offset : SIGHT_SAMPLE_OFFSETS) {
            Vec3 to = new Vec3(pos.getX() + offset[0], pos.getY() + offset[1], pos.getZ() + offset[2]);
            BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Scans main storage + hotbar (Inventory slots 0-35 -- armor/offhand
     * slots 36-42 are deliberately excluded, swapping a chestplate into
     * the hotbar to mine with makes no sense) for whichever carried item
     * has the highest real ItemStack.getDestroySpeed(state) against the
     * target block, and hotbar-swaps to it (via InventoryActions.
     * moveToHotbar's existing local-state-swap idiom, reusing hotbar slot
     * 8) if it beats what's currently in hand. Real getDestroySpeed is
     * the same per-item mining-speed value BlockState.getDestroyProgress
     * itself reads internally (confirmed via BlockBehaviour's decompiled
     * source), so "best tool" here means exactly what the game's own
     * progress calculation would agree is fastest -- no separate
     * tool-category guessing (pickaxe vs axe vs shovel) needed.
     *
     * Called only when starting a fresh target (see tryBreak's isNewTarget
     * branch), not every tick of an already-in-progress break. This used
     * to run unconditionally every tick specifically so FoodEater's
     * auto-eat logic stealing the hotbar selection mid-break (to eat)
     * would get corrected on the very next tick rather than silently
     * mining the rest of that block with whatever was left selected --
     * but real vanilla treats *any* mid-break hotbar swap, even
     * reselecting the exact same logical item, as starting a brand new
     * destroy sequence (MultiPlayerGameMode.sameDestroyTarget requires the
     * held ItemStack to stay identical by full component comparison, not
     * just same item type -- confirmed via decompiled source), resetting
     * real destroy progress back to zero either way. Reported live: mined
     * stone with a real pickaxe in hand consistently appeared to break
     * (client-side prediction showed it gone, sometimes visibly
     * reappearing and needing a second "break") but never actually
     * produced a drop -- exactly what a client-predicted completion
     * getting silently reverted by the server's own still-resetting
     * destroy-progress tracking would look like from outside. Restricting
     * this to only new-target ticks removes one concrete way this mod
     * itself could trigger that reset mid-break; FoodEater stealing the
     * selection mid-break remains a real, separate, pre-existing tradeoff
     * (see its own docstring) this doesn't attempt to solve.
     */
    private static boolean maybeSwitchToBestTool(final LocalPlayer player, final BlockState state) {
        Inventory inventory = player.getInventory();
        int selectedSlot = inventory.getSelectedSlot();
        ItemStack selectedStack = inventory.getItem(selectedSlot);
        int bestSlot = -1;
        // Starting bestSpeed from the currently-selected item's own speed
        // means "nothing carried beats it" is only really true when
        // something is actually selected. Reported live: !collect on a
        // hardness-0 block (carrots -- real vanilla ItemStack.
        // getDestroySpeed against a 0-hardness block returns 1.0
        // regardless of what's held, tools included) with an empty
        // hotbar slot already selected (e.g. after !give emptied it and
        // nothing was ever reselected) never switched away from that
        // empty slot -- every real carried item tied bestSpeed (1.0)
        // rather than strictly beating it, and an empty ItemStack was
        // never excluded from being the *starting* baseline the way the
        // scan loop below already excludes it from being a *candidate*
        // (stack.isEmpty() continue, just below). Bare hands should never
        // be preferred over holding literally anything, independent of
        // whatever tie the speed math produces -- forcing bestSpeed to
        // -1 whenever nothing is currently selected means the very first
        // real (non-empty) item found always counts as strictly better,
        // regardless of its own speed.
        float bestSpeed = selectedStack.isEmpty() ? -1.0f : selectedStack.getDestroySpeed(state);

        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            float speed = stack.getDestroySpeed(state);
            // Per-slot scan detail stays at debug (one line per carried
            // item, every tick a switch is even considered) -- the two
            // summary lines below are the ones that actually matter for
            // "is it picking the right tool", promoted to info
            // (temporarily, for the live report this was added to chase
            // -- "still not using the pickaxe" after the pickSlot fix)
            // since this client's default log4j config filters debug
            // output entirely and no debug line from this mod has ever
            // actually appeared in a real log.
            MinebotMod.LOGGER.debug("mining: slot {} ({}) speed={}", slot, stack.getItem(), speed);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = slot;
            }
        }

        if (bestSlot >= 0) {
            MinebotMod.LOGGER.info(
                "mining: switching to slot {} ({}, speed={}) -- was slot {} ({}, speed={})",
                bestSlot, inventory.getItem(bestSlot).getItem(), bestSpeed, selectedSlot, selectedStack.getItem(),
                selectedStack.getDestroySpeed(state)
            );
            InventoryActions.moveToHotbar(player, bestSlot, 8);
            MinebotMod.LOGGER.info(
                "mining: after switch, selected slot is now {} ({})",
                inventory.getSelectedSlot(), inventory.getItem(inventory.getSelectedSlot()).getItem()
            );
            return true;
        }
        MinebotMod.LOGGER.info("mining: keeping slot {} ({}) -- nothing carried beats it", selectedSlot, selectedStack.getItem());
        return false;
    }
}
