package com.github.guoyyit.symbolicexecutionjava.cfg;

import com.github.guoyyit.symbolicexecutionjava.model.ast.Stmt;

/** One node of the control-flow graph. */
public record CfgNode(int id, Kind kind, Stmt statement) {

    public enum Kind {
        ENTRY,
        EXIT,
        /** Variable declaration. */
        DECL,
        /** Assignment. */
        ASSIGN,
        /** Conditional branch: outgoing edges are true / negated-false guards. */
        BRANCH,
        /** Assertion: outgoing edges are failure (guard holds) / success (guard negated). */
        ASSERT,
        /** Merges control-flow edges. */
        JOIN
    }

    @Override
    public String toString() {
        return kind + "#" + id;
    }
}
