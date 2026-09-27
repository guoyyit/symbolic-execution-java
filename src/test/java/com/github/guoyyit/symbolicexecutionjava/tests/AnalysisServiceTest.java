package com.github.guoyyit.symbolicexecutionjava.tests;

import com.github.guoyyit.symbolicexecutionjava.api.AnalysisRequest;
import com.github.guoyyit.symbolicexecutionjava.api.AnalysisResult;
import com.github.guoyyit.symbolicexecutionjava.checkpoint.FileCheckpointStore;
import com.github.guoyyit.symbolicexecutionjava.engine.PathOutcome;
import com.github.guoyyit.symbolicexecutionjava.service.AnalysisService;
import com.github.guoyyit.symbolicexecutionjava.service.JobStatus;
import com.github.guoyyit.symbolicexecutionjava.service.ServiceConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisServiceTest {

    private AnalysisService service;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        ServiceConfig config = new ServiceConfig();
        config.setCheckpointDir(dir.resolve("cp").toString());
        config.setWorkerThreads(4);
        config.setDefaultTimeoutMillis(2000);
        service = new AnalysisService(config, new FileCheckpointStore(dir.resolve("cp")));
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private AnalysisRequest request(String program, Long seed) {
        return new AnalysisRequest(program, seed, null);
    }

    @Test
    void analyzesSimpleProgramToCompletion() throws Exception {
        AnalysisResult result = service.submit(request("int x; assert(x == 0);", 0L));
        result = awaitCompleted(result.jobId());
        assertEquals(JobStatus.COMPLETED, result.status());
        assertEquals(2, result.totalPaths());
        assertEquals(1, result.successCount());
        assertEquals(1, result.failureCount());
    }

    @Test
    void rejectsUndefinedVariableWithPosition() {
        AnalysisService.PositionedRejection ex = assertThrows(
                AnalysisService.PositionedRejection.class,
                () -> service.submit(request("int x = y;", 0L)));
        assertTrue(ex.getMessage().contains("undefined variable 'y'"));
        assertNotNull(ex.position());
        assertEquals(1, ex.position().line());
        assertEquals(9, ex.position().column());
    }

    @Test
    void rejectsMissingLoopBoundWithPosition() {
        AnalysisService.PositionedRejection ex = assertThrows(
                AnalysisService.PositionedRejection.class,
                () -> service.submit(
                        request("int i; for (i = 0; i < 3 && i > 0; i = i + 1) { }", 0L)));
        assertTrue(ex.getMessage().contains("single comparison"));
        assertNotNull(ex.position());
    }

    @Test
    void unknownJobReturnsNotFound() {
        assertThrows(AnalysisService.JobNotFoundException.class,
                () -> service.getResult("does-not-exist"));
    }

    @Test
    void multipleJobsRunConcurrentlyWithoutBlocking() throws Exception {
        String program = "int x; int y; assert(x + 2*y >= 0);";
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            ids.add(service.submit(request(program, (long) i)).jobId());
        }
        for (String id : ids) {
            AnalysisResult r = awaitCompleted(id);
            assertEquals(JobStatus.COMPLETED, r.status(), id);
        }
    }

    @Test
    void cancelLeavesNoPartialResult() throws Exception {
        // Program with a symbolic-bound loop limited only by a high unroll cap.
        String program = "int n; for (int i = 0; i < n; i = i + 1) { assert(i >= 0); }";
        AnalysisResult submitted = service.submit(new AnalysisRequest(program, 0L,
                new AnalysisRequest.AnalysisLimitsDto(60_000L, 100_000, 100_000,
                        100_000_000, 100_000_000)));
        // Give it a moment to start then cancel.
        Thread.sleep(100);
        service.cancel(submitted.jobId());
        AnalysisResult after = service.getResult(submitted.jobId());
        assertEquals(JobStatus.CANCELLED, after.status());
        assertEquals(0, after.totalPaths());
        assertTrue(after.paths() == null || after.paths().isEmpty());
    }

    @Test
    void timeoutProducesNoPartialResult() throws Exception {
        String program = "int n; for (int i = 0; i < n; i = i + 1) { assert(i >= 0); }";
        AnalysisResult submitted = service.submit(new AnalysisRequest(program, 0L,
                new AnalysisRequest.AnalysisLimitsDto(50L, 100_000, 1_000_000,
                        100_000_000, 100_000_000)));
        AnalysisResult result = awaitStatus(submitted.jobId(),
                JobStatus.COMPLETED, JobStatus.TIMEOUT, JobStatus.FAILED);
        assertEquals(JobStatus.TIMEOUT, result.status());
        // No partial result surfaced for a timeout: the path list is empty.
        assertTrue(result.paths() == null || result.paths().isEmpty());
    }

    @Test
    void deterministicForSameProgramAndSeed() throws Exception {
        String program = """
                int x;
                if (x > 0) {
                    assert(x == 1);
                } else {
                    assert(x == 0);
                }
                """;
        AnalysisResult a = awaitCompleted(service.submit(request(program, 42L)).jobId());
        AnalysisResult b = awaitCompleted(service.submit(request(program, 42L)).jobId());
        assertEquals(a.totalPaths(), b.totalPaths());
        for (int i = 0; i < a.paths().size(); i++) {
            assertEquals(a.paths().get(i).constraints(), b.paths().get(i).constraints());
            assertEquals(a.paths().get(i).inputModel(), b.paths().get(i).inputModel());
            assertEquals(a.paths().get(i).pathId(), b.paths().get(i).pathId());
        }
        // A different seed may change satisfying models but structure stays.
        AnalysisResult c = awaitCompleted(service.submit(request(program, 43L)).jobId());
        assertEquals(a.totalPaths(), c.totalPaths());
    }

    @Test
    void resumeReusesIdAndDoesNotDuplicateOrLosePaths() throws Exception {
        String program = "int x; assert(x == 0);";
        AnalysisResult first = awaitCompleted(service.submit(request(program, 0L)).jobId());
        int firstCount = first.totalPaths();

        AnalysisResult resumed = service.resume(first.jobId());
        resumed = awaitCompleted(resumed.jobId());
        // Complete analysis resumes to the same set without duplication.
        assertEquals(firstCount, resumed.totalPaths());
        assertEquals(first.paths().get(0).pathId(), resumed.paths().get(0).pathId());
    }

    private AnalysisResult awaitCompleted(String jobId) throws InterruptedException {
        return awaitStatus(jobId, JobStatus.COMPLETED, JobStatus.FAILED,
                JobStatus.CANCELLED, JobStatus.TIMEOUT);
    }

    private AnalysisResult awaitStatus(String jobId, JobStatus... anyOf)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        AnalysisResult r;
        do {
            r = service.getResult(jobId);
            for (JobStatus s : anyOf) {
                if (r.status() == s) {
                    return r;
                }
            }
            Thread.sleep(5);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("job " + jobId + " did not reach terminal state, last="
                + r.status());
    }
}
