package minebot.mod.statemachine.legs;

import minebot.mod.MinebotMod;
import minebot.mod.MovementIntent;
import minebot.mod.pathfinding.Move;
import minebot.mod.pathfinding.WaypointClassifier;
import minebot.mod.statemachine.BlackboardKey;
import minebot.mod.statemachine.StateNode;
import minebot.mod.statemachine.TickContext;
import minebot.mod.statemachine.playerintention.NavIntent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.List;

/**
 * Walks toward whatever position PlayerIntention is currently publishing via
 * NavIntent.NAV_TARGET -- the first real Legs behavior, ported from
 * MinebotMod.resolveMovementIntent's old FOLLOW case + its shared
 * pathfinding pipeline (see STATE_MACHINE.md's git history / the
 * conversation that produced this class for the full step-by-step
 * mapping).
 *
 * No longer tracks followEntityId or does its own stop-distance check --
 * whichever PlayerIntention node is currently active owns both (see NavIntent's
 * own docstring for why this is a shared channel, not FOLLOW-specific
 * anymore: GO_TO_DEATH_POSITION/PICKUP_ITEMS need the exact same "walk
 * toward this point, stop when close enough" shape for their own
 * targets). LegsStateMachine's own NAVIGATE<->IDLE edges already gate on
 * a NAV_TARGET existing and not yet being within range, so by the time
 * this node's onTick runs, "should Legs be walking at all" is already
 * answered -- this node only ever needs to answer "walk toward this
 * specific position," with zero awareness of WHY (which PlayerIntention state
 * asked, or what it's for).
 *
 * Deliberately excludes, as real known gaps for future Hands SM nodes
 * (NOT ported here, unlike the rest of the old pipeline):
 * - Door-opening while walking (was DoorOpener.maybeOpenDoorNear, called
 *   unconditionally inside the old shared pipeline).
 * - Digging through obstacle blocks while walking (was
 *   MinebotMod.maybeBreakBlocksNear / pathBlockBreaker).
 * Both are real hand interactions (a useItemOn call, a keyAttack hold)
 * that happened to live inside movement code only because nothing else
 * separated "decide where to walk" from "do the interactions needed to
 * get there" -- per explicit direction, they belong to Hands SM later
 * (e.g. hands:open_door), not duplicated into Legs now. Until then,
 * !follow simply cannot walk through a closed door or a blocked path --
 * an honest regression from the old behavior, not an oversight.
 *
 * Deliberately never WRITES yaw/pitch -- look direction is Head SM's
 * exclusive concern (see STATE_MACHINE.md's axis split, and HeadState/
 * HeadNavigateNode, which now actually owns it) -- but DOES READ the
 * player's current yaw, to compute forward/backward/left/right relative
 * to whatever direction the player currently happens to be facing
 * (Minecraft's own movement model is inherently yaw-relative -- "forward"
 * always means "whichever way the entity is currently facing", confirmed
 * live: an earlier version of this node only ever set `forward` with
 * nothing anywhere setting yaw at all, and the bot walked in a fixed,
 * arbitrary direction -- whichever way it happened to be facing when
 * !follow started -- completely independent of the actual target's real
 * position). Reading yaw without writing it is what actually keeps this
 * decoupled from Head SM: real kiting (backing/strafing away from a
 * target while FACING it) needs exactly this -- Legs moving in an
 * arbitrary absolute direction while Head points the camera wherever it
 * wants, independent of travel direction.
 *
 * Uses ctx.pathTracker -- a single PathTracker shared across every Legs
 * node that walks (this one, and LegsFleeNode via its own delegation into
 * walkTowardNavTarget below), living on TickContext itself (see its own
 * docstring) rather than owned privately per-node or threaded through
 * node constructors -- per explicit direction, nodes reach shared engine
 * state the same way they already reach everything else per-tick
 * (ctx.blackboard, ctx.input, ...), not via ad hoc references passed
 * around outside that channel. NAVIGATE/FLEE are never active at the same
 * time (see LegsStateMachine's own edges), and PathVisualizer draws
 * whichever plan is current regardless of which state produced it, so one
 * shared instance is exactly right here.
 */
public final class LegsNavigateNode implements StateNode<LegsState> {
    /**
     * The next unreached waypoint's raw block position, or null when
     * there's no real planned waypoint (no path found/needed -- walking
     * straight at the raw target) or this node isn't active at all.
     * Published every tick this node is active -- read by HeadNavigateNode
     * (which derives its own fractional aim point from it) and
     * HandsOpenDoorNode (which checks the actual block there for a closed
     * door), without either needing a direct reference to this node (see
     * STATE_MACHINE.md's "The blackboard"). Deliberately the raw integer
     * block position, not a pre-offset Vec3 -- a fractional aim point is
     * only meaningful to a consumer that wants to aim at it (Head); a
     * consumer that wants to know what block this actually is (Hands)
     * needs the real coordinates, not an already-offset point that could
     * floor() back to the wrong block in edge cases (see the conversation
     * that produced this field for the full reasoning).
     */
    public static final BlackboardKey<BlockPos> WAYPOINT_COORDINATES = new BlackboardKey<>("WAYPOINT_COORDINATES");

    /**
     * The block(s), if any, the current waypoint's own planned Move needs
     * dug through to actually execute it (Move.toBreak, see its own
     * docstring -- e.g. a leaves block sitting in the headroom of a
     * getMoveJumpUp step) -- published alongside WAYPOINT_COORDINATES so
     * HandsMineNode can drive BlockBreaker against the REAL planned
     * target(s), not re-derive "what needs breaking" from the waypoint's
     * own block state the way WaypointClassifier's door/farmland checks
     * do. That live-reclassify approach doesn't work for toBreak: a jump
     * move's obstruction can be a block ABOVE the landing waypoint (e.g.
     * blockA/blockH in Movements.getMoveJumpUp), not the waypoint position
     * itself, so there's no way to reconstruct which block(s) A* actually
     * meant from the waypoint coordinates alone -- this has to come
     * straight from the Move that was chosen. Empty (never null) when the
     * current waypoint needs no digging, mirroring Move.toBreak's own
     * never-null contract.
     */
    public static final BlackboardKey<List<BlockPos>> WAYPOINT_TO_BREAK = new BlackboardKey<>("WAYPOINT_TO_BREAK");

    /**
     * The exact position Move.digStance (see its own docstring) says the
     * bot needs to be standing at while breaking WAYPOINT_TO_BREAK's own
     * blocks -- null whenever WAYPOINT_TO_BREAK is empty. Deliberately
     * NOT the same value as WAYPOINT_COORDINATES: that field is the
     * move's DESTINATION (where Legs is walking/jumping TO), while digging
     * happens from the move's ORIGIN (where the bot already is BEFORE
     * completing the move) -- reported live: HandsMineNode's own arrival
     * gate first compared against WAYPOINT_COORDINATES by mistake, which
     * is a position the bot can never actually reach until the digging is
     * done in the first place (a real chicken-and-egg deadlock, the same
     * shape as the still-needs-digging jump-suppression fix elsewhere in
     * this class). Published separately so HandsMineNode never has to
     * guess or reconstruct which position a toBreak entry's own
     * visibility was actually verified from at planning time.
     */
    public static final BlackboardKey<BlockPos> WAYPOINT_DIG_STANCE = new BlackboardKey<>("WAYPOINT_DIG_STANCE");

    /**
     * Consecutive on-ground ticks spent walking/sprinting toward the
     * current waypoint, tracked so a requiresJump move (real parkour --
     * see Move.requiresJump's own docstring) can hold off firing the
     * jump key until real sprint speed has actually built up, not just
     * fire it the instant walking starts. Reported live, tested live by
     * hand: a 3-block parkour move ((-350,105,-1388) -> (-350,106,-1391),
     * cost=9.0, d=3 in Movements.getMoveParkourForward) is a real jump a
     * human player can make, but ONLY running -- jumping from a standing
     * start doesn't carry anywhere near enough horizontal momentum to
     * cross it. Confirmed via a live diagnostic trace: forward+jump
     * fired correctly every single tick (intent computation was never
     * the bug), but the bot's very first jump attempt fired on the same
     * tick it started walking -- with zero run-up, real vanilla physics
     * gave it a standing jump's momentum, it fell short, and every
     * attempt after that repeated the identical short hop against the
     * same wall forever. A plain step-up/jump-up move (requiresJump
     * false) doesn't need this at all -- those only need to clear ~1
     * block of height, well within standing-jump range, and gating them
     * the same way would just add pointless delay to ordinary walking.
     * Stored on the blackboard (not a field on this class) because
     * walkTowardNavTarget is a static method shared with LegsFleeNode's
     * own delegation into it (see this class's own docstring) -- no
     * per-instance state naturally available to either caller, and
     * fleeing was never going to fire a requiresJump move anyway
     * (GoalNear-planned real waypoints aren't part of FLEE's own
     * computed retreat-point publishing), so a single shared counter is
     * fine. Reset (not incremented) on any tick that isn't actually
     * building real run-up -- not on ground, not walking, not facing the
     * waypoint, or not actually pressing forward this tick -- so a fall,
     * a stop, a sideways correction, or a fresh path replan can't leave
     * a stale count around from an unrelated earlier approach.
     */
    // Package-visible (not private) -- LegsGotoNode also needs to reset
    // this on its own onEnter, same as this class's own onEnter/onExit do
    // (see both their own docstrings). Confirmed live as a real bug:
    // LegsGotoNode never reset either this counter or ctx.pathTracker on
    // entry, so stale runup-tick/plan state from whatever walk (a
    // DIFFERENT !goto, or an earlier !follow/NAVIGATE session) happened
    // to run immediately before could leak into a fresh !goto -- observed
    // live as a perfectly alternating FAIL/PASS/FAIL/PASS pattern across
    // repeated runs of the exact same scenario: a run that ended
    // mid-flight (the outer test timeout cancelling it before arrival)
    // left this counter/plan in a different state than a run that
    // completed cleanly, and that leftover state fed directly into
    // whether the NEXT run's own tight-runway jump could build enough
    // runupTicks before reaching the platform edge.
    static final BlackboardKey<Integer> SPRINT_RUNUP_TICKS = new BlackboardKey<>("SPRINT_RUNUP_TICKS");
    // A real vanilla sprint ramps up from a standing start over roughly
    // half a second (LivingEntity's own gradual speed-attribute
    // acceleration, not instant), so a handful of ticks of forward
    // pressure should already be well clear of that -- not tuned against
    // a precise measured vanilla constant, just picked comfortably above
    // the ramp-up window. Worth re-checking against a live retry of the
    // exact jump this was built for ((-350,105,-1388) -> (-350,106,-1391))
    // if it still falls short.
    private static final int SPRINT_RUNUP_TICKS_REQUIRED = 8;

    @Override
    public void onEnter(final TickContext ctx, final LegsState previousState) {
        ctx.pathTracker.reset();
        ctx.blackboard.put(SPRINT_RUNUP_TICKS, 0);
    }

    @Override
    public void onTick(final TickContext ctx) {
        walkTowardNavTarget(ctx, false, false);
    }

    @Override
    public void onExit(final TickContext ctx) {
        ctx.input.setIntent(new MovementIntent());
        ctx.blackboard.put(WAYPOINT_COORDINATES, null);
        ctx.blackboard.put(WAYPOINT_TO_BREAK, Collections.emptyList());
        ctx.blackboard.put(WAYPOINT_DIG_STANCE, null);
        ctx.blackboard.put(SPRINT_RUNUP_TICKS, 0);
    }

    /**
     * The actual "walk toward whatever NavIntent.NAV_TARGET currently
     * says, using ctx.pathTracker's own plan" logic -- shared by this
     * node's own onTick and by LegsFleeNode's (see its own docstring for
     * why FLEE delegates its actual movement here after publishing its
     * own computed retreat point as NAV_TARGET, rather than duplicating
     * this walk logic or driving movement itself): both ultimately reduce
     * to the same "walk toward whatever position NAV_TARGET holds right
     * now" problem, and NAVIGATE/FLEE are never active at the same time
     * (see LegsStateMachine's own edges), so there's no real reason for
     * two separate implementations of it.
     *
     * `alwaysSprint` sprints whenever actually walking forward, not only
     * while jumping (see below) -- LegsFleeNode passes true (per explicit
     * direction: fleeing should always sprint, speed matters more than
     * anything else while trying to put distance between the bot and
     * danger), LegsNavigateNode passes false, keeping ordinary navigation
     * unchanged (walking calmly rather than sprinting everywhere by
     * default, matching the old shared pipeline's own jump-triggered-only
     * sprint heuristic). Real vanilla sprint requires forward movement
     * (see setDirectionalKeys/Minecraft's own sprint key handling), so
     * this is safe to request unconditionally whenever `walking` is true
     * -- it simply has no effect on ticks where the bot isn't actually
     * moving forward at all.
     *
     * `avoidLiquid` is forwarded straight to PathTracker.maybeReplan/
     * Movements (see their own docstrings) -- LegsFleeNode passes true
     * per explicit direction ("when legs fleeing, avoid going into
     * water"), LegsNavigateNode passes false, keeping ordinary navigation
     * free to cross water when that's genuinely the shortest route.
     */
    static void walkTowardNavTarget(final TickContext ctx, final boolean alwaysSprint, final boolean avoidLiquid) {
        NavIntent.Target target = ctx.blackboard.get(NavIntent.NAV_TARGET);
        if (target == null) {
            // QUESTION: why do we set a MovementIntent here on each tick?
            ctx.input.setIntent(new MovementIntent());
            ctx.blackboard.put(WAYPOINT_COORDINATES, null);
            return;
        }
        Vec3 targetPosition = target.position();
        double stopDistanceValue = target.stopDistance();

        double selfX = ctx.player.getX();
        double selfY = ctx.player.getY();
        double selfZ = ctx.player.getZ();
        double targetX = targetPosition.x();
        double targetY = targetPosition.y();
        double targetZ = targetPosition.z();

        ctx.pathTracker.maybeReplan(ctx.level, ctx.player, selfX, selfY, selfZ, targetX, targetY, targetZ, stopDistanceValue, avoidLiquid);

        if (ctx.pathTracker.lastSearchFoundNoPath()) {
            // A real, completed search found no route at all -- stop
            // rather than blindly walking straight at the raw target, per
            // explicit direction: "walking in straight line towards the
            // target is undesired behavior, NO_PATH should block the
            // bot". The old straight-line fallback (see this method's own
            // git history) risked walking off ledges, into obstacles, or
            // into hazards exactly when pathfinding had already
            // determined there was no SAFE way to actually get there --
            // continuing to move toward an unreachable target on blind
            // faith was never actually useful, just risky. See
            // PathTracker.lastSearchFoundNoPath's own docstring for why
            // this is NOT the same as "no plan yet" (a fresh/first-tick
            // NAV_TARGET) or "already arrived" (a trivially empty
            // success) -- both of those still fall through to the normal
            // raw-target-aim path below, unaffected.
            ctx.input.setIntent(new MovementIntent());
            ctx.blackboard.put(WAYPOINT_COORDINATES, null);
            ctx.blackboard.put(WAYPOINT_TO_BREAK, Collections.emptyList());
            ctx.blackboard.put(WAYPOINT_DIG_STANCE, null);
            return;
        }

        Move waypoint = ctx.pathTracker.nextWaypoint(ctx.level, selfX, selfY, selfZ, ctx.player.onGround());
        ctx.blackboard.put(WAYPOINT_COORDINATES, waypoint != null ? new BlockPos(waypoint.x, waypoint.y, waypoint.z) : null);
        ctx.blackboard.put(WAYPOINT_TO_BREAK, waypoint != null ? waypoint.toBreak : Collections.emptyList());
        ctx.blackboard.put(WAYPOINT_DIG_STANCE, waypoint != null ? waypoint.digStance : null);

        // Aim at the next unreached waypoint's block center, or the raw
        // target if we have no plan yet (the very first tick after a
        // fresh NAV_TARGET, before maybeReplan's own first real search
        // has run) or have already arrived -- same as the old shared
        // pipeline. A genuine NO_PATH never reaches here at all (see the
        // early return above).
        double aimX = waypoint != null ? waypoint.x + 0.5 : targetX;
        double aimY = waypoint != null ? waypoint.y : targetY;
        double aimZ = waypoint != null ? waypoint.z + 0.5 : targetZ;

        double dx = aimX - selfX;
        double dz = aimZ - selfZ;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

        // Only the raw target (not a waypoint) should stop the bot when
        // close enough -- a waypoint just short of the goal must still be
        // walked through, not treated as "arrived".
        double distanceToStopAt = waypoint != null ? 0.0 : stopDistanceValue;

        MovementIntent intent = new MovementIntent();
        boolean walking = horizontalDistance > distanceToStopAt;
        boolean facingWaypoint = false;
        if (walking) {
            // World-space angle to the aim point (same atan2(-dx, dz)
            // convention as the old pipeline/BowShooter/BlockBreaker,
            // confirmed via decompiled Entity.calculateViewVector: yaw 0
            // = south/+z), minus the player's CURRENT yaw (read, never
            // written here -- see this class's own docstring) gives the
            // direction to walk RELATIVE to whichever way the player
            // happens to be facing right now. Necessary because
            // Minecraft's movement is inherently yaw-relative -- there is
            // no "walk toward absolute world direction X" primitive,
            // only forward/backward/left/right relative to facing.
            double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
            double relativeYaw = normalizeDegrees(targetYaw - ctx.player.getYRot());
            setDirectionalKeys(intent, relativeYaw);
            // Same +-67.5 degree cone setDirectionalKeys itself uses to
            // decide "forward" -- used below to gate a requiresJump move's
            // jump input on ACTUALLY facing the target, not just intending
            // to walk toward it (see requiresJumpSafeToFire's own comment
            // for why).
            facingWaypoint = relativeYaw > -67.5 && relativeYaw < 67.5;
        }

        // Same MAX_STEP_HEIGHT_TRIGGER/always-sprint reasoning as the old
        // shared pipeline (see its own extensive comment history in
        // MinebotMod's git log). Farmland check corrected from the old
        // pipeline's own version, per explicit direction: jumping itself
        // (e.g. over/from a farmland tile to reach some other block) is
        // fine -- it's specifically LANDING on farmland that tramples
        // real vanilla FarmBlock.fallOn mechanics, so this checks the
        // waypoint being jumped TO, not whichever block the bot currently
        // happens to be standing on.
        boolean landingOnFarmland = waypoint != null && WaypointClassifier.classify(ctx.level, waypoint.x, waypoint.y, waypoint.z).farmland();
        double dy = aimY - selfY;
        // dy > 0.1 alone misses a parkour-forward move that lands level
        // with (or even below) takeoff -- Move.requiresJump (see its own
        // docstring) is the real signal for "this move needs a jump
        // input regardless of relative height", added specifically
        // because a live parkour gap was being walked, not jumped, and
        // failed every single attempt as a result (no jump input queued
        // at all when dy was ~0).
        boolean waypointRequiresJump = waypoint != null && waypoint.requiresJump;
        // A requiresJump move additionally needs facingWaypoint -- live-
        // confirmed via debug logging that Legs' own yaw can be up to ~90
        // degrees off the real target angle for a tick or two right as the
        // path advances onto a new waypoint (Head hasn't snapped yaw to
        // the new target yet -- it ticks the SAME tick, right after Legs,
        // so by next tick yaw has caught up, but this tick it's still
        // stale). A plain step-up (dy > 0.1, requiresJump false) tolerates
        // that fine -- vanilla's own step-up assist covers a slightly
        // off-angle approach. A real jump over an actual gap does not: if
        // relativeYaw is outside the forward cone on the exact tick jump
        // fires, setDirectionalKeys produces a sideways-only intent (no
        // forward key at all -- see its own comment), so the jump leaves
        // with zero forward momentum in the intended direction and falls
        // straight into the gap it was supposed to clear. Confirmed live:
        // a jump fired with currentYaw=92.8 against a real target yaw of
        // 169.4 produced exactly intent[right=true] with no forward key,
        // and the bot fell all the way back down afterward. Simplest fix
        // is to just wait a tick (or however many) until yaw has actually
        // caught up before committing to the jump -- Head's per-tick snap
        // means this is never more than a tick or two of extra delay, far
        // cheaper than a failed jump that sends the bot back to the start
        // of the whole climb.
        // The waypoint's own toBreak blocks (if any) must be fully
        // cleared before jumping is even attempted -- reported live: a
        // jump-up move needing 2 leaf blocks broken through its headroom
        // kept firing intent.jump every single tick regardless of mining
        // progress, sending the bot repeatedly bouncing up and down.
        // That bounce swings the bot's own eye height across a full
        // block each cycle, which was enough to swing BlockBreaker's own
        // per-tick aim angle off the target block and onto its neighbor,
        // resetting vanilla's real destroy-progress tracking every time
        // it did (confirmed live via BlockBreaker's own hitResult debug
        // logging: realHitBlockPos flipped between the two toBreak
        // blocks in lockstep with the bot's eye height swinging through
        // the jump arc) -- a genuine chicken-and-egg deadlock: the jump
        // can't succeed until the leaves are mined through, but the
        // leaves can never finish breaking because the failed jump keeps
        // the bot bouncing, which keeps breaking the aim needed to mine
        // them. Holding off on the jump entirely until toBreak is
        // actually clear lets the bot stand still (real onGround,
        // constant eye height) long enough for HandsMineNode/
        // BlockBreaker to actually finish, then jump cleanly once there's
        // nothing left to break.
        boolean stillNeedsDigging = waypoint != null && !waypoint.toBreak.isEmpty()
                && waypoint.toBreak.stream().anyMatch(pos -> !ctx.level.getBlockState(pos).isAir());
        // Ticks spent holding forward+sprint toward this waypoint, used to
        // gate a requiresJump move's jump -- see SPRINT_RUNUP_TICKS' own
        // docstring for the live "fires the jump the instant walking
        // starts, falls short of a real running jump every time" bug this
        // exists to fix. Deliberately NOT also gated on real measured
        // horizontal speed (an earlier version of this fix required
        // getDeltaMovement() to reach a minimum threshold too) -- reported
        // live: the takeoff stance for the exact jump this was built for
        // has effectively zero run-up room before the wall (real speed
        // measured ~0 the entire approach, confirmed via this same
        // diagnostic), so a real-speed requirement can never be satisfied
        // there and permanently blocked the jump from ever firing at all
        // -- worse than the original bug (that one at least attempted the
        // jump). Per explicit direction (a live by-hand test confirming
        // this jump IS makeable, but only while sprinting): what actually
        // matters is holding the sprint key for real vanilla's own
        // gradual sprint-speed ramp-up to take effect, not distance
        // physically covered beforehand -- ticks-held is a correct proxy
        // for that on its own.
        boolean buildingRunup = walking && facingWaypoint && intent.forward && ctx.player.onGround();
        int runupTicks = buildingRunup
            ? (ctx.blackboard.get(SPRINT_RUNUP_TICKS) == null ? 0 : ctx.blackboard.get(SPRINT_RUNUP_TICKS)) + 1
            : 0;
        ctx.blackboard.put(SPRINT_RUNUP_TICKS, runupTicks);
        boolean hasRunup = runupTicks >= SPRINT_RUNUP_TICKS_REQUIRED;
        boolean requiresJumpSafeToFire = (!waypointRequiresJump || (facingWaypoint && hasRunup)) && !stillNeedsDigging;
        boolean wantsToJump = walking && (dy > 0.1 || waypointRequiresJump) && !landingOnFarmland && requiresJumpSafeToFire;
        if (wantsToJump) {
            intent.jump = true;
            intent.sprint = true;
        }
        if (walking && alwaysSprint) {
            intent.sprint = true;
        }
        // TEMPORARY DEBUG: sprint held for the entire walk, not just once
        // a jump is about to fire -- per explicit direction, to check
        // whether SPRINT_RUNUP_TICKS' own tick-counting is actually
        // coinciding with a REAL held sprint the whole run-up (previously
        // intent.sprint only went true on the same tick wantsToJump did,
        // so the counter could reach SPRINT_RUNUP_TICKS_REQUIRED while the
        // bot was still just walking, never actually sprinting, during
        // the run-up itself). Remove once confirmed whether this is what
        // was missing.
        if (walking) {
            intent.sprint = true;
        }

        // Temporary diagnostic, extended to cover the new run-up gating --
        // still verifying the SPRINT_RUNUP_TICKS fix live against the
        // exact jump it was built for. Throttled to every 10 ticks (0.5s).
        // Intended to be removed once confirmed working; not gated behind
        // isDebugEnabled() since this client's log4j config filters debug
        // output entirely (see BlockBreaker's own per-tick diagnostic for
        // the same reasoning).
        if (walking && waypoint != null && ctx.player.tickCount % 10 == 0) {
            MinebotMod.LOGGER.info(
                "navigate[diag]: self=({}, {}, {}) waypoint=({}, {}, {}) requiresJump={} dy={} relativeYawIntent=[fwd={} back={} left={} right={}] facingWaypoint={} runupTicks={} hasRunup={} jump={} onGround={}",
                selfX, selfY, selfZ, waypoint.x, waypoint.y, waypoint.z, waypointRequiresJump, dy,
                intent.forward, intent.backward, intent.left, intent.right, facingWaypoint,
                runupTicks, hasRunup, wantsToJump, ctx.player.onGround()
            );
        }
        ctx.input.setIntent(intent);
    }

    /**
     * Sets forward/backward/left/right on `intent` for `relativeYaw`
     * degrees (0 = straight ahead, +90 = the player's own right,
     * -90 = left, +-180 = straight behind -- confirmed live, see
     * setDirectionalKeys' own comment for why this isn't the sign one
     * might expect from this codebase's usual facing-yaw convention) --
     * an 8-way (octant) approximation of a continuous direction, the same
     * granularity real discrete WASD keys are limited to (a real player
     * can't press "43% forward, 57% left" either, only combinations of
     * whole keys). Combines two keys for a diagonal (e.g. forward+left)
     * exactly like a real player would to walk at an angle.
     */
    private static void setDirectionalKeys(final MovementIntent intent, final double relativeYaw) {
        // Forward/backward: within 67.5 degrees of straight ahead/behind.
        if (relativeYaw > -67.5 && relativeYaw < 67.5) {
            intent.forward = true;
        } else if (relativeYaw > 112.5 || relativeYaw < -112.5) {
            intent.backward = true;
        }
        // Left/right: within 67.5 degrees of straight left/right.
        // Confirmed live (a first attempt using the opposite sign made
        // the bot strafe right when the target was to its left) --
        // positive relativeYaw is toward the player's own RIGHT, not
        // left, despite the seemingly-analogous atan2(-dx, dz) facing
        // convention used elsewhere in this codebase; movement's own
        // left/right input axis evidently isn't mirrored the same way.
        if (relativeYaw < -22.5 && relativeYaw > -157.5) {
            intent.left = true;
        } else if (relativeYaw > 22.5 && relativeYaw < 157.5) {
            intent.right = true;
        }
    }

    /** Wraps `degrees` into (-180, 180], the same range Entity.getYRot()/setYRot() themselves use. */
    private static double normalizeDegrees(double degrees) {
        degrees %= 360.0;
        if (degrees <= -180.0) {
            degrees += 360.0;
        } else if (degrees > 180.0) {
            degrees -= 360.0;
        }
        return degrees;
    }
}
