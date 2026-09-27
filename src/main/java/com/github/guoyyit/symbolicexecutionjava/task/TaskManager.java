package com.github.guoyyit.symbolicexecutionjava.task;

import com.github.guoyyit.symbolicexecutionjava.cfg.Cfg;
import com.github.guoyyit.symbolicexecutionjava.cfg.CfgBuilder;
import com.github.guoyyit.symbolicexecutionjava.config.AnalysisProperties;
import com.github.guoyyit.symbolicexecutionjava.error.OverloadedException;
import com.github.guoyyit.symbolicexecutionjava.exec.Checkpoint;
import com.github.guoyyit.symbolicexecutionjava.exec.ExplorationResult;
import com.github.guoyyit.symbolicexecutionjava.exec.SymbolicExecutor;
import com.github.guoyyit.symbolicexecutionjava.lang.Program;
import com.github.guoyyit.symbolicexecutionjava.lang.VarDecl;
import com.github.guoyyit.symbolicexecutionjava.parse.ProgramParser;
import com.github.guoyyit.symbolicexecutionjava.solver.IntegerLinearSolver;
import com.github.guoyyit.symbolicexecutionjava.validate.StaticValidator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coordinates analysis tasks.
 *
 * <h3>Fairness and isolation</h3>
 * Worker threads pull task ids from a FIFO queue and execute one quantum
 * ({@code analysis.quantum-states} states); an unfinished task is
 * checkpointed and re-enqueued at the tail, so a slow task cannot starve
 * others. Each task owns its own engine, frontier and path conditions; limits
 * and cancellation are per-task.
 *
 * <h3>Cancellation / timeout / limits</h3>
 * The engine checks the cancel flag and the absolute nanoTime deadline before
 * every state expansion. On CANCELLED, TIMEOUT or LIMIT_EXCEEDED the snapshot
 * is deleted and no result is attached to the record: partial findings stay
 * invisible.
 *
 * <h3>Restart recovery</h3>
 * The record is persisted at admission and on every transition; after each
 * running quantum the full exploration snapshot (frontier + committed findings
 * + counters) is atomically written. On start-up, QUEUED/RUNNING tasks are
 * rebuilt from these files and re-enqueued, so reported paths are neither
 * repeated nor skipped.
 */
public final class TaskManager {

    private final AnalysisProperties properties;
    private final CheckpointStore store;

    private final ConcurrentHashMap<String, AnalysisTask> tasks = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final List<Thread> workers = new ArrayList<>();
    private final AtomicLong idSequence = new AtomicLong();
    private final AtomicLong deadlineClamp = new AtomicLong(Long.MAX_VALUE);

    /** Test hook: forces every freshly built engine's deadline to clampMs. */
    public void setDeadlineClampForTests(long clampMs) {
        this.deadlineClamp.set(clampMs);
    }

    public TaskManager(AnalysisProperties properties) {
        this.properties = properties;
        this.store = new CheckpointStore(Path.of(properties.getDataDir()));
    }

    public void start() {
        store.cleanupTemporaryFiles();
        resumePersistedTasks();
        int n = Math.max(1, properties.getWorkers());
        for (int i = 0; i < n; i++) {
            Thread worker = new Thread(this::workerLoop, "symbolic-worker-" + i);
            worker.setDaemon(true);
            worker.start();
            workers.add(worker);
        }
    }

    public String submit(TaskSpec spec) {
        int active = 0;
        for (AnalysisTask task : tasks.values()) {
            if (!isTerminal(task.status())) {
                active++;
            }
        }
        if (active >= properties.getMaxActiveTasks()) {
            throw new OverloadedException("too many active tasks (" + active
                    + "), retry later");
        }
        // Validate eagerly so malformed programs are rejected with a precise
        // location instead of being accepted as a failed task.
        parseAndValidate(spec);

        String id = newTaskId();
        AnalysisTask task = new AnalysisTask(id, spec, TaskStatus.QUEUED,
                System.currentTimeMillis());
        tasks.put(id, task);
        persist(task);
        queue.add(id);
        return id;
    }

    public Optional<AnalysisTask> get(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    public List<AnalysisTask> list() {
        return tasks.values().stream()
                .sorted((a, b) -> Long.compare(a.createdAt(), b.createdAt()))
                .toList();
    }

    public boolean cancel(String taskId) {
        AnalysisTask task = tasks.get(taskId);
        if (task == null || isTerminal(task.status())) {
            return false;
        }
        return task.requestCancel();
    }

    /**
     * Loads every persisted task after a restart. Terminal tasks are
     * registered in the in-memory index (with their stored result) so they
     * remain queryable; non-terminal tasks are rebuilt and re-enqueued.
     */
    public void resumePersistedTasks() {
        for (TaskRecord r : store.loadAllRecords()) {
            AnalysisTask t = new AnalysisTask(r.getId(), r.getSpec(),
                    r.getStatus(), r.getCreatedAt());
            t.setFinishedAt(r.getFinishedAt());
            if (r.getError() != null) {
                t.setError(r.getError());
            }
            if (r.getResult() != null) {
                t.setResult(r.getResult());
            }
            tasks.put(r.getId(), t);
            if (!isTerminal(r.getStatus())) {
                t.setStatus(TaskStatus.QUEUED);
                queue.add(r.getId());
            }
        }
    }

    public void shutdown() {
        for (Thread worker : workers) {
            worker.interrupt();
        }
        for (Thread worker : workers) {
            try {
                worker.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ---- scheduling ----

    private void workerLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            String id;
            try {
                id = queue.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (id == null) {
                continue;
            }
            AnalysisTask task = tasks.get(id);
            if (task == null || isTerminal(task.status())) {
                continue;
            }
            try {
                runQuantum(task);
            } catch (RuntimeException e) {
                failTask(task, e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    private void runQuantum(AnalysisTask task) {
        if (task.status() == TaskStatus.QUEUED) {
            task.setStatus(TaskStatus.RUNNING);
            persist(task);
        }
        SymbolicExecutor executor = task.executor();
        if (executor == null) {
            executor = buildExecutor(task);
            task.setExecutor(executor);
        }
        if (task.cancelRequested()) {
            executor.requestCancel();
        }
        executor.step(properties.getQuantumStates());
        switch (executor.outcome()) {
            case COMPLETED -> {
                ExplorationResult result = executor.result();
                task.setResult(result);
                task.setStatus(TaskStatus.COMPLETED);
                task.setFinishedAt(System.currentTimeMillis());
                persist(task);
                store.deleteCheckpoint(task.id());
            }
            case CANCELLED -> finishIncomplete(task, TaskStatus.CANCELLED);
            case TIMEOUT -> finishIncomplete(task, TaskStatus.TIMEOUT);
            case LIMIT_EXCEEDED -> finishIncomplete(task, TaskStatus.LIMIT_EXCEEDED);
            case RUNNING -> {
                store.saveCheckpoint(task.id(), executor.snapshot());
                queue.add(task.id());
            }
        }
    }

    private SymbolicExecutor buildExecutor(AnalysisTask task) {
        Program program = parseAndValidate(task.spec());
        Cfg cfg = new CfgBuilder().build(program);
        List<String> all = new ArrayList<>(program.variables().keySet());
        List<String> inputs = new ArrayList<>();
        for (VarDecl decl : program.variables().values()) {
            if (decl.input()) {
                inputs.add(decl.name());
            }
        }
        long timeoutMs = effectiveTimeoutMs(task.spec());
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        if (deadlineClamp.get() != Long.MAX_VALUE) {
            deadlineNanos = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(deadlineClamp.get());
        }
        SymbolicExecutor.Limits limits = new SymbolicExecutor.Limits(
                effectiveMaxStates(task.spec()), effectiveMaxPaths(task.spec()),
                deadlineNanos);
        IntegerLinearSolver solver = new IntegerLinearSolver();
        long seed = task.spec().getSeed();
        Optional<Checkpoint> checkpoint = store.loadCheckpoint(task.id());
        return checkpoint.map(cp -> SymbolicExecutor.fromCheckpoint(cfg, all, inputs,
                solver, seed, limits, cp)).orElseGet(() ->
                new SymbolicExecutor(cfg, all, inputs, solver, seed, limits));
    }

    private void finishIncomplete(AnalysisTask task, TaskStatus status) {
        task.setStatus(status);
        task.setFinishedAt(System.currentTimeMillis());
        persist(task);
        // No partial findings may survive: drop the exploration snapshot.
        store.deleteCheckpoint(task.id());
    }

    private void failTask(AnalysisTask task, String message) {
        task.setError(message);
        task.setStatus(TaskStatus.FAILED);
        task.setFinishedAt(System.currentTimeMillis());
        persist(task);
        store.deleteCheckpoint(task.id());
    }

    private Program parseAndValidate(TaskSpec spec) {
        Program program = new ProgramParser().parse(spec.getProgramJson());
        new StaticValidator().validate(program);
        return program;
    }

    private long effectiveTimeoutMs(TaskSpec spec) {
        long requested = spec.getTimeoutMs() == null
                ? properties.getDefaultTimeoutMs()
                : spec.getTimeoutMs();
        return Math.max(1L, requested);
    }

    private long effectiveMaxStates(TaskSpec spec) {
        long requested = spec.getMaxStates() == null
                ? properties.getDefaultMaxStates()
                : spec.getMaxStates();
        return Math.max(1L, requested);
    }

    private int effectiveMaxPaths(TaskSpec spec) {
        int requested = spec.getMaxPaths() == null
                ? properties.getDefaultMaxPaths()
                : spec.getMaxPaths();
        return Math.max(1, requested);
    }

    private void persist(AnalysisTask task) {
        TaskRecord record = new TaskRecord();
        record.setId(task.id());
        record.setSpec(task.spec());
        record.setStatus(task.status());
        record.setError(task.error());
        record.setResult(task.result());
        record.setCreatedAt(task.createdAt());
        record.setFinishedAt(task.finishedAt());
        store.saveRecord(record);
    }

    private static boolean isTerminal(TaskStatus status) {
        return status == TaskStatus.COMPLETED || status == TaskStatus.CANCELLED
                || status == TaskStatus.TIMEOUT || status == TaskStatus.LIMIT_EXCEEDED
                || status == TaskStatus.FAILED;
    }

    private synchronized String newTaskId() {
        long sequence = idSequence.incrementAndGet();
        String candidate;
        do {
            candidate = String.format("task-%013d-%04d",
                    System.currentTimeMillis(), sequence & 0xFFFF);
        } while (tasks.containsKey(candidate));
        return candidate;
    }
}
