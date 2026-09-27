package com.github.guoyyit.symbolicexecutionjava.api;

import com.github.guoyyit.symbolicexecutionjava.service.JobStatus;

import java.util.List;
import java.util.Map;

/** Result view returned to clients. */
public record AnalysisResult(
        String jobId,
        JobStatus status,
        int totalPaths,
        int successCount,
        int failureCount,
        List<PathView> paths,
        String error,
        PositionView errorPosition) {

    public record PathView(int pathId, String outcome, List<String> constraints,
                           Map<String, Long> inputModel, Map<String, Long> finalValues,
                           String target) {
    }

    public record PositionView(int line, int column) {
    }
}
