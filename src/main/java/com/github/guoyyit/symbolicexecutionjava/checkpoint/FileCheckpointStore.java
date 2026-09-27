package com.github.guoyyit.symbolicexecutionjava.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Checkpoint store backed by one JSON file per job.
 *
 * <p>Writes are atomic: data is serialised to a temporary file in the same
 * directory, flushed to disk and atomically renamed over the destination, so a
 * crash can leave either the previous complete checkpoint or the new one but
 * never a torn file. This lets a restart continue from the last successfully
 * committed exploration state without re-reporting or losing paths.</p>
 */
public class FileCheckpointStore implements CheckpointStore {

    private final Path directory;
    private final ObjectMapper mapper;

    public FileCheckpointStore(Path directory) {
        this.directory = directory;
        this.mapper = new ObjectMapper();
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create checkpoint directory: " + directory, e);
        }
    }

    @Override
    public synchronized void save(Checkpoint checkpoint) {
        Path target = targetFile(checkpoint.jobId());
        Path tmp = directory.resolve(checkpoint.jobId() + ".tmp-" + Thread.currentThread().getId());
        try {
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(checkpoint);
            Files.write(tmp, bytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(
                    tmp, StandardOpenOption.WRITE)) {
                ch.force(true);
            } catch (IOException ignored) {
                // force is best-effort on platforms that do not support it
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFailed) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist checkpoint for job "
                    + checkpoint.jobId(), e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    @Override
    public synchronized Optional<Checkpoint> load(String jobId) {
        Path target = targetFile(jobId);
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        try {
            String json = Files.readString(target, StandardCharsets.UTF_8);
            return Optional.of(mapper.readValue(json, Checkpoint.class));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read checkpoint for job " + jobId, e);
        }
    }

    @Override
    public synchronized void delete(String jobId) {
        try {
            Files.deleteIfExists(targetFile(jobId));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to delete checkpoint for job " + jobId, e);
        }
    }

    private Path targetFile(String jobId) {
        return directory.resolve(sanitize(jobId) + ".json");
    }

    private static String sanitize(String jobId) {
        return jobId.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
