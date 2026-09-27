package com.github.guoyyit.symbolicexecutionjava.model.symbolic;

import com.github.guoyyit.symbolicexecutionjava.model.ast.Cond;
import com.github.guoyyit.symbolicexecutionjava.model.ast.Expr;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts AST conditions over a symbolic store into linear constraints.
 *
 * <p>A condition is converted to <strong>disjunctive normal form</strong>: a
 * list of "minterms", each a conjunction (list) of linear constraints. Branch
 * exploration treats every feasible minterm as one possible path, which keeps
 * the engine complete for {@code &&}, {@code ||} and {@code !} while all
 * underlying constraints remain linear.</p>
 */
public final class ConstraintEncoder {

    private ConstraintEncoder() {
    }

    /** Linearises an arithmetic expression by substituting the symbolic store. */
    public static AffineExpr linear(Expr expr, SymbolicStore store) {
        if (expr instanceof Expr.IntLit lit) {
            return AffineExpr.constant(lit.value());
        }
        if (expr instanceof Expr.Var var) {
            AffineExpr value = store.get(var.name());
            return value == null ? AffineExpr.variable(var.name()) : value;
        }
        if (expr instanceof Expr.Bin bin) {
            AffineExpr l = linear(bin.left(), store);
            AffineExpr r = linear(bin.right(), store);
            if (bin.op() == Expr.Op.ADD) {
                return l.add(r);
            }
            if (bin.op() == Expr.Op.SUB) {
                return l.sub(r);
            }
            if (l.isConstant()) {
                return r.multiply(l.constant());
            }
            if (r.isConstant()) {
                return l.multiply(r.constant());
            }
            throw new IllegalArgumentException(
                    "non-linear expression reached encoder at " + bin.pos());
        }
        throw new IllegalArgumentException("unsupported expression: " + expr);
    }

    /**
     * Expands a condition (with a requested polarity) to DNF: a list of
     * conjunctions, where satisfying any conjunction satisfies the condition.
     */
    public static List<List<LinearConstraint>> dnf(Cond cond, boolean polarity,
                                                   SymbolicStore store) {
        List<List<LinearConstraint>> result = new ArrayList<>();
        result.add(new ArrayList<>());
        expandDnf(cond, polarity, store, result);
        return result;
    }

    /**
     * Distributes {@code cond} (with polarity) over a growing list of minterms.
     * AND merges into each minterm; OR splits the list into alternatives.
     */
    private static void expandDnf(Cond cond, boolean polarity, SymbolicStore store,
                                  List<List<LinearConstraint>> minterms) {
        if (cond instanceof Cond.Rel rel) {
            LinearConstraint c = toConstraint(rel, polarity, store);
            for (List<LinearConstraint> m : minterms) {
                m.add(c);
            }
            return;
        }
        if (cond instanceof Cond.And and) {
            if (polarity) {
                expandDnf(and.left(), true, store, minterms);
                expandDnf(and.right(), true, store, minterms);
            } else {
                // !(A && B) = !A || !B
                List<List<LinearConstraint>> copy = deepCopy(minterms);
                expandDnf(and.left(), false, store, minterms);
                expandDnf(and.right(), false, store, copy);
                minterms.addAll(copy);
            }
            return;
        }
        if (cond instanceof Cond.Or or) {
            if (polarity) {
                // A || B
                List<List<LinearConstraint>> copy = deepCopy(minterms);
                expandDnf(or.left(), true, store, minterms);
                expandDnf(or.right(), true, store, copy);
                minterms.addAll(copy);
            } else {
                // !(A || B) = !A && !B
                expandDnf(or.left(), false, store, minterms);
                expandDnf(or.right(), false, store, minterms);
            }
            return;
        }
        if (cond instanceof Cond.Not not) {
            expandDnf(not.inner(), !polarity, store, minterms);
        }
    }

    private static LinearConstraint toConstraint(Cond.Rel rel, boolean polarity,
                                                 SymbolicStore store) {
        AffineExpr l = linear(rel.left(), store);
        AffineExpr r = linear(rel.right(), store);
        // Normalise to  (l - r) OP 0.
        AffineExpr diff = l.sub(r);
        Cond.RelOp op = polarity ? rel.op() : negate(rel.op());
        return new LinearConstraint(diff.coefficients(),
                LinearConstraint.Op.valueOf(op.name()), -diff.constant());
    }

    private static Cond.RelOp negate(Cond.RelOp op) {
        return switch (op) {
            case LT -> Cond.RelOp.GE;
            case LE -> Cond.RelOp.GT;
            case GT -> Cond.RelOp.LE;
            case GE -> Cond.RelOp.LT;
            case EQ -> Cond.RelOp.NE;
            case NE -> Cond.RelOp.EQ;
        };
    }

    private static List<List<LinearConstraint>> deepCopy(List<List<LinearConstraint>> src) {
        List<List<LinearConstraint>> copy = new ArrayList<>();
        for (List<LinearConstraint> m : src) {
            copy.add(new ArrayList<>(m));
        }
        return copy;
    }
}
