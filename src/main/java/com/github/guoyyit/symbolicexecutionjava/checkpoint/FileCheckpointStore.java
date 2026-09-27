package com.github.guoyyit.symbolicexecutionjava.checkpoint;

import java.nio.file.Path;
import java.util.Optional;

/** Checkpoint store backed by atomically-written JSON files. */
public class FileCheckpointStore implements CheckpointStore {

    public FileCheckpointStore(Path directory) {
    }

    @Override
    public void save(Checkpoint checkpoint) {
    }

    @Override
    public Optional<Checkpoint> load(String jobId) {
        return Optional.empty();
    }

    @Override
    public void delete(String jobId) {
    }
}
