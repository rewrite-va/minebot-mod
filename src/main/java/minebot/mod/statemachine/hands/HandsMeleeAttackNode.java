package minebot.mod.statemachine.hands;

import minebot.mod.InventoryController;
import minebot.mod.MinebotMod;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import minebot.mod.statemachine.playerintention.CombatEngagement;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;

/**
 * Swings at CombatEngagement's live target (whichever PlayerIntention:KILL/
 * DEFEND fight is currently active) with whatever real melee weapon
 * InventoryController picked -- ported from the old shared tickAttack's melee
 * branch (see git history: MinebotMod.tickAttack's `else` case,
 * `gameMode.attack(player, target)` + `player.swing(...)`). Only active
 * when HandsStateMachine's own entry edge finds SELECTED_WEAPON is NOT a
 * bow -- see HandsStateMachine's own docstring for why MELEE_ATTACK and
 * DRAW_BOW are mutually exclusive, and HandsDrawBowNode for the real
 * bow-drawing/firing mechanics this no longer needs to fall back to
 * (an earlier version of this node melee-swung with a selected bow as a
 * stopgap before real bow drawing existed -- see git history).
 *
 * Waits for a full attack-strength charge before swinging, rather than
 * calling Player.attack() every tick -- confirmed live (and via
 * decompiled Player.attack/getAttackStrengthScale/
 * getCurrentItemAttackStrengthDelay source) that Player.attack() does
 * NOT gate itself on cooldown: it always executes and always resets
 * attackStrengthTicker back to 0 (via onAttack() -> resetOnlyAttack
 * StrengthTicker()), so calling it every tick just means
 * attackStrengthTicker never climbs past 1 before being reset again.
 * getAttackStrengthScale never crosses vanilla's own ">0.9F" full-
 * strength threshold that gates critical hits, the sprint-attack
 * knockback bonus, sweep attacks, and (for enchanted weapons) the full
 * enchantment damage bonus (magicBoost is multiplied by
 * attackStrengthScale directly) -- base weapon damage itself isn't
 * scaled down, but every one of those bonuses is silently lost,
 * matching the live report ("hitting the target every tick... is
 * better to wait a little to fully charge the attack").
 *
 * getCurrentItemAttackStrengthDelay() = 20 / Attributes.ATTACK_SPEED
 * ticks -- the real, per-weapon-dependent number of ticks needed to
 * reach a scale of 1.0 (DEFAULT_ATTACK_SPEED = 4.0 -> 5 ticks for bare
 * hands/anything with no attack-speed modifier of its own; a diamond
 * sword's real attack speed of 1.6 -> 12.5 ticks) -- computed fresh each
 * tick (not hardcoded) since it depends on whatever's currently
 * selected, which can change mid-fight (a weapon breaking, for
 * instance).
 *
 * Never touches yaw/pitch -- Head:AIM_AT_TARGET's exclusive concern (see
 * its own docstring), same split every other Hands node already follows.
 */
public final class HandsMeleeAttackNode implements StateNode<HandsState> {
    @Override
    public void onTick(final TickContext ctx) {
        Integer targetEntityId = ctx.blackboard.get(CombatEngagement.TARGET_ENTITY_ID);
        if (targetEntityId == null) {
            return;
        }
        Entity target = ctx.level.getEntity(targetEntityId);
        if (target == null) {
            return;
        }

        // Read the SAME weapon choice CombatEngagement already computed
        // this tick (see its own docstring for why this isn't
        // independently re-computed here) -- by the time this node is
        // even active, HandsStateMachine's own entry edge has already
        // confirmed it isn't a bow.
        InventoryController.Choice weapon = ctx.blackboard.get(CombatEngagement.SELECTED_WEAPON);
        if (weapon != null) {
            // moveToHotbar is already a no-op if this slot is already
            // selected/already in the hotbar -- safe to call every tick
            // unconditionally, same as HandsEatNode's own food-swap call.
            InventoryController.moveToHotbar(ctx.player, weapon.slot(), 8);
        }
        // weapon == null means nothing beats bare hands -- still a real,
        // if weak, attack (vanilla lets an empty main hand swing too), so
        // fall through to swinging regardless.

        // Only swing once fully charged -- see this class's own
        // docstring for why Player.attack() itself doesn't gate this,
        // and why calling it every tick loses crits/knockback/sweep/full
        // enchantment damage. getAttackStrengthScale(0.0F) reads the
        // ticker BEFORE this tick's own attackStrengthTicker++ (already
        // applied earlier in Player.tick(), which runs before any SM
        // ticks this same client tick -- see MinebotMod's own tick
        // order), so this is comparing against the true current charge,
        // not a stale one.
        if (ctx.player.getAttackStrengthScale(0.0f) < 1.0f) {
            return;
        }

        swing(ctx, target);
    }

    private static void swing(final TickContext ctx, final Entity target) {
        Minecraft.getInstance().gameMode.attack(ctx.player, target);
        ctx.player.swing(InteractionHand.MAIN_HAND);
        MinebotMod.LOGGER.info(
            "hands: melee swing at entity {} ({}) -- attackStrengthDelay={}",
            target.getId(), target.getType(), ctx.player.getCurrentItemAttackStrengthDelay()
        );
    }
}
