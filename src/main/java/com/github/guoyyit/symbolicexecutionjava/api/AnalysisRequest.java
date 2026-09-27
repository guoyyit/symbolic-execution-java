package com.github.guoyyit.symbolicexecutionjava.api;

/** Analysis submission payload. */
public record AnalysisRequest(String program, Long seed, AnalysisLimitsDto limits) {

    public record AnalysisLimitsDto(Long timeoutMillis, Integer maxPaths, Integer maxUnroll,
                                    Integer maxSteps, Integer maxBacktracks) {
    }
}
