package com.github.guoyyit.symbolicexecutionjava.model.ast;

/** Boolean condition over linear integer arithmetic. */
public sealed interface Cond permits Cond.Rel, Cond.And, Cond.Or, Cond.Not {

    Position pos();

    enum RelOp {
        LT, LE, GT, GE, EQ, NE
    }

    record Rel(RelOp op, Expr left, Expr right, Position pos) implements Cond {}

    record And(Cond left, Cond right, Position pos) implements Cond {}

    record Or(Cond left, Cond right, Position pos) implements Cond {}

    record Not(Cond inner, Position pos) implements Cond {}
}
