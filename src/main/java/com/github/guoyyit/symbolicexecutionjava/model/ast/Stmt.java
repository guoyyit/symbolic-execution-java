package com.github.guoyyit.symbolicexecutionjava.model.ast;

import java.util.List;

/** Statements of the supported language subset. */
public sealed interface Stmt
        permits Stmt.Decl, Stmt.Assign, Stmt.Block, Stmt.If, Stmt.For, Stmt.Assert {

    Position pos();

    /** Variable declaration, optionally with an initialising affine expression. */
    record Decl(String name, Expr init, Position namePos, Position pos) implements Stmt {}

    record Assign(String name, Expr value, Position namePos, Position pos) implements Stmt {}

    record Block(List<Stmt> statements, Position pos) implements Stmt {}

    record If(Cond cond, Block thenBlock, Block elseBlock, Position pos) implements Stmt {}

    /**
     * C-style for loop. init / cond / update may be {@code null} when omitted in source;
     * semantic validation rejects loops without a well-formed bound.
     */
    record For(Stmt init, Cond cond, Stmt update, Block body, Position pos) implements Stmt {}

    record Assert(Cond cond, Position pos) implements Stmt {}
}
