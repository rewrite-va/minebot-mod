package minebot.mod.statemachine.head;

/**
 * Where the bot is looking and why -- one of the peer axes described in
 * STATE_MACHINE.md (no hierarchy over PlayerIntention/Legs/Hands). Owns yaw/
 * pitch exclusively; Legs deliberately never writes either (see
 * LegsNavigateNode's own docstring) -- this is the other half of that
 * split actually being implemented.
 *
 * NAVIGATE aims at whatever point Legs is currently walking toward (its
 * published AIM_POINT on the Blackboard -- see LegsNavigateNode), the
 * same "look at the next waypoint while walking" behavior the old shared
 * pipeline had baked in.
 *
 * AIM_AT_TARGET aims straight at CombatEngagement's live target (eye
 * level or arc-lifted for a bow -- see HeadAimAtTargetNode's own
 * docstring), shared by PlayerIntention:KILL and PlayerIntention:DEFEND (whichever's
 * currently fighting something) -- takes priority over NAVIGATE (checked
 * first in HeadStateMachine's own edge table) so combat aim always wins
 * even on a tick Legs also happens to be walking into melee range,
 * matching the old shared pipeline's own "combat aim always overrides
 * waypoint aim" rule (tickAttack's own aimAtEntity call used to run after
 * resolveMovementIntent's, so it always won last -- same effective
 * priority, just expressed as edge order instead of call order now).
 * Active regardless of whether Legs is NAVIGATE or IDLE -- the bot needs
 * to keep facing its target even while standing still swinging.
 *
 * FLEE takes priority over even AIM_AT_TARGET (checked first in
 * HeadStateMachine's own edge table -- unlike AIM_AT_TARGET vs. NAVIGATE,
 * this is NOT expressed as one node winning over another via priority; it
 * is its own genuine state, entered/exited purely by mirroring Legs:FLEE,
 * with its own reason for existing) -- looks toward wherever Legs:FLEE is
 * currently walking (the SAME channel NAVIGATE already reads -- see
 * HeadStateMachine's own docstring for why FLEE is wired to literally the
 * same node instance as NAVIGATE) rather than staring at the live combat
 * target, per explicit direction: "walking backwards is slower, in combat
 * we need to minimize slow navigation, the bot needs to look where
 * navigation is trying to go only when legs:flee". A bot fleeing while
 * still facing the threat it's fleeing (AIM_AT_TARGET's usual behavior
 * whenever a live target exists, which it always does while FLEE is
 * active -- see LegsFleeNode's own docstring) would be backing away or
 * strafing rather than sprinting forward, a real vanilla movement-speed
 * cost worth avoiding specifically while already low on health and
 * trying to put distance between itself and danger.
 *
 * MINE aims at whatever block HandsMineNode is actively committed to
 * breaking (HandsMineNode.CURRENT_MINING_TARGET), including the near-
 * vertical-safe offset and same-tick yRotO/xRotO snap the real per-frame
 * crosshair raycast needs to actually land on the target (see
 * HeadMineNode's own docstring for the live bug this fixed: mining used to
 * set yaw/pitch directly from inside Hands' own tick, racing against this
 * class's own NAVIGATE pitch-pinning with no coordination between them --
 * the outcome depended purely on incidental tick-call order and which of
 * BlockBreaker.tryBreak's several early-return branches fired that tick,
 * and reproduced live as the bot's held keyAttack locking onto a block
 * several positions away from its real intended target, forever). Takes
 * priority over NAVIGATE (checked after AIM_AT_TARGET/FLEE but before
 * NAVIGATE in HeadStateMachine's own edge table) since a path obstacle
 * being mined always needs the bot actually looking at it, not merely
 * toward the waypoint beyond it -- but yields to AIM_AT_TARGET/FLEE the
 * same way NAVIGATE does, since a live combat threat or an active flee
 * always outranks clearing a path obstacle (see HandsStateMachine's own
 * blockedByObstacle predicate, which excludes fleeing for the identical
 * reason on the Hands side).
 *
 * More states (looking at a nearby player while idle, etc.) get added as
 * those behaviors are ported in later, per STATE_MACHINE.md's
 * "Implementation order" (built node by node, not all at once).
 */
public enum HeadState {
    IDLE,
    NAVIGATE,
    AIM_AT_TARGET,
    FLEE,
    MINE
}
