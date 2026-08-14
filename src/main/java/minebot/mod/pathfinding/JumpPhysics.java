package minebot.mod.pathfinding;

/**
 * Computes how many ticks of ground run-up (and whether to sprint) a jump
 * needs to land a given horizontal distance away, by directly simulating
 * real vanilla per-tick physics -- replaces the old fixed
 * SPRINT_RUNUP_TICKS_REQUIRED=5-and-always-sprint approach in
 * LegsNavigateNode, which was tuned against exactly one jump scenario
 * (goto_jump_2's own 2-block gap) and, confirmed live, systematically
 * OVERSHOOTS shorter jumps: sprinting compounds horizontal speed too fast
 * for a short (~1-tile) runway, landing well past a close target and
 * falling off the far side.
 *
 * Constants below are transcribed directly from the real decompiled
 * vanilla source (Loom's decompile cache, see CLAUDE.md for how to browse
 * it -- confirmed against net.minecraft.world.entity.LivingEntity and
 * net.minecraft.world.entity.Entity in this MC version), not guessed or
 * back-fit from observed behavior alone (though the model IS cross-checked
 * against one real observed log trace -- see this class's own test/
 * calibration notes in the conversation that produced it):
 * - LivingEntity.travelInAir + handleRelativeFrictionAndCalculateMovement:
 *   on-ground horizontal accel per tick is
 *   getSpeed() * (0.216 / blockFriction^3) via getFrictionInfluencedSpeed
 *   -- for the default block friction (0.6), 0.6^3 == 0.216, so this
 *   collapses to exactly getSpeed() (the MOVEMENT_SPEED attribute value,
 *   already sprint-modified if sprinting) for ordinary ground. Applied via
 *   Entity.moveRelative, which simply ADDS this tick's accel vector to
 *   the existing delta-movement (velocity) -- real per-tick integration,
 *   not an instant snap to max speed.
 * - Once airborne (!onGround()), getFrictionInfluencedSpeed instead
 *   returns getFlyingSpeed() = 0.02 (NOT sprint-scaled at all) -- holding
 *   forward while airborne only adds this small constant push per tick,
 *   confirmed live: this is why sprinting harder before liftoff, not
 *   holding forward longer in the air, is what actually controls jump
 *   distance.
 * - Velocity decays by a friction factor every tick regardless of accel:
 *   on ground, blockFriction * 0.91 (0.6 * 0.91 = 0.546 for default
 *   blocks); airborne, travelInAir's own blockFriction local is forced to
 *   1.0F (only ever read from the real block when onGround()), so the
 *   decay is 1.0 * 0.91 = 0.91.
 * - LivingEntity.BASE_JUMP_POWER = 0.42F (vertical liftoff velocity, see
 *   jumpFromGround), and Entity's own per-tick gravity for a player-like
 *   entity is 0.08 blocks/tick^2 (subtracted from vertical velocity each
 *   tick before it's applied to position -- see LivingEntity.travelInAir's
 *   own movementY -= getEffectiveGravity()).
 * - Sprint itself is a flat +30% multiplier on the MOVEMENT_SPEED
 *   attribute (LivingEntity.SPEED_MODIFIER_SPRINTING, ADD_MULTIPLIED_
 *   TOTAL 0.3F) applied instantly the tick setSprinting(true) is called --
 *   base walk speed 0.1, sprint speed 0.1 * 1.3 = 0.13. There is no real
 *   gradual "ramp-up" at the attribute level; what actually takes several
 *   ticks to develop is the entity's own VELOCITY converging toward that
 *   per-tick accel ceiling under repeated friction-damped integration
 *   (see simulate() below) -- an earlier assumption in this codebase's
 *   own investigation history that sprint ramps up "like a real runner"
 *   was closer to this velocity-convergence effect than the attribute
 *   itself, but SPRINT_RUNUP_TICKS existed to capture exactly this
 *   convergence, not a modifier delay.
 * - LivingEntity.jumpFromGround has its own separate sprint-only term on
 *   top of all the above: when isSprinting() at the moment of liftoff, it
 *   adds a flat 0.2-block/tick horizontal impulse in the facing direction
 *   (addDeltaMovement((-sin(yaw)*0.2, 0, cos(yaw)*0.2))) -- a one-time
 *   liftoff kick, not part of the ground run-up accel loop. Missing this
 *   term made earlier versions of this model require far more run-up than
 *   real sprint-jumping needs (see SPRINT_JUMP_LIFTOFF_BOOST's own
 *   comment for the live goto_jump_4 case this was confirmed against).
 */
public final class JumpPhysics {
    public static final float BASE_JUMP_POWER = 0.42F;
    private static final double GRAVITY_PER_TICK = 0.08;
    private static final float WALK_SPEED = 0.1F;
    private static final float SPRINT_SPEED = 0.13F;
    private static final double GROUND_FRICTION_DECAY = 0.6 * 0.91; // default block friction
    private static final double AIR_FRICTION_DECAY = 1.0 * 0.91;
    // Player.getFlyingSpeed overrides LivingEntity's flat 0.02 constant --
    // while NOT actually flying (the normal on-foot case), it returns
    // 0.026 instead of 0.02 whenever isSprinting() is true, a airborne-
    // accel detail LivingEntity's own javadoc/base implementation doesn't
    // have at all (only Player's override does). Missing this (along with
    // SPRINT_JUMP_LIFTOFF_BOOST below) made the model under-predict how
    // far a real sprint jump travels -- confirmed live on goto_jump_4: a
    // plan built with sprint@5 ticks was simulated to land at 4.32 blocks
    // (just barely short of the needed ~4.34), but the bot's real landing
    // came up well shorter than even that in practice, consistent with
    // this term being missing entirely rather than just a rounding gap.
    private static final double AIR_ACCEL_WALK = 0.02;
    private static final double AIR_ACCEL_SPRINT = 0.026;
    private static final int MAX_AIR_TICKS = 60; // generous cap -- real jumps land well before this
    // LivingEntity.jumpFromGround's own sprint branch adds this as a flat
    // horizontal liftoff impulse (addDeltaMovement, facing-direction
    // unit vector * 0.2) the instant a sprinting jump leaves the ground --
    // separate from, and on top of, the ground run-up accel simulated
    // above. Missing this made the model systematically require far more
    // run-up than real play needs for sprint jumps specifically (confirmed
    // live: goto_jump_4's 3-wide gap, easily cleared running with only a
    // couple of blocks of run-up, was computed as needing 7 sprint ticks
    // without this term -- the bot then undershot into the gap on a
    // shorter real platform).
    private static final double SPRINT_JUMP_LIFTOFF_BOOST = 0.2;
    // Real vanilla player hitbox is 0.6 blocks wide -- 0.3 blocks from
    // center to the LEADING face in the direction of travel. Confirmed
    // live via a real navigate[collision] AABB trace on goto_jump_4:
    // box=[0.2,5.4]-[0.8,6.0] at the tick horizontalCollision first fired,
    // i.e. the leading face (z=6.0) hit the landing block's own wall a
    // full 0.3 blocks before the player's own CENTER (aimed at the
    // waypoint's block center, one more 0.5 beyond the wall) would have.
    // The height-at-target check below must ask "is the bot still above
    // ledge height by the time its LEADING FACE reaches the wall", not
    // "...by the time its distant-in-comparison CENTER reaches the far
    // waypoint's own center" -- checking the wrong (later, further) point
    // let the first version of this height check pass every candidate
    // with no effect at all, since by the waypoint's own center the arc
    // had always already fallen well below ledge height regardless.
    private static final double PLAYER_HALF_WIDTH = 0.3;

    private JumpPhysics() {
    }

    /** One (runupTicks, sprint) candidate and the horizontal distance it actually lands at. */
    public record Plan(int runupTicks, boolean sprint, double landingDistance) {
    }

    /**
     * One full simulated jump arc's outcome: `landingDistance` is the
     * horizontal distance traveled once vertical position returns to (at
     * or below) takeoff height -- a level landing, matching this mod's
     * jump moves, which only ever connect two standing surfaces of the
     * same reachable height (see Movements' own MAX_STEP_HEIGHT-gated
     * move generation; a jump onto a DIFFERENT height is a different,
     * taller/shorter arc this simple level-landing model doesn't cover).
     * `heightAtTargetDistance` is the real per-tick height (see
     * simulateArc's own docstring for why this is a SEPARATE field from
     * landingDistance, not derivable from it) at the first tick horizontal
     * position reaches whatever target distance the caller asked about --
     * NaN if the arc never reaches that far at all within MAX_AIR_TICKS.
     */
    private record Arc(double landingDistance, double heightAtTargetDistance) {
    }

    /**
     * Simulates a single run-up-then-jump attempt: `runupTicks` ticks of
     * ground accel (sprinting or not), then a liftoff at BASE_JUMP_POWER,
     * tracking BOTH horizontal distance and real height every tick --
     * unlike an earlier version of this method that only ever tracked
     * distance at the moment of a level landing, that alone can't tell
     * "clears the gap" from "arrives at the right total DISTANCE only
     * after already falling below the landing ledge's own height and
     * slamming into its wall face instead." Confirmed live via a real
     * `navigate[collision]` trace on goto_jump_4: a plan whose landing
     * DISTANCE was more than sufficient (a level-landing arc reaching
     * well past the target) still failed for real, because by the tick
     * horizontal position actually reached the target's own x/z, the
     * bot's height had already dropped below the landing platform's own
     * top surface -- `horizontalCollision=true` against the platform's
     * SIDE, deltaMovement.z snapped to 0.0, exactly like a real player
     * jumping a hair too low and short and bonking into a ledge instead
     * of landing on it. A same-height "total distance at re-landing"
     * check can never see this, since it only asks "how far does this
     * arc go in total", never "is the arc still above ledge height at
     * the specific point it needs to be."
     */
    static Arc simulateArc(final int runupTicks, final boolean sprint, final double ledgeEdgeDistance) {
        double v = 0.0;
        double pos = 0.0;
        double accel = sprint ? SPRINT_SPEED : WALK_SPEED;
        for (int i = 0; i < runupTicks; i++) {
            v += accel;
            pos += v;
            v *= GROUND_FRICTION_DECAY;
        }
        double vy = BASE_JUMP_POWER;
        double y = 0.0;
        double prevY;
        double prevPos = pos;
        double heightAtTarget = Double.NaN;
        if (sprint) {
            v += SPRINT_JUMP_LIFTOFF_BOOST;
        }
        double airAccel = sprint ? AIR_ACCEL_SPRINT : AIR_ACCEL_WALK;
        for (int t = 0; t < MAX_AIR_TICKS; t++) {
            v += airAccel;
            prevPos = pos;
            pos += v;
            v *= AIR_FRICTION_DECAY;
            vy -= GRAVITY_PER_TICK;
            prevY = y;
            y += vy;
            if (Double.isNaN(heightAtTarget) && pos >= ledgeEdgeDistance && prevPos < ledgeEdgeDistance) {
                // Linearly interpolate height at the exact tick horizontal
                // position crosses the ledge edge -- close enough for
                // planning purposes (a single tick's worth of extra
                // vertical fall, ~0.02-0.08 blocks at this point in the
                // arc, is well inside this model's other approximations).
                double frac = (ledgeEdgeDistance - prevPos) / (pos - prevPos);
                heightAtTarget = prevY + (y - prevY) * frac;
            }
            if (t > 0 && prevY > 0.0 && y <= 0.0) {
                return new Arc(pos, heightAtTarget);
            }
        }
        return new Arc(pos, heightAtTarget); // never came back down within MAX_AIR_TICKS
    }

    // How much closer a candidate's landing distance has to be to prefer
    // it over one that needs fewer run-up ticks -- see planRunup's own
    // docstring for the real bug this guards against: a walking option
    // that lands 0.1 blocks closer to the target than a sprinting option,
    // but needs 2+ MORE run-up ticks to get there, is not actually a
    // better choice on a short platform (a real run-up needs physical
    // runway to build in, which a "closest landing distance" comparison
    // alone knows nothing about). Picked well above the sub-0.1-block
    // differences typically separating adjacent walk/sprint tick counts
    // (see JumpPhysics' own docstring/simulation table), so this only
    // breaks a near-tie in favor of shorter run-up, not silently prefer
    // an overshoot large enough to matter.
    private static final double LANDING_DISTANCE_TIE_MARGIN = 0.3;

    /**
     * Picks the run-up (ticks held forward+sprint-or-not on the ground
     * before jumping) that lands at or past `horizontalDistance` for a
     * level landing (never intentionally choosing an option that lands
     * short of the target -- undershooting drops the bot into the gap it
     * was trying to clear, exactly the failure mode PathTracker's own
     * vertical-drift replan fix now recovers from, but still a wasted
     * attempt every jump would otherwise commit), preferring FEWER run-up
     * ticks whenever landing distances are within LANDING_DISTANCE_TIE_
     * MARGIN of each other, and only preferring a closer landing distance
     * once the tick-count gap is small enough not to matter.
     *
     * Confirmed live as a real bug in an earlier version of this method
     * that only ever minimized landing distance: for a long jump (e.g.
     * ~3.16 blocks), walk@8-ticks lands at 3.159 (essentially exact) while
     * sprint@6-ticks lands at 3.266 (0.1 block further) -- minimizing
     * distance alone always picked the walking option, but 8 ticks of
     * ground run-up does not physically fit on a platform only 1 block
     * deep before reaching the edge, so the bot walked off the edge
     * every attempt, never building enough run-up to satisfy hasRunup at
     * all. Preferring fewer ticks whenever the landing-distance
     * difference is this small picks sprint@6 instead, which both fits
     * the available runway and lands close enough to still clear the gap.
     *
     * Deliberately brute-force over a small fixed range of candidate
     * ticks/sprint combinations rather than solving the underlying
     * geometric series in closed form -- MAX_AIR_TICKS-bounded simulate()
     * calls are cheap (a few hundred float ops each), this only runs once
     * per fresh jump waypoint (not every tick), and a closed-form inverse
     * of friction-damped tick-by-tick integration is far more error-prone
     * to get right than just searching the same simulate() this class
     * already trusts.
     */
    // Minimum height (relative to takeoff/landing height, 0.0) the arc
    // must still be at by the tick it reaches the target distance -- see
    // Arc.heightAtTargetDistance's own docstring for the real
    // wall-collision bug this guards against. A small positive margin
    // (not exactly 0.0): simulateArc's own height-at-target is a single
    // linear interpolation between two real tick samples, not a
    // continuous curve, and real vanilla collision resolution against a
    // ledge's exact corner has its own small margins this simple model
    // can't capture exactly -- landing with SOME real clearance above the
    // ledge top, not just barely grazing it, is what a real player
    // running the same jump would experience too.
    private static final double MIN_HEIGHT_AT_TARGET = 0.1;

    public static Plan planRunup(final double horizontalDistance) {
        // horizontalDistance (the caller's own aim-point distance, always
        // measured to the TARGET BLOCK'S OWN CENTER, x+0.5/z+0.5 -- see
        // LegsNavigateNode's own aimX/aimZ) is 0.5 blocks further than the
        // landing block's own NEAR edge -- the wall a too-low arc actually
        // collides with, well before ever reaching the center. The
        // player's own leading hitbox face reaches that same wall
        // PLAYER_HALF_WIDTH blocks earlier still (see its own comment).
        // The height check below must fire at THIS distance, not the
        // full horizontalDistance -- see simulateArc's own docstring for
        // the live goto_jump_4 bug from checking at the wrong (too far)
        // point.
        double ledgeEdgeDistance = horizontalDistance - 0.5 - PLAYER_HALF_WIDTH;
        Plan best = null;
        for (boolean sprint : new boolean[]{false, true}) {
            for (int runup = 0; runup <= 20; runup++) {
                Arc arc = simulateArc(runup, sprint, ledgeEdgeDistance);
                if (arc.landingDistance() < horizontalDistance) {
                    continue; // undershoots -- never falls short of the target on purpose
                }
                if (Double.isNaN(arc.heightAtTargetDistance()) || arc.heightAtTargetDistance() < MIN_HEIGHT_AT_TARGET) {
                    // Reaches the target DISTANCE eventually, but has
                    // already dropped below the landing ledge's own
                    // height by the tick it gets there -- a real
                    // horizontalCollision into the ledge's side face, not
                    // a clean landing on top of it (see Arc's own
                    // docstring for the live goto_jump_4 trace that
                    // exposed this). Not a viable candidate no matter how
                    // good its total landing distance looks.
                    continue;
                }
                double landing = arc.landingDistance();
                if (best == null) {
                    best = new Plan(runup, sprint, landing);
                    continue;
                }
                boolean fewerTicks = runup < best.runupTicks();
                boolean withinTieMargin = landing <= best.landingDistance() + LANDING_DISTANCE_TIE_MARGIN;
                boolean strictlyCloser = landing < best.landingDistance() - LANDING_DISTANCE_TIE_MARGIN;
                if (strictlyCloser || (fewerTicks && withinTieMargin)) {
                    best = new Plan(runup, sprint, landing);
                }
            }
        }
        if (best == null) {
            // No walk/sprint/runup combination within the search range clears this
            // distance (and stays above ledge height doing it) at all (a
            // genuinely too-far or too-flat jump) -- fall back to the
            // longest-reaching option found (max runup, sprinting) rather than
            // returning null, since Movements' own move-generation is the real
            // gate on whether this distance should have been offered as a move
            // in the first place (see MAX_STEP_HEIGHT/jump-height reasoning
            // there); this is a last-resort "do your best" rather than a second
            // reachability check duplicating that one.
            best = new Plan(20, true, simulateArc(20, true, ledgeEdgeDistance).landingDistance());
        }
        return best;
    }
}
