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

    // How long a single shot attempt gets before giving up on it and
    // starting a completely fresh one -- confirmed live that the real
    // server-synced "using item" state can drop out silently partway
    // through a draw (isUsingItem() confirms true once, then reverts to
    // false permanently, getTicksUsingItem() stuck at 0 forever after
    // that with no further error/signal of any kind) with no way to
    // resume the same attempt once that happens. Double FULL_DRAW_TICKS
    // -- generous enough that a real, merely-slow confirmation round
    // trip never trips this, bounded so a genuinely stalled attempt
    // doesn't strand the fight forever.
    private static final int STALLED_ATTEMPT_TIMEOUT_TICKS = FULL_DRAW_TICKS * 2;

    private boolean drawing;
    private int ticksWaitingForUseItemConfirm;
    private boolean everConfirmedUsing;
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
            ticksWaitingForUseItemConfirm = 0;
            everConfirmedUsing = false;
            ticksSinceDrawStarted = 0;
            Minecraft.getInstance().gameMode.useItem(player, InteractionHand.MAIN_HAND);
            logDiagnostics(player, "starting draw");
            // Deliberately falls through to the isUsingItem() check right
            // below instead of returning here -- a real, if momentary,
            // client-side prediction can make isUsingItem() true on this
            // exact same tick (confirmed live: "starting draw" itself
            // logged isUsingItem=true), and that must be allowed to set
            // everConfirmedUsing immediately. An early return here used
            // to skip that check entirely on the very first tick, so
            // everConfirmedUsing stayed false even when this tick's own
            // prediction was already true -- the very next tick then saw
            // isUsingItem() revert to false (never actually confirmed by
            // the server) and, since everConfirmedUsing was still
            // unset, retried useItem() -- which restarts a real
            // in-progress draw instead of extending it, producing the
            // exact "spammed every tick, vibrating" loop reported live.
        }

        if (++ticksSinceDrawStarted > STALLED_ATTEMPT_TIMEOUT_TICKS) {
            // This one attempt has gone on too long with nothing to show
            // for it -- confirmed live: the real server-synced "using
            // item" state can silently drop out partway through a draw
            // (isUsingItem() confirms true once, then reverts to false
            // permanently, getTicksUsingItem() stuck at 0 forever after)
            // with no further signal of any kind that anything went
            // wrong. Rather than wait forever for a round trip that's
            // never coming, release whatever local state might be stuck
            // and start a genuinely fresh attempt -- same target, same
            // aim, but a brand new useItem() call and a clean
            // everConfirmedUsing/ticksWaitingForUseItemConfirm slate.
            MinebotMod.LOGGER.warn(
                "bow: attempt stalled after {} ticks with no confirmed progress -- starting a fresh attempt", ticksSinceDrawStarted
            );
            Minecraft.getInstance().gameMode.releaseUsingItem(player);
            drawing = false;
            return false; // next tick's !drawing branch starts clean
        }

        if (player.isUsingItem()) {
            everConfirmedUsing = true;
        } else if (!everConfirmedUsing) {
            // Retry useItem() only until the draw is confirmed at least
            // once, not every tick unconditionally -- confirmed live
            // that calling useItem() again *while a real draw is already
            // in progress* restarts it instead of extending it (the
            // user's own report: "the bow being spammed every tick, like
            // vibrating" -- a real, visible re-trigger loop, not just a
            // logging artifact). isUsingItem() itself flickers false for
            // a tick or two around the initial client/server round trip
            // even on a draw that ultimately succeeds, so a single
            // missed confirmation isn't proof the first useItem() call
            // never landed -- only retry while it has *never once* been
            // seen true since this draw started; once confirmed even
            // one time, a later isUsingItem()==false tick just means the
            // draw is still settling, not that it needs restarting.
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
