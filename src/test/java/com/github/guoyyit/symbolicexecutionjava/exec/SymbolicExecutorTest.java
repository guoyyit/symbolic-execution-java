package com.github.guoyyit.symbolicexecutionjava.exec;

import com.github.guoyyit.symbolicexecutionjava.cfg.Cfg;
import com.github.guoyyit.symbolicexecutionjava.cfg.CfgBuilder;
import com.github.guoyyit.symbolicexecutionjava.lang.Program;
import com.github.guoyyit.symbolicexecutionjava.lang.VarDecl;
import com.github.guoyyit.symbolicexecutionjava.parse.ProgramParser;
import com.github.guoyyit.symbolicexecutionjava.solver.IntegerLinearSolver;
import com.github.guoyyit.symbolicexecutionjava.validate.StaticValidator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SymbolicExecutorTest {

    private static final long LONG_ENOUGH = 100_000L;

    private record Engine(SymbolicExecutor executor, Cfg cfg, List<String> vars,
                          List<String> inputs) {
        private static final long DEFAULT_SEED = 0L;

        static Engine of(String json) {
            return of(json, DEFAULT_SEED);
        }

        static Engine of(String json, long seed) {
            Program program = new ProgramParser().parse(json);
            new StaticValidator().validate(program);
            Cfg cfg = new CfgBuilder().build(program);
            List<String> vars = new ArrayList<>(program.variables().keySet());
            List<String> inputs = program.variables().values().stream()
                    .filter(VarDecl::input).map(VarDecl::name).toList();
            SymbolicExecutor executor = new SymbolicExecutor(cfg, vars, inputs,
                    new IntegerLinearSolver(), seed,
                    new SymbolicExecutor.Limits(LONG_ENOUGH, 1000, Long.MAX_VALUE));
            return new Engine(executor, cfg, vars, inputs);
        }

        Checkpoint snapshot() {
            return executor.snapshot();
        }

        static Engine restored(Engine template, Checkpoint checkpoint) {
            SymbolicExecutor executor = SymbolicExecutor.fromCheckpoint(template.cfg,
                    template.vars, template.inputs, new IntegerLinearSolver(),
                    DEFAULT_SEED,
                    new SymbolicExecutor.Limits(LONG_ENOUGH, 1000, Long.MAX_VALUE),
                    checkpoint);
            return new Engine(executor, template.cfg, template.vars, template.inputs);
        }

        void runAll(long quantum) {
            while (!executor.isDone()) {
                executor.step(quantum);
            }
            assertThat(executor.outcome()).isEqualTo(SymbolicExecutor.Outcome.COMPLETED);
        }

        List<Finding> findings() {
            return executor.result().findings();
        }

        <T> T apply(java.util.function.Function<Engine, T> fn) {
            return fn.apply(this);
        }
    }

    @Test
    void enumeratesBothBranchesWithNegatedConditions() {
        Engine engine = Engine.of("""
                {"variables":[{"name":"x","input":true}],"statements":[
                  {"kind":"if","line":1,"condition":{"kind":"binary","op":"ge",
                    "left":{"kind":"var","name":"x"},"right":{"kind":"const","value":0}},
                    "then":[{"kind":"assert","line":2,"condition":{"kind":"binary","op":"ge",
                      "left":{"kind":"var","name":"x"},"right":{"kind":"const","value":0}}}],
                    "else":[{"kind":"assert","line":3,"condition":{"kind":"binary","op":"lt",
                      "left":{"kind":"var","name":"x"},"right":{"kind":"const","value":0}}}]}
                ]}
                """);
        engine.runAll(1000);
        List<Finding> findings = engine.findings().stream()
                .filter(f -> f.kind() == Finding.Kind.ASSERT_PASSED).toList();
        assertThat(findings).hasSize(2);
        assertThat(findings.get(0).pathId()).isLessThan(findings.get(1).pathId());
        // TRUE branch explored first with the condition, FALSE branch with negation.
        assertThat(findings.get(0).model()).isEqualTo(java.util.Map.of("x", 0L));
        assertThat(findings.get(1).model()).isEqualTo(java.util.Map.of("x", -1L));
    }

    @Test
    void reportsViolatedAndPassedPathsForSameAssert() {
        Engine engine = Engine.of("""
                {"variables":[{"name":"x","input":true}],"statements":[
                  {"kind":"assert","line":1,"condition":{"kind":"binary","op":"ge",
                    "left":{"kind":"var","name":"x"},"right":{"kind":"const","value":0}}}
                ]}
                """);
        engine.runAll(1000);
        Finding violated = engine.findings().stream()
                .filter(f -> f.kind() == Finding.Kind.ASSERT_VIOLATED).findFirst().orElseThrow();
        assertThat(violated.model()).isEqualTo(java.util.Map.of("x", -1L));
        assertThat(violated.finalValues()).isEqualTo(java.util.Map.of("x", -1L));
        assertThat(violated.pathId()).isEqualTo(0); // violation reported first
        Finding passed = engine.findings().stream()
                .filter(f -> f.kind() == Finding.Kind.ASSERT_PASSED).findFirst().orElseThrow();
        assertThat(passed.model()).isEqualTo(java.util.Map.of("x", 0L));
    }

    @Test
    void unrollsLoopUpToDeclaredBoundAndTerminates() {
        // i := 0; loop i < 100 (bound 5) { i := i + 1 }; assert i == 5.
        Engine engine = Engine.of("""
                {"variables":[{"name":"i"}],"statements":[
                  {"kind":"assign","line":1,"target":"i","expression":{"kind":"const","value":0}},
                  {"kind":"loop","line":2,"condition":{"kind":"binary","op":"lt",
                    "left":{"kind":"var","name":"i"},"right":{"kind":"const","value":100}},
                    "maxIterations":5,"body":[
                      {"kind":"assign","line":3,"target":"i","expression":
                        {"kind":"binary","op":"add","left":{"kind":"var","name":"i"},
                         "right":{"kind":"const","value":1}}}
                    ]},
                  {"kind":"assert","line":4,"condition":{"kind":"binary","op":"eq",
                    "left":{"kind":"var","name":"i"},"right":{"kind":"const","value":5}}}
                ]}
                """);
        engine.runAll(1000);
        // Deterministic: exactly one completed path, i ends at the bound 5.
        List<Finding> completed = engine.findings().stream()
                .filter(f -> f.kind() == Finding.Kind.PATH_COMPLETED).toList();
        assertThat(completed).hasSize(1);
        assertThat(completed.get(0).finalValues()).isEqualTo(java.util.Map.of("i", 5L));
    }

    @Test
    void prunesUnsatisfiableBranchesImmediately() {
        // Both sides contain an assert guarded by the opposite condition of
        // the outer split, so every assert is unreachable:
        //   x < 0 -> x > 0 -> assert x < 0   (infeasible twice)
        //   x >= 0 -> x < 0 -> assert x > 0  (infeasible twice)
        Engine engine = Engine.of("""
                {"variables":[{"name":"x","input":true}],"statements":[
                  {"kind":"if","line":1,"condition":{"kind":"binary","op":"lt",
                    "left":{"kind":"var","name":"x"},"right":{"kind":"const","value":0}},
                    "then":[
                      {"kind":"if","line":2,"condition":{"kind":"binary","op":"gt",
                        "left":{"kind":"var","name":"x"},"right":{"kind":"const","value":0}},
                        "then":[{"kind":"assert","line":3,"condition":{"kind":"binary","op":"lt",
                          "left":{"kind":"var","name":"x"},"right":{"kind":"const","value":0}}}]}
                    ],
                    "else":[
                      {"kind":"if","line":4,"condition":{"kind":"binary","op":"lt",
                        "left":{"kind":"var","name":"x"},"right":{"kind":"const","value":0}},
                        "then":[{"kind":"assert","line":5,"condition":{"kind":"binary","op":"gt",
                          "left":{"kind":"var","name":"x"},"right":{"kind":"const","value":0}}}]}
                    ]}
                ]}
                """);
        engine.runAll(1000);
        // Infeasible successors are dropped before entering the frontier, so
        // neither assert is ever reported (only the two feasible completions).
        assertThat(engine.findings()).extracting(Finding::kind)
                .containsOnly(Finding.Kind.PATH_COMPLETED);
        // ENTRY + outer BRANCH + two inner BRANCH + two EXIT states.
        assertThat(engine.executor.result().statesExplored()).isLessThanOrEqualTo(8L);
    }

    @Test
    void reportsFinalValuesOfAllCompletedPaths() {
        Engine engine = Engine.of("""
                {"variables":[{"name":"x","input":true},{"name":"y"}],"statements":[
                  {"kind":"if","line":1,"condition":{"kind":"binary","op":"eq",
                    "left":{"kind":"var","name":"x"},"right":{"kind":"const","value":7}},"then":[
                      {"kind":"assign","line":2,"target":"y","expression":{"kind":"const","value":1}}
                    ],"else":[
                      {"kind":"assign","line":3,"target":"y","expression":{"kind":"const","value":0}}
                    ]}
                ]}
                """);
        engine.runAll(1000);
        List<Finding> completed = engine.findings().stream()
                .filter(f -> f.kind() == Finding.Kind.PATH_COMPLETED)
                .sorted(java.util.Comparator.comparingInt(Finding::pathId)).toList();
        assertThat(completed).hasSize(2);
        assertThat(completed.get(0).model()).isEqualTo(java.util.Map.of("x", 7L));
        assertThat(completed.get(0).finalValues()).isEqualTo(java.util.Map.of("x", 7L, "y", 1L));
        assertThat(completed.get(1).model()).isEqualTo(java.util.Map.of("x", 0L));
        assertThat(completed.get(1).finalValues()).isEqualTo(java.util.Map.of("x", 0L, "y", 0L));
    }

    @Test
    void enumerationIsDeterministicAcrossRunsAndQuanta() {
        String json = """
                {"variables":[{"name":"x","input":true},{"name":"y"}],"statements":[
                  {"kind":"if","line":1,"condition":{"kind":"binary","op":"gt",
                    "left":{"kind":"var","name":"x"},"right":{"kind":"const","value":0}},"then":[
                      {"kind":"assign","line":2,"target":"y","expression":{"kind":"binary","op":"add",
                        "left":{"kind":"var","name":"x"},"right":{"kind":"const","value":10}}}
                    ],"else":[
                      {"kind":"assign","line":3,"target":"y","expression":{"kind":"const","value":0}}
                    ]},
                  {"kind":"assert","line":4,"condition":{"kind":"binary","op":"ne",
                    "left":{"kind":"var","name":"y"},"right":{"kind":"const","value":42}}}
                ]}
                """;
        Engine a = Engine.of(json);
        a.runAll(1);
        Engine b = Engine.of(json);
        b.runAll(1_000_000);
        assertThat(a.findings()).usingRecursiveFieldByFieldElementComparator()
                .isEqualTo(b.findings());
        // Second independent engine reproduces the same report.
        Engine c = Engine.of(json);
        c.runAll(17);
        assertThat(c.findings()).usingRecursiveFieldByFieldElementComparator()
                .isEqualTo(a.findings());
    }

    @Test
    void resumesFromCheckpointWithoutDroppingOrRepeatingPaths() {
        String json = """
                {"variables":[{"name":"x","input":true}],"statements":[
                  {"kind":"if","line":1,"condition":{"kind":"binary","op":"ge",
                    "left":{"kind":"var","name":"x"},"right":{"kind":"const","value":100}},"then":[
                      {"kind":"assert","line":2,"condition":{"kind":"binary","op":"ge",
                        "left":{"kind":"var","name":"x"},"right":{"kind":"const","value":0}}}
                    ],"else":[
                      {"kind":"if","line":3,"condition":{"kind":"binary","op":"lt",
                        "left":{"kind":"var","name":"x"},"right":{"kind":"const","value":0}},"then":[
                          {"kind":"assert","line":4,"condition":{"kind":"binary","op":"lt",
                            "left":{"kind":"var","name":"x"},"right":{"kind":"const","value":0}}}
                        ],"else":[
                          {"kind":"assert","line":5,"condition":{"kind":"binary","op":"eq",
                            "left":{"kind":"var","name":"x"},"right":{"kind":"const","value":0}}}
                        ]}
                    ]}
                ]}
                """;
        // Uninterrupted reference run.
        Engine reference = Engine.of(json);
        reference.runAll(1_000_000);

        // Interrupted run: tiny quanta, checkpoint after every one, restart.
        Engine interrupted = Engine.of(json);
        Checkpoint checkpoint = null;
        int restarts = 0;
        while (!interrupted.executor.isDone()) {
            interrupted.executor.step(1);
            checkpoint = interrupted.snapshot();
            interrupted = Engine.restored(interrupted, checkpoint);
            restarts++;
        }
        assertThat(restarts).isGreaterThan(5); // the run really was interrupted repeatedly
        assertThat(interrupted.executor.outcome())
                .isEqualTo(SymbolicExecutor.Outcome.COMPLETED);
        assertThat(interrupted.findings()).usingRecursiveFieldByFieldElementComparator()
                .isEqualTo(reference.findings());
        // Path ids unique and contiguous from 0.
        List<Integer> ids = interrupted.findings().stream().map(Finding::pathId).toList();
        assertThat(ids).doesNotHaveDuplicates();
        for (int i = 0; i < ids.size(); i++) {
            assertThat(ids.get(i)).isEqualTo(i);
        }
    }

    private static final String TWO_BRANCHES = """
            {"variables":[{"name":"x","input":true},{"name":"y"}],"statements":[
              {"kind":"if","line":1,"condition":{"kind":"binary","op":"gt",
                "left":{"kind":"var","name":"x"},"right":{"kind":"const","value":100}},
                "then":[{"kind":"assign","line":2,"target":"y","expression":{"kind":"const","value":1}}],
                "else":[{"kind":"assign","line":3,"target":"y","expression":{"kind":"const","value":0}}]},
              {"kind":"if","line":4,"condition":{"kind":"binary","op":"lt",
                "left":{"kind":"var","name":"x"},"right":{"kind":"const","value":-100}},
                "then":[{"kind":"assign","line":5,"target":"y",
                  "expression":{"kind":"binary","op":"add",
                    "left":{"kind":"var","name":"y"},"right":{"kind":"const","value":2}}}],
                "else":[]}
            ]}
            """;

    @Test
    void seedReproducesOrderAndDifferentSeedCanReorder() {
        // Same seed twice -> identical path id/model sequence.
        Engine a = Engine.of(TWO_BRANCHES, 12345);
        a.runAll(1000);
        Engine again = Engine.of(TWO_BRANCHES, 12345);
        again.runAll(1000);
        List<Integer> aIds = a.findings().stream().map(Finding::pathId).toList();
        List<java.util.Map<String, Long>> aModels = a.findings().stream()
                .map(Finding::model).toList();
        assertThat(again.findings().stream().map(Finding::pathId).toList()).isEqualTo(aIds);
        assertThat(again.findings().stream().map(Finding::model).toList()).isEqualTo(aModels);

        // A different seed keeps the same set of concrete models but may
        // report them in a different order; find a seed that reorders.
        List<java.util.Map<String, Long>> baseOrder =
                Engine.of(TWO_BRANCHES, 0).apply(e -> {
                    e.runAll(1000);
                    return e.findings().stream().map(Finding::model).toList();
                });
        List<java.util.Map<String, Long>> reordered = baseOrder;
        for (long candidate = 1; candidate <= 1_000 && reordered.equals(baseOrder);
             candidate++) {
            long seed = candidate;
            reordered = Engine.of(TWO_BRANCHES, seed).apply(e -> {
                e.runAll(1000);
                return e.findings().stream().map(Finding::model).toList();
            });
        }
        assertThat(reordered).isNotEqualTo(baseOrder);
        assertThat(reordered).containsExactlyInAnyOrderElementsOf(baseOrder);
    }

    @Test
    void splitsDisjunctiveOrConditionIntoTwoPaths() {
        // assert (x == 1 or x == 2): two passing models, plus failing path.
        Engine engine = Engine.of("""
                {"variables":[{"name":"x","input":true}],"statements":[
                  {"kind":"assert","line":1,"condition":{"kind":"binary","op":"or",
                    "left":{"kind":"binary","op":"eq","left":{"kind":"var","name":"x"},
                      "right":{"kind":"const","value":1}},
                    "right":{"kind":"binary","op":"eq","left":{"kind":"var","name":"x"},
                      "right":{"kind":"const","value":2}}}}
                ]}
                """);
        engine.runAll(1000);
        List<Finding> passed = engine.findings().stream()
                .filter(f -> f.kind() == Finding.Kind.ASSERT_PASSED).toList();
        assertThat(passed).extracting(f -> f.model().get("x"))
                .containsExactly(1L, 2L);
        List<Finding> violated = engine.findings().stream()
                .filter(f -> f.kind() == Finding.Kind.ASSERT_VIOLATED).toList();
        assertThat(violated).hasSize(1);
        // Nearest-to-zero failing assignment is x = 0.
        assertThat(violated.get(0).model()).isEqualTo(java.util.Map.of("x", 0L));
    }

    @Test
    void emptyProgramCompletesWithOnePath() {
        Engine engine = Engine.of("""
                {"variables":[{"name":"x","input":true}],"statements":[]}
                """);
        engine.runAll(1000);
        assertThat(engine.findings()).hasSize(1);
        assertThat(engine.findings().get(0).kind()).isEqualTo(Finding.Kind.PATH_COMPLETED);
    }
}
