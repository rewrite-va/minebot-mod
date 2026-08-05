package minebot.mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BowItem;

/**
 * Draws and fires a real bow at a target, the same real-keybind-hold
 * mechanism FoodEater/BlockBreaker already established for eating/mining
 * (a direct API call to start/complete using an item does not reliably
 * work -- see FoodEater's own docstring for the live investigation that
 * found this -- so this holds the real `keyUse` keybind down instead of
 * calling startUsingItem/releaseUsingItem directly, letting vanilla's
 * own per-tick Minecraft.handleKeybinds() drive the actual draw/release
 * exactly as it would for a human's held right-click).
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
    // faster, weaker partial-draw shots.
    private static final int FULL_DRAW_TICKS = 20;

    private boolean drawing;

    /**
     * Aims at `target`, holds the draw, and releases once fully drawn --
     * call every tick a bow shot should be in progress. Returns true once
     * a shot has actually been released this tick (caller can use this to
     * decide when to allow movement/target reassignment again), false
     * while still drawing.
     */
    public boolean tick(final LocalPlayer player, final Entity target) {
        if (!(player.getMainHandItem().getItem() instanceof BowItem)) {
            // Something else took over the main hand mid-draw (a tool
            // switch elsewhere, !give, ...) -- abandon this shot cleanly
            // rather than holding keyUse against whatever's now selected.
            if (drawing) {
                MinebotMod.LOGGER.info(
                    "bow: main hand no longer a bow ({}) mid-draw -- abandoning shot", player.getMainHandItem().getItem()
                );
            }
            stop();
            return false;
        }

        aimAt(player, target);

        // Held every tick the draw should continue, not just once on the
        // first tick -- mirrors FoodEater.holdUseKey's own docstring
        // ("idempotent to call every tick while eating should continue")
        // exactly, rather than assuming the keybind stays down on its
        // own once set. real-input-driven interactions in this mod have
        // already shown once (FoodEater's own investigation) that
        // subtleties here can silently break the interaction with
        // nothing else about the state looking wrong.
        holdUseKey();

        if (!drawing) {
            drawing = true;
            logDiagnostics(player, "starting draw");
            return false;
        }

        int ticksUsing = player.getTicksUsingItem();
        if (ticksUsing < FULL_DRAW_TICKS) {
            // At info (temporarily, same reasoning BlockBreaker's own
            // tool-switch logging gives -- this client's default log4j
            // config filters debug output entirely), but only every few
            // ticks, not every single one -- this exists specifically to
            // make a stuck draw observable (found live: a real !kill
            // shot silently never completed, with nothing at all in the
            // log to explain why), not to spam a line 20 times per shot.
            if (ticksUsing % 5 == 0) {
                logDiagnostics(player, "drawing, " + ticksUsing + " / " + FULL_DRAW_TICKS + " ticks");
            }
            return false;
        }

        MinebotMod.LOGGER.info("bow: full draw reached, releasing");
        releaseUseKey();
        drawing = false;
        return true;
    }

    /** Releases the draw key if a shot was left mid-draw -- e.g. the target died, was abandoned, or !stop/a new command superseded this attack. */
    public void stop() {
        if (drawing) {
            releaseUseKey();
            drawing = false;
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

    /**
     * Dumps every real precondition Minecraft.startUseItem checks before
     * it actually sets the LivingEntity "using item" flag that isUsingItem()/
     * getTicksUsingItem() read (confirmed via decompiled source: that flag
     * is only ever set server-side, in response to a real client->server
     * interaction packet -- so a draw that never progresses could be
     * blocked at any of several distinct real gates: MultiPlayerGameMode.
     * isDestroying(), LocalPlayer.isHandsBusy(), whether the real
     * Minecraft.hitResult happens to be aimed at the target entity itself
     * (which would route the interaction to MultiPlayerGameMode.interact
     * instead of useItem -- a materially different code path than eating/
     * mining ever exercised, since neither is ever aimed at a hostile
     * entity), or simply the keyUse keybind's own isDown() not reading
     * back as expected). Added specifically because a real stuck draw
     * produced no other diagnostic signal anywhere in this class.
     */
    private static void logDiagnostics(final LocalPlayer player, final String phase) {
        Minecraft client = Minecraft.getInstance();
        var hitResult = client.hitResult;
        String hitDescription = hitResult == null ? "null" : hitResult.getType() + (
            hitResult instanceof net.minecraft.world.phys.EntityHitResult entityHit
                ? " entity=" + entityHit.getEntity()
                : ""
        );
        MinebotMod.LOGGER.info(
            "bow: {} -- isUsingItem={} usedItemHand={} keyUseDown={} isDestroying={} isHandsBusy={} hitResult={}",
            phase, player.isUsingItem(), player.getUsedItemHand(), client.options.keyUse.isDown(),
            client.gameMode.isDestroying(), player.isHandsBusy(), hitDescription
        );
    }

    private static void holdUseKey() {
        Minecraft.getInstance().options.keyUse.setDown(true);
    }

    private static void releaseUseKey() {
        Minecraft.getInstance().options.keyUse.setDown(false);
    }
}
