package minebot.mod;

import net.minecraft.core.Holder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;

/**
 * Decides what to fight with -- a real bow (plus arrows to shoot) if
 * carried, otherwise the best melee weapon by real attack damage,
 * otherwise nothing (bare hands). Pure decision logic, no inventory
 * mutation of its own -- HandsMeleeAttackNode is what actually calls
 * InventoryActions.moveToHotbar to act on the Choice this returns, same
 * "PlayerIntention/a node decides, InventoryActions/a real interaction acts"
 * split every other Hands node already follows (see HandsEatNode).
 *
 * Restored (see git history for the original, deleted during the mass
 * command-removal) for PlayerIntention:KILL/PlayerIntention:DEFEND/Hands:MELEE_ATTACK.
 * Kind exists so HandsDrawBowNode can tell "should I be holding to fire
 * at range" apart from "just melee-swing with whatever's selected" -- but
 * HandsMeleeAttackNode itself acts on EITHER kind for its own melee
 * swing (a bow melee-swings too, just weakly, matching real vanilla
 * behavior -- see its own docstring for the live bug an earlier version
 * had treating Kind.BOW as "nothing to act on", which left a bow-
 * carrying bot never attacking at all).
 *
 * Real bow DRAWING (holding to fire an arrow at range -- see the deleted
 * BowShooter's own docstring in git history for the 4 layered root
 * causes solved to make it fire) is separate, not-yet-wired-up mechanics
 * -- per explicit "melee first, bow later" sequencing -- but that's a
 * missing SECOND way to use a bow, not a reason to skip melee-swinging
 * with it in the meantime.
 *
 * Melee scoring mirrors BlockBreaker.maybeSwitchToBestTool's shape for
 * mining tools, but reads real attack-damage data instead of destroy
 * speed: modern vanilla (confirmed via decompiled ItemStack/
 * AttributeModifiers source) has no per-item "damage" field on the Item
 * class itself -- weapon damage is entirely data-driven through
 * ItemAttributeModifiers, the same "Tool component instead of a
 * PickaxeItem subclass" pattern BlockBreaker's own docstring already
 * found for mining tools. forEachModifier(EquipmentSlot.MAINHAND, ...)
 * sums whatever Attributes.ATTACK_DAMAGE modifiers a stack actually
 * carries, which is the real total bonus a real player would deal
 * holding that item.
 */
public final class WeaponSelector {
    private WeaponSelector() {
    }

    public enum Kind { BOW, MELEE }

    public record Choice(Kind kind, int slot) {
    }

    /**
     * Returns the slot to fight with and what kind of weapon it is, or
     * null if there's genuinely nothing worth switching to (already
     * holding the best option, or nothing beats bare hands and bare
     * hands are already selected).
     */
    public static Choice choose(final Player player) {
        Inventory inventory = player.getInventory();

        int bowSlot = findBowWithAmmo(player, inventory);
        if (bowSlot >= 0) {
            return new Choice(Kind.BOW, bowSlot);
        }

        int meleeSlot = findBestMeleeWeapon(inventory);
        return meleeSlot >= 0 ? new Choice(Kind.MELEE, meleeSlot) : null;
    }

    /**
     * A bow is only a real choice if there's also ammo for it --
     * Player.getProjectile(weapon) is the same real lookup vanilla's own
     * bow-use logic uses to find matching ammo (see BowItem.releaseUsing's
     * decompiled source), so this asks the exact same question a real
     * draw attempt would.
     */
    private static int findBowWithAmmo(final Player player, final Inventory inventory) {
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof BowItem)) {
                continue;
            }
            if (!player.getProjectile(stack).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    private static int findBestMeleeWeapon(final Inventory inventory) {
        int bestSlot = -1;
        double bestDamage = -1.0;

        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            // Bows/ammo themselves are never melee candidates -- a bow
            // with no arrows left (the case that fell through
            // findBowWithAmmo above) shouldn't get punched with either.
            if (stack.isEmpty() || stack.getItem() instanceof BowItem || stack.getItem() instanceof ArrowItem) {
                continue;
            }
            double damage = attackDamageOf(stack);
            if (damage > bestDamage) {
                bestDamage = damage;
                bestSlot = slot;
            }
        }
        return bestSlot;
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
}
