package com.github.guoyyit.symbolicexecutionjava.service;

import java.time.Instant;

/** Server-side handle for a submitted analysis task. */
public class Job {

    private final String id;
    private volatile JobStatus status;
    private final Instant createdAt;

    public Job(String id, Instant createdAt) {
        this.id = id;
        this.status = JobStatus.QUEUED;
        this.createdAt = createdAt;
    }

    public String id() {
        return id;
    }

    public JobStatus status() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
