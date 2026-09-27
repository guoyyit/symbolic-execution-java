package com.github.guoyyit.symbolicexecutionjava.solver;

import com.github.guoyyit.symbolicexecutionjava.model.symbolic.LinearConstraint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Integer linear arithmetic solver over a finite declared domain.
 *
 * <p>It combines (1) interval propagation over the current variable bounds to a
 * fixed point, pruning infeasible conjunctions early, and (2) chronological
 * backtracking that assigns variables one at a time. Because the search domain
 * is finite ([lowerBound, upperBound]), the result is sound and complete within
 * that domain: it returns UNSAT only when no in-domain integer assignment
 * exists.</p>
 *
 * <p>Determinism: variable and value enumeration order is fully determined by
 * the constraint insertion order and the seed. Identical inputs and seed always
 * produce identical models.</p>
 */
public class IntervalSolver {

    private final long lowerBound;
    private final long upperBound;
    private final long seed;

    public IntervalSolver(long lowerBound, long upperBound, long seed) {
        this.lowerBound = lowerBound;
        this.upperBound = upperBound;
        this.seed = seed;
    }

    /**
     * @param variables all variables that must receive a value (e.g. program inputs)
     * @param constraints conjunction of linear constraints
     */
    public SolverResult solve(List<String> variables, List<LinearConstraint> constraints) {
        // Reject/accept ground constraints containing no variables (e.g. 0 == 0).
        for (LinearConstraint c : constraints) {
            if (c.coefficients().isEmpty() && !groundHolds(0L, c)) {
                return SolverResult.unsat();
            }
        }
        List<String> vars = new ArrayList<>(variables);
        // Add variables that only appear in constraints (defensive: store should list all).
        for (LinearConstraint c : constraints) {
            for (String name : c.coefficients().keySet()) {
                if (!vars.contains(name)) {
                    vars.add(name);
                }
            }
        }

        Map<String, long[]> bounds = new LinkedHashMap<>();
        for (String v : vars) {
            bounds.put(v, new long[] {lowerBound, upperBound});
        }

        if (!propagate(constraints, bounds)) {
            return SolverResult.unsat();
        }

        Map<String, Long> assignment = new LinkedHashMap<>();
        boolean ok = backtrack(vars, 0, assignment, constraints, bounds);
        if (!ok) {
            return SolverResult.unsat();
        }
        // Ensure every requested variable is present even if unconstrained.
        for (String v : vars) {
            assignment.putIfAbsent(v, Math.max(lowerBound, 0L) <= upperBound
                    ? Math.max(lowerBound, 0L) : lowerBound);
        }
        return SolverResult.sat(assignment);
    }

    private boolean backtrack(List<String> vars, int idx, Map<String, Long> assignment,
                              List<LinearConstraint> constraints,
                              Map<String, long[]> baseBounds) {
        if (idx == vars.size()) {
            return satisfiesAll(assignment, constraints);
        }
        String var = vars.get(idx);
        long[] interval = baseBounds.get(var);
        long lo = interval[0];
        long hi = interval[1];
        if (lo > hi) {
            return false;
        }

        // Prefer values that most tighten other constraints: start near 0,
        // enumerate deterministically. Seed only changes the direction when
        // configured through an even/odd seed, keeping reproducibility.
        long pivot = clamp(0L, lo, hi);
        boolean upwardFirst = (seed & 1L) == 0L;
        long[] candidates = orderedValues(lo, hi, pivot, upwardFirst);

        for (long value : candidates) {
            assignment.put(var, value);
            Map<String, long[]> copy = copyBounds(baseBounds);
            copy.put(var, new long[] {value, value});
            if (!propagate(constraints, copy)) {
                assignment.remove(var);
                continue;
            }
            // Eagerly check fully-ground constraints.
            if (violatesGround(assignment, constraints)) {
                assignment.remove(var);
                continue;
            }
            if (backtrack(vars, idx + 1, assignment, constraints, copy)) {
                return true;
            }
            assignment.remove(var);
        }
        return false;
    }

    /**
     * Fixed-point interval propagation. Every pass tightens bounds in place;
     * the loop terminates when no bound moves or an interval becomes empty.
     */
    private boolean propagate(List<LinearConstraint> constraints, Map<String, long[]> bounds) {
        boolean changed = true;
        int guard = 0;
        int maxPasses = Math.max(8, bounds.size() * 16 + constraints.size() * 4);
        while (changed) {
            changed = false;
            if (++guard > maxPasses) {
                break;
            }
            for (LinearConstraint c : constraints) {
                for (Map.Entry<String, long[]> e : bounds.entrySet()) {
                    long[] before = {e.getValue()[0], e.getValue()[1]};
                    if (!propagateVariable(c, e.getKey(), bounds)) {
                        return false;
                    }
                    long[] after = e.getValue();
                    if (after[0] != before[0] || after[1] != before[1]) {
                        changed = true;
                    }
                }
            }
        }
        return true;
    }

    /** Tighten only the bounds of {@code x} induced by one constraint. */
    private boolean propagateVariable(LinearConstraint c, String x,
                                      Map<String, long[]> bounds) {
        Long coefficient = c.coefficients().get(x);
        if (coefficient == null) {
            return true;
        }
        long a = coefficient;
        long b = c.rhs();
        long minOther = 0;
        long maxOther = 0;
        for (Map.Entry<String, Long> o : c.coefficients().entrySet()) {
            if (o.getKey().equals(x)) {
                continue;
            }
            long oc = o.getValue();
            long[] iv = bounds.get(o.getKey());
            long p1 = oc * iv[0];
            long p2 = oc * iv[1];
            minOther += Math.min(p1, p2);
            maxOther += Math.max(p1, p2);
        }
        long[] cur = bounds.get(x);
        long lo = cur[0];
        long hi = cur[1];
        long rhsUpper = b - minOther; // bound when others are minimal
        long rhsLower = b - maxOther; // bound when others are maximal
        switch (c.op()) {
            case LE -> {
                long[] nb = applyUpper(a, rhsUpper, lo, hi);
                if (nb == null) return false;
                lo = nb[0]; hi = nb[1];
            }
            case LT -> {
                long[] nb = applyUpper(a, rhsUpper - 1, lo, hi);
                if (nb == null) return false;
                lo = nb[0]; hi = nb[1];
            }
            case GE -> {
                long[] nb = applyLower(a, rhsLower, lo, hi);
                if (nb == null) return false;
                lo = nb[0]; hi = nb[1];
            }
            case GT -> {
                long[] nb = applyLower(a, rhsLower + 1, lo, hi);
                if (nb == null) return false;
                lo = nb[0]; hi = nb[1];
            }
            case EQ -> {
                long[] up = applyUpper(a, rhsUpper, lo, hi);
                long[] dn = applyLower(a, rhsLower, lo, hi);
                if (up == null || dn == null) return false;
                lo = Math.max(up[0], dn[0]);
                hi = Math.min(up[1], dn[1]);
                if (lo > hi) return false;
            }
            case NE -> {
                if (c.coefficients().size() == 1 && lo == hi && a * lo == b) {
                    return false;
                }
            }
        }
        cur[0] = lo;
        cur[1] = hi;
        return true;
    }

    private long[] applyUpper(long a, long rhs, long lo, long hi) {
        if (a > 0) {
            long maxX = floorDiv(rhs, a);
            hi = Math.min(hi, maxX);
        } else {
            // a*x <= rhs, a<0 => x >= ceil(rhs/a)
            long minX = ceilDiv(rhs, a);
            lo = Math.max(lo, minX);
        }
        return lo > hi ? null : new long[] {lo, hi};
    }

    private long[] applyLower(long a, long rhs, long lo, long hi) {
        if (a > 0) {
            long minX = ceilDiv(rhs, a);
            lo = Math.max(lo, minX);
        } else {
            // a*x >= rhs, a<0 => x <= floor(rhs/a)
            long maxX = floorDiv(rhs, a);
            hi = Math.min(hi, maxX);
        }
        return lo > hi ? null : new long[] {lo, hi};
    }

    private boolean violatesGround(Map<String, Long> assignment,
                                   List<LinearConstraint> constraints) {
        for (LinearConstraint c : constraints) {
            boolean allKnown = true;
            for (String v : c.coefficients().keySet()) {
                if (!assignment.containsKey(v)) {
                    allKnown = false;
                    break;
                }
            }
            if (allKnown && !holds(assignment, c)) {
                return true;
            }
        }
        return false;
    }

    private boolean satisfiesAll(Map<String, Long> assignment,
                                 List<LinearConstraint> constraints) {
        for (LinearConstraint c : constraints) {
            if (!holds(assignment, c)) {
                return false;
            }
        }
        return true;
    }

    private boolean groundHolds(long lhs, LinearConstraint c) {
        return switch (c.op()) {
            case LT -> lhs < c.rhs();
            case LE -> lhs <= c.rhs();
            case GT -> lhs > c.rhs();
            case GE -> lhs >= c.rhs();
            case EQ -> lhs == c.rhs();
            case NE -> lhs != c.rhs();
        };
    }

    private boolean holds(Map<String, Long> values, LinearConstraint c) {
        long lhs = 0;
        for (Map.Entry<String, Long> e : c.coefficients().entrySet()) {
            lhs += e.getValue() * values.get(e.getKey());
        }
        return switch (c.op()) {
            case LT -> lhs < c.rhs();
            case LE -> lhs <= c.rhs();
            case GT -> lhs > c.rhs();
            case GE -> lhs >= c.rhs();
            case EQ -> lhs == c.rhs();
            case NE -> lhs != c.rhs();
        };
    }

    private long[] orderedValues(long lo, long hi, long pivot, boolean upwardFirst) {
        int count = (int) Math.min((long) Integer.MAX_VALUE, hi - lo + 1);
        long[] values = new long[count];
        int k = 0;
        if (upwardFirst) {
            for (long v = pivot; v <= hi && k < count; v++) {
                values[k++] = v;
            }
            for (long v = pivot - 1; v >= lo && k < count; v--) {
                values[k++] = v;
            }
        } else {
            for (long v = pivot; v >= lo && k < count; v--) {
                values[k++] = v;
            }
            for (long v = pivot + 1; v <= hi && k < count; v++) {
                values[k++] = v;
            }
        }
        return values;
    }

    private static Map<String, long[]> copyBounds(Map<String, long[]> bounds) {
        Map<String, long[]> copy = new LinkedHashMap<>();
        bounds.forEach((k, v) -> copy.put(k, new long[] {v[0], v[1]}));
        return copy;
    }

    private static long clamp(long v, long lo, long hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static long floorDiv(long a, long b) {
        return Math.floorDiv(a, b);
    }

    private static long ceilDiv(long a, long b) {
        return (long) Math.ceil((double) a / b);
    }
}
