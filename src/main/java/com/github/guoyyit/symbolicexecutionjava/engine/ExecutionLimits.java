package com.github.guoyyit.symbolicexecutionjava.engine;

/** Per-task resource limits. */
public record ExecutionLimits(
        long timeoutMillis,
        int maxPaths,
        int maxUnroll,
        int maxSteps,
        int maxBacktracks) {
}
