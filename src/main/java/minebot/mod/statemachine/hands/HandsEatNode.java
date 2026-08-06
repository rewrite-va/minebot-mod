package minebot.mod.statemachine.hands;

import minebot.mod.InventoryActions;
import minebot.mod.statemachine.BlackboardKey;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
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
 * NEEDS_HEAL is still published (moved here from the deleted
 * GeneralSelfHealNode, same key/meaning: true for every tick this node
 * is genuinely active and trying to eat) -- purely informational now
 * (surfaced on StatusHud), NOT read by Legs:FLEE -- Legs reacts to
 * lowHealth() directly instead (see LegsStateMachine's own docstring for
 * why reacting to NEEDS_HEAL specifically would have deadlocked: FLEE
 * gating on "Hands already eating" while Hands:EAT's own entry now gates
 * on FLEE being active AND far enough from the threat).
 *
 * "Is this food" is a data-component check (DataComponents.FOOD), not the
 * older Item.getFoodProperties() method, which no longer exists in this
 * version -- confirmed via decompiled ItemStack/DataComponentHolder.
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

    @Override
    public void onEnter(final TickContext ctx, final HandsState previousState) {
        ctx.blackboard.put(NEEDS_HEAL, true);
    }

    @Override
    public void onTick(final TickContext ctx) {
        ctx.blackboard.put(NEEDS_HEAL, true);

        // Prefer the offhand if it's edible AND actually eatable right
        // now -- a canAlwaysEat item there beats hunting through the
        // main inventory, and needs no hotbar selection at all.
        if (isEatableNow(ctx, ctx.player.getOffhandItem())) {
            holdUseKey();
            return;
        }

        Inventory inventory = ctx.player.getInventory();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!isEatableNow(ctx, stack)) {
                continue;
            }
            InventoryActions.moveToHotbar(ctx.player, slot, Inventory.isHotbarSlot(slot) ? slot : 8);
            holdUseKey();
            return;
        }

        // Nothing eatable right now (defensive only -- HandsStateMachine's
        // own entry/exit edges already gate this state on hasFood(),
        // so this shouldn't normally be reachable, but don't hold a stale
        // keyUse if it ever is).
        releaseUseKey();
    }

    @Override
    public void onExit(final TickContext ctx) {
        releaseUseKey();
        ctx.blackboard.put(NEEDS_HEAL, false);
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

    private static boolean isEdible(final ItemStack stack) {
        return !stack.isEmpty() && stack.get(DataComponents.FOOD) != null;
    }

    /**
     * True only if starting to eat this stack right now would actually
     * succeed, per the real vanilla gate: Consumable's startConsuming()
     * -> canConsume() -> Player.canEat(canAlwaysEat). Mirroring canEat()
     * itself (rather than re-deriving invulnerable/hunger state by hand)
     * keeps this correct if that logic ever changes. Package-visible (not
     * private) so this class's own hasFood() can reuse the exact same
     * gate for HandsStateMachine's entry/exit edges, rather than
     * re-deriving a second version of it.
     */
    static boolean isEatableNow(final TickContext ctx, final ItemStack stack) {
        if (!isEdible(stack)) {
            return false;
        }
        FoodProperties food = stack.get(DataComponents.FOOD);
        return ctx.player.canEat(food.canAlwaysEat());
    }

    /** True if health has dropped low enough to consider eating at all -- shared by HandsStateMachine's own entry edge, and by LegsStateMachine's own FLEE trigger (see LegsFleeNode's own docstring for why Legs reacts to this SAME fact directly, rather than to whether Hands has actually started eating yet). Public (not package-private) specifically for that cross-package Legs use. */
    public static boolean lowHealth(final TickContext ctx) {
        return ctx.player.getHealth() <= ctx.player.getMaxHealth() * LOW_HEALTH_FRACTION;
    }

    /** True if ANY real food (offhand or inventory) would actually be eatable right now -- same scan onTick performs, exposed for HandsStateMachine's own entry/exit edges so EAT never enters/stays when it has nothing to act on, AND for LegsStateMachine's own FLEE exit edge: "hunger is full, so there is no way to heal" is exactly !hasFood() (canAlwaysEat items like a golden apple still count as eatable at full hunger -- see isEatableNow's own docstring -- so this is a more precise check than a raw hunger-level read would be), per explicit direction that FLEE should give up and let Legs resume combat positioning once eating genuinely can't help. Renamed from canEatSomething (see git history) -- the hunger-gating half of "eatable" is Player.canEat() itself (called directly inside isEatableNow, no wrapper needed for that part); what this method actually adds on top is the inventory/offhand SCAN for whether any such stack exists at all. Public (not package-private) for that same cross-package Legs use lowHealth() already has. */
    public static boolean hasFood(final TickContext ctx) {
        if (isEatableNow(ctx, ctx.player.getOffhandItem())) {
            return true;
        }
        Inventory inventory = ctx.player.getInventory();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            if (isEatableNow(ctx, inventory.getItem(slot))) {
                return true;
            }
        }
        return false;
    }
}
