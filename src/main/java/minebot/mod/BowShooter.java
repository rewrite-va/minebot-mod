package minebot.mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;

/**
 * Draws and fires a real bow at a target via a direct call to
 * MultiPlayerGameMode.useItem/releaseUsingItem, not the keyUse-keybind-
 * hold mechanism FoodEater/BlockBreaker use for eating/mining.
 *
 * That keybind-hold approach was tried first here too (matching the
 * established pattern), but confirmed live to fail for a real,
 * understood reason specific to this case: Minecraft.handleKeybinds'
 * own keyUse handling first checks the real Minecraft.hitResult, and
 * when it's an EntityHitResult (which it always was here -- the bot is
 * deliberately aimed straight at its combat target, see
 * MinebotMod.aimAtEntity/this class's own aimAt), the interaction is
 * routed to MultiPlayerGameMode.interact(player, entity, hit, hand)
 * instead of MultiPlayerGameMode.useItem(player, hand) -- and
 * right-clicking a hostile mob has no special interaction, so the draw
 * never actually starts (confirmed live via added diagnostics: keyUse
 * held down every tick, nothing else blocking, yet isUsingItem() stayed
 * false and getTicksUsingItem() stuck at 0 indefinitely). Neither
 * FoodEater (never aimed at an entity) nor BlockBreaker (aimed at a
 * block, a different HitResult.Type entirely) ever exercised this
 * branch, so this is a materially different failure from FoodEater's
 * own "direct API call doesn't work" finding, not a re-run of it --
 * calling useItem() directly here sidesteps the hitResult-based
 * branching entirely, which is exactly the problem.
 *
 * Draw timing is tracked locally (our own tick counter), not via
 * LocalPlayer.isUsingItem()/getTicksUsingItem() -- confirmed via
 * decompiled LivingEntity.startUsingItem/stopUsingItem that the
 * DATA_LIVING_ENTITY_FLAGS bit those methods read is only ever set
 * locally when `!level().isClientSide()`, i.e. never by the local
 * player's own client-side prediction; it only becomes true once the
 * server independently processes our ServerboundUseItemPacket and
 * syncs the flag back down. Confirmed live this round trip is far too
 * unreliable to drive real draw timing on: isUsingItem() would confirm
 * true for exactly one tick after useItem() and then permanently
 * revert false for the rest of the attempt, every single attempt, with
 * total consistency (not a flaky/occasional drop). Inspecting a known
 * -working reference mod (MC_Instant-Shoot_Mod, a BowItem.use() mixin
 * that calls BowItem.releaseUsing(stack, level, player, 72000 - 20)
 * directly for an instant full-power shot) confirmed the real
 * server-side shot doesn't actually depend on the client ever
 * observing isUsingItem() go true at all -- the server runs its own
 * independent copy of the draw/release state machine from the same
 * ServerboundUseItemPacket/ServerboundPlayerActionPacket(RELEASE_USE_ITEM)
 * pair MultiPlayerGameMode.useItem()/releaseUsingItem() already send;
 * the synced flag on our own client is just a display-ish echo of that,
 * not a precondition for it. So: send useItem() once, count our own
 * FULL_DRAW_TICKS locally, then call releaseUsingItem() -- the real
 * client/server exchange those two calls trigger is what fires the
 * shot, regardless of what isUsingItem() ever reports back to us.
 *
 * Firing itself calls BowItem.releaseUsing(stack, level, player,
 * timeLeft) directly rather than MultiPlayerGameMode.releaseUsingItem(),
 * for the same class of reason: LivingEntity.releaseUsingItem() (which
 * MultiPlayerGameMode.releaseUsingItem() calls locally after sending the
 * network release packet) computes its own timeLeft from
 * getUseItemRemainingTicks() -- a field only ever decremented inside
 * LivingEntity.updateUsingItem(), which is only called from
 * LivingEntity.tick()'s own "am I using an item" branch when
 * isUsingItem() is true. Since isUsingItem() is essentially never true
 * on this client (see above), that decrement never ran locally, so
 * useItemRemaining sat at its untouched initial value (BowItem.
 * getUseDuration() = 72000) the whole draw -- making the computed
 * ticksUsed (getUseDuration - timeLeft) come out near zero and
 * BowItem.releaseUsing's own power = getPowerForTime(ticksUsed) fall
 * under its 0.1 minimum, so it silently returned false and fired
 * nothing every time (confirmed live: the draw/release cycle completed
 * cleanly on a steady ~1s timer with zero stalling after the isUsingItem
 * -polling fix above, but target health and carried-arrow count never
 * moved even once across 15+ consecutive "full draw reached, releasing"
 * cycles). Passing BowItem.getUseDuration() - ticksSinceDrawStarted
 * directly as timeLeft (the same trick MC_Instant-Shoot_Mod's mixin
 * uses with a hardcoded 72000 - 20 for its instant full-power shot)
 * sidesteps this local-decrement dependency entirely -- BowItem.
 * releaseUsing still needs the player.getProjectile(stack) check to
 * pass (real ammo) and still only actually spawns the arrow when
 * `level` is a ServerLevel, but the *client* call here isn't what
 * spawns the arrow anyway -- MultiPlayerGameMode.releaseUsingItem()'s
 * own ServerboundPlayerActionPacket(RELEASE_USE_ITEM) send (kept
 * below) is what tells the real server to run its own independent
 * releaseUsing with its own correctly-ticked-down remaining-use time,
 * which is what actually fires the arrow. This client-side call exists
 * only to mirror vanilla's local player.releaseUsingItem() clearing
 * useItem/useItemRemaining/stopUsingItem's local echo, using a power
 * value that won't fall under the 0.1 floor and silently no-op the
 * local half of it.
 *
 * Aim direction is ported directly from AbstractSkeleton.performRangedAttack
 * (confirmed via decompiled 26.1.2 source) rather than solving real
 * projectile ballistics from scratch: vanilla's own ranged-mob AI doesn't
 * compute a special arc pitch either -- it aims the raw vector to the
 * target with a fixed, distance-proportional lift added to the
 * direction's own Y component (more lift the farther away, compensating
 * for the longer flight time gravity has to act over), not a solved
 * launch angle. Reusing the same real, already-tuned formula the game's
 * own ranged mobs use is both simpler and more trustworthy than
 * re-deriving an equivalent one by hand.
 */
public final class BowShooter {
    // AbstractSkeleton.performRangedAttack's own constant (decompiled:
    // aimed direction's Y component is dy + horizontalDistance * 0.2).
    private static final double ARC_LIFT_PER_BLOCK = 0.2;

    // BowItem.MAX_DRAW_DURATION -- holding this long reaches
    // getPowerForTime's max (1.0), a full-strength/full-accuracy shot,
    // matching the explicit ask to always fully draw rather than firing
    // faster, weaker partial-draw shots. Counted locally now (see class
    // docstring), not read back from the server-synced
    // getTicksUsingItem().
    private static final int FULL_DRAW_TICKS = 20;

    private boolean drawing;
    private int ticksSinceDrawStarted;

    /**
     * Aims at `target`, starts (or continues) the draw, and releases
     * once fully drawn -- call every tick a bow shot should be in
     * progress. Returns true once a shot has actually been released
     * this tick (caller can use this to decide when to allow movement/
     * target reassignment again), false while still drawing.
     */
    public boolean tick(final LocalPlayer player, final Entity target) {
        if (!(player.getMainHandItem().getItem() instanceof BowItem)) {
            // Something else took over the main hand mid-draw (a tool
            // switch elsewhere, !give, ...) -- abandon this shot cleanly
            // rather than leaving a real server-side "using item" state
            // stuck on whatever's now selected.
            if (drawing) {
                MinebotMod.LOGGER.info(
                    "bow: main hand no longer a bow ({}) mid-draw -- abandoning shot", player.getMainHandItem().getItem()
                );
            }
            stop(player);
            return false;
        }

        aimAt(player, target);

        if (!drawing) {
            drawing = true;
            ticksSinceDrawStarted = 0;
            Minecraft.getInstance().gameMode.useItem(player, InteractionHand.MAIN_HAND);
            logDiagnostics(player, "starting draw");
        }

        ticksSinceDrawStarted++;
        if (ticksSinceDrawStarted < FULL_DRAW_TICKS) {
            if (ticksSinceDrawStarted % 5 == 0) {
                logDiagnostics(player, "drawing, " + ticksSinceDrawStarted + " / " + FULL_DRAW_TICKS + " ticks");
            }
            return false;
        }

        MinebotMod.LOGGER.info("bow: full draw reached, releasing");
        release(player, ticksSinceDrawStarted);
        drawing = false;
        return true;
    }

    /** Releases a shot left mid-draw -- e.g. the target died, was abandoned, or !stop/a new command superseded this attack. */
    public void stop(final LocalPlayer player) {
        if (drawing) {
            release(player, ticksSinceDrawStarted);
            drawing = false;
        }
    }

    /**
     * Sends the real network release (telling the server, which has its
     * own correctly-ticked-down draw state, to fire) and separately
     * drives BowItem.releaseUsing directly with a locally-computed
     * timeLeft so this client's own local echo doesn't silently no-op --
     * see the class docstring for why MultiPlayerGameMode.
     * releaseUsingItem()'s own local half can't be relied on here.
     */
    private static void release(final LocalPlayer player, final int ticksHeld) {
        Minecraft.getInstance().gameMode.releaseUsingItem(player);
        ItemStack bow = player.getMainHandItem();
        if (bow.getItem() instanceof BowItem bowItem) {
            int timeLeft = bowItem.getUseDuration(bow, player) - ticksHeld;
            bowItem.releaseUsing(bow, player.level(), player, timeLeft);
        }
    }

    public boolean isDrawing() {
        return drawing;
    }

    private static void aimAt(final LocalPlayer player, final Entity target) {
        double dx = target.getX() - player.getX();
        double dz = target.getZ() - player.getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        // Aim at roughly a third of the way up the target's body --
        // Entity.getY(fraction) is the exact same real convenience
        // AbstractSkeleton.performRangedAttack itself calls
        // (target.getY(0.3333)) to get this height; eye level would be
        // a real player's instinct, but this ports the exact real
        // formula rather than substituting a different (if plausible-
        // sounding) one.
        double dy = (target.getY(1.0 / 3.0) - player.getEyeY()) + horizontalDistance * ARC_LIFT_PER_BLOCK;

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        // Positive pitch = looking down (same convention already
        // established in NearbyPlayerLookAt/BlockBreaker.aimAt, both
        // confirmed via decompiled Entity.calculateViewVector).
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontalDistance));
        player.setYRot(yaw);
        player.setXRot(pitch);
    }

    private static void logDiagnostics(final LocalPlayer player, final String phase) {
        MinebotMod.LOGGER.info(
            "bow: {} -- isUsingItem={} usedItemHand={}", phase, player.isUsingItem(), player.getUsedItemHand()
        );
    }
}
