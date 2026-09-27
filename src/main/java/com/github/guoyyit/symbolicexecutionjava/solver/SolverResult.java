package com.github.guoyyit.symbolicexecutionjava.solver;

import java.util.Map;

/** Result of an integer linear-arithmetic satisfiability check. */
public record SolverResult(boolean satisfiable, Map<String, Long> model) {

    public static SolverResult unsat() {
        return new SolverResult(false, Map.of());
    }

    public static SolverResult sat(Map<String, Long> model) {
        return new SolverResult(true, Map.copyOf(model));
    }
}
