package com.github.guoyyit.symbolicexecutionjava.model.symbolic;

import java.util.List;

/** Conjunction of linear constraints accumulated along one control-flow path. */
public record PathCondition(List<LinearConstraint> constraints) {

    public static PathCondition empty() {
        return new PathCondition(List.of());
    }
}
