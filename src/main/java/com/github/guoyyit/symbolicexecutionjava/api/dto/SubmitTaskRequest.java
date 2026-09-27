package com.github.guoyyit.symbolicexecutionjava.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

/** Client submission: program JSON plus optional seed and per-task limits. */
public record SubmitTaskRequest(JsonNode program,
                                Long seed,
                                Long timeoutMs,
                                Long maxStates,
                                Integer maxPaths) {
}
