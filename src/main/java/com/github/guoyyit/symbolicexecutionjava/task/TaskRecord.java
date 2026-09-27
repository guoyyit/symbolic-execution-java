package com.github.guoyyit.symbolicexecutionjava.task;

import com.github.guoyyit.symbolicexecutionjava.exec.ExplorationResult;

/**
 * Persistable task view. Written atomically on every lifecycle transition;
 * {@code result} is only ever populated together with
 * {@link TaskStatus#COMPLETED}, so cancelled/timed-out tasks never expose
 * partial findings.
 */
public final class TaskRecord {

    private String id;
    private TaskSpec spec;
    private TaskStatus status;
    private String error;
    private ExplorationResult result;
    private long createdAt;
    private long finishedAt;

    public TaskRecord() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public TaskSpec getSpec() {
        return spec;
    }

    public void setSpec(TaskSpec spec) {
        this.spec = spec;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public ExplorationResult getResult() {
        return result;
    }

    public void setResult(ExplorationResult result) {
        this.result = result;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(long finishedAt) {
        this.finishedAt = finishedAt;
    }
}
