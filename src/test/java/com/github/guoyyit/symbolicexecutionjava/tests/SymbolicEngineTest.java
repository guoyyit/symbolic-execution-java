package com.github.guoyyit.symbolicexecutionjava.tests;

import com.github.guoyyit.symbolicexecutionjava.cfg.Cfg;
import com.github.guoyyit.symbolicexecutionjava.cfg.CfgBuilder;
import com.github.guoyyit.symbolicexecutionjava.engine.EngineConfig;
import com.github.guoyyit.symbolicexecutionjava.engine.ExecutionLimits;
import com.github.guoyyit.symbolicexecutionjava.engine.PathOutcome;
import com.github.guoyyit.symbolicexecutionjava.engine.PathReport;
import com.github.guoyyit.symbolicexecutionjava.engine.SymbolicEngine;
import com.github.guoyyit.symbolicexecutionjava.parser.Parser;
import com.github.guoyyit.symbolicexecutionjava.solver.LinearSolver;
import com.github.guoyyit.symbolicexecutionjava.validation.ResolvedProgram;
import com.github.guoyyit.symbolicexecutionjava.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SymbolicEngineTest {

    private List<PathReport> run(String src) {
        ResolvedProgram resolved = new Validator().validate(Parser.parseSource(src));
        Cfg cfg = new CfgBuilder().build(resolved);
        EngineConfig config = new EngineConfig(0L, -1024, 1024);
        LinearSolver solver = new LinearSolver(config.seed(),
                config.solverLowerBound(), config.solverUpperBound());
        SymbolicEngine engine = new SymbolicEngine(resolved, cfg, solver, config);
        List<PathReport> paths = new ArrayList<>();
        ExecutionLimits limits = new ExecutionLimits(10_000, 1000, 128, 1_000_000, 100_000);
        engine.run(limits, new AtomicBoolean(false), paths::add, null);
        return paths;
    }

    @Test
    void singleUnconstrainedInputOneSuccessPath() {
        List<PathReport> paths = run("int x; assert(x == 0);");
        long fail = paths.stream().filter(p -> p.outcome() == PathOutcome.ASSERTION_FAILED).count();
        long ok = paths.stream().filter(p -> p.outcome() == PathOutcome.SUCCESS).count();
        assertEquals(1, fail);
        assertEquals(1, ok);
        // success path: condition x == 0 holds
        PathReport success = paths.stream()
                .filter(p -> p.outcome() == PathOutcome.SUCCESS).findFirst().orElseThrow();
        assertEquals(0L, success.inputModel().get("x"));
        // failure path: condition negated -> smallest non-zero value
        PathReport failure = paths.stream()
                .filter(p -> p.outcome() == PathOutcome.ASSERTION_FAILED).findFirst().orElseThrow();
        assertFalse(failure.inputModel().get("x") == 0L);
    }

    @Test
    void bothBranchesEnumeratedInFixedOrder() {
        List<PathReport> paths = run("""
                int x;
                if (x > 0) {
                    assert(x >= 1);
                } else {
                    assert(x <= 0);
                }
                """);
        // both branches feasible; each assertion always holds on its branch,
        // so there are exactly 2 success paths and no failure paths.
        assertEquals(2, paths.size());
        assertTrue(paths.stream().allMatch(p -> p.outcome() == PathOutcome.SUCCESS),
                paths.toString());
        // positive (x > 0) branch enumerated first
        assertTrue(paths.get(0).inputModel().get("x") > 0);
        assertTrue(paths.get(1).inputModel().get("x") <= 0);
    }

    @Test
    void infeasibleBranchIsPruned() {
        List<PathReport> paths = run("""
                int x;
                if (x >= 10) {
                    x = 5;
                }
                assert(x == 5);
                """);
        // Two feasible branches reach the assertion:
        //   then: x == 5 always -> only success
        //   else: x <= 9 and x == 5 -> success; negation x != 5 (x<=9) -> failure
        long fails = paths.stream().filter(pp -> pp.outcome() == PathOutcome.ASSERTION_FAILED)
                .count();
        long oks = paths.stream().filter(pp -> pp.outcome() == PathOutcome.SUCCESS).count();
        assertEquals(1, fails, paths.toString());
        assertEquals(2, oks, paths.toString());
    }

    @Test
    void infeasibleBranchIsPrunedCompletely() {
        List<PathReport> paths = run("""
                int x;
                if (x >= 10 && x <= 10) {
                    if (x < 0) {
                        assert(x == -1);
                    }
                    assert(x == 10);
                }
                """);
        // Under x == 10 the inner x < 0 branch is unsat and is pruned, so the
        // inner assertion never fails. The outer condition's negation expands by
        // De Morgan into two minterms (x > 10 and x < 10), hence 3 success paths:
        // x == 10 (then), x > 10, x < 10 (the two else minterms).
        long fails = paths.stream().filter(pp -> pp.outcome() == PathOutcome.ASSERTION_FAILED)
                .count();
        long oks = paths.stream().filter(pp -> pp.outcome() == PathOutcome.SUCCESS).count();
        assertEquals(0, fails, paths.toString());
        assertEquals(3, oks, paths.toString());
    }

    @Test
    void boundedLoopUnrollsAndTerminates() {
        List<PathReport> paths = run("""
                int sum = 0;
                for (int i = 0; i < 3; i = i + 1) {
                    sum = sum + i;
                }
                assert(sum == 3);
                """);
        // only one path through fully-constant loop, assertion holds
        assertEquals(1, paths.size());
        assertEquals(PathOutcome.SUCCESS, paths.get(0).outcome());
        assertEquals(3L, paths.get(0).finalValues().get("sum"));
        assertEquals(3L, paths.get(0).finalValues().get("i"));
    }

    @Test
    void assertionFailureInsideLoopReported() {
        List<PathReport> paths = run("""
                for (int i = 0; i < 4; i = i + 1) {
                    assert(i < 3);
                }
                """);
        // Reaching i == 4 requires passing the assertion at i == 3, which fails,
        // so exactly one terminal path exists: the failure at the 4th iteration.
        long fails = paths.stream()
                .filter(p -> p.outcome() == PathOutcome.ASSERTION_FAILED).count();
        long oks = paths.stream().filter(p -> p.outcome() == PathOutcome.SUCCESS).count();
        assertEquals(1, fails, paths.toString());
        assertEquals(0, oks, paths.toString());
        PathReport failure = paths.stream()
                .filter(p -> p.outcome() == PathOutcome.ASSERTION_FAILED).findFirst().orElseThrow();
        assertEquals(3L, failure.finalValues().get("i"));
    }

    @Test
    void loopWithConditionalExitGivesSuccessAndFailure() {
        List<PathReport> paths = run("""
                int n;
                int sum = 0;
                for (int i = 0; i < n; i = i + 1) {
                    assert(i <= 1);
                    sum = sum + i;
                }
                """);
        // Symbolic bound n in the solver domain. Paths exiting with n <= 2 succeed;
        // entering a third iteration (n >= 3) violates i <= 1 at i == 2.
        long fails = paths.stream().filter(p -> p.outcome() == PathOutcome.ASSERTION_FAILED)
                .count();
        long oks = paths.stream().filter(p -> p.outcome() == PathOutcome.SUCCESS).count();
        assertTrue(fails >= 1, paths.toString());
        assertTrue(oks >= 1, paths.toString());
        PathReport fail = paths.stream()
                .filter(p -> p.outcome() == PathOutcome.ASSERTION_FAILED).findFirst().orElseThrow();
        assertEquals(2L, fail.finalValues().get("i"));
        assertTrue(fail.inputModel().get("n") >= 3);
    }

    @Test
    void pathIdsAreDeterministic() {
        String src = """
                int x;
                if (x > 0) {
                    assert(x == 1);
                } else {
                    assert(x == -1);
                }
                """;
        List<PathReport> a = run(src);
        List<PathReport> b = run(src);
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).pathId(), b.get(i).pathId());
            assertEquals(a.get(i).inputModel(), b.get(i).inputModel());
            assertEquals(a.get(i).constraints(), b.get(i).constraints());
        }
    }
}
