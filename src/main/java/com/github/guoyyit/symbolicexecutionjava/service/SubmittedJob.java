package com.github.guoyyit.symbolicexecutionjava.service;

import com.github.guoyyit.symbolicexecutionjava.checkpoint.Checkpoint;
import com.github.guoyyit.symbolicexecutionjava.checkpoint.PathSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Mutable server-side bookkeeping for one analysis job. */
final class SubmittedJob {

    private final String id;
    private final String programHash;
    private final String source;
    private volatile JobStatus status = JobStatus.QUEUED;
    private volatile String error;
    private volatile com.github.guoyyit.symbolicexecutionjava.api.AnalysisResult.PositionView errorPosition;
    private final List<PathSnapshot> paths = java.util.Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean cancellation = new AtomicBoolean(false);
    private volatile Thread worker;
    private volatile boolean complete;
    private volatile long seed;

    SubmittedJob(String id, String programHash, String source, long seed) {
        this.id = id;
        this.programHash = programHash;
        this.source = source;
        this.seed = seed;
    }

    String id() {
        return id;
    }

    String programHash() {
        return programHash;
    }

    String source() {
        return source;
    }

    long seed() {
        return seed;
    }

    JobStatus status() {
        return status;
    }

    void setStatus(JobStatus status) {
        this.status = status;
    }

    AtomicBoolean cancellation() {
        return cancellation;
    }

    Thread worker() {
        return worker;
    }

    void setWorker(Thread worker) {
        this.worker = worker;
    }

    List<PathSnapshot> paths() {
        return paths;
    }

    boolean complete() {
        return complete;
    }

    void setComplete(boolean complete) {
        this.complete = complete;
    }

    String error() {
        return error;
    }

    void setError(String error) {
        this.error = error;
    }

    com.github.guoyyit.symbolicexecutionjava.api.AnalysisResult.PositionView errorPosition() {
        return errorPosition;
    }

    void setErrorPosition(com.github.guoyyit.symbolicexecutionjava.api.AnalysisResult.PositionView pos) {
        this.errorPosition = pos;
    }

    Checkpoint checkpoint() {
        return new Checkpoint(id, programHash, paths.size(), List.copyOf(paths), complete);
    }
}
