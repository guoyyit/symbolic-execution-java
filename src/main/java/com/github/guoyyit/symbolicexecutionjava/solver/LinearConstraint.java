package com.github.guoyyit.symbolicexecutionjava.solver;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Atomic path constraint: {@code expression <relation> 0}.
 *
 * <p>The solver itself only reasons about inequalities of the form
 * {@code linearExpr <= 0}. {@code LT} is strengthened for integers
 * ({@code e < 0} becomes {@code e + 1 <= 0}), {@code EQ} is a pair of
 * inequalities, {@code GE/GT} are rewritten by negating the expression, and
 * {@code NE} is resolved with a deterministic two-way case split.
 */
public record LinearConstraint(
        @JsonProperty("expression") LinearExpr expression,
        @JsonProperty("relation") Relation relation) {

    @JsonCreator
    public LinearConstraint(@JsonProperty("expression") LinearExpr expression,
                            @JsonProperty("relation") Relation relation) {
        this.expression = expression;
        this.relation = relation;
    }

    public enum Relation {
        LE, LT, EQ, NE, GE, GT
    }

    public static LinearConstraint of(LinearExpr expression, Relation relation) {
        return new LinearConstraint(expression, relation);
    }

    public static LinearConstraint le(LinearExpr expression) {
        return new LinearConstraint(expression, Relation.LE);
    }

    public static LinearConstraint eq(LinearExpr expression) {
        return new LinearConstraint(expression, Relation.EQ);
    }

    public boolean holds(Map<String, Long> model) {
        long value = expression.eval(model);
        return switch (relation) {
            case LE -> value <= 0;
            case LT -> value < 0;
            case EQ -> value == 0;
            case NE -> value != 0;
            case GE -> value >= 0;
            case GT -> value > 0;
        };
    }

    @Override
    public String toString() {
        return expression + " " + switch (relation) {
            case LE -> "<=";
            case LT -> "<";
            case EQ -> "==";
            case NE -> "!=";
            case GE -> ">=";
            case GT -> ">";
        } + " 0";
    }
}
