package com.github.guoyyit.symbolicexecutionjava.tests;

import com.github.guoyyit.symbolicexecutionjava.cfg.Cfg;
import com.github.guoyyit.symbolicexecutionjava.cfg.CfgBuilder;
import com.github.guoyyit.symbolicexecutionjava.checkpoint.Checkpoint;
import com.github.guoyyit.symbolicexecutionjava.checkpoint.FileCheckpointStore;
import com.github.guoyyit.symbolicexecutionjava.checkpoint.PathSnapshot;
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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckpointResumeTest {

    private static final String PROGRAM = """
            int x;
            int y;
            if (x + y > 0) {
                assert(x >= 0);
            } else {
                assert(y <= 0);
            }
            """;

    private List<PathReport> runFresh(List<PathSnapshot> sink) {
        ResolvedProgram resolved = new Validator().validate(Parser.parseSource(PROGRAM));
        Cfg cfg = new CfgBuilder().build(resolved);
        LinearSolver solver = new LinearSolver(0, -16, 16);
        SymbolicEngine engine = new SymbolicEngine(resolved, cfg, solver,
                new EngineConfig(0, -16, 16));
        List<PathReport> reports = new ArrayList<>();
        engine.run(new ExecutionLimits(10_000, 1000, 64, 1_000_000, 100_000),
                new AtomicBoolean(false), reports::add, null);
        reports.forEach(r -> sink.add(new PathSnapshot(r.pathId(), r.outcome(),
                r.constraints(), r.inputModel(), r.finalValues(), r.targetDescription())));
        return reports;
    }

    @Test
    void freshRunIsReplayableViaCheckpointWithoutDuplicatesOrGaps(@TempDir Path dir) {
        FileCheckpointStore store = new FileCheckpointStore(dir);

        // Baseline full enumeration.
        List<PathSnapshot> baseline = new ArrayList<>();
        runFresh(baseline);
        int total = baseline.size();
        assertTrue(total >= 2);

        // Simulate an interrupted run that committed only the first k paths.
        int k = total / 2;
        List<PathSnapshot> partial = new ArrayList<>(baseline.subList(0, k));
        Checkpoint interrupted = new Checkpoint("job-1", "hash", k, partial, false);
        store.save(interrupted);

        // Resume from the durable checkpoint: engine replays deterministic
        // enumeration, skipping the k committed paths.
        Checkpoint loaded = store.load("job-1").orElseThrow();
        List<PathReport> resumedReports = new ArrayList<>();
        ResolvedProgram resolved = new Validator().validate(Parser.parseSource(PROGRAM));
        Cfg cfg = new CfgBuilder().build(resolved);
        LinearSolver solver = new LinearSolver(0, -16, 16);
        SymbolicEngine engine = new SymbolicEngine(resolved, cfg, solver,
                new EngineConfig(0, -16, 16));
        engine.run(new ExecutionLimits(10_000, 1000, 64, 1_000_000, 100_000),
                new AtomicBoolean(false), resumedReports::add, loaded);

        // The resumed engine reports only the previously unseen suffix.
        assertEquals(total - k, resumedReports.size());

        // Merge committed + resumed and compare against the full baseline.
        List<PathSnapshot> merged = new ArrayList<>(partial);
        resumedReports.forEach(r -> merged.add(new PathSnapshot(r.pathId(), r.outcome(),
                r.constraints(), r.inputModel(), r.finalValues(), r.targetDescription())));

        assertEquals(total, merged.size());
        for (int i = 0; i < total; i++) {
            PathSnapshot a = baseline.get(i);
            PathSnapshot b = merged.get(i);
            assertEquals(a.pathId(), b.pathId());
            assertEquals(a.outcome(), b.outcome());
            assertEquals(a.constraints(), b.constraints());
            assertEquals(a.inputModel(), b.inputModel());
            assertEquals(a.finalValues(), b.finalValues());
        }
    }

    @Test
    void persistedCheckpointSurvivesStoreRecreation(@TempDir Path dir) {
        FileCheckpointStore first = new FileCheckpointStore(dir);
        List<PathSnapshot> sink = new ArrayList<>();
        runFresh(sink);
        Checkpoint cp = new Checkpoint("persist", "h", sink.size(), sink, true);
        first.save(cp);

        FileCheckpointStore second = new FileCheckpointStore(dir);
        Checkpoint reloaded = second.load("persist").orElseThrow();
        assertEquals(sink.size(), reloaded.paths().size());
        assertTrue(reloaded.complete());

        second.delete("persist");
        assertTrue(second.load("persist").isEmpty());
    }
}
