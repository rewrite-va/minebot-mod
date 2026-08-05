package minebot.mod.statemachine;

import java.util.function.Predicate;

/**
 * One directed transition in a StateMachine's graph: from `from` to `to`,
 * taken the first tick `condition` is true while `from` is the current
 * state. Declared as plain Java data (a List<Edge<S>> built in a config
 * method), not a string-expression DSL -- see STATE_MACHINE.md's "Why
 * edges are data, not a string-expression DSL".
 */
public record Edge<S extends Enum<S>>(S from, S to, Predicate<TickContext> condition) {
}
