package minebot.mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BowItem;

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
    private int ticksWaitingForUseItemConfirm;

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
            ticksWaitingForUseItemConfirm = 0;
            logDiagnostics(player, "starting draw");
        }

        if (!player.isUsingItem()) {
            // Keep calling useItem() every tick until the server-synced
            // "using item" flag actually confirms it took -- a single
            // call isn't reliable enough on its own. Confirmed live
            // (three separate real fights, same confirmed-correct build
            // each time): calling this exactly once, only on the tick
            // the draw starts, worked in one attempt out of several but
            // left isUsingItem()/getTicksUsingItem() stuck at false/0
            // forever in the others, with nothing else about the state
            // ever looking wrong -- real client/server round-trip
            // flakiness, not a logic bug, so this retries every tick
            // instead of assuming the very first attempt landed. The
            // original keyUse-keybind-hold approach got this resilience
            // "for free" (Minecraft.handleKeybinds re-attempts every
            // tick the key is still down); calling useItem() directly
            // has to re-earn the same resilience explicitly.
            Minecraft.getInstance().gameMode.useItem(player, InteractionHand.MAIN_HAND);
            ticksWaitingForUseItemConfirm++;
            if (ticksWaitingForUseItemConfirm % 5 == 0) {
                logDiagnostics(player, "still waiting for isUsingItem to confirm, retried " + ticksWaitingForUseItemConfirm + " times");
            }
            return false;
        }

        int ticksUsing = player.getTicksUsingItem();
        if (ticksUsing < FULL_DRAW_TICKS) {
            // At info (temporarily, same reasoning BlockBreaker's own
            // tool-switch logging gives -- this client's default log4j
            // config filters debug output entirely), but only every few
            // ticks, not every single one -- kept from the investigation
            // that found the real cause above, to make any future
            // regression here immediately observable instead of silent.
            if (ticksUsing % 5 == 0) {
                logDiagnostics(player, "drawing, " + ticksUsing + " / " + FULL_DRAW_TICKS + " ticks");
            }
            return false;
        }

        MinebotMod.LOGGER.info("bow: full draw reached, releasing");
        Minecraft.getInstance().gameMode.releaseUsingItem(player);
        drawing = false;
        return true;
    }

    /** Releases a shot left mid-draw -- e.g. the target died, was abandoned, or !stop/a new command superseded this attack. */
    public void stop(final LocalPlayer player) {
        if (drawing) {
            Minecraft.getInstance().gameMode.releaseUsingItem(player);
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

    private static void logDiagnostics(final LocalPlayer player, final String phase) {
        MinebotMod.LOGGER.info(
            "bow: {} -- isUsingItem={} usedItemHand={}", phase, player.isUsingItem(), player.getUsedItemHand()
        );
    }
}
