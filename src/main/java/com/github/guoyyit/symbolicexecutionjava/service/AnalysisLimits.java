package com.github.guoyyit.symbolicexecutionjava.service;

/** User-supplied / defaulted resource limits for an analysis request. */
public record AnalysisLimits(Long timeoutMillis, Integer maxPaths, Integer maxUnroll,
                             Integer maxSteps, Integer maxBacktracks) {
}
