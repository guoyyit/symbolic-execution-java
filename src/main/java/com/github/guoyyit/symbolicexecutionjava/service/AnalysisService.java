package com.github.guoyyit.symbolicexecutionjava.service;

import com.github.guoyyit.symbolicexecutionjava.api.AnalysisResult;
import com.github.guoyyit.symbolicexecutionjava.cfg.Cfg;
import com.github.guoyyit.symbolicexecutionjava.cfg.CfgBuilder;
import com.github.guoyyit.symbolicexecutionjava.checkpoint.Checkpoint;
import com.github.guoyyit.symbolicexecutionjava.checkpoint.CheckpointStore;
import com.github.guoyyit.symbolicexecutionjava.checkpoint.PathSnapshot;
import com.github.guoyyit.symbolicexecutionjava.engine.EngineConfig;
import com.github.guoyyit.symbolicexecutionjava.engine.ExecutionLimits;
import com.github.guoyyit.symbolicexecutionjava.engine.PathListener;
import com.github.guoyyit.symbolicexecutionjava.engine.PathOutcome;
import com.github.guoyyit.symbolicexecutionjava.engine.PathReport;
import com.github.guoyyit.symbolicexecutionjava.engine.SymbolicEngine;
import com.github.guoyyit.symbolicexecutionjava.api.AnalysisRequest;
import com.github.guoyyit.symbolicexecutionjava.parser.ParseException;
import com.github.guoyyit.symbolicexecutionjava.parser.Parser;
import com.github.guoyyit.symbolicexecutionjava.solver.LinearSolver;
import com.github.guoyyit.symbolicexecutionjava.validation.ResolvedProgram;
import com.github.guoyyit.symbolicexecutionjava.validation.ValidationException;
import com.github.guoyyit.symbolicexecutionjava.validation.Validator;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Submits, tracks, cancels and resumes isolated symbolic-analysis jobs.
 *
 * <p>Jobs run on a bounded thread pool, so a slow task cannot block other
 * submissions. Each task has its own solver/engine, per-task resource limits
 * and an independent cancellation flag. On timeout or cancellation the engine
 * is interrupted mid-exploration and no partial result is exposed through the
 * result API; on timeout the durable checkpoint is retained so a later resume
 * continues from the last committed state, while explicit cancellation deletes
 * it. Every reported path is committed to the checkpoint store before it
 * becomes visible, which makes restart/resume neither re-report nor miss
 * already discovered paths.</p>
 */
@Service
public class AnalysisService {

    private final ServiceConfig config;
    private final CheckpointStore checkpointStore;
    private final ExecutorService workers;
    private final Map<String, SubmittedJob> jobs = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public AnalysisService(ServiceConfig config, CheckpointStore checkpointStore) {
        this.config = config;
        this.checkpointStore = checkpointStore;
        this.workers = Executors.newFixedThreadPool(Math.max(1, config.getWorkerThreads()),
                r -> {
                    Thread t = new Thread(r, "analysis-worker");
                    t.setDaemon(true);
                    return t;
                });
    }

    public AnalysisResult submit(AnalysisRequest request) {
        String source = request.program() == null ? "" : request.program();
        String hash = hash(source);
        long seed = request.seed() == null ? config.getDefaultSeed() : request.seed();

        // Fail fast on syntax / semantic errors and return positioned rejection.
        PreparedProgram prepared = prepare(source);

        String jobId = newJobId(hash);
        SubmittedJob job = new SubmittedJob(jobId, hash, source, seed);
        jobs.put(jobId, job);
        schedule(job, prepared, resolveLimits(request.limits()), null);
        return toResult(job);
    }

    public AnalysisResult getResult(String jobId) {
        SubmittedJob job = requireJob(jobId);
        return toResult(job);
    }

    public void cancel(String jobId) {
        SubmittedJob job = requireJob(jobId);
        job.cancellation().set(true);
        Thread w = job.worker();
        if (w != null) {
            w.interrupt();
        }
        // Explicit cancellation leaves no partial result or checkpoint.
        job.paths().clear();
        checkpointStore.delete(jobId);
        job.setStatus(JobStatus.CANCELLED);
    }

    public AnalysisResult resume(String jobId) {
        return resume(jobId, null);
    }

    public AnalysisResult resume(String jobId, AnalysisRequest.AnalysisLimitsDto limitsDto) {
        SubmittedJob job = requireJob(jobId);
        Optional<Checkpoint> saved = checkpointStore.load(jobId);
        if (saved.isEmpty()) {
            // Nothing durable to resume from; return current state.
            return toResult(job);
        }
        Checkpoint checkpoint = saved.get();
        if (!checkpoint.programHash().equals(job.programHash())) {
            throw new IllegalStateException("checkpoint belongs to a different program");
        }
        if (checkpoint.complete()) {
            job.setStatus(JobStatus.COMPLETED);
            return toResult(job);
        }
        if (job.status() == JobStatus.CANCELLED) {
            throw new IllegalStateException("job was cancelled and cannot be resumed");
        }
        PreparedProgram prepared = prepare(job.source());
        synchronized (job.paths()) {
            job.paths().clear();
            job.paths().addAll(checkpoint.paths());
        }
        job.cancellation().set(false);
        job.setStatus(JobStatus.QUEUED);
        ExecutionLimits limits = resolveLimits(limitsDto);
        schedule(job, prepared, limits, checkpoint);
        return toResult(job);
    }

    // ----- internals -----

    private void schedule(SubmittedJob job, PreparedProgram prepared, ExecutionLimits limits,
                          Checkpoint resumeFrom) {
        long generation = job.nextGeneration();
        job.setStatus(JobStatus.RUNNING);
        workers.submit(() -> runJob(job, prepared, limits, resumeFrom, generation));
    }

    private void runJob(SubmittedJob job, PreparedProgram prepared,
                        ExecutionLimits limits, Checkpoint resumeFrom, long generation) {
        job.setWorker(Thread.currentThread());
        try {
            LinearSolver solver = new LinearSolver(job.seed(),
                    config.getSolverLowerBound(), config.getSolverUpperBound());
            EngineConfig engineConfig = new EngineConfig(job.seed(),
                    config.getSolverLowerBound(), config.getSolverUpperBound());
            SymbolicEngine engine = new SymbolicEngine(prepared.resolved(), prepared.cfg(),
                    solver, engineConfig);

            PathListener listener = report -> {
                // A newer run (resume) supersedes this one: do not clobber its state.
                if (job.generation() != generation) {
                    return;
                }
                PathSnapshot snapshot = toSnapshot(report);
                synchronized (job.paths()) {
                    job.paths().add(snapshot);
                    // Commit before the path is considered discovered.
                    checkpointStore.save(job.checkpoint());
                }
            };

            engine.run(limits, job.cancellation(), listener, resumeFrom);
            if (job.generation() != generation) {
                return;
            }
            job.setComplete(true);
            checkpointStore.save(job.checkpoint());
            job.setStatus(JobStatus.COMPLETED);
        } catch (com.github.guoyyit.symbolicexecutionjava.engine.CancelledException e) {
            if (job.generation() != generation) {
                return;
            }
            if (e.timeout()) {
                // Persist whatever was durably committed so a later resume can
                // continue from the last reported path. No partial result is
                // presented as a finished analysis.
                synchronized (job.paths()) {
                    if (!job.paths().isEmpty()) {
                        checkpointStore.save(job.checkpoint());
                    }
                }
                job.setStatus(JobStatus.TIMEOUT);
            } else {
                job.setStatus(JobStatus.CANCELLED);
            }
        } catch (com.github.guoyyit.symbolicexecutionjava.engine.PathLimitException e) {
            if (job.generation() != generation) {
                return;
            }
            // Reached the path budget: persist an INCOMPLETE checkpoint so a
            // later resume can enumerate the rest. No partial result is shown.
            synchronized (job.paths()) {
                if (!job.paths().isEmpty()) {
                    checkpointStore.save(job.checkpoint());
                }
            }
            job.setStatus(JobStatus.LIMITED);
        } catch (RuntimeException e) {
            if (job.generation() != generation) {
                return;
            }
            job.setStatus(JobStatus.FAILED);
            job.setError(e.getMessage());
        } finally {
            if (job.generation() == generation) {
                job.setWorker(null);
            }
        }
    }

    private PreparedProgram prepare(String source) {
        try {
            var program = Parser.parseSource(source);
            ResolvedProgram resolved = new Validator().validate(program);
            Cfg cfg = new CfgBuilder().build(resolved);
            return new PreparedProgram(resolved, cfg);
        } catch (ParseException e) {
            throw new PositionedRejection(e.getMessage(), position(e.position()));
        } catch (ValidationException e) {
            throw new PositionedRejection(e.getMessage(), position(e.position()));
        }
    }

    private ExecutionLimits resolveLimits(AnalysisRequest.AnalysisLimitsDto dto) {
        long timeout = dto == null || dto.timeoutMillis() == null
                ? config.getDefaultTimeoutMillis() : dto.timeoutMillis();
        int maxPaths = dto == null || dto.maxPaths() == null
                ? config.getDefaultMaxPaths() : dto.maxPaths();
        int maxUnroll = dto == null || dto.maxUnroll() == null
                ? config.getDefaultMaxUnroll() : dto.maxUnroll();
        int maxSteps = dto == null || dto.maxSteps() == null
                ? config.getDefaultMaxSteps() : dto.maxSteps();
        int maxBacktracks = dto == null || dto.maxBacktracks() == null
                ? config.getDefaultMaxBacktracks() : dto.maxBacktracks();
        return new ExecutionLimits(timeout, maxPaths, maxUnroll, maxSteps, maxBacktracks);
    }

    private PathSnapshot toSnapshot(PathReport report) {
        return new PathSnapshot(report.pathId(), report.outcome(),
                List.copyOf(report.constraints()),
                Map.copyOf(report.inputModel()),
                Map.copyOf(report.finalValues()),
                report.targetDescription());
    }

    private AnalysisResult toResult(SubmittedJob job) {
        // Snapshot under the list's own monitor so a concurrently running worker
        // appending newly committed paths cannot cause ConcurrentModification.
        final List<PathSnapshot> snapshot;
        synchronized (job.paths()) {
            snapshot = List.copyOf(job.paths());
        }
        List<AnalysisResult.PathView> views = snapshot.stream()
                .map(s -> new AnalysisResult.PathView(s.pathId(), s.outcome().name(),
                        s.constraints(), s.inputModel(), s.finalValues(), s.targetDescription()))
                .toList();
        long success = snapshot.stream()
                .filter(p -> p.outcome() == PathOutcome.SUCCESS).count();
        long failure = snapshot.stream()
                .filter(p -> p.outcome() == PathOutcome.ASSERTION_FAILED).count();
        boolean hidePartial = job.status() == JobStatus.CANCELLED
                || job.status() == JobStatus.TIMEOUT
                || job.status() == JobStatus.LIMITED;
        List<AnalysisResult.PathView> exposed = hidePartial ? List.of() : views;
        int total = hidePartial ? 0 : snapshot.size();
        return new AnalysisResult(job.id(), job.status(), total,
                hidePartial ? 0 : (int) success,
                hidePartial ? 0 : (int) failure,
                exposed, job.error(), job.errorPosition());
    }

    private SubmittedJob requireJob(String jobId) {
        SubmittedJob job = jobs.get(jobId);
        if (job == null) {
            throw new JobNotFoundException(jobId);
        }
        return job;
    }

    private AnalysisResult.PositionView position(com.github.guoyyit.symbolicexecutionjava.model.ast.Position p) {
        if (p == null) {
            return null;
        }
        return new AnalysisResult.PositionView(p.line(), p.column());
    }

    private String newJobId(String hash) {
        return Long.toHexString(System.nanoTime()) + "-" + sequence.incrementAndGet()
                + "-" + hash.substring(0, Math.min(8, hash.length()));
    }

    private static String hash(String source) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Raised when a client references an unknown job id. */
    public static final class JobNotFoundException extends RuntimeException {
        public JobNotFoundException(String jobId) {
            super("unknown analysis job: " + jobId);
        }
    }

    /** Raised when parsing or validation rejects a submission; carries a position. */
    public static final class PositionedRejection extends RuntimeException {
        private final AnalysisResult.PositionView position;

        public PositionedRejection(String message, AnalysisResult.PositionView position) {
            super(message);
            this.position = position;
        }

        public AnalysisResult.PositionView position() {
            return position;
        }
    }

    private record PreparedProgram(ResolvedProgram resolved, Cfg cfg) {
    }

    // Exposed for graceful shutdown.
    public void shutdown() {
        workers.shutdownNow();
        try {
            workers.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
