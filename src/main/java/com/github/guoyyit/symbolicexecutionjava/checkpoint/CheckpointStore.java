package com.github.guoyyit.symbolicexecutionjava.checkpoint;

import java.util.Optional;

/** Durable storage of exploration checkpoints. */
public interface CheckpointStore {

    void save(Checkpoint checkpoint);

    Optional<Checkpoint> load(String jobId);

    void delete(String jobId);
}
