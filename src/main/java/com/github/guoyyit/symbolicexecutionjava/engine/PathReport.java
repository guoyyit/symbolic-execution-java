package com.github.guoyyit.symbolicexecutionjava.engine;

import com.github.guoyyit.symbolicexecutionjava.model.symbolic.PathCondition;

import java.util.List;
import java.util.Map;

/**
 * One reported path: deterministic id, constraints, outcome,
 * a concrete satisfying input model, and final variable values.
 */
public record PathReport(
        int pathId,
        PathOutcome outcome,
        List<String> constraints,
        PathCondition pathCondition,
        Map<String, Long> inputModel,
        Map<String, Long> finalValues,
        String targetDescription) {
}
