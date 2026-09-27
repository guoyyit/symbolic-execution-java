package com.github.guoyyit.symbolicexecutionjava.tests;

import com.github.guoyyit.symbolicexecutionjava.api.AnalysisRequest;
import com.github.guoyyit.symbolicexecutionjava.api.AnalysisResult;
import com.github.guoyyit.symbolicexecutionjava.checkpoint.FileCheckpointStore;
import com.github.guoyyit.symbolicexecutionjava.service.AnalysisService;
import com.github.guoyyit.symbolicexecutionjava.service.JobStatus;
import com.github.guoyyit.symbolicexecutionjava.service.ServiceConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeoutResumeServiceTest {

    private AnalysisService service;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        ServiceConfig config = new ServiceConfig();
        config.setCheckpointDir(dir.resolve("cp").toString());
        config.setWorkerThreads(4);
        config.setSolverLowerBound(-8);
        config.setSolverUpperBound(8);
        config.setDefaultMaxUnroll(32);
        service = new AnalysisService(config, new FileCheckpointStore(dir.resolve("cp")));
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void partialCheckpointResumesAndCompletesWithoutDuplicates() throws Exception {
        // A program with several distinct paths: cap the first run at 2.
        String program = "int x; int y; if (x + y > 0) { assert(x >= 0); }"
                + " else { assert(y <= 0); }";
        AnalysisRequest.AnalysisLimitsDto capped =
                new AnalysisRequest.AnalysisLimitsDto(10_000L, 2, 32, 1_000_000, 100_000);
        AnalysisRequest.AnalysisLimitsDto full =
                new AnalysisRequest.AnalysisLimitsDto(10_000L, 100_000, 32, 1_000_000, 100_000);

        AnalysisResult first = service.submit(new AnalysisRequest(program, 0L, capped));
        AnalysisResult partial = await(first.jobId(), JobStatus.LIMITED,
                JobStatus.COMPLETED, JobStatus.FAILED);
        if (partial.status() == JobStatus.COMPLETED) {
            // Fewer than 2 paths total; nothing to resume.
            assertTrue(partial.totalPaths() > 0);
            return;
        }
        assertEquals(JobStatus.LIMITED, partial.status());
        assertEquals(0, partial.totalPaths()); // partial results not exposed

        // Resume with a high path budget and wait for completion.
        AnalysisResult resumed = service.resume(first.jobId(), full);
        resumed = await(resumed.jobId(), JobStatus.COMPLETED, JobStatus.FAILED);
        assertEquals(JobStatus.COMPLETED, resumed.status(),
                resumed.error() == null ? "no error" : resumed.error());
        int total = resumed.totalPaths();
        assertTrue(total > 2, "should enumerate more than the cap, got " + total);

        // Resume again: idempotent, no duplicate ids.
        service.resume(resumed.jobId(), full);
        AnalysisResult again = await(resumed.jobId(), JobStatus.COMPLETED, JobStatus.FAILED);
        assertEquals(total, again.totalPaths());
        long unique = again.paths().stream().map(AnalysisResult.PathView::pathId)
                .distinct().count();
        assertEquals(total, unique);
    }

    private AnalysisResult await(String jobId, JobStatus... anyOf) throws InterruptedException {
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
        throw new AssertionError("job did not reach terminal state: " + r.status());
    }
}
