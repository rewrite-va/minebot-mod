package minebot.mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Everything inventory-related in one place -- atomic (single-tick) real-
 * interaction actions (moveToHotbar/equip/moveToMainStorage/drop, from the
 * deleted InventoryActions), read-only selection queries (chooseWeapon/
 * hasFood, from the deleted WeaponSelector), a couple of "select AND act"
 * conveniences that bundle a query with the moveToHotbar call needed to
 * actually wield the result (selectFood -- see its own docstring), and
 * the always-on per-tick auto-equip-armor maintenance (from the deleted
 * AutoEquipArmor). Merged per explicit direction: inventory logic was
 * scattered across Hands (EAT/MELEE_ATTACK/DRAW_BOW each independently
 * calling InventoryActions/WeaponSelector), Legs (BlockBreaker's own
 * tool-switching, entirely outside Hands), and two classes that bypassed
 * every peer SM entirely (AutoEquipArmor, InventoryReporter) -- confirmed
 * via survey that inventory was never actually "Hands' concern" to begin
 * with, so consolidating it into one shared utility every axis calls into
 * (the same way every axis already reaches Blackboard/TickContext) is
 * more honest about its actual shape than leaving it split three ways.
 *
 * Fully static (no instance, no constructor state) -- every method takes
 * the LocalPlayer/Player it acts on directly, same convention
 * InventoryActions/WeaponSelector already had; there is nothing here that
 * needs to persist between calls (tick()'s own armor comparison re-reads
 * real equipped/carried state fresh every call, same as the old
 * AutoEquipArmor.tick() did).
 *
 * tick(player) is still called directly from MinebotMod every client
 * tick, unconditionally, independent of every peer StateMachine -- NOT
 * folded into any SM's own edge table, per the same reasoning
 * AutoEquipArmor's own (now superseded) docstring gave: a single-tick
 * container click has no multi-tick behavior to coordinate with Legs/
 * Head/PlayerIntention/Hands, so it doesn't need a state competing for a
 * slot in any of their graphs.
 *
 * Real-interaction actions never do a direct ItemStack/Inventory mutation
 * -- everything goes through the same paths a human client uses
 * (container clicks / the Q-drop action / a real hotbar-select /
 * LocalPlayer.canEat + the real keyUse-hold eat mechanism), matching
 * DoorOpener/the old FoodEater's "no protocol reimplementation" rule (see
 * moveToHotbar's own docstring for the live desync bug a local-only
 * mutation caused, confirmed via an extensive human-driven A/B test).
 */
public final class InventoryController {
    private InventoryController() {
    }

    // SPEAR is its own Kind (not lumped into MELEE) because it carries a
    // real per-item DataComponents.ATTACK_RANGE with a nonzero minReach --
    // see WeaponCandidate/collectMeleeCandidates below for why every
    // consumer that cares about engagement distance (CombatEngagement's
    // kiting, HandsStateMachine's inMeleeAttackRange) needs to know "this
    // has a minimum standoff distance", not just a maximum one, which a
    // plain MELEE tag can't express.
    public enum Kind { BOW, CROSSBOW, MELEE, SPEAR }

    public record Choice(Kind kind, int slot) {
    }

    private static final EquipmentSlot[] ARMOR_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };


    // ---- Atomic actions (from the deleted InventoryActions) ----

    /**
     * Moves the item in `slot` into hotbar slot `hotbarSlot` (0-8),
     * selecting it as the active hotbar slot in the process -- same
     * mechanism as pressing a number key while holding a main-inventory
     * item's matching item, generalized to a specific target slot instead
     * of "whatever's currently selected". A no-op if `slot` is already
     * hotbar slot `hotbarSlot`.
     *
     * Two real client-parity paths, mirroring exactly how AutoTools (a
     * real, working, third-party auto-tool-switch mod -- read directly to
     * find this gap, see FINDINGS.md) implements the identical operation
     * in its own `selectItem`:
     * - `slot` already in the hotbar (0-8): no swap needed at all, the
     *   item's already reachable -- just a real `setSelectedSlot`, same
     *   as pressing that number key (`Minecraft.handleKeybinds`'s own
     *   hotbar-select branch does exactly this one call).
     * - `slot` in main storage (9-35): a real container click
     *   (`MultiPlayerGameMode.handleContainerInput`,
     *   `ContainerInput.SWAP`) -- NOT a direct `Inventory.setItem`
     *   mutation. This used to do the swap locally, which does update
     *   this client's own `Inventory` correctly but is not itself
     *   packet-synced (relying entirely on `ensureHasSentCarriedItem`'s
     *   own later, separate, best-effort sync of just the *selected
     *   index* -- never the actual slot *contents* swap). Confirmed live
     *   via an extensive human-driven A/B test (see FINDINGS.md's "the
     *   real cause was InventoryActions.moveToHotbar's local-only swap
     *   genuinely desyncing the server's view of the held item" section):
     *   even a real physical keypress on the bot's own client left the
     *   server (and so every other client, including a human observer)
     *   disagreeing about which item the bot was holding, reproducibly,
     *   in both directions -- and it stopped the moment the swap went
     *   through a real container-click-synced action instead.
     *   `handleContainerInput` both predicts the change locally AND sends
     *   the real `ServerboundContainerClickPacket` reporting exactly
     *   which slots changed (confirmed via decompiled source), which is
     *   the piece the old local-only mutation always skipped.
     *
     * `SWAP`'s own real slot-numbering split (confirmed via decompiled
     * `AbstractContainerMenu.clicked`): the container-menu `slotNum`
     * parameter needs the same +36 hotbar-index shift
     * `mainInventorySlotToContainerSlot` already applies elsewhere in
     * this class, but `buttonNum` (which hotbar slot to swap *into*) is
     * a raw 0-8 index into `Inventory` directly, unshifted.
     */
    public static void moveToHotbar(final LocalPlayer player, final int slot, final int hotbarSlot) {
        Inventory inventory = player.getInventory();
        if (hotbarSlot < 0 || hotbarSlot > 8) {
            MinebotMod.LOGGER.warn("move_to_hotbar: hotbar_slot {} out of range 0-8", hotbarSlot);
            return;
        }
        if (slot == hotbarSlot) {
            inventory.setSelectedSlot(hotbarSlot);
            return;
        }
        if (Inventory.isHotbarSlot(slot)) {
            // Already in the hotbar somewhere else -- no swap needed,
            // the item's already reachable, just select it directly
            // (the exact same call pressing that number key would make).
            inventory.setSelectedSlot(slot);
            return;
        }
        int containerMenuSlot = mainInventorySlotToContainerSlot(slot);
        Minecraft.getInstance().gameMode.handleContainerInput(
            player.containerMenu.containerId, containerMenuSlot, hotbarSlot, ContainerInput.SWAP, player
        );
        inventory.setSelectedSlot(hotbarSlot);
    }

    /**
     * Equips whatever's in `slot` into its matching armor/offhand slot via
     * a real container click (InventoryMenu.quickMoveStack routes armor
     * items to the correct empty armor slot automatically), the same
     * shift-click-to-equip a human would use -- sends an actual
     * ServerboundContainerClickPacket, unlike moveToHotbar's local-state
     * swap above. No-op (server will simply reject/ignore) if the item
     * isn't equippable.
     */
    public static void equip(final LocalPlayer player, final int slot) {
        int containerMenuSlot = mainInventorySlotToContainerSlot(slot);
        Minecraft.getInstance().gameMode.handleContainerInput(
            player.containerMenu.containerId, containerMenuSlot, 0, ContainerInput.QUICK_MOVE, player
        );
    }

    /**
     * Shift-click-moves whatever's in hotbar slot `hotbarSlot` into main
     * storage (InventoryMenu.quickMoveStack shifts a hotbar item into the
     * first available main-storage slot, the same as a human
     * shift-clicking it there) -- a real ServerboundContainerClickPacket,
     * same as equip above, unlike moveToHotbar's own local-only swap.
     */
    public static void moveToMainStorage(final LocalPlayer player, final int hotbarSlot) {
        int containerMenuSlot = mainInventorySlotToContainerSlot(hotbarSlot);
        Minecraft.getInstance().gameMode.handleContainerInput(
            player.containerMenu.containerId, containerMenuSlot, 0, ContainerInput.QUICK_MOVE, player
        );
    }

    /**
     * Drops up to `count` of `slot`'s contents (capped at however many are
     * actually there): the currently-selected hotbar slot goes through
     * LocalPlayer.drop (the real Q/Ctrl+Q action, sends
     * ServerboundPlayerActionPacket), any other slot goes through a
     * container-click THROW (the real click-and-drop-from-inventory-screen
     * action, sends ServerboundContainerClickPacket) -- both are real
     * client-parity paths, neither is a direct inventory mutation.
     *
     * Real drop actions only support "drop one" or "drop the whole
     * stack", not an arbitrary count in a single action -- `count >=`
     * the stack size drops the whole stack in one shot (matching
     * Ctrl+Q), anything smaller drops one at a time in a loop, same as
     * repeatedly pressing plain Q.
     */
    public static void drop(final LocalPlayer player, final int slot, final int count) {
        ItemStack stack = player.getInventory().getItem(slot);
        if (stack.isEmpty() || count <= 0) {
            return;
        }
        int toDrop = Math.min(count, stack.getCount());
        boolean isSelectedHotbarSlot = slot == player.getInventory().getSelectedSlot();

        if (toDrop >= stack.getCount()) {
            if (isSelectedHotbarSlot) {
                player.drop(true);
            } else {
                Minecraft.getInstance().gameMode.handleContainerInput(
                    player.containerMenu.containerId, mainInventorySlotToContainerSlot(slot), 1, ContainerInput.THROW, player
                );
            }
            return;
        }

        for (int i = 0; i < toDrop; i++) {
            if (isSelectedHotbarSlot) {
                player.drop(false);
            } else {
                Minecraft.getInstance().gameMode.handleContainerInput(
                    player.containerMenu.containerId, mainInventorySlotToContainerSlot(slot), 0, ContainerInput.THROW, player
                );
            }
        }
    }

    /**
     * InventoryMenu (the always-live containerMenu even with no screen
     * open) numbers slots differently from Inventory itself. Confirmed by
     * disassembling AbstractContainerMenu.addStandardInventorySlots (the
     * method InventoryMenu's constructor uses to build its slot list):
     * it calls addInventoryExtendedSlots first (which wraps Inventory's
     * own indices 9-35, the 27 main-storage slots, unchanged -- landing
     * at InventoryMenu slots 9-35 too, i.e. no shift), then
     * addInventoryHotbarSlots (which wraps Inventory's indices 0-8, the
     * hotbar -- landing at InventoryMenu slots 36-44, a +36 shift, right
     * after the 27 main-storage slots already added). So: hotbar slots
     * need +36, main-storage slots need +0 -- NOT a uniform +9 as an
     * earlier version of this method assumed (that would have clicked
     * the wrong slot for every single call). Armor/offhand (Inventory
     * slots 36-42) aren't addressed this way -- equip's whole point is
     * that the destination armor slot is picked automatically by
     * quickMoveStack, and drop doesn't currently support dropping
     * directly from an equipped slot.
     */
    private static int mainInventorySlotToContainerSlot(final int inventorySlot) {
        return Inventory.isHotbarSlot(inventorySlot) ? inventorySlot + 36 : inventorySlot;
    }

    // ---- Weapon selection (from the deleted WeaponSelector) ----

    // Real melee reach (Attributes.ENTITY_INTERACTION_RANGE's own vanilla
    // default, confirmed via decompiled Attributes source -- same value
    // CombatEngagement falls back on before a live player attribute read
    // is available). Used here only as the melee candidate's max-range
    // cutoff -- see findBestWeapon's own docstring for why every
    // candidate needs one.
    private static final double DEFAULT_MELEE_RANGE = 3.0;
    // BowItem.DEFAULT_RANGE == CrossbowItem.DEFAULT_RANGE (confirmed via
    // decompiled source, same constant CombatEngagement's own BOW_RANGE
    // mirrors) -- the real distance vanilla itself considers either
    // ranged weapon's effective range, used here as the max-range cutoff
    // for both Kind.BOW and Kind.CROSSBOW candidates.
    private static final double BOW_RANGE = 15.0;

    /**
     * One fightable candidate: a real slot, its Kind, how much damage it
     * deals, and the range band it can actually reach a target from.
     * minRange is 0 for every Kind except SPEAR (see collectMeleeCandidates
     * for why only spears carry a nonzero minReach) -- kept on every
     * candidate uniformly rather than only on SPEAR ones so findBestWeapon's
     * own range filter below doesn't need a Kind-specific branch. Purely a
     * scoring intermediate for findBestWeapon below -- never escapes this
     * class.
     */
    private record WeaponCandidate(Kind kind, int slot, double damage, double minRange, double maxRange) {
    }

    /**
     * Returns the slot to fight with and what kind of weapon it is, given
     * the current real distance to the target, or null if there's
     * genuinely nothing worth switching to (nothing carried can even
     * reach that far, or nothing beats bare hands). Pure query, no
     * mutation -- findXxx methods only ever search/compare, never call
     * moveToHotbar (see selectWeapon below for the paired "find, then
     * act" convenience); CombatEngagement calls this directly (not
     * selectWeapon) since it only needs the Choice itself to publish as a
     * shared fact -- the Hands nodes that actually act on it
     * (HandsMeleeAttackNode/HandsDrawBowNode) each call moveToHotbar
     * themselves once they're the one about to swing/draw with it, not
     * before.
     *
     * Every carried weapon (bow-with-ammo, crossbow-with-ammo, plus every
     * melee weapon) is scored as a WeaponCandidate: real attack damage
     * (attackDamageOf, same ItemAttributeModifiers read as before) and a
     * real max range (BOW_RANGE for either ranged weapon,
     * DEFAULT_MELEE_RANGE for melee -- a fixed per-Kind cutoff rather
     * than a per-item one, since reach is a ranged-vs-melee property, not
     * something individual melee weapons vary by). Candidates that can't
     * actually reach `distanceToTarget` at all are dropped first (a bow
     * with ammo out past melee reach beats a sword that can't land a hit
     * from here, but a sword up close beats a bow that objectively CAN
     * still hit -- see below for why a ranged weapon isn't just always
     * preferred once in range), then the highest-damage survivor wins.
     * This is deliberately NOT "prefer a bow whenever one with ammo is
     * carried" (the old behavior) -- per explicit direction, a target at
     * melee distance should draw whichever weapon actually deals more
     * damage from there, which in vanilla is usually the melee weapon
     * (loosing an arrow at point-blank range is both slower per-hit and
     * often weaker than a good sword/axe).
     *
     * `target` (nullable, for callers with no live entity yet) gates
     * whether ranged candidates are even collected at all -- confirmed
     * via decompiled source that some hostiles actively counter arrows
     * rather than just taking the hit: EnderMan.hurtServer branches on
     * DamageTypeTags.IS_PROJECTILE and teleports (up to 64 attempts)
     * instead of applying the damage, and Breeze.deflection reverses any
     * projectile matching EntityTypeTags.DEFLECTS_PROJECTILES straight
     * back at the shooter. Shooting either one is worse than just not
     * having a bow at all, so isArrowResistant(target) drops ranged
     * candidates entirely rather than merely deprioritizing them.
     */
    public static Choice findBestWeapon(final Player player, final double distanceToTarget) {
        return findBestWeapon(player, distanceToTarget, null);
    }

    public static Choice findBestWeapon(final Player player, final double distanceToTarget, final Entity target) {
        Inventory inventory = player.getInventory();
        List<WeaponCandidate> candidates = new ArrayList<>();

        if (!isArrowResistant(target)) {
            collectRangedCandidates(player, inventory, candidates);
        }
        collectMeleeCandidates(inventory, candidates);

        WeaponCandidate best = null;
        for (WeaponCandidate candidate : candidates) {
            if (candidate.maxRange() < distanceToTarget || distanceToTarget < candidate.minRange()) {
                continue;
            }
            if (best == null || candidate.damage() > best.damage()) {
                best = candidate;
            }
        }
        return best != null ? new Choice(best.kind(), best.slot()) : null;
    }

    /** True for hostiles where shooting an arrow is actively counterproductive (evaded or reversed back at the shooter), not just "unnecessary" -- see findBestWeapon's own docstring for the decompiled-source confirmation. Null target (no live entity yet) is never resistant. */
    private static boolean isArrowResistant(final Entity target) {
        return target instanceof EnderMan || target instanceof Breeze;
    }

    /** findBestWeapon(player, distanceToTarget), then moveToHotbar if it found something -- the paired "find, then act" convenience (see findBestWeapon's own docstring for why callers that only need the Choice itself, like CombatEngagement, call findBestWeapon directly instead). Not currently called anywhere (HandsMeleeAttackNode/HandsDrawBowNode each do their own moveToHotbar against CombatEngagement's already-published Choice, not a freshly re-found one) -- kept for symmetry with selectFood/any future caller that wants selection bundled with the act. */
    public static Choice selectWeapon(final LocalPlayer player, final double distanceToTarget, final Entity target) {
        Choice choice = findBestWeapon(player, distanceToTarget, target);
        if (choice != null) {
            moveToHotbar(player, choice.slot(), 8);
        }
        return choice;
    }

    /**
     * Every carried bow/crossbow that's actually a real choice right now,
     * appended to `out` -- a bow needs ammo to draw at all (Player.
     * getProjectile(weapon) is the same real lookup vanilla's own bow-use
     * logic uses to find matching ammo, see BowItem.releaseUsing's
     * decompiled source, so this asks the exact same question a real draw
     * attempt would), but a crossbow that's ALREADY charged
     * (CrossbowItem.isCharged -- confirmed via decompiled CrossbowItem.
     * use(): a charged crossbow fires immediately on the next real use()
     * call, no ammo check at all) is still a real choice even with zero
     * arrows left to load a NEXT shot with -- the bolt it already loaded
     * doesn't need re-checking. Both share BOW_RANGE (BowItem.DEFAULT_RANGE
     * == CrossbowItem.DEFAULT_RANGE, confirmed via decompiled source, and
     * see this class's own docstring for why "ranged" is a single reach
     * category, not a per-weapon one).
     */
    private static void collectRangedCandidates(final Player player, final Inventory inventory, final List<WeaponCandidate> out) {
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.getItem() instanceof BowItem && !player.getProjectile(stack).isEmpty()) {
                out.add(new WeaponCandidate(Kind.BOW, slot, attackDamageOf(stack), 0.0, BOW_RANGE));
            } else if (stack.getItem() instanceof CrossbowItem
                && (CrossbowItem.isCharged(stack) || !player.getProjectile(stack).isEmpty())) {
                out.add(new WeaponCandidate(Kind.CROSSBOW, slot, attackDamageOf(stack), 0.0, BOW_RANGE));
            }
        }
    }

    /**
     * Every carried melee-capable stack as a WeaponCandidate, appended to
     * `out` -- mirrors maybeSwitchToBestTool's shape (BlockBreaker) for
     * mining tools, but reads real attack-damage data instead of destroy
     * speed: modern vanilla (confirmed via decompiled ItemStack/
     * AttributeModifiers source) has no per-item "damage" field on the
     * Item class itself -- weapon damage is entirely data-driven through
     * ItemAttributeModifiers, the same "Tool component instead of a
     * PickaxeItem subclass" pattern mining tools use.
     *
     * Spears (and anything else carrying a real DataComponents.ATTACK_RANGE
     * component -- confirmed via decompiled Item.java that vanilla 26.1.2
     * spears are plain Items built with Item.Properties.spear(...), not a
     * dedicated SpearItem subclass, so this is the only reliable way to
     * detect one) are scored as Kind.SPEAR with their own real min/max
     * reach instead of the flat DEFAULT_MELEE_RANGE every other melee
     * weapon uses -- confirmed via decompiled AttackRange/Items source
     * that a spear's minReach is nonzero (e.g. iron_spear: 2.0-4.5), so
     * treating it as "just needs to be within 3 blocks" like a sword would
     * both let the bot try to stab point-blank (where a real thrust misses
     * per AttackRange.isInRange's own minReach check) and stop short of a
     * spear's real, longer maxReach.
     */
    private static void collectMeleeCandidates(final Inventory inventory, final List<WeaponCandidate> out) {
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            // Bows/crossbows/ammo themselves are never melee candidates --
            // a bow/crossbow with no arrows left and no bolt already
            // loaded (the case that fell through collectRangedCandidates
            // above) shouldn't get punched with either.
            if (stack.isEmpty() || stack.getItem() instanceof BowItem
                || stack.getItem() instanceof CrossbowItem || stack.getItem() instanceof ArrowItem) {
                continue;
            }
            double damage = attackDamageOf(stack);
            if (damage <= 0) {
                continue;
            }
            AttackRange attackRange = stack.get(DataComponents.ATTACK_RANGE);
            if (attackRange != null) {
                out.add(
                    new WeaponCandidate(
                        Kind.SPEAR,
                        slot,
                        damage,
                        attackRange.minReach(),
                        attackRange.maxReach()
                    )
                );
            } else {
                out.add(new WeaponCandidate(Kind.MELEE, slot, damage, 0.0, DEFAULT_MELEE_RANGE));
            }
        }
    }

    private static double attackDamageOf(final ItemStack stack) {
        double[] total = {0.0};
        stack.forEachModifier(EquipmentSlot.MAINHAND, (Holder<Attribute> attribute, AttributeModifier modifier) -> {
            if (attribute.is(Attributes.ATTACK_DAMAGE)) {
                total[0] += modifier.amount();
            }
        });
        return total[0];
    }

    // ---- Food selection (extracted from the deleted HandsEatNode inline scan) ----

    // Never eaten, full stop -- guaranteed or near-guaranteed harmful
    // status effects with no offsetting benefit worth it for an
    // autonomous bot (unlike a human player who might eat these
    // deliberately for a specific reason, e.g. a pufferfish for the
    // brewing ingredient or a spider eye for a potion). Per explicit
    // direction: "not all food is good... totally prohibit those".
    private static final Set<Item> PROHIBITED_FOOD = Set.of(Items.SPIDER_EYE, Items.PUFFERFISH, Items.POISONOUS_POTATO);

    // Eaten ONLY when nothing else edible is carried (checked via a
    // second findBestFood pass) -- rotten flesh's hunger restored is
    // real and worth it as a genuine last resort against starvation,
    // just not preferred over any other real food. Per explicit
    // direction: "as an exception, lets allow rotten meat as last
    // resource".
    private static final Set<Item> LAST_RESORT_FOOD = Set.of(Items.ROTTEN_FLESH);

    /**
     * True only if starting to eat this stack right now would actually
     * succeed, per the real vanilla gate: Consumable's startConsuming()
     * -> canConsume() -> Player.canEat(canAlwaysEat). Mirroring canEat()
     * itself (rather than re-deriving invulnerable/hunger state by hand)
     * keeps this correct if that logic ever changes. `allowLastResort`
     * gates LAST_RESORT_FOOD specifically -- PROHIBITED_FOOD is excluded
     * unconditionally, regardless of this flag (see this class's own
     * PROHIBITED_FOOD/LAST_RESORT_FOOD docstrings for the why behind
     * each list).
     */
    public static boolean isEatableNow(final Player player, final ItemStack stack, final boolean allowLastResort) {
        if (stack.isEmpty() || stack.get(DataComponents.FOOD) == null) {
            return false;
        }
        Item item = stack.getItem();
        if (PROHIBITED_FOOD.contains(item)) {
            return false;
        }
        if (LAST_RESORT_FOOD.contains(item) && !allowLastResort) {
            return false;
        }
        FoodProperties food = stack.get(DataComponents.FOOD);
        return player.canEat(food.canAlwaysEat());
    }

    /** True if ANY real food (offhand or inventory) would actually be eatable right now -- pure query, same scan selectFood performs, exposed separately for HandsStateMachine/LegsStateMachine's own edges so they never enter/stay in a state that has nothing to act on. Canonical "hunger is full, so there is no way to heal" check per LegsFleeNode's own docstring: canAlwaysEat items (golden apple/carrot) still count as eatable at full hunger, so this is more precise than a raw hunger-level read. Last-resort food counts here (matching findBestFood's own eventual fallback) -- otherwise a bot carrying only rotten flesh would report hasFood() == false and never even try. */
    public static boolean hasFood(final Player player) {
        if (isEatableNow(player, player.getOffhandItem(), true)) {
            return true;
        }
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            if (isEatableNow(player, inventory.getItem(slot), true)) {
                return true;
            }
        }
        return false;
    }

    /** Where the best real-food-to-eat-right-now came from -- `slot == OFFHAND_SLOT` means the offhand item itself (nothing to hotbar-select), otherwise a real Inventory slot index selectFood still needs to moveToHotbar. */
    public record FoodChoice(int slot, Item item) {
    }

    /** Sentinel FoodChoice.slot() value meaning "the offhand item" -- distinct from any real Inventory slot index (always >= 0), so callers never need a separate boolean alongside the slot number. */
    public static final int OFFHAND_SLOT = -1;

    /**
     * Pure query, no mutation (see findBestWeapon's own docstring for the
     * same find-vs-act split) -- which real food would actually be
     * eatable right now, and where it currently is, or null if nothing
     * is. Prefers the offhand if it's edible AND actually eatable right
     * now -- a canAlwaysEat item there beats hunting through the main
     * inventory, and needs no hotbar selection at all.
     *
     * Two full passes, not one: the first excludes LAST_RESORT_FOOD
     * entirely (allowLastResort=false), so any normal food anywhere in
     * the inventory is preferred over rotten flesh even if rotten flesh
     * would've been found first by scan order. Only once that whole pass
     * comes up empty does a second pass (allowLastResort=true) even
     * consider it. PROHIBITED_FOOD is never eligible in either pass (see
     * isEatableNow).
     */
    public static FoodChoice findBestFood(final Player player) {
        FoodChoice preferred = findBestFood(player, false);
        return preferred != null ? preferred : findBestFood(player, true);
    }

    private static FoodChoice findBestFood(final Player player, final boolean allowLastResort) {
        ItemStack offhand = player.getOffhandItem();
        if (isEatableNow(player, offhand, allowLastResort)) {
            return new FoodChoice(OFFHAND_SLOT, offhand.getItem());
        }

        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!isEatableNow(player, stack, allowLastResort)) {
                continue;
            }
            return new FoodChoice(slot, stack.getItem());
        }
        return null;
    }

    /**
     * findBestFood(player), then moveToHotbar if it found something that
     * isn't already the offhand -- "select a food, and then eat" per
     * explicit direction: HandsEatNode calls this once per tick, then
     * just holds the real keyUse keybind. Returns the selected Item (for
     * HandsEatNode's own bite-completion tracking -- see its own
     * docstring), or null if nothing is eatable right now.
     */
    public static Item selectFood(final LocalPlayer player) {
        FoodChoice choice = findBestFood(player);
        if (choice == null) {
            return null;
        }
        if (choice.slot() != OFFHAND_SLOT) {
            moveToHotbar(player, choice.slot(), Inventory.isHotbarSlot(choice.slot()) ? choice.slot() : 8);
        }
        return choice.item();
    }

    // ---- Tool selection (extracted from BlockBreaker's own maybeSwitchToBestTool) ----

    /** Where the best real tool-to-mine-`block`-with came from, and the real stack itself -- mirrors FoodChoice's own shape (slot + item identity), used by HandsMineNode/BlockBreaker the same "find, then act" way selectFood is used for eating. */
    public record ToolChoice(int slot, ItemStack stack) {
    }

    /**
     * Returns the best real tool in the hotbar/main inventory (slots 0-35
     * -- armor/offhand slots 36-42 excluded, same reasoning findBestWeapon
     * already applies: swapping a chestplate into the hotbar to mine with
     * makes no sense) to break `state` with, or null if nothing carried
     * beats what's already selected. Pure query, no mutation -- same
     * find-vs-act split as findBestWeapon/findBestFood (see their own
     * docstrings); selectTool below is the paired convenience that also
     * calls moveToHotbar.
     *
     * Ranks candidates by real ItemStack.getDestroySpeed(state) (the same
     * per-item mining-speed value BlockState.getDestroyProgress itself
     * reads internally, confirmed via decompiled BlockBehaviour source),
     * but -- unlike the version this replaces (BlockBreaker's own
     * maybeSwitchToBestTool, which scored raw speed alone) -- a candidate
     * that would break the block without actually producing its drops
     * (ItemStack.isCorrectToolForDrops(state) false, while the block
     * itself requires the correct tool at all -- BlockState.
     * requiresCorrectToolForDrops(), confirmed via decompiled source
     * these are two entirely independent questions, not implied by each
     * other) is excluded whenever a real correct-tool candidate exists.
     * Reported live: a faster but WRONG tool (e.g. an enchanted sword
     * scoring higher raw speed than a plain pickaxe against some blocks)
     * could win the old raw-speed-only comparison, breaking the block
     * quickly but producing no drop at all -- exactly the same class of
     * bug as mining with bare hands, just less obviously wrong from the
     * log alone (the break visibly "succeeds"). A wrong-tool candidate is
     * only ever considered if NO correct-tool candidate exists at all
     * (better to break it fast with the wrong tool than not break it), and
     * blocks that don't require a correct tool to begin with (state.
     * requiresCorrectToolForDrops() false -- dirt, wood, etc.) are
     * unaffected either way, matching real vanilla exactly.
     *
     * Bare hands (or an empty/no-beats-it selection) is never preferred
     * over holding literally anything -- same -1 starting-baseline
     * reasoning findBestWeapon/the old maybeSwitchToBestTool already
     * established (see their own comments): forcing the baseline down
     * whenever nothing is currently selected means the very first real
     * candidate found always counts as strictly better.
     */
    public static ToolChoice findBestTool(final Player player, final BlockState state) {
        ToolChoice correct = findBestTool(player, state, true);
        return correct != null ? correct : findBestTool(player, state, false);
    }

    private static ToolChoice findBestTool(final Player player, final BlockState state, final boolean requireCorrectTool) {
        boolean mustBeCorrect = requireCorrectTool && state.requiresCorrectToolForDrops();
        Inventory inventory = player.getInventory();
        int selectedSlot = inventory.getSelectedSlot();
        ItemStack selectedStack = inventory.getItem(selectedSlot);
        boolean selectedQualifies = !selectedStack.isEmpty() && (!mustBeCorrect || selectedStack.isCorrectToolForDrops(state));
        int bestSlot = -1;
        ItemStack bestStack = selectedStack;
        float bestSpeed = selectedQualifies ? selectedStack.getDestroySpeed(state) : -1.0f;

        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (mustBeCorrect && !stack.isCorrectToolForDrops(state)) {
                continue;
            }
            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = slot;
                bestStack = stack;
            }
        }

        return bestSlot >= 0 ? new ToolChoice(bestSlot, bestStack) : null;
    }

    /** findBestTool(player, state), then moveToHotbar if it found something -- the paired "find, then act" convenience (see findBestWeapon's own docstring for why some callers want the Choice alone instead). Returns the resulting ToolChoice (null if nothing carried beats what's already selected), same shape selectWeapon returns its Choice. */
    public static ToolChoice selectTool(final LocalPlayer player, final BlockState state) {
        ToolChoice choice = findBestTool(player, state);
        if (choice != null) {
            moveToHotbar(player, choice.slot(), 8);
        }
        return choice;
    }

    // ---- Always-on auto-equip-armor (from the deleted AutoEquipArmor) ----

    /**
     * Equips a stronger piece of armor from the inventory the moment
     * one's carried, for each of the four humanoid armor slots (head/
     * chest/legs/feet) independently -- called unconditionally every
     * client tick from MinebotMod, regardless of any StateMachine's
     * current state (see this class's own docstring for why this stays
     * outside every peer SM's edge table).
     *
     * Deliberately inventory-only, per explicit direction: reacts to
     * armor already carried (picked up incidentally while following/
     * mining/PICKUP_ITEMS' own item recovery), never sends Legs anywhere
     * looking for armor on the ground -- if nothing better is carried,
     * this simply has nothing to do.
     *
     * "Stronger" is real armor-value comparison, not item-tier guessing:
     * the same Attributes.ARMOR (+ ARMOR_TOUGHNESS as a tiebreaker)
     * values the game itself computes for combat damage reduction, read
     * directly off each ItemStack's own DataComponents.ATTRIBUTE_MODIFIERS
     * -- see armorScore()'s own docstring for why this is the correct
     * value to compare rather than e.g. sorting by material name.
     */
    public static void tick(final LocalPlayer player) {
        Inventory inventory = player.getInventory();
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack equipped = player.getItemBySlot(slot);
            double equippedScore = armorScore(equipped, slot);

            int bestSlot = -1;
            double bestScore = equippedScore;
            for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
                ItemStack candidate = inventory.getItem(i);
                if (armorSlotOf(candidate) != slot) {
                    continue;
                }
                double candidateScore = armorScore(candidate, slot);
                if (candidateScore > bestScore) {
                    bestScore = candidateScore;
                    bestSlot = i;
                }
            }

            if (bestSlot != -1) {
                equip(player, bestSlot);
            }
        }
    }

    /** Which humanoid armor slot `stack` equips into, or null if it isn't armor (or is empty) -- DataComponents.EQUIPPABLE is present on every equippable item (tools/weapons included, via MAINHAND/OFFHAND), so this filters down to just the four ARMOR_SLOTS. */
    private static EquipmentSlot armorSlotOf(final ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        if (equippable == null) {
            return null;
        }
        EquipmentSlot slot = equippable.slot();
        for (EquipmentSlot armorSlot : ARMOR_SLOTS) {
            if (armorSlot == slot) {
                return armorSlot;
            }
        }
        return null;
    }

    /**
     * Sums the ARMOR attribute modifier(s) this stack would contribute if
     * worn in `slot`, plus ARMOR_TOUGHNESS scaled down as a tiebreaker
     * (never enough alone to outweigh a real armor-value difference, e.g.
     * leather's 0 toughness vs iron's 0 toughness would otherwise tie on
     * defense alone in some comparisons -- toughness only matters for
     * reducing high-damage hits, so it's a secondary signal, not a
     * primary one). Reading real ItemAttributeModifiers (the exact data
     * ArmorMaterial.createAttributes bakes into each item, confirmed via
     * decompiled source) rather than inferring strength from the item's
     * name/material keeps this correct for anything with the right
     * attributes (enchanted/modded armor included), not just vanilla's
     * own known material list. An empty stack (nothing equipped in this
     * slot) scores 0, same as vanilla's own baseline.
     */
    private static double armorScore(final ItemStack stack, final EquipmentSlot slot) {
        if (stack.isEmpty()) {
            return 0.0;
        }
        ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (modifiers == null) {
            return 0.0;
        }
        double[] armor = {0.0};
        double[] toughness = {0.0};
        modifiers.forEach(slot, (Holder<Attribute> attribute, AttributeModifier modifier) -> {
            if (attribute.is(Attributes.ARMOR)) {
                armor[0] += modifier.amount();
            } else if (attribute.is(Attributes.ARMOR_TOUGHNESS)) {
                toughness[0] += modifier.amount();
            }
        });
        return armor[0] + toughness[0] * 0.1;
    }

    /**
     * Drops up to `quantity` of `item` (its registry id, e.g.
     * "minecraft:diamond" -- same wire shape every other item reference in
     * this mod uses, see ItemDropTracker/InventoryReporter) from wherever
     * it's currently carried, via the same real drop() action used
     * elsewhere in this class -- searches main inventory + hotbar for the
     * first slot holding a matching stack, since a caller asking to give
     * an item by name has no slot number to give. No-op if the item isn't
     * a real registered item, or none is currently carried. `quantity <=
     * 0` means "drop the whole stack found" (mirrors drop()'s own "count
     * >= stack size drops everything" behavior via Integer.MAX_VALUE)
     * rather than dropping nothing, since a caller asking to give an item
     * with no explicit quantity means "give what I have", not "give
     * zero".
     */
    public static void dropItem(final LocalPlayer player, final String item, final int quantity) {
        Item resolvedItem = BuiltInRegistries.ITEM.getOptional(Identifier.parse(item)).orElse(null);
        if (resolvedItem == null) {
            MinebotMod.LOGGER.warn("give: unknown item '{}'", item);
            return;
        }

        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.getItem() == resolvedItem) {
                drop(player, slot, quantity <= 0 ? Integer.MAX_VALUE : quantity);
                return;
            }
        }
        MinebotMod.LOGGER.warn("give: no '{}' carried", item);
    }
}
