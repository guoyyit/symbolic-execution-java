package com.github.guoyyit.symbolicexecutionjava.exec;

import com.github.guoyyit.symbolicexecutionjava.solver.LinearConstraint;
import com.github.guoyyit.symbolicexecutionjava.solver.LinearExpr;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Immutable symbolic execution frontier item: CFG node to visit, symbolic
 * environment (variable -> affine expression over inputs), accumulated path
 * condition, per-loop-head iteration counters, and search depth.
 */
public record ExecutorState(
        @JsonProperty("nodeId") int nodeId,
        @JsonProperty("environment") Map<String, LinearExpr> environment,
        @JsonProperty("pathCondition") List<LinearConstraint> pathCondition,
        @JsonProperty("loopIterations") Map<Integer, Integer> loopIterations,
        @JsonProperty("depth") int depth) {

    @JsonCreator
    public ExecutorState(@JsonProperty("nodeId") int nodeId,
                         @JsonProperty("environment") Map<String, LinearExpr> environment,
                         @JsonProperty("pathCondition") List<LinearConstraint> pathCondition,
                         @JsonProperty("loopIterations") Map<Integer, Integer> loopIterations,
                         @JsonProperty("depth") int depth) {
        this.nodeId = nodeId;
        this.environment = environment;
        this.pathCondition = pathCondition;
        this.loopIterations = loopIterations;
        this.depth = depth;
    }
}
