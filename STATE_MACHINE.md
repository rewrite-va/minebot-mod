# minebot-mod state machine architecture

Design doc for replacing the mod's current single flat `ControlState.Mode`
enum (and the several independent, uncoordinated per-tick tickers --
`FoodEater`, `BlockBreaker`, `BowShooter`, `RespawnHandler`) with multiple
independent, peer state machines, one per "axis" of the bot's behavior,
plus a small shared-resource arbiter for real keybind conflicts
(`Options.keyUse`/`keyAttack`) between them.

This file exists so a fresh agent picking this up later (or a different
session of the same agent) has the full design and its reasoning without
needing to re-derive it or re-read the whole conversation history that
produced it. **Read this file before touching anything described here.**
Keep it up to date as the real implementation diverges from the plan --
a stale design doc is worse than none.

## Why this exists -- the bug that motivated it

See `FINDINGS.md`'s "Bow-drawing never actually fired an arrow" section
for the full story. Short version: `BowShooter` (draws/fires a real bow)
and `FoodEater` (auto-eats at low health) both need to hold the real
`Options.keyUse` keybind down for their own interaction to work (vanilla's
own `Minecraft.handleKeybinds()` force-releases `isUsingItem()` every tick
`keyUse.isDown()` is false -- a real, load-bearing vanilla mechanism, not
a bug in this mod). Neither class knew the other existed. `FoodEater`
called `Options.keyUse.setDown(false)` *unconditionally* every tick it
had nothing to eat, silently clobbering a bow draw `BowShooter` had
started the same tick. This wasn't a coding mistake in either class --
each was correct in isolation -- it's a structural gap: nothing in the
mod tracks "who currently owns this shared piece of real input state" at
all. `ControlState.Mode` doesn't help here either: `FoodEater`/
`BlockBreaker`/`BowShooter`/`RespawnHandler` all run every tick
*regardless* of `ControlState.mode`, by design (a bot should be able to
eat while mining, or while idle, or while fighting) -- they were never
inside that state machine to begin with.

## Core idea: independent peer state machines, one per axis, no hierarchy

Instead of one flat `Mode` enum trying to describe the bot's entire
situation in one label (which can't express "fleeing AND eating AND
almost dead" without either a combinatorial explosion of modes or
silently dropping one of those facts), the bot's behavior is modeled as
several **independent state machines**, each owning one axis of "what is
the bot currently doing":

- **Legs SM** -- what are the feet doing (idle, walking to a goal,
  following, fleeing, kiting, ...). Roughly today's `ControlState.Mode`
  plus the retreat/kiting logic currently jammed into `MinebotMod.
  tickAttack`.
- **Hands SM** -- what is the main/off hand doing (idle, eating, drawing
  a bow, mining/breaking a block, ...). Currently split across
  `FoodEater`/`BowShooter`/`BlockBreaker` with no shared coordination.
- **Head SM** -- where is the bot looking and why (idle/no goal, aiming
  at a combat target, looking at a nearby player, aiming a raycast for
  `!save chest`, ...). Currently the ad-hoc yaw/pitch-precedence logic in
  `MinebotMod.onClientTick`/`resolveMovementIntent` ("whichever thing set
  yaw/pitch last this tick wins").
- **General SM** -- the bot's overall behavioral intent (IDLE, COMBAT,
  FLEEING, FARMING, BUILDING, DEAD, ...), driven mostly (but not
  exclusively -- see below) by backend chat commands. Roughly what
  `ControlState.Mode` conflates together with Legs today.

**Explicitly no hierarchy.** General is not a "parent" or "controller"
of the other three -- it is a peer, exactly like Legs/Hands/Head. The
fact that chat commands happen to update General's state most directly
is incidental, not structural: any SM can react to a chat command
directly if that makes sense for it (e.g. Head reacting to `!look
<player>` doesn't need to go through General at all), and any SM's edge
conditions can read *any other SM's* current state, including General's,
symmetrically. There is no SM whose state other SMs cannot read, and no
SM that is disallowed from reading another's state. Avoid ever writing
code that assumes General "commands" the others -- if a design decision
seems to need that, that's a sign the axis split needs rethinking, not a
reason to special-case General.

More SMs than these four are expected over time (this is a starting set,
not a fixed count) -- e.g. a dedicated Inventory/Hotbar SM if hotbar
selection logic grows more complex, or a Voice/Chat SM once autonomous
chat exists. Adding a new SM should never require changes to existing
SMs (see the blackboard model below) -- that's the whole point of the
peer design.

## The blackboard: how SMs read each other's state

Every SM publishes its current state to a single shared, globally
readable object every tick (a `Blackboard`, new class) -- a plain
`Map<StateMachineId, Enum>`-shaped read model (see "Concrete Java shape"
below for the actual interface). Any edge condition, in any SM, can read
any entry. This is what lets `legs:kite`'s condition read `hands ==
DRAWING_BOW` without Legs needing a reference to the Hands SM object
itself, a dependency, or an import cycle -- it just reads a value out of
the shared blackboard by key, the same way it reads raw signals like
`player.getHealth()`.

The blackboard is written once per tick, after all SMs have resolved
their transitions for that tick (see "Tick order" below) -- so a
condition reading another SM's state always sees that SM's value **as of
the end of the previous tick**, never a half-updated value from partway
through the current tick. This one-tick lag is deliberate and matches an
existing, working pattern already in this codebase: `ControlState.
stopDistance`/`attackRetreating` already have exactly this same
one-tick-lag shape between `MinebotMod.tickAttack` (which computes them)
and `resolveMovementIntent` (which consumes them, having run *earlier*
the same tick) -- see `ControlState.attackRetreating`'s own doc comment.
This isn't a new kind of complexity being introduced, just formalizing a
pattern this codebase already relies on.

## Concrete Java shape

```java
// One per axis. Keep these as plain enums -- no need for anything fancier.
enum LegsState { IDLE, GOTO, FOLLOW, GIVE, FLEEING, KITING, DEAD }
enum HandsState { IDLE, EATING, DRAWING_BOW, MINING }
enum HeadState { IDLE, AIMING_AT_TARGET, LOOKING_AT_PLAYER, RAYCASTING }
enum GeneralState { IDLE, COMBAT, FLEEING, FARMING, BUILDING, DEAD }

// The read side every edge condition and every other piece of code uses.
interface Blackboard {
    <S extends Enum<S>> S get(StateMachine<S> machine);
}

// A node: owns real per-tick behavior via onEnter/onTick/onExit, per the
// explicit decision to have states be more than labels (see "Why nodes
// own behavior" below).
interface StateNode<S extends Enum<S>> {
    default void onEnter(TickContext ctx) {}
    /** Called every tick this node is the active state. */
    void onTick(TickContext ctx);
    default void onExit(TickContext ctx) {}
}

// An edge: declared as data (a List<Edge<S>> built in a plain Java
// config method -- see "Why data, not a DSL" below), but the condition
// itself is just a Predicate, not a parsed expression string.
record Edge<S extends Enum<S>>(S from, S to, Predicate<TickContext> condition) {}

// The engine. One instance per axis (Legs/Hands/Head/General each get
// their own StateMachine instance, all registered on the same
// Blackboard).
final class StateMachine<S extends Enum<S>> {
    // ticks the current node, evaluates every outgoing edge from the
    // current state in declaration order, takes the first whose
    // condition is true (calling onExit/onEnter as needed), publishes
    // the (possibly new) current state to the Blackboard.
    void tick(TickContext ctx, Blackboard blackboard) { ... }
    S currentState() { ... }
}
```

`TickContext` is the per-tick bag of everything a condition or a node's
`onTick` might need to read: `LocalPlayer`, `ClientLevel`, the
`Blackboard` itself (so a condition can read other SMs' states), and
whatever raw signals don't belong on the blackboard (health, nearby
entity lookups, `ControlState`-equivalent command-derived fields).

### Why nodes own behavior, not just labels

Explicit decision (see this file's own git history / the conversation
that produced it): a node's `onTick` calls the real per-tick logic
directly (e.g. `HandsState.DRAWING_BOW`'s node calls what
`BowShooter.tick()` does today), rather than the SM being a pure label
that some external tick method still separately checks. This means
porting `BowShooter`/`FoodEater`/`BlockBreaker`/`tickAttack`/
`tickCollect`'s existing logic into node classes as this gets
implemented, not just wrapping them. This is a bigger lift than a
label-only coordination layer, but keeps the *behavior* and the *state
it's contingent on* in the same place instead of split across a node
enum in one file and a big if/else tick method in another -- exactly the
split that made the `keyUse` conflict hard to see in the first place
(nothing in `FoodEater.java` or `BowShooter.java` alone suggested they
might run the same tick).

### Why edges are data, not a string-expression DSL

Also an explicit decision: the edge table is built as real Java (a
`List<Edge<S>>` constructed in a plain method), not an external JSON/YAML
file with condition strings parsed at runtime. The graph is still
"data" in the sense that it's an inspectable/iterable list (useful for
e.g. a future `!debug state_machine` dump), but conditions are plain
`Predicate<TickContext>` lambdas -- no expression parser to build, and
no risk of a condition string drifting out of sync with what fields
actually exist on `TickContext`/the `Blackboard` (a real risk with
string-based conditions, since nothing would catch `"hp < 0.5"` silently
never matching a field that was actually renamed `health`).

## The shared-resource arbiter (`keyUse`/`keyAttack`)

Separate from the four behavior SMs above -- this exists specifically to
prevent a repeat of the bug in "Why this exists". One arbiter instance
per real shared resource (`keyUse`, `keyAttack` today; anything else
that ever needs `Options.<mapping>.setDown(true)` held across multiple
ticks in the future).

```java
interface SharedResourceOwner {
    /** Called by the arbiter if this owner still holds the resource when
     * something else acquires it, OR when MinebotMod's own top-level
     * safety net (see below) detects the resource is still marked held
     * by an owner whose node is no longer active -- the "a node forgot
     * to release in its own onExit" case. Named onOwnershipLost rather
     * than onExit to make clear this is specifically the abnormal/
     * fallback path, not the normal release flow (see below). */
    void onOwnershipLost();
}

final class SharedResourceArbiter {
    private SharedResourceOwner currentOwner;

    /** Normal path: a node's onEnter acquires; the SAME node's onExit
     * releases explicitly. No preemption -- see "Ownership release
     * model" below for why. */
    boolean tryAcquire(SharedResourceOwner requester) { ... }
    void release(SharedResourceOwner requester) { ... }

    /** The safety net: called once per tick (from MinebotMod.onClientTick,
     * after all four SMs have ticked). If the current owner's node is no
     * longer the active state in whichever SM registered it, force-
     * releases and calls onOwnershipLost() on it -- this is the actual
     * fix for the exact bug that motivated this whole document: even if
     * some future node forgets its own explicit release, the resource
     * can never stay stuck held by a state that isn't active anymore. */
    void reapAbandonedOwnership() { ... }
}
```

### Ownership release model: explicit release in `onExit`, plus a `finally`-style safety net

Explicit decision: the *normal* release path is the owning node's own
`onExit` releasing the resource when its SM transitions away from that
state -- not the arbiter preemptively revoking it the instant a
higher-priority request shows up. A higher-priority need (e.g.
self-preservation eating while a bow draw is in progress) works by
driving the *current* node to transition out via its own edge
conditions (e.g. `HandsState.DRAWING_BOW -> HandsState.IDLE` has an edge
condition that includes "a higher-priority hand-user wants in"), which
then naturally calls that node's `onExit`, which releases the resource
-- not the arbiter reaching in and interrupting mid-action. This keeps
"a node can be interrupted at literally any point in its own logic" from
becoming an invariant every node has to defend against; nodes only ever
get torn down at a transition boundary they can see coming via their own
edges.

**But**: relying purely on "every node's `onExit` remembers to release"
is exactly the same class of mistake that caused the original bug (a
class silently assuming it's the only thing touching a resource). So the
arbiter also has `reapAbandonedOwnership()` -- a `finally()`-style safety
net (name TBD, but the intent is exactly Java's `try/finally`: whatever
happens in the normal path, this always runs and cleans up) called once
per tick by `MinebotMod.onClientTick`, independent of any individual
node's own correctness, that force-releases and notifies
`onOwnershipLost()` if the resource is still marked held by a node whose
SM has already moved on to a different state. This is a backstop, not
the primary mechanism -- if this ever actually fires in practice, that's
a real bug in some node's `onExit` worth fixing, not something to rely
on routinely.

### Priority

A fixed, hardcoded priority order per resource (not user-configurable,
not derived from anything dynamic) -- e.g. for `keyUse`:
self-preservation eating (health critically low) > eating (health low,
not critical) > bow-drawing > (nothing else currently needs `keyUse`).
Exact ordering is a product decision to make when Hands SM is actually
implemented (see "Implementation order" below), not fixed by this
document -- the important structural point is that priority is a single
static list checked by whichever node is trying to acquire, not
something negotiated dynamically between nodes.

## Tick order

Once this is fully implemented, `MinebotMod.onClientTick` becomes
roughly:

1. Read raw signals for this tick into a fresh `TickContext` (health,
   nearby entities, whatever `ControlState`-equivalent fields the
   backend last set).
2. Tick each SM once (order between the four doesn't matter *for
   correctness* since they all read the *previous* tick's blackboard
   values -- see "The blackboard" above -- but keep a fixed, documented
   order for reproducibility/debuggability; suggested: General, Legs,
   Hands, Head).
3. Each SM publishes its (possibly just-transitioned) current state to
   the `Blackboard`.
4. `SharedResourceArbiter.reapAbandonedOwnership()` for each arbiter.
5. Whatever's left that isn't really state-machine-shaped at all (the
   position/entity/inventory/health broadcasting to the backend) --
   these are one-shot side effects, not states, and don't need to move
   into this system at all.

## Implementation order

Per explicit decision: **write this whole document first (done, this
file), then implement incrementally, one SM at a time, against a
codebase that keeps working throughout** -- never a big-bang rewrite.
Suggested order, each step independently shippable/revertable/testable
live before starting the next:

1. **`SharedResourceArbiter` + the Hands SM first.** This is the one
   with the actual, real, already-twice-diagnosed bug history
   (`FoodEater` vs `BowShooter` over `keyUse`) -- porting `FoodEater`/
   `BowShooter`/`BlockBreaker`'s existing logic into `HandsState` nodes
   built on the arbiter directly replaces the ad-hoc fix currently in
   place (`FoodEater.holdingUseKey`, added as a point-fix -- see
   `FINDINGS.md`) with the real mechanism this document describes, and
   is the axis most likely to have a similar bug again if left
   uncoordinated (e.g. once shields/blocking or eating-while-mining ever
   need to coexist).
2. **Legs SM**, folding in today's `ControlState.Mode` plus the
   kiting/retreat logic currently embedded in `MinebotMod.tickAttack`.
3. **Head SM**, folding in the yaw/pitch-precedence logic currently
   spread across `resolveMovementIntent`/`BlockBreaker.aimAt`/
   `BowShooter.aimAt`/`NearbyPlayerLookAt`.
4. **General SM** last, once there's more than one real "overall
   behavior" concept worth distinguishing beyond what Legs already
   captures (today's `ControlState.Mode` largely already plays General's
   role for lack of a better place to put it -- pulling it out into its
   own true peer SM is more valuable once FARMING/BUILDING/DEFENDING
   etc. are real, not just IDLE/COMBAT wrapping what Legs already knows).

Each step should leave `!attack`/`!goto`/`!collect`/etc. working
end-to-end via live testing (see `AGENTS.md`'s build/deploy/verify
routine) before moving to the next step -- do not let two control
paradigms (old direct-tick-methods and new SM engine) silently diverge
in behavior for whatever hasn't been migrated yet.

## Open questions / not yet decided

- Exact `keyUse` priority ordering between eating-tiers and bow-drawing
  (see "Priority" above) -- decide when Hands SM is actually built.
  - Answered: none yet -- worth revisiting once "flee" (Legs) and "eat"
    (Hands) both need to run during the same fight, per the original
    kiting-while-eating scenario that prompted this whole document. That
    scenario itself needs NO special-casing once Legs and Hands are
    truly independent SMs (fleeing is a Legs state, eating is a Hands
    state, they don't conflict) -- it was only ever hard to express
    under the old single-flat-`Mode` model.
- Whether `TickContext` should be a single shared mutable object rebuilt
  once per tick (cheap, matches this codebase's existing "plain mutable
  fields, no synchronization needed, only touched from the tick thread"
  style -- see `ControlState`'s own doc comment) or an immutable
  snapshot -- lean mutable/shared for consistency with the rest of the
  codebase, but not fixed yet.
- Naming for `onOwnershipLost` -- placeholder name, open to something
  clearer once it's actually implemented and its real call sites are
  visible.
