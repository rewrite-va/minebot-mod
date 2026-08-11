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
 */
public final class JumpPhysics {
    public static final float BASE_JUMP_POWER = 0.42F;
    private static final double GRAVITY_PER_TICK = 0.08;
    private static final float WALK_SPEED = 0.1F;
    private static final float SPRINT_SPEED = 0.13F;
    private static final double GROUND_FRICTION_DECAY = 0.6 * 0.91; // default block friction
    private static final double AIR_FRICTION_DECAY = 1.0 * 0.91;
    private static final double AIR_ACCEL = 0.02;
    private static final int MAX_AIR_TICKS = 60; // generous cap -- real jumps land well before this

    private JumpPhysics() {
    }

    /** One (runupTicks, sprint) candidate and the horizontal distance it actually lands at. */
    public record Plan(int runupTicks, boolean sprint, double landingDistance) {
    }

    /**
     * Simulates a single run-up-then-jump attempt: `runupTicks` ticks of
     * ground accel (sprinting or not), then a liftoff at BASE_JUMP_POWER,
     * tracking horizontal distance traveled until vertical position
     * returns to (at or below) the takeoff height -- a level landing,
     * matching this mod's jump moves, which only ever connect two
     * standing surfaces of the same reachable height (see Movements'
     * own MAX_STEP_HEIGHT-gated move generation; a jump onto a
     * DIFFERENT height is a different, taller/shorter arc this simple
     * level-landing model doesn't cover).
     */
    static double simulateLevelLanding(final int runupTicks, final boolean sprint) {
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
        for (int t = 0; t < MAX_AIR_TICKS; t++) {
            v += AIR_ACCEL;
            pos += v;
            v *= AIR_FRICTION_DECAY;
            vy -= GRAVITY_PER_TICK;
            prevY = y;
            y += vy;
            if (t > 0 && prevY > 0.0 && y <= 0.0) {
                return pos;
            }
        }
        return pos; // never came back down within MAX_AIR_TICKS -- return whatever distance was reached
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
    public static Plan planRunup(final double horizontalDistance) {
        Plan best = null;
        for (boolean sprint : new boolean[]{false, true}) {
            for (int runup = 0; runup <= 20; runup++) {
                double landing = simulateLevelLanding(runup, sprint);
                if (landing < horizontalDistance) {
                    continue; // undershoots -- never falls short of the target on purpose
                }
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
            // distance at all (a genuinely too-far jump) -- fall back to the
            // longest-reaching option found (max runup, sprinting) rather than
            // returning null, since Movements' own move-generation is the real
            // gate on whether this distance should have been offered as a move
            // in the first place (see MAX_STEP_HEIGHT/jump-height reasoning
            // there); this is a last-resort "do your best" rather than a second
            // reachability check duplicating that one.
            best = new Plan(20, true, simulateLevelLanding(20, true));
        }
        return best;
    }
}
