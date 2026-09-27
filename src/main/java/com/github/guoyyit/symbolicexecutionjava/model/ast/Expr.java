package com.github.guoyyit.symbolicexecutionjava.model.ast;

/** Integer arithmetic expression. Only linear arithmetic survives validation. */
public sealed interface Expr permits Expr.IntLit, Expr.Var, Expr.Bin {

    Position pos();

    record IntLit(long value, Position pos) implements Expr {}

    record Var(String name, Position pos) implements Expr {}

    record Bin(Op op, Expr left, Expr right, Position pos) implements Expr {}

    enum Op {
        ADD, SUB, MUL
    }
}
