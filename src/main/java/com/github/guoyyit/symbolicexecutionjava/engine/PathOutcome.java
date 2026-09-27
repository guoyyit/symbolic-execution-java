package com.github.guoyyit.symbolicexecutionjava.engine;

/** Classification of a reported path. */
public enum PathOutcome {
    /** Program finished without violating an assertion. */
    SUCCESS,
    /** An assertion was violated along this path. */
    ASSERTION_FAILED,
    /** A resource limit (e.g. loop unroll cap) was hit. */
    LIMIT_REACHED
}
