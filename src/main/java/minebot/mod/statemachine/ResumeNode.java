package minebot.mod.statemachine;

/**
 * A generic, reusable "go back to whatever was running before" node --
 * intended as the shared destination for any state's own "I'm finished"
 * edge (e.g. `new Edge<>(SELF_HEAL, RESUME, ctx -> node.isFinished())`,
 * see StateNode.isFinished's own docstring). Does nothing itself on
 * onTick (it's a pure transition waypoint, entered and immediately left
 * again the same or next tick via its own outgoing edges) -- the actual
 * "go back to X" logic is a normal Edge per real destination state,
 * declared once per StateMachine alongside its other edges, each
 * checking `machine.stateBeforeCurrent() == X` (see StateMachine.
 * stateBeforeCurrent's own docstring, and the conversation that produced
 * this class for why Edge.to stays static rather than becoming
 * dynamically computed).
 *
 * One instance per StateMachine that wants this pattern -- needs a
 * reference to its OWN owning StateMachine<S>, but can't take one via
 * its constructor: a StateMachine<S> needs its complete node map (this
 * node included) before IT can be constructed, so the reference has to
 * be bound afterward instead (bindTo, called by the owning
 * `*StateMachine.create()` factory immediately after constructing the
 * real StateMachine -- see e.g. GeneralStateMachine.create()).
 */
public final class ResumeNode<S extends Enum<S>> implements StateNode<S> {
    private StateMachine<S> ownMachine;

    /** Must be called once, immediately after constructing the StateMachine<S> this node belongs to, before that machine's first tick() -- see this class's own docstring. */
    public void bindTo(final StateMachine<S> ownMachine) {
        this.ownMachine = ownMachine;
    }

    @Override
    public void onTick(final TickContext ctx) {
    }

    /** For the edges declared per real destination state -- see this class's own docstring. */
    public S stateToResumeInto() {
        return ownMachine.stateBeforeCurrent();
    }
}
