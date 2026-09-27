package com.github.guoyyit.symbolicexecutionjava.checkpoint;

import com.github.guoyyit.symbolicexecutionjava.engine.PathOutcome;

import java.util.List;
import java.util.Map;

/** Persistable, JSON-friendly view of one reported path. */
public record PathSnapshot(
        int pathId,
        PathOutcome outcome,
        List<String> constraints,
        Map<String, Long> inputModel,
        Map<String, Long> finalValues,
        String targetDescription) {
}
