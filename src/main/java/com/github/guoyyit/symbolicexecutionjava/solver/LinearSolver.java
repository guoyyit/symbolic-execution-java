package com.github.guoyyit.symbolicexecutionjava.solver;

import com.github.guoyyit.symbolicexecutionjava.model.symbolic.LinearConstraint;
import com.github.guoyyit.symbolicexecutionjava.model.symbolic.PathCondition;

import java.util.List;

/**
 * Public satisfiability checker for integer linear arithmetic.
 *
 * <p>Delegates to {@link IntervalSolver} (interval propagation plus bounded
 * chronological backtracking). Deterministic for identical path conditions,
 * variable order and seed.</p>
 */
public class LinearSolver {

    private final IntervalSolver delegate;
    private final long seed;
    private final long lowerBound;
    private final long upperBound;

    public LinearSolver(long seed, long lowerBound, long upperBound) {
        this.seed = seed;
        this.lowerBound = lowerBound;
        this.upperBound = upperBound;
        this.delegate = new IntervalSolver(lowerBound, upperBound, seed);
    }

    /** Check satisfiability; all variables that may occur must be supplied. */
    public SolverResult check(List<String> variables, PathCondition condition) {
        return delegate.solve(variables, condition.constraints());
    }

    public SolverResult checkConstraints(List<String> variables,
                                        List<LinearConstraint> constraints) {
        return delegate.solve(variables, constraints);
    }

    public long seed() {
        return seed;
    }

    public long lowerBound() {
        return lowerBound;
    }

    public long upperBound() {
        return upperBound;
    }
}
