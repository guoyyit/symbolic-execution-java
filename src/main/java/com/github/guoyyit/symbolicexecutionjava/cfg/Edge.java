package com.github.guoyyit.symbolicexecutionjava.cfg;

import com.github.guoyyit.symbolicexecutionjava.model.ast.Cond;

/**
 * Directed CFG edge. For BRANCH nodes the two outgoing edges are the positive
 * condition and its negation; for ASSERT nodes they represent failure/success.
 */
public record Edge(CfgNode from, CfgNode to, Cond guard, boolean negated, Kind kind) {

    public enum Kind {
        NORMAL,
        /** Branch condition holds. */
        TRUE,
        /** Branch condition is negated. */
        FALSE,
        /** Assertion violated (condition holds). */
        ASSERT_FAIL,
        /** Assertion satisfied (condition negated). */
        ASSERT_OK
    }

    public static Edge normal(CfgNode from, CfgNode to) {
        return new Edge(from, to, null, false, Kind.NORMAL);
    }

    public static Edge branchTrue(CfgNode from, CfgNode to, Cond guard) {
        return new Edge(from, to, guard, false, Kind.TRUE);
    }

    public static Edge branchFalse(CfgNode from, CfgNode to, Cond guard) {
        return new Edge(from, to, guard, true, Kind.FALSE);
    }

    public static Edge assertionFail(CfgNode from, CfgNode to, Cond guard) {
        return new Edge(from, to, guard, false, Kind.ASSERT_FAIL);
    }

    public static Edge assertionOk(CfgNode from, CfgNode to, Cond guard) {
        return new Edge(from, to, guard, true, Kind.ASSERT_OK);
    }
}
