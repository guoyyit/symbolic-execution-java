package com.github.guoyyit.symbolicexecutionjava.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.symbolicexecutionjava.exec.Checkpoint;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * File-based persistence for task records and exploration checkpoints under
 * {@code <dataDir>/tasks/<taskId>/}.
 *
 * <p>Every write goes to a temporary file followed by an atomic rename, so a
 * crash mid-write can never leave a torn or half-visible file. Records are
 * read back sorted by creation time, which makes restart recovery order
 * deterministic.
 */
public final class CheckpointStore {

    private static final String TASKS_DIR = "tasks";
    private static final String RECORD_FILE = "task.json";
    private static final String CHECKPOINT_FILE = "checkpoint.json";

    private final Path rootDir;
    private final ObjectMapper mapper;

    public CheckpointStore(Path rootDir) {
        this.rootDir = rootDir;
        this.mapper = new ObjectMapper();
        try {
            Files.createDirectories(rootDir.resolve(TASKS_DIR));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot initialise data directory: " + rootDir, e);
        }
    }

    public void saveRecord(TaskRecord record) {
        Path target = recordPath(record.getId());
        try {
            Files.createDirectories(target.getParent());
            writeAtomically(target, mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(record));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot persist task record: " + record.getId(), e);
        }
    }

    public Optional<TaskRecord> loadRecord(String taskId) {
        Path target = recordPath(taskId);
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(target.toFile(), TaskRecord.class));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read task record: " + taskId, e);
        }
    }

    public List<TaskRecord> loadAllRecords() {
        Path tasks = rootDir.resolve(TASKS_DIR);
        try (Stream<Path> dirs = Files.list(tasks)) {
            List<TaskRecord> records = dirs
                    .filter(Files::isDirectory)
                    .map(dir -> dir.resolve(RECORD_FILE))
                    .filter(Files::exists)
                    .map(this::readRecord)
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparingLong(TaskRecord::getCreatedAt))
                    .toList();
            return records;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list task records", e);
        }
    }

    private Optional<TaskRecord> readRecord(Path path) {
        try {
            return Optional.of(mapper.readValue(path.toFile(), TaskRecord.class));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read task record: " + path, e);
        }
    }

    public void saveCheckpoint(String taskId, Checkpoint checkpoint) {
        Path target = checkpointPath(taskId);
        try {
            Files.createDirectories(target.getParent());
            writeAtomically(target, mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(checkpoint));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot persist checkpoint: " + taskId, e);
        }
    }

    public Optional<Checkpoint> loadCheckpoint(String taskId) {
        Path target = checkpointPath(taskId);
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(target.toFile(), Checkpoint.class));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read checkpoint: " + taskId, e);
        }
    }

    public void deleteCheckpoint(String taskId) {
        try {
            Files.deleteIfExists(checkpointPath(taskId));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete checkpoint: " + taskId, e);
        }
    }

    public boolean checkpointExists(String taskId) {
        return Files.exists(checkpointPath(taskId));
    }

    /** Removes {@code .tmp} leftovers from crashes during previous runs. */
    public void cleanupTemporaryFiles() {
        Path tasks = rootDir.resolve(TASKS_DIR);
        try (Stream<Path> walk = Files.walk(tasks)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".tmp"))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot clean temporary files", e);
        }
    }

    private void writeAtomically(Path target, byte[] payload) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temporary, payload);
        try {
            Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicFailure) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path recordPath(String taskId) {
        return rootDir.resolve(TASKS_DIR).resolve(taskId).resolve(RECORD_FILE);
    }

    private Path checkpointPath(String taskId) {
        return rootDir.resolve(TASKS_DIR).resolve(taskId).resolve(CHECKPOINT_FILE);
    }
}
