package com.github.guoyyit.symbolicexecutionjava.model.symbolic;

import java.util.Map;

/** Linear integer constraint: sum of coeff_i * var_i OP constant. */
public record LinearConstraint(Map<String, Long> coefficients, Op op, long rhs) {

    public enum Op {
        LT, LE, GT, GE, EQ, NE
    }
}
