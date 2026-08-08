package minebot.mod.statemachine.hands;

import minebot.mod.InventoryController;
import minebot.mod.statemachine.BlackboardKey;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Eats food from the bot's own inventory whenever health is low AND
 * eating is actually possible right now -- ported from the old
 * standalone FoodEater class (see git history), which used to run
 * unconditionally every tick regardless of any mode/state at all. The
 * actual eating mechanism (holding the real `keyUse` keybind -- see
 * class docstring below for why) is unchanged.
 *
 * Owns its own low-health/can-eat/safe-to-eat gating entirely -- there is
 * deliberately NO PlayerIntention:SELF_HEAL state anymore (see PlayerIntentionState's
 * own docstring for why it was removed): an earlier design had PlayerIntention
 * publish NEEDS_HEAL and Hands:EAT just react to it, but PlayerIntention's own
 * isFinished() only ever checked whether health had recovered, never
 * whether Hands could actually make progress -- a bot at low health with
 * hunger already full (blocking all non-canAlwaysEat food) got stuck in
 * PlayerIntention:SELF_HEAL indefinitely, confirmed live. HandsStateMachine's own
 * entry edge for EAT checks lowHealth() && hasFood() && isSafeToEat()
 * (see below), so Hands naturally gives up (falls back to IDLE) the
 * instant eating stops being possible, with zero PlayerIntention
 * involvement.
 *
 * SAFE_DISTANCE/isSafeToEat() decide for THIS node's own purposes whether
 * it's safe to eat right now, rather than relying on Legs to report an
 * "arrived"/"safe" signal -- per explicit direction: "flee should be a
 * state of constant reposition... we just need 2 conditions to eat,
 * legs:flee and distance to hostiles more than X... this is something
 * eats decide" (an earlier version had LegsFleeNode publish an
 * arrival/NAV_ARRIVED signal for this to wait on; removed once FLEE
 * itself stopped having any single destination to "arrive" at -- see
 * LegsFleeNode's own docstring). This node reads CombatEngagement.
 * TARGET_ENTITY_ID and Legs' own current state directly rather than
 * LegsFleeNode reaching back into Hands' concerns.
 *
 * Selecting WHICH food to eat is InventoryController.selectFood()'s job
 * (scan + moveToHotbar in one call, see its own docstring for why this
 * node stops doing that inline -- inventory logic consolidated out of
 * Hands per explicit direction) -- this node only holds the real keyUse
 * keybind once a food is selected, and tracks bite completion (below).
 *
 * isBiteStillProtected() exists for HandsStateMachine's own debounce (see
 * its own docstring) -- confirmed live that checking the safe-distance
 * condition continuously (every tick) made EAT flicker in and out
 * constantly while a chasing zombie kept crossing back within
 * EAT_SAFE_DISTANCE, briefly interrupting an eat already in progress over
 * and over rather than ever completing a single bite. An earlier version
 * compared ticksSinceEnter against Item.getUseDuration() directly --
 * confirmed live this was off by roughly a tick (the real completion
 * tick and the computed duration didn't line up exactly, for reasons not
 * fully pinned down -- possibly which tick the server's own consume
 * actually lands on vs. when the client-side use timer reads as expired).
 * Rather than fight that by fudging the comparison, this tracks the
 * REAL, DIRECTLY OBSERVABLE effect of a completed bite instead: the
 * total count of whichever Item is currently being eaten (across BOTH
 * the offhand and the main inventory -- per explicit direction, slot is
 * meaningless to the actual goal here; only the total on-hand count of
 * that item type matters) actually dropping. Vanilla's own Consumable.
 * onConsume() shrinks the eaten stack by exactly 1 the instant a bite
 * finishes, so watching the item's own total count is a ground-truth
 * signal, not a computed one that can drift by a tick. Protection lifts
 * (isBiteStillProtected() goes false) the moment the tracked item's total
 * count drops below what it was at bite-start. Kept as this node's own
 * private state (see the fields below), not published to the Blackboard
 * -- this is internal lifecycle bookkeeping for one node's own decision,
 * not a cross-SM fact anything else needs to read, so HandsStateMachine
 * just calls isBiteStillProtected() directly on the same node instance it
 * already holds a reference to, the same way it already calls the static
 * lowHealth()/hasFood() helpers below.
 *
 * NEEDS_HEAL is still published (moved here from the deleted
 * GeneralSelfHealNode, same key/meaning: true for every tick this node
 * is genuinely active and trying to eat) -- purely informational now
 * (surfaced on StatusHud), NOT read by Legs:FLEE -- Legs reacts to
 * lowHealth() directly instead (see LegsStateMachine's own docstring for
 * why reacting to NEEDS_HEAL specifically would have deadlocked: FLEE
 * gating on "Hands already eating" while Hands:EAT's own entry now gates
 * on FLEE being active AND far enough from the threat).
 *
 * IMPORTANT gating subtlety (found via decompiled Player/Consumable
 * source): eating is gated on HUNGER, not health -- Player.canEat(canAlwaysEat)
 * == invulnerable || canAlwaysEat || foodData.needsFood() (foodLevel < 20).
 * A bot at critical health with a still-full hunger bar can't eat normal
 * food at all; only canAlwaysEat food (golden apple/carrot) bypasses that.
 *
 * HOW THIS ACTUALLY TRIGGERS EATING (found only after an extensive live
 * investigation -- see FINDINGS.md for the full story): calling
 * MultiPlayerGameMode.useItem() directly, the way DoorOpener does for its
 * instant block interaction, does NOT work for eating specifically, even
 * though every vanilla mechanism involved (canEat gating, hotbar
 * selection, call rate, the client/server synced "using item" flag) was
 * individually confirmed correct via bytecode. A live test proved the
 * actual difference: a human manually holding right-click on the SAME
 * client/account/item ate normally, while the mod's direct useItem() call
 * on the identical setup never completed a single eat. So instead of
 * calling useItem() at all, this holds the real `keyUse` keybind down
 * (Options.keyUse.setDown(true)) -- vanilla's own per-tick
 * Minecraft.handleKeybinds() then drives the interaction exactly as it
 * would for a human physically holding the button, which is confirmed to
 * work where the programmatic call didn't. Only ever holds it while THIS
 * node is active (released in onExit).
 */
public final class HandsEatNode implements StateNode<HandsState> {
    // Matches the deleted GeneralSelfHealNode's own LOW_HEALTH_FRACTION
    // default, itself matching the original FoodEater's default.
    private static final float LOW_HEALTH_FRACTION = 0.80f;

    /** True for every tick this node is genuinely active (trying to eat) -- purely informational (surfaced on StatusHud); NOT read by Legs:FLEE (see this class's own docstring for why). Always false/absent once this state exits (see onExit). */
    public static final BlackboardKey<Boolean> NEEDS_HEAL = new BlackboardKey<>("NEEDS_HEAL");

    // Internal debounce bookkeeping -- see this class's own docstring for
    // why this tracks a real, observable "did the bite actually finish"
    // signal (the eaten Item's own TOTAL on-hand count dropping) rather
    // than a computed tick count, and why it stays a private field rather
    // than a published Blackboard fact. eatingItem is WHICH Item the
    // current bite is being eaten from (identity, not a specific stack/
    // slot); countAtBiteStart is that item's own total count (offhand +
    // main inventory) at the moment THIS bite started. eatingItem is null
    // whenever nothing is currently committed to (before the first real
    // tick, or once a bite's completion has already been observed) --
    // isBiteStillProtected() only ever reports true while it's non-null.
    private Item eatingItem;
    private int countAtBiteStart;

    @Override
    public void onEnter(final TickContext ctx, final HandsState previousState) {
        eatingItem = null;
        ctx.blackboard.put(NEEDS_HEAL, true);
    }

    @Override
    public void onTick(final TickContext ctx) {
        ctx.blackboard.put(NEEDS_HEAL, true);

        if (eatingItem != null && totalCount(ctx, eatingItem) < countAtBiteStart) {
            // A real completed bite -- vanilla's own Consumable.
            // onConsume() shrinks the eaten item's total count by exactly
            // 1 the instant it finishes.
            eatingItem = null;
        }

        Item selected = InventoryController.selectFood(ctx.player);
        if (selected == null) {
            // Nothing eatable right now (defensive only -- HandsStateMachine's
            // own entry/exit edges already gate this state on hasFood(),
            // so this shouldn't normally be reachable, but don't hold a
            // stale keyUse if it ever is).
            eatingItem = null;
            releaseUseKey();
            return;
        }
        if (eatingItem == null) {
            commitToBite(ctx, selected);
        }
        holdUseKey();
    }

    @Override
    public void onExit(final TickContext ctx) {
        releaseUseKey();
        ctx.blackboard.put(NEEDS_HEAL, false);
        eatingItem = null;
    }

    /** True while a bite is genuinely still in progress (hasn't yet been observed to complete) -- see this class's own docstring for why HandsStateMachine calls this directly (not via the Blackboard) to debounce its own "too close to eat" exit check. False before any tick has actually committed to a real item, and false again the instant its total count is observed to drop. */
    boolean isBiteStillProtected() {
        return eatingItem != null;
    }

    /** Commits to tracking THIS bite -- called once per bite, the first tick after the previous one (if any) completed or this node was freshly entered. */
    private void commitToBite(final TickContext ctx, final Item item) {
        eatingItem = item;
        countAtBiteStart = totalCount(ctx, item);
    }

    /** `item`'s total count across the offhand AND the whole main inventory -- what actually matters for "did a bite complete" (see this class's own docstring for why slot/specific-stack tracking isn't used: moveToHotbar or ordinary inventory shuffling could otherwise be mistaken for a completed bite, or a completed bite on a stack that then got reordered could be missed). */
    private static int totalCount(final TickContext ctx, final Item item) {
        int total = 0;
        ItemStack offhand = ctx.player.getOffhandItem();
        if (offhand.getItem() == item) {
            total += offhand.getCount();
        }
        Inventory inventory = ctx.player.getInventory();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * Holds the real `keyUse` keybind down -- Minecraft.handleKeybinds()
     * (called every client tick regardless of this mod) reads this every
     * tick on its own and drives the actual eat interaction from it,
     * confirmed to work where a direct useItem() call didn't. Idempotent
     * to call every tick while eating should continue.
     */
    private static void holdUseKey() {
        Minecraft.getInstance().options.keyUse.setDown(true);
    }

    private static void releaseUseKey() {
        Minecraft.getInstance().options.keyUse.setDown(false);
    }

    /** True if health has dropped low enough to consider eating at all -- shared by HandsStateMachine's own entry edge, and by LegsStateMachine's own FLEE trigger (see LegsFleeNode's own docstring for why Legs reacts to this SAME fact directly, rather than to whether Hands has actually started eating yet). Public (not package-private) specifically for that cross-package Legs use. */
    public static boolean lowHealth(final TickContext ctx) {
        return ctx.player.getHealth() <= ctx.player.getMaxHealth() * LOW_HEALTH_FRACTION;
    }
}
