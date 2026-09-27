package com.github.guoyyit.symbolicexecutionjava.lang;

/**
 * Expressions in the analyzed language subset.
 * Integers are the only variable type; boolean expressions appear only
 * as conditions (comparisons and logical connectives).
 */
public sealed interface Expr {

    record Const(long value) implements Expr {
    }

    record Var(String name) implements Expr {
    }

    enum BinaryOp {
        ADD, SUB, MUL, DIV, MOD,
        EQ, NE, LT, LE, GT, GE,
        AND, OR
    }

    record Binary(BinaryOp op, Expr left, Expr right) implements Expr {
    }

    enum UnaryOp {
        NEG, NOT
    }

    record Unary(UnaryOp op, Expr operand) implements Expr {
    }
}
