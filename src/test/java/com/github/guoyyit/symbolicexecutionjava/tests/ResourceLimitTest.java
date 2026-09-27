package com.github.guoyyit.symbolicexecutionjava.tests;

import com.github.guoyyit.symbolicexecutionjava.cfg.Cfg;
import com.github.guoyyit.symbolicexecutionjava.cfg.CfgBuilder;
import com.github.guoyyit.symbolicexecutionjava.engine.EngineConfig;
import com.github.guoyyit.symbolicexecutionjava.engine.ExecutionLimits;
import com.github.guoyyit.symbolicexecutionjava.engine.PathReport;
import com.github.guoyyit.symbolicexecutionjava.engine.ResourceLimitException;
import com.github.guoyyit.symbolicexecutionjava.engine.SymbolicEngine;
import com.github.guoyyit.symbolicexecutionjava.parser.Parser;
import com.github.guoyyit.symbolicexecutionjava.solver.LinearSolver;
import com.github.guoyyit.symbolicexecutionjava.validation.ResolvedProgram;
import com.github.guoyyit.symbolicexecutionjava.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceLimitTest {

    @Test
    void maxStepsIsEnforced() {
        ResolvedProgram resolved = new Validator().validate(
                Parser.parseSource("int x; int y; assert(x + y == 0);"));
        Cfg cfg = new CfgBuilder().build(resolved);
        SymbolicEngine engine = new SymbolicEngine(resolved, cfg,
                new LinearSolver(0, -16, 16), new EngineConfig(0, -16, 16));
        List<PathReport> paths = new ArrayList<>();
        // Allow only a couple of symbolic steps — exploration cannot finish.
        ExecutionLimits tiny = new ExecutionLimits(10_000, 1000, 8, 1, 100_000);
        assertThrows(ResourceLimitException.class,
                () -> engine.run(tiny, new AtomicBoolean(false), paths::add, null));
    }

    @Test
    void maxBacktracksIsEnforcedWhenBranchesAreInfeasible() {
        // After pinning x == 5, several contradictory branches are pruned.
        String src = """
                int x;
                assert(x == 5);
                if (x < 0) { x = 1; }
                if (x > 100) { x = 2; }
                if (x == 6) { x = 3; }
                if (x != 5) { x = 4; }
                """;
        ResolvedProgram resolved = new Validator().validate(Parser.parseSource(src));
        Cfg cfg = new CfgBuilder().build(resolved);
        SymbolicEngine engine = new SymbolicEngine(resolved, cfg,
                new LinearSolver(0, -16, 16), new EngineConfig(0, -16, 16));
        // Budget of zero backtracks: the first pruned (infeasible) sibling trips it.
        ExecutionLimits limits = new ExecutionLimits(10_000, 1000, 8, 1_000_000, 0);
        assertThrows(ResourceLimitException.class,
                () -> engine.run(limits, new AtomicBoolean(false), r -> { }, null));
    }
}
