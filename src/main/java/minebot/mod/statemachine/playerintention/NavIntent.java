package minebot.mod.statemachine.playerintention;

import minebot.mod.statemachine.BlackboardKey;
import net.minecraft.world.phys.Vec3;

/**
 * The shared "something wants Legs to walk somewhere right now" channel
 * -- published by whichever PlayerIntention node (FOLLOW walking toward
 * a followed entity, CombatEngagement for KILL/DEFEND) or Legs node
 * itself (GO_TO_DEATH_POSITION walking back to where the bot died,
 * PICKUP_ITEMS walking to whichever dropped item is nearest, FLEE's own
 * continuous retreat point) currently has a reason to move the bot, read
 * by Legs:NAVIGATE and Head:NAVIGATE without either needing to know WHY
 * -- same "the publisher only ever publishes facts, Legs/Head are pure
 * consumers" split PlayerIntentionFollowNode originally established
 * alone; this generalizes it now that many publishers need the same
 * shape (previously these keys lived on PlayerIntentionFollowNode
 * itself, back when FOLLOW was the only thing that ever published them).
 * GO_TO_DEATH_POSITION/PICKUP_ITEMS moved from PlayerIntention to Legs
 * (see PlayerIntentionState's own docstring for why) without changing
 * how either uses this channel at all -- publishing through NAV_TARGET/
 * NAV_ARRIVED was never PlayerIntention-specific to begin with. Renamed
 * from GeneralNav/NAV_WITHIN_RANGE
 * (see git history) once "within range" stopped being accurate for
 * every publisher -- see CombatEngagement's own docstring for the
 * kiting case (retreating away from a target) that motivated the
 * NAV_ARRIVED name: "arrived at the current nav target" covers both
 * "close enough to a followed player" and "reached a retreat point",
 * where "within range" only ever meant the former.
 *
 * NAV_TARGET bundles the position AND the arrival tolerance into one
 * record (Target) rather than two independent keys -- confirmed live
 * that a single fixed shared stop distance breaks the moment two
 * publishers need genuinely different tolerances: FOLLOW wants ~2 blocks
 * (close enough to a followed player without crowding them),
 * PICKUP_ITEMS needs to walk essentially onto the item itself (vanilla's
 * real pickup AABB is well under a block -- an earlier version that
 * reused FOLLOW's 2.0 made Legs stop and go idle a full 2 blocks short
 * of items that were then never actually picked up, despite looking
 * "right next to the bot" to a human observer). Bundling the two into
 * one key means a publisher can't set a target without also setting its
 * tolerance (no separate key to forget, no silent fallback-to-a-
 * possibly-wrong-default). defaultStopDistance() is FOLLOW's own value,
 * exposed here since it's the original/common case, not because every
 * publisher has to use it.
 *
 * NAV_ARRIVED is a separate key -- unlike Target's two fields, it's
 * a DERIVED fact (distance <= target.stopDistance()), not something a
 * publisher decides directly, so Legs/Head can read "should I actually
 * be moving/looking right now" without redoing that distance math
 * themselves. Defaults meaning "close enough, don't walk" (true) when
 * nothing is being published at all -- see each publisher's own onExit
 * for why it's set that way rather than left stale.
 */
public final class NavIntent {
    private NavIntent() {
    }

    /** FOLLOW's own arrival tolerance -- close enough to a followed player without crowding them. Not a shared default for every publisher; see this class's own docstring. */
    private static final double DEFAULT_STOP_DISTANCE = 2.0;

    public static double defaultStopDistance() {
        return DEFAULT_STOP_DISTANCE;
    }

    /** A live position PlayerIntention currently wants Legs to walk toward, plus how close counts as "arrived" there -- see this class's own docstring for why these two are bundled into one record/key instead of independent keys. */
    public record Target(Vec3 position, double stopDistance) {
    }

    /** The current Target, or null if nothing to walk toward right now. */
    public static final BlackboardKey<Target> NAV_TARGET = new BlackboardKey<>("NAV_TARGET");

    /** True if within NAV_TARGET's own stopDistance() of its position (or nothing to walk toward) -- Legs/Head use this to know whether to actually be navigating/looking right now. */
    public static final BlackboardKey<Boolean> NAV_ARRIVED = new BlackboardKey<>("NAV_ARRIVED");
}
