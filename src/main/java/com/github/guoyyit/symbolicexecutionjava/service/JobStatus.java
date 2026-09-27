package com.github.guoyyit.symbolicexecutionjava.service;

/** Lifecycle status of an analysis job. */
public enum JobStatus {
    QUEUED,
    RUNNING,
    /** Exploration finished; all reachable paths reported. */
    COMPLETED,
    /** A static/runtime error prevented analysis. */
    FAILED,
    /** Explicitly cancelled; no partial result or checkpoint. */
    CANCELLED,
    /** Deadline elapsed; checkpoint retained for resume, no partial result exposed. */
    TIMEOUT,
    /** Max reported paths reached; checkpoint retained for resume. */
    LIMITED
}
