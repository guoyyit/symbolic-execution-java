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
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdgeCaseEngineTest {

    private List<PathReport> run(String src, long lo, long hi) {
        ResolvedProgram resolved = new Validator().validate(Parser.parseSource(src));
        Cfg cfg = new CfgBuilder().build(resolved);
        EngineConfig config = new EngineConfig(0L, lo, hi);
        LinearSolver solver = new LinearSolver(0L, lo, hi);
        SymbolicEngine engine = new SymbolicEngine(resolved, cfg, solver, config);
        List<PathReport> paths = new ArrayList<>();
        engine.run(new ExecutionLimits(30_000, 5000, 64, 5_000_000, 500_000),
                new AtomicBoolean(false), paths::add, null);
        return paths;
    }

    @Test
    void nestedConstantLoopsComputeProduct() {
        String src = """
                int acc = 0;
                for (int i = 0; i < 2; i = i + 1) {
                    for (int j = 0; j < 3; j = j + 1) {
                        acc = acc + 1;
                    }
                }
                assert(acc == 6);
                """;
        List<PathReport> paths = run(src, -64, 64);
        assertEquals(1, paths.size(), paths.toString());
        assertEquals(PathOutcome.SUCCESS, paths.get(0).outcome());
        assertEquals(6L, paths.get(0).finalValues().get("acc"));
    }

    @Test
    void descendingLoopSumsCorrectly() {
        String src = """
                int s = 0;
                for (int k = 3; k > 0; k = k - 1) {
                    s = s + k;
                }
                assert(s == 6);
                """;
        List<PathReport> paths = run(src, -64, 64);
        assertEquals(1, paths.size());
        assertEquals(PathOutcome.SUCCESS, paths.get(0).outcome());
        assertEquals(6L, paths.get(0).finalValues().get("s"));
        assertEquals(0L, paths.get(0).finalValues().get("k"));
    }

    @Test
    void negativeConstantsAndSubtraction() {
        String src = """
                int a = -5;
                int b = a - 3;
                assert(b == -8);
                """;
        List<PathReport> paths = run(src, -64, 64);
        long failures = paths.stream()
                .filter(p -> p.outcome() == PathOutcome.ASSERTION_FAILED).count();
        assertEquals(0, failures, paths.toString());
        assertEquals(-8L, paths.get(0).finalValues().get("b"));
    }

    @Test
    void symbolicLoopBoundWithFailureAndSuccess() {
        String src = """
                int n;
                int s = 0;
                for (int i = 0; i < n; i = i + 1) {
                    assert(i < 2);
                    s = s + i;
                }
                """;
        List<PathReport> paths = run(src, -4, 4);
        // n <= 2 exits normally -> success; n >= 3 fails the assertion at i == 2.
        assertTrue(paths.stream().anyMatch(p -> p.outcome() == PathOutcome.SUCCESS),
                paths.toString());
        List<PathReport> failures = paths.stream()
                .filter(p -> p.outcome() == PathOutcome.ASSERTION_FAILED).toList();
        assertTrue(!failures.isEmpty(), "expected an assertion-failure path");
        PathReport f = failures.get(0);
        assertEquals(2L, f.finalValues().get("i"));
        assertTrue(f.inputModel().get("n") >= 3);
    }

    @Test
    void notConditionSplitsBothWays() {
        String src = "int x; if (!(x == 0)) { assert(x != 0); } else { assert(x == 0); }";
        List<PathReport> paths = run(src, -4, 4);
        long failures = paths.stream()
                .filter(p -> p.outcome() == PathOutcome.ASSERTION_FAILED).count();
        assertEquals(0, failures, paths.toString());
        assertEquals(2, paths.size(), paths.toString());
    }

    @Test
    void emptyProgramProducesSingleSuccessPath() {
        List<PathReport> paths = run("", -4, 4);
        assertEquals(1, paths.size());
        assertEquals(PathOutcome.SUCCESS, paths.get(0).outcome());
        assertTrue(paths.get(0).constraints().isEmpty());
    }
}
