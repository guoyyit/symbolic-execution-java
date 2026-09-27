package com.github.guoyyit.symbolicexecutionjava.task;

import com.github.guoyyit.symbolicexecutionjava.exec.ExplorationResult;
import com.github.guoyyit.symbolicexecutionjava.exec.SymbolicExecutor;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Server-side mutable task record: immutable spec, volatile lifecycle fields,
 * and the cooperative cancellation flag.
 */
public final class AnalysisTask {

    private final String id;
    private final TaskSpec spec;
    private volatile TaskStatus status;
    private volatile String error;
    private volatile ExplorationResult result;
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final long createdAt;
    private volatile long finishedAt;

    /**
     * In-memory only engine handle, lazily created on first scheduling and
     * never shared between tasks; absent after restart until rebuilt from the
     * persisted checkpoint.
     */
    private volatile SymbolicExecutor executor;

    public AnalysisTask(String id, TaskSpec spec, TaskStatus status, long createdAt) {
        this.id = id;
        this.spec = spec;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String id() {
        return id;
    }

    public TaskSpec spec() {
        return spec;
    }

    public TaskStatus status() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public String error() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public ExplorationResult result() {
        return result;
    }

    public void setResult(ExplorationResult result) {
        this.result = result;
    }

    public boolean cancelRequested() {
        return cancelRequested.get();
    }

    public boolean requestCancel() {
        return cancelRequested.compareAndSet(false, true);
    }

    public long createdAt() {
        return createdAt;
    }

    public long finishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(long finishedAt) {
        this.finishedAt = finishedAt;
    }

    public SymbolicExecutor executor() {
        return executor;
    }

    public void setExecutor(SymbolicExecutor executor) {
        this.executor = executor;
    }
}
