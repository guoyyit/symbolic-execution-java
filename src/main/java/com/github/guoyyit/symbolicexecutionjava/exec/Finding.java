package com.github.guoyyit.symbolicexecutionjava.exec;

import com.github.guoyyit.symbolicexecutionjava.solver.LinearConstraint;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * One reported path outcome. Path ids are assigned sequentially in the fixed
 * exploration order, so identical inputs always produce identical reports.
 */
public record Finding(
        @JsonProperty("pathId") int pathId,
        @JsonProperty("kind") Kind kind,
        @JsonProperty("line") int line,
        @JsonProperty("pathCondition") List<LinearConstraint> pathCondition,
        @JsonProperty("model") Map<String, Long> model,
        @JsonProperty("finalValues") Map<String, Long> finalValues) {

    @JsonCreator
    public Finding(@JsonProperty("pathId") int pathId,
                   @JsonProperty("kind") Kind kind,
                   @JsonProperty("line") int line,
                   @JsonProperty("pathCondition") List<LinearConstraint> pathCondition,
                   @JsonProperty("model") Map<String, Long> model,
                   @JsonProperty("finalValues") Map<String, Long> finalValues) {
        this.pathId = pathId;
        this.kind = kind;
        this.line = line;
        this.pathCondition = pathCondition;
        this.model = model;
        this.finalValues = finalValues;
    }

    public enum Kind {
        ASSERT_VIOLATED,
        ASSERT_PASSED,
        PATH_COMPLETED
    }
}
