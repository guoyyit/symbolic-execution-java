package com.github.guoyyit.symbolicexecutionjava.solver;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Deterministic decision procedure and model builder for conjunctions of
 * integer linear arithmetic constraints.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Every relation is normalised to {@code a.x <= b} rows
 *       ({@code <} strengthened by one for integers, equality to a row pair,
 *       disequality to a deterministic two-way split).</li>
 *   <li>A bounded box {@code -B <= x_i <= B} is added. The standard integer
 *       feasibility bound ({@code B = n! * M^n}, M the largest absolute
 *       coefficient/right-hand side) guarantees that if the system has any
 *       integer solution it has one inside the box.</li>
 *   <li>Fourier-Motzkin elimination rows are precomputed for every variable
 *       level, giving exact per-variable lower/upper bounds.</li>
 *   <li>Variables are assigned in reverse elimination order, trying integer
 *       values in the fixed order {@code 0, 1, -1, 2, -2, ...} inside the
 *       feasible interval, with backtracking.</li>
 * </ol>
 * The same conjunction therefore always returns the same decision and model.
 */
public final class IntegerLinearSolver {

    /** Practical cap for the completeness box (see class javadoc). */
    static final long MAX_BOX_BOUND = 1_000_000_000L;

    private record Row(Map<String, Long> a, long b) {
        Row {
            TreeMap<String, Long> sorted = new TreeMap<>();
            if (a != null) {
                a.forEach((k, v) -> {
                    if (v != 0L) {
                        sorted.put(k, v);
                    }
                });
            }
            a = Map.copyOf(sorted);
        }

        static Row of(LinearExpr expr) {
            // expr <= 0  ==>  coeff.x <= -constant
            return new Row(new TreeMap<>(expr.coefficients()), Math.negateExact(expr.constant()));
        }

        String key() {
            StringBuilder sb = new StringBuilder();
            a.forEach((k, v) -> sb.append(k).append('=').append(v).append(';'));
            return sb.append('|').append(b).toString();
        }
    }

    public boolean isSatisfiable(List<LinearConstraint> constraints) {
        return solve(constraints).isPresent();
    }

    public Optional<Map<String, Long>> solve(List<LinearConstraint> constraints) {
        // Expand disequalities into a deterministic disjunction of conjunctions.
        List<Row> base = new ArrayList<>();
        for (LinearConstraint c : constraints) {
            List<Row> normalised = normalise(c);
            if (normalised.size() == 2 && c.relation() == LinearConstraint.Relation.NE) {
                Optional<Map<String, Long>> first = solveConjunction(concat(base, List.of(normalised.get(0))));
                if (first.isPresent()) {
                    return first;
                }
                base.add(normalised.get(1));
            } else {
                base.addAll(normalised);
            }
        }
        return solveConjunction(base);
    }

    private List<Row> concat(List<Row> rows, List<Row> extra) {
        List<Row> all = new ArrayList<>(rows.size() + extra.size());
        all.addAll(rows);
        all.addAll(extra);
        return all;
    }

    private List<Row> normalise(LinearConstraint c) {
        LinearExpr e = c.expression();
        return switch (c.relation()) {
            case LE -> List.of(Row.of(e));
            case LT -> List.of(Row.of(new LinearExpr(e.coefficients(),
                    Math.addExact(e.constant(), 1)))); // e + 1 <= 0
            case GE -> List.of(Row.of(e.negate()));
            case GT -> List.of(Row.of(new LinearExpr(e.negate().coefficients(),
                    Math.addExact(e.negate().constant(), 1)))); // -e + 1 <= 0
            case EQ -> List.of(Row.of(e), Row.of(e.negate()));
            case NE -> List.of(
                    Row.of(new LinearExpr(e.coefficients(), Math.addExact(e.constant(), 1))), // e <= -1
                    Row.of(new LinearExpr(e.negate().coefficients(),
                            Math.addExact(e.negate().constant(), 1))));                       // -e <= -1
        };
    }

    private Optional<Map<String, Long>> solveConjunction(List<Row> inputRows) {
        // Safe dominance simplification: among rows with identical left-hand
        // sides only the tightest (smallest) right-hand side can ever bind.
        // This collapses long chains of e.g. i <= 0, i <= 1, ... accumulated
        // along loop-unrolled paths without changing feasibility.
        List<Row> rows = tightenBySameLeftHandSide(inputRows);

        TreeSet<String> vars = new TreeSet<>();
        long maxEntry = 1L;
        for (Row r : rows) {
            vars.addAll(r.a().keySet());
            maxEntry = Math.max(maxEntry, Math.abs(r.b()));
            for (long v : r.a().values()) {
                maxEntry = Math.max(maxEntry, Math.abs(v));
            }
        }
        List<String> order = new ArrayList<>(vars);
        int n = order.size();

        long box = completenessBound(n, maxEntry);
        List<Row> withBox = new ArrayList<>(rows);
        for (String v : order) {
            withBox.add(new Row(Map.of(v, 1L), box));
            withBox.add(new Row(Map.of(v, -1L), box));
        }

        // levels[i] = FM rows over variables order[i..n-1], after eliminating
        // order[0..i-1]. levels[n] contains only constant rows.
        List<List<Row>> levels = new ArrayList<>(n + 1);
        levels.add(dedupe(withBox));
        for (int i = 0; i < n; i++) {
            levels.add(eliminate(levels.get(i), order.get(i)));
        }
        for (Row r : levels.get(n)) {
            if (r.b() < 0) {
                return Optional.empty(); // 0 <= negative
            }
        }

        Map<String, Long> assignment = new HashMap<>();
        if (!assign(levels, order, n - 1, box, assignment)) {
            return Optional.empty();
        }
        TreeMap<String, Long> model = new TreeMap<>();
        for (String v : order) {
            model.put(v, assignment.getOrDefault(v, 0L));
        }
        return Optional.of(model);
    }

    /**
     * Standard integer-feasibility box: B = n! * M^n, M the largest absolute
     * value found in the (boxed) system. Capped for implementation safety.
     */
    private long completenessBound(int n, long m) {
        if (n == 0) {
            return 0;
        }
        BigInteger factorial = BigInteger.ONE;
        for (int i = 2; i <= n; i++) {
            factorial = factorial.multiply(BigInteger.valueOf(i));
        }
        BigInteger bound = factorial.multiply(BigInteger.valueOf(m).pow(n));
        if (bound.compareTo(BigInteger.valueOf(MAX_BOX_BOUND)) > 0) {
            return MAX_BOX_BOUND;
        }
        return bound.longValueExact();
    }

    private List<Row> tightenBySameLeftHandSide(List<Row> rows) {
        // Key on the normalised coefficient map; keep the minimum b per LHS.
        java.util.TreeMap<String, Row> tightest = new java.util.TreeMap<>();
        for (Row r : rows) {
            Row existing = tightest.get(lhsKey(r));
            if (existing == null || r.b() < existing.b()) {
                tightest.put(lhsKey(r), r);
            }
        }
        return new ArrayList<>(tightest.values());
    }

    private static String lhsKey(Row r) {
        StringBuilder sb = new StringBuilder();
        r.a().forEach((k, v) -> sb.append(k).append('=').append(v).append(';'));
        return sb.toString();
    }

    private List<Row> dedupe(List<Row> rows) {
        LinkedHashSetPreservingOrder<String> seen = new LinkedHashSetPreservingOrder<>();
        List<Row> unique = new ArrayList<>();
        for (Row r : rows) {
            if (seen.add(r.key())) {
                unique.add(r);
            }
        }
        return unique;
    }

    /** One Fourier-Motzkin elimination step for {@code var}. */
    private List<Row> eliminate(List<Row> rows, String var) {
        List<Row> positive = new ArrayList<>();
        List<Row> negative = new ArrayList<>();
        List<Row> zero = new ArrayList<>();
        for (Row r : rows) {
            long c = r.a().getOrDefault(var, 0L);
            if (c > 0) {
                positive.add(r);
            } else if (c < 0) {
                negative.add(r);
            } else {
                zero.add(r);
            }
        }
        Set<String> seen = new TreeSet<>();
        List<Row> result = new ArrayList<>();
        for (Row z : zero) {
            if (seen.add(z.key())) {
                result.add(z);
            }
        }
        for (Row p : positive) {
            long ap = p.a().get(var);
            for (Row ng : negative) {
                long an = ng.a().get(var); // negative
                long scaleP = Math.negateExact(an);
                long scaleN = ap;
                TreeMap<String, Long> coeffs = new TreeMap<>();
                addScaled(coeffs, p.a(), scaleP, var);
                addScaled(coeffs, ng.a(), scaleN, var);
                long rhs = Math.addExact(
                        Math.multiplyExact(scaleP, p.b()),
                        Math.multiplyExact(scaleN, ng.b()));
                Row combined = new Row(coeffs, rhs);
                if (combined.a().isEmpty() && combined.b() < 0) {
                    // Concrete contradiction; propagate it as the level result.
                    return List.of(new Row(Map.of(), -1L));
                }
                if (seen.add(combined.key())) {
                    result.add(combined);
                }
            }
        }
        return result;
    }

    private void addScaled(Map<String, Long> target, Map<String, Long> source,
                           long scale, String excludedVar) {
        for (Map.Entry<String, Long> e : source.entrySet()) {
            if (e.getKey().equals(excludedVar)) {
                continue;
            }
            target.merge(e.getKey(), Math.multiplyExact(e.getValue(), scale),
                    (x, y) -> {
                        long sum = Math.addExact(x, y);
                        return sum == 0L ? null : sum;
                    });
        }
    }

    /**
     * Assigns variables in reverse elimination order using the exact FM
     * bounds, deterministic candidate order 0, 1, -1, 2, -2, ...
     */
    private boolean assign(List<List<Row>> levels, List<String> order,
                           int index, long box, Map<String, Long> assignment) {
        if (index < 0) {
            return true;
        }
        String var = order.get(index);
        long lower = -box;
        long upper = box;
        List<Row> rows = levels.get(index);
        for (Row r : rows) {
            long a = r.a().getOrDefault(var, 0L);
            long rest = 0L;
            for (Map.Entry<String, Long> e : r.a().entrySet()) {
                if (!e.getKey().equals(var)) {
                    rest = Math.addExact(rest,
                            Math.multiplyExact(e.getValue(), assignment.get(e.getKey())));
                }
            }
            long rhs = Math.subtractExact(r.b(), rest); // a*var <= rhs
            if (a == 0L) {
                if (rhs < 0) {
                    return false;
                }
            } else if (a > 0) {
                upper = Math.min(upper, floorDiv(rhs, a));
            } else {
                lower = Math.max(lower, ceilDiv(rhs, a)); // a < 0
            }
            if (lower > upper) {
                return false;
            }
        }
        // Deterministic value order: 0, 1, -1, 2, -2, ...
        if (lower <= 0L && 0L <= upper) {
            assignment.put(var, 0L);
            if (assign(levels, order, index - 1, box, assignment)) {
                return true;
            }
        }
        for (long k = 1L; k <= Math.max(Math.abs(lower), Math.abs(upper)) + 1L; k++) {
            if (k >= lower && k <= upper) {
                assignment.put(var, k);
                if (assign(levels, order, index - 1, box, assignment)) {
                    return true;
                }
            }
            if (-k >= lower && -k <= upper) {
                assignment.put(var, -k);
                if (assign(levels, order, index - 1, box, assignment)) {
                    return true;
                }
            }
        }
        assignment.remove(var);
        return false;
    }

    private static long floorDiv(long x, long y) {
        // y > 0 here.
        long q = x / y;
        long r = x % y;
        if (r != 0 && r < 0) {
            q--;
        }
        return q;
    }

    private static long ceilDiv(long x, long y) {
        // y < 0 here: ceil(x/y) = -floor(x/(-y)).
        return -floorDiv(x, -y);
    }

    /** Minimal insertion-ordered set to avoid a java.awt type name collision. */
    private static final class LinkedHashSetPreservingOrder<E> extends java.util.LinkedHashSet<E> {
    }
}
