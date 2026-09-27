package com.github.guoyyit.symbolicexecutionjava.task;

import org.junit.jupiter.api.Test;
import com.github.guoyyit.symbolicexecutionjava.config.AnalysisProperties;
import com.github.guoyyit.symbolicexecutionjava.exec.Finding;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

class TaskManagerTest {

    @TempDir
    Path tempDir;

    private AnalysisProperties properties(int workers, long quantum) {
        AnalysisProperties props = new AnalysisProperties();
        props.setWorkers(workers);
        props.setQuantumStates(quantum);
        props.setDataDir(tempDir.resolve("data").toString());
        props.setDefaultTimeoutMs(60_000);
        props.setDefaultMaxStates(10_000_000);
        props.setDefaultMaxPaths(100_000);
        props.setMaxActiveTasks(8);
        return props;
    }

    private String loopProgram(int bound) {
        // i := 0; loop i < huge { i := i + 1 }; assert i == bound.
        return """
                {"variables":[{"name":"i"}],"statements":[
                  {"kind":"assign","line":1,"target":"i","expression":{"kind":"const","value":0}},
                  {"kind":"loop","line":2,"condition":{"kind":"binary","op":"lt",
                    "left":{"kind":"var","name":"i"},"right":{"kind":"const","value":999999999}},
                    "maxIterations":%d,"body":[
                      {"kind":"assign","line":3,"target":"i","expression":
                        {"kind":"binary","op":"add","left":{"kind":"var","name":"i"},
                         "right":{"kind":"const","value":1}}}
                    ]},
                  {"kind":"assert","line":4,"condition":{"kind":"binary","op":"eq",
                    "left":{"kind":"var","name":"i"},"right":{"kind":"const","value":%d}}}
                ]}
                """.formatted(bound, bound);
    }

    private String branchProgram() {
        return """
                {"variables":[{"name":"x","input":true}],"statements":[
                  {"kind":"assert","line":1,"condition":{"kind":"binary","op":"ge",
                    "left":{"kind":"var","name":"x"},"right":{"kind":"const","value":0}}}
                ]}
                """;
    }

    private void awaitTerminal(TaskManager manager, String id, TaskStatus expected) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(10))
                .until(() -> manager.get(id)
                        .map(t -> t.status() == expected).orElse(false));
    }

    @Test
    void completesTaskAndExposesResult() {
        TaskManager manager = new TaskManager(properties(1, 64));
        manager.start();
        try {
            String id = manager.submit(new TaskSpec(branchProgram(), 0, null, null, null));
            awaitTerminal(manager, id, TaskStatus.COMPLETED);
            AnalysisTask task = manager.get(id).orElseThrow();
            assertThat(task.result()).isNotNull();
            assertThat(task.result().findings()).extracting(Finding::kind)
                    .contains(Finding.Kind.ASSERT_VIOLATED, Finding.Kind.ASSERT_PASSED);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void slowTaskDoesNotBlockFastTasks() {
        TaskManager manager = new TaskManager(properties(2, 4));
        manager.start();
        try {
            String slow = manager.submit(new TaskSpec(loopProgram(2_000_000), 0,
                    null, null, null));
            // Give the slow task a head start so it occupies a worker.
            await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(5))
                    .until(() -> manager.get(slow)
                            .map(t -> t.status() == TaskStatus.RUNNING).orElse(false));
            List<String> fast = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                fast.add(manager.submit(new TaskSpec(branchProgram(), 0, null, null, null)));
            }
            for (String id : fast) {
                awaitTerminal(manager, id, TaskStatus.COMPLETED);
            }
            manager.cancel(slow);
            awaitTerminal(manager, slow, TaskStatus.CANCELLED);
            // Cancelled task must not expose partial results.
            assertThat(manager.get(slow).orElseThrow().result()).isNull();
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void timedOutTaskKeepsNoPartialResult() throws Exception {
        AnalysisProperties props = properties(2, 2);
        TaskManager manager = new TaskManager(props);
        manager.setDeadlineClampForTests(30);
        manager.start();
        try {
            String id = manager.submit(new TaskSpec(loopProgram(10_000_000), 0,
                    null, null, null));
            awaitTerminal(manager, id, TaskStatus.TIMEOUT);
            AnalysisTask task = manager.get(id).orElseThrow();
            assertThat(task.result()).isNull();
            assertThat(task.status()).isEqualTo(TaskStatus.TIMEOUT);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void rejectsOverCapacityAdmission() {
        AnalysisProperties props = properties(1, 1);
        props.setMaxActiveTasks(2);
        TaskManager manager = new TaskManager(props);
        manager.start();
        try {
            manager.submit(new TaskSpec(loopProgram(10_000_000), 0, null, null, null));
            manager.submit(new TaskSpec(loopProgram(10_000_000), 0, null, null, null));
            org.junit.jupiter.api.Assertions.assertThrows(
                    com.github.guoyyit.symbolicexecutionjava.error.OverloadedException.class,
                    () -> manager.submit(new TaskSpec(loopProgram(10_000_000), 0,
                            null, null, null)));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void resumesFromCheckpointAfterRestart() {
        AnalysisProperties props = properties(1, 7);
        String id;
        TaskManager first = new TaskManager(props);
        first.start();
        try {
            id = first.submit(new TaskSpec(loopProgram(5_000), 0, null, null, null));
            // Wait until at least one committed quantum has been flushed to
            // disk, so the crash really happens mid-exploration.
            java.nio.file.Path cp = tempDir.resolve("data").resolve("tasks")
                    .resolve(id).resolve("checkpoint.json");
            await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(5))
                    .until(() -> java.nio.file.Files.exists(cp));
            first.shutdown();
        } finally {
            first.shutdown();
        }

        // New process: rebuild from persisted record and checkpoint.
        TaskManager second = new TaskManager(properties(1, 7));
        second.start();
        try {
            awaitTerminal(second, id, TaskStatus.COMPLETED);
            AnalysisTask task = second.get(id).orElseThrow();
            assertThat(task.result()).isNotNull();
            assertThat(task.result().findings())
                    .extracting(Finding::pathId)
                    .doesNotHaveDuplicates();
            List<Finding> paths = task.result().findings();
            // One assert violation/pass outcome plus one completed path.
            assertThat(paths).isNotEmpty();
            assertThat(paths.get(paths.size() - 1).kind())
                    .isEqualTo(Finding.Kind.PATH_COMPLETED);
            assertThat(paths.get(paths.size() - 1).finalValues())
                    .isEqualTo(java.util.Map.of("i", 5_000L));
        } finally {
            second.shutdown();
        }
    }

    @Test
    void sameProgramProducesIdenticalReports() {
        TaskManager manager = new TaskManager(properties(2, 13));
        manager.start();
        try {
            String a = manager.submit(new TaskSpec(branchProgram(), 42, null, null, null));
            String b = manager.submit(new TaskSpec(branchProgram(), 42, null, null, null));
            awaitTerminal(manager, a, TaskStatus.COMPLETED);
            awaitTerminal(manager, b, TaskStatus.COMPLETED);
            List<?> ra = manager.get(a).orElseThrow().result().findings();
            List<?> rb = manager.get(b).orElseThrow().result().findings();
            assertThat(ra).usingRecursiveFieldByFieldElementComparator().isEqualTo(rb);
        } finally {
            manager.shutdown();
        }
    }
}
