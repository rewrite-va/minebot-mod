package minebot.mod;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.Equippable;

/**
 * Equips a stronger piece of armor from the inventory the moment one's
 * carried, for each of the four humanoid armor slots (head/chest/legs/
 * feet) independently -- always-on, ticked every client tick regardless
 * of any StateMachine's current state, the same shape ItemDropTracker/
 * InventoryReporter already have (see their own docstrings): this is a
 * single-tick, instant action (one real container click via
 * InventoryActions.equip, no walking/multi-tick behavior of its own), so
 * it doesn't need a PlayerIntention/Hands state competing for a slot in either
 * SM's graph -- confirmed via explicit direction not to give this its
 * own state, matching how FoodEater/RespawnHandler both ran
 * unconditionally every tick before the SM refactor.
 *
 * Deliberately inventory-only, per explicit direction: reacts to armor
 * already carried (picked up incidentally while following/mining/
 * PICKUP_ITEMS' own item recovery), never sends Legs anywhere looking
 * for armor on the ground -- if nothing better is carried, this simply
 * has nothing to do.
 *
 * "Stronger" is real armor-value comparison, not item-tier guessing: the
 * same Attributes.ARMOR (+ ARMOR_TOUGHNESS as a tiebreaker) values the
 * game itself computes for combat damage reduction, read directly off
 * each ItemStack's own DataComponents.ATTRIBUTE_MODIFIERS -- see
 * armorScore()'s own docstring for why this is the correct value to
 * compare rather than e.g. sorting by material name.
 */
public final class AutoEquipArmor {
    private static final EquipmentSlot[] ARMOR_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    /** Safe to call every tick. */
    public void tick(final LocalPlayer player) {
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
                InventoryActions.equip(player, bestSlot);
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
     * (never enough alone to outweigh a real armor-value difference,
     * e.g. leather's 0 toughness vs iron's 0 toughness would otherwise
     * tie on defense alone in some comparisons -- toughness only matters
     * for reducing high-damage hits, so it's a secondary signal, not a
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
}
