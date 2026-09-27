package com.github.guoyyit.symbolicexecutionjava.tests;

import com.github.guoyyit.symbolicexecutionjava.model.symbolic.LinearConstraint;
import com.github.guoyyit.symbolicexecutionjava.model.symbolic.PathCondition;
import com.github.guoyyit.symbolicexecutionjava.solver.LinearSolver;
import com.github.guoyyit.symbolicexecutionjava.solver.SolverResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinearSolverTest {

    private LinearSolver solver() {
        return new LinearSolver(1L, -1024, 1024);
    }

    private LinearConstraint c(Map<String, Long> coeffs, LinearConstraint.Op op, long rhs) {
        return new LinearConstraint(coeffs, op, rhs);
    }

    @Test
    void triviallySatisfiableReturnsModel() {
        SolverResult r = solver().check(List.of("x"), PathCondition.empty());
        assertTrue(r.satisfiable());
        assertEquals(0L, r.model().get("x"));
    }

    @Test
    void simpleUpperAndLowerBounds() {
        var cond = new PathCondition(List.of(
                c(Map.of("x", 1L), LinearConstraint.Op.GE, 5L),
                c(Map.of("x", 1L), LinearConstraint.Op.LE, 10L)));
        SolverResult r = solver().check(List.of("x"), cond);
        assertTrue(r.satisfiable());
        long x = r.model().get("x");
        assertTrue(x >= 5 && x <= 10, "x=" + x);
    }

    @Test
    void detectsContradictionAndPrunes() {
        var cond = new PathCondition(List.of(
                c(Map.of("x", 1L), LinearConstraint.Op.GE, 10L),
                c(Map.of("x", 1L), LinearConstraint.Op.LE, 3L)));
        SolverResult r = solver().check(List.of("x"), cond);
        assertFalse(r.satisfiable());
    }

    @Test
    void equalityConstraintIsSolved() {
        // 2*x + 3*y = 16, x >= 1, y >= 1
        var cond = new PathCondition(List.of(
                c(Map.of("x", 2L, "y", 3L), LinearConstraint.Op.EQ, 16L),
                c(Map.of("x", 1L), LinearConstraint.Op.GE, 1L),
                c(Map.of("y", 1L), LinearConstraint.Op.GE, 1L)));
        SolverResult r = solver().check(List.of("x", "y"), cond);
        assertTrue(r.satisfiable(), r.toString());
        long x = r.model().get("x");
        long y = r.model().get("y");
        assertEquals(16L, 2 * x + 3 * y);
    }

    @Test
    void equalityOverDomainCanBeUnsat() {
        // 2*x = 1 has no integer solution
        var cond = new PathCondition(List.of(
                c(Map.of("x", 2L), LinearConstraint.Op.EQ, 1L)));
        SolverResult r = solver().check(List.of("x"), cond);
        assertFalse(r.satisfiable());
    }

    @Test
    void notEqualIsHandled() {
        var cond = new PathCondition(List.of(
                c(Map.of("x", 1L), LinearConstraint.Op.NE, 0L),
                c(Map.of("x", 1L), LinearConstraint.Op.LE, 0L)));
        SolverResult r = solver().check(List.of("x"), cond);
        assertTrue(r.satisfiable());
        assertEquals(-1L, r.model().get("x"));
    }

    @Test
    void negativeCoefficients() {
        // -x + 2*y >= 4
        var cond = new PathCondition(List.of(
                c(Map.of("x", -1L, "y", 2L), LinearConstraint.Op.GE, 4L),
                c(Map.of("x", 1L), LinearConstraint.Op.EQ, 0L)));
        SolverResult r = solver().check(List.of("x", "y"), cond);
        assertTrue(r.satisfiable());
        assertEquals(0L, r.model().get("x"));
        assertTrue(2L * r.model().get("y") >= 4);
    }

    @Test
    void sameInputsAndSeedYieldSameModel() {
        var cond = new PathCondition(List.of(
                c(Map.of("x", 1L), LinearConstraint.Op.GE, 100L),
                c(Map.of("x", 1L), LinearConstraint.Op.LE, 200L)));
        SolverResult r1 = new LinearSolver(7L, -1024, 1024).check(List.of("x"), cond);
        SolverResult r2 = new LinearSolver(7L, -1024, 1024).check(List.of("x"), cond);
        assertEquals(r1.model(), r2.model());
        // even seed enumerates upward from pivot 0 -> first value 100
        assertEquals(100L, r1.model().get("x"));
    }

    @Test
    void strictInequalityBoundary() {
        // x < 5 => max 4; with x >= 4 gives exactly 4
        var cond = new PathCondition(List.of(
                c(Map.of("x", 1L), LinearConstraint.Op.LT, 5L),
                c(Map.of("x", 1L), LinearConstraint.Op.GE, 4L)));
        SolverResult r = solver().check(List.of("x"), cond);
        assertTrue(r.satisfiable());
        assertEquals(4L, r.model().get("x"));
    }

    @Test
    void customDomainIsRespected() {
        var small = new LinearSolver(0L, -2, 2);
        var cond = new PathCondition(List.of(
                c(Map.of("x", 1L), LinearConstraint.Op.GE, 100L)));
        assertFalse(small.check(List.of("x"), cond).satisfiable());
    }
}
