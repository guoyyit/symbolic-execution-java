package com.github.guoyyit.symbolicexecutionjava.solver;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;
import java.util.TreeMap;

/**
 * Affine linear expression over symbolic input variables:
 * {@code sum coeff_i * var_i + constant}. Instances are immutable; the
 * coefficient map is sorted by variable name for deterministic serialisation
 * and output. All arithmetic uses exact methods, so coefficient overflow
 * (e.g. during Fourier-Motzkin elimination) fails loudly instead of
 * silently corrupting a decision.
 */
public final class LinearExpr {

    private final Map<String, Long> coefficients;
    private final long constant;

    @JsonCreator
    public LinearExpr(@JsonProperty("coefficients") Map<String, Long> coefficients,
                      @JsonProperty("constant") long constant) {
        TreeMap<String, Long> sorted = new TreeMap<>();
        if (coefficients != null) {
            for (Map.Entry<String, Long> e : coefficients.entrySet()) {
                if (e.getValue() != null && e.getValue() != 0L) {
                    sorted.put(e.getKey(), e.getValue());
                }
            }
        }
        this.coefficients = Map.copyOf(sorted);
        this.constant = constant;
    }

    public static LinearExpr constant(long value) {
        return new LinearExpr(Map.of(), value);
    }

    public static LinearExpr variable(String name) {
        return new LinearExpr(Map.of(name, 1L), 0);
    }

    @JsonProperty("coefficients")
    public Map<String, Long> coefficients() {
        return coefficients;
    }

    @JsonProperty("constant")
    public long constant() {
        return constant;
    }

    @JsonIgnore
    public long coefficientOf(String name) {
        return coefficients.getOrDefault(name, 0L);
    }

    @JsonIgnore
    public boolean isConstant() {
        return coefficients.isEmpty();
    }

    public LinearExpr add(LinearExpr other) {
        TreeMap<String, Long> merged = new TreeMap<>(coefficients);
        for (Map.Entry<String, Long> e : other.coefficients.entrySet()) {
            merged.merge(e.getKey(), e.getValue(),
                    (a, b) -> Math.addExact(a, b));
        }
        merged.values().removeIf(v -> v == 0L);
        return new LinearExpr(merged, Math.addExact(constant, other.constant));
    }

    public LinearExpr subtract(LinearExpr other) {
        return add(other.negate());
    }

    public LinearExpr negate() {
        TreeMap<String, Long> neg = new TreeMap<>();
        for (Map.Entry<String, Long> e : coefficients.entrySet()) {
            neg.put(e.getKey(), Math.negateExact(e.getValue()));
        }
        return new LinearExpr(neg, Math.negateExact(constant));
    }

    /** Multiplication by a concrete scalar. */
    public LinearExpr scale(long factor) {
        if (factor == 0L) {
            return constant(0);
        }
        TreeMap<String, Long> scaled = new TreeMap<>();
        for (Map.Entry<String, Long> e : coefficients.entrySet()) {
            scaled.put(e.getKey(), Math.multiplyExact(e.getValue(), factor));
        }
        return new LinearExpr(scaled, Math.multiplyExact(constant, factor));
    }

    /** Evaluates the expression under a concrete integer assignment. */
    public long eval(Map<String, Long> model) {
        long result = constant;
        for (Map.Entry<String, Long> e : coefficients.entrySet()) {
            Long value = model.get(e.getKey());
            if (value == null) {
                throw new IllegalArgumentException("model does not bind variable: " + e.getKey());
            }
            result = Math.addExact(result, Math.multiplyExact(e.getValue(), value));
        }
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LinearExpr that)) {
            return false;
        }
        return constant == that.constant && coefficients.equals(that.coefficients);
    }

    @Override
    public int hashCode() {
        return 31 * coefficients.hashCode() + Long.hashCode(constant);
    }

    /** Canonical deterministic textual form, e.g. {@code "2*x - y + 3"}. */
    @Override
    public String toString() {
        if (coefficients.isEmpty()) {
            return Long.toString(constant);
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Long> e : coefficients.entrySet()) {
            long c = e.getValue();
            if (first) {
                if (c == -1L) {
                    sb.append('-');
                } else if (c != 1L) {
                    sb.append(c).append('*');
                }
                first = false;
            } else {
                sb.append(c > 0 ? " + " : " - ");
                long abs = Math.abs(c);
                if (abs != 1L) {
                    sb.append(abs).append('*');
                }
            }
            sb.append(e.getKey());
        }
        if (constant != 0L) {
            sb.append(constant > 0 ? " + " : " - ").append(Math.abs(constant));
        }
        return sb.toString();
    }
}
