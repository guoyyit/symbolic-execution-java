package com.github.guoyyit.symbolicexecutionjava.solver;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class IntegerLinearSolverTest {

    private final IntegerLinearSolver solver = new IntegerLinearSolver();

    private static LinearExpr v(String name) {
        return LinearExpr.variable(name);
    }

    private static LinearExpr c(long k) {
        return LinearExpr.constant(k);
    }

    @Test
    void satisfiesTrivial() {
        Optional<Map<String, Long>> model =
                solver.solve(List.of(LinearConstraint.le(v("x").subtract(c(5)))));
        assertThat(model).contains(Map.of("x", 0L));
    }

    @Test
    void detectsContradictoryBounds() {
        boolean sat = solver.isSatisfiable(List.of(
                LinearConstraint.of(v("x").subtract(c(5)), LinearConstraint.Relation.GE),
                LinearConstraint.le(v("x").subtract(c(3)))));
        assertThat(sat).isFalse();
    }

    @Test
    void detectsIntegerInfeasibilityOfRationalSystem() {
        // 2x == 1 is feasible over rationals but not integers.
        assertThat(solver.solve(List.of(LinearConstraint.eq(v("x").scale(2).subtract(c(1))))))
                .isEmpty();
    }

    @Test
    void detectsIntegerInfeasibilityOfUnboundedLine() {
        // 2x + 2y == 1: unbounded rational hyperplane without integer points.
        assertThat(solver.solve(List.of(
                LinearConstraint.eq(v("x").scale(2).add(v("y").scale(2)).subtract(c(1))))))
                .isEmpty();
    }

    @Test
    void solvesTwoVariableSystem() {
        Optional<Map<String, Long>> model = solver.solve(List.of(
                LinearConstraint.eq(v("x").add(v("y")).subtract(c(10))),
                LinearConstraint.of(v("x").subtract(v("y").scale(2)),
                        LinearConstraint.Relation.GE)));
        assertThat(model).isPresent();
        long x = model.get().get("x");
        long y = model.get().get("y");
        assertThat(x + y).isEqualTo(10);
        assertThat(x).isGreaterThanOrEqualTo(2 * y);
    }

    @Test
    void disequalitySplitsDeterministically() {
        Optional<Map<String, Long>> first = solver.solve(List.of(
                LinearConstraint.of(v("x"), LinearConstraint.Relation.NE)));
        assertThat(first).contains(Map.of("x", -1L));
        // Same input always gives the same model.
        Optional<Map<String, Long>> again = solver.solve(List.of(
                LinearConstraint.of(v("x"), LinearConstraint.Relation.NE)));
        assertThat(again).isEqualTo(first);
    }

    @Test
    void strictInequalitiesStrengthenByOne() {
        // 0 < x < 3 -> smallest positive model x = 1.
        Optional<Map<String, Long>> model = solver.solve(List.of(
                LinearConstraint.of(v("x"), LinearConstraint.Relation.GT),
                LinearConstraint.of(v("x").subtract(c(3)), LinearConstraint.Relation.LT)));
        assertThat(model).contains(Map.of("x", 1L));
    }

    @Test
    void emptyConstraintSystemBindsZero() {
        assertThat(solver.solve(List.of())).contains(Map.of());
    }

    @Test
    void negativeCoefficientsHandled() {
        // -2x + 4 <= 0, x <= 5  ->  x >= 2 ; expected model x = 2.
        Optional<Map<String, Long>> model = solver.solve(List.of(
                LinearConstraint.of(v("x").scale(-2).add(c(4)), LinearConstraint.Relation.LE),
                LinearConstraint.le(v("x").subtract(c(5)))));
        assertThat(model).contains(Map.of("x", 2L));
    }
}
