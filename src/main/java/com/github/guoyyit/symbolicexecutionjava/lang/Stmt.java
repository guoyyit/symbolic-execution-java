package com.github.guoyyit.symbolicexecutionjava.lang;

import java.util.List;

/**
 * Statements of the analyzed language subset. Every statement carries the
 * 1-based source line reported by the client (0 when absent) so that
 * diagnostics can point at a concrete program position.
 */
public sealed interface Stmt {

    record Assign(String target, Expr expression, int line) implements Stmt {
    }

    record If(Expr condition, List<Stmt> thenBranch, List<Stmt> elseBranch, int line) implements Stmt {
    }

    /**
     * Bounded while-loop. {@code maxIterations} is {@code null} when the
     * program omits the declared loop bound; the static validator rejects
     * such programs ("loop bound missing").
     */
    record Loop(Expr condition, List<Stmt> body, Integer maxIterations, int line) implements Stmt {
    }

    record Assert(Expr condition, int line) implements Stmt {
    }
}
