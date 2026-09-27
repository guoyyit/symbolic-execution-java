package com.github.guoyyit.symbolicexecutionjava.service;

/** Lifecycle status of an analysis job. */
public enum JobStatus {
    QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, TIMEOUT
}
