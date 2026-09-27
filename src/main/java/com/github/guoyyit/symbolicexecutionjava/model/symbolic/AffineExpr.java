package com.github.guoyyit.symbolicexecutionjava.model.symbolic;

import java.util.LinkedHashMap;
import java.util.Map;

/** Linear (affine) integer expression: sum of coeff_i * var_i + constant. */
public record AffineExpr(Map<String, Long> coefficients, long constant) {

    public static AffineExpr constant(long value) {
        return new AffineExpr(Map.of(), value);
    }

    public static AffineExpr variable(String name) {
        return new AffineExpr(Map.of(name, 1L), 0L);
    }

    public long get(String var) {
        return coefficients.getOrDefault(var, 0L);
    }

    public boolean isConstant() {
        return coefficients.isEmpty();
    }

    public AffineExpr add(AffineExpr other) {
        Map<String, Long> c = new LinkedHashMap<>(coefficients);
        other.coefficients.forEach((k, v) -> c.merge(k, v, Long::sum));
        return new AffineExpr(normalize(c), constant + other.constant);
    }

    public AffineExpr sub(AffineExpr other) {
        Map<String, Long> c = new LinkedHashMap<>(coefficients);
        other.coefficients.forEach((k, v) -> c.merge(k, -v, Long::sum));
        return new AffineExpr(normalize(c), constant - other.constant);
    }

    public AffineExpr multiply(long scalar) {
        Map<String, Long> c = new LinkedHashMap<>();
        coefficients.forEach((k, v) -> c.put(k, v * scalar));
        return new AffineExpr(normalize(c), constant * scalar);
    }

    public AffineExpr negate() {
        return multiply(-1L);
    }

    /** Evaluate this expression under a concrete (total) assignment. */
    public long evaluate(Map<String, Long> values) {
        long result = constant;
        for (Map.Entry<String, Long> e : coefficients.entrySet()) {
            Long v = values.get(e.getKey());
            if (v != null) {
                result += e.getValue() * v;
            }
        }
        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Long> e : coefficients.entrySet()) {
            long v = e.getValue();
            if (v == 0) {
                continue;
            }
            sb.append(first ? signPrefix(v) : sep(v));
            first = false;
            long abs = Math.abs(v);
            if (abs != 1) {
                sb.append(abs).append('*');
            }
            sb.append(e.getKey());
        }
        if (sb.length() == 0) {
            return Long.toString(constant);
        }
        if (constant != 0) {
            sb.append(constant > 0 ? " + " : " - ").append(Math.abs(constant));
        }
        return sb.toString();
    }

    private static String signPrefix(long v) {
        return v < 0 ? "-" : "";
    }

    private static String sep(long v) {
        return v < 0 ? " - " : " + ";
    }

    private static Map<String, Long> normalize(Map<String, Long> raw) {
        Map<String, Long> c = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (v != 0L) {
                c.put(k, v);
            }
        });
        return Map.copyOf(c);
    }
}
