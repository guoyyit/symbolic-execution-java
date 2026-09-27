package com.github.guoyyit.symbolicexecutionjava.validate;

import com.github.guoyyit.symbolicexecutionjava.error.ValidationException;
import com.github.guoyyit.symbolicexecutionjava.lang.Expr;
import com.github.guoyyit.symbolicexecutionjava.lang.Program;
import com.github.guoyyit.symbolicexecutionjava.lang.Stmt;
import com.github.guoyyit.symbolicexecutionjava.lang.VarDecl;

import java.util.Map;

/**
 * Static checks performed after parsing:
 * <ul>
 *   <li>every referenced variable is declared;</li>
 *   <li>expression type consistency: integer arithmetic on integers, boolean
 *       connectives on booleans, comparisons yield booleans;</li>
 *   <li>{@code if}/{@code loop}/{@code assert} conditions are booleans;</li>
 *   <li>assignments target declared variables with integer expressions;</li>
 *   <li>loops declare a non-negative {@code maxIterations} bound;</li>
 *   <li>{@code div}/{@code mod} are rejected (outside the supported subset)
 *       and multiplication must stay linear (constant factor on one side).</li>
 * </ul>
 * Diagnostics carry a JSON-path style location and the client source line.
 */
public final class StaticValidator {

    private enum Type {
        INT, BOOL
    }

    private Map<String, VarDecl> variables;

    public void validate(Program program) {
        this.variables = program.variables();
        validateStatements(program.statements(), "$.statements");
    }

    private void validateStatements(java.util.List<Stmt> statements, String path) {
        for (int i = 0; i < statements.size(); i++) {
            validateStatement(statements.get(i), path + "[" + i + "]");
        }
    }

    private void validateStatement(Stmt stmt, String path) {
        if (stmt instanceof Stmt.Assign a) {
            if (!variables.containsKey(a.target())) {
                throw new ValidationException(
                        "assignment to undefined variable: '" + a.target() + "'",
                        loc(path + ".target", a.line()));
            }
            Type type = typeOf(a.expression(), path + ".expression", a.line());
            if (type != Type.INT) {
                throw new ValidationException(
                        "cannot assign boolean expression to integer variable '"
                                + a.target() + "'",
                        loc(path + ".expression", a.line()));
            }
        } else if (stmt instanceof Stmt.If f) {
            requireBool(f.condition(), path + ".condition", f.line(), "if");
            validateStatements(f.thenBranch(), path + ".then");
            validateStatements(f.elseBranch(), path + ".else");
        } else if (stmt instanceof Stmt.Loop l) {
            if (l.maxIterations() == null) {
                throw new ValidationException(
                        "loop bound missing: 'loop' statement must declare a non-negative "
                                + "'maxIterations' field",
                        loc(path, l.line()));
            }
            if (l.maxIterations() < 0) {
                throw new ValidationException(
                        "loop bound must be non-negative",
                        loc(path + ".maxIterations", l.line()));
            }
            requireBool(l.condition(), path + ".condition", l.line(), "loop");
            validateStatements(l.body(), path + ".body");
        } else if (stmt instanceof Stmt.Assert as) {
            requireBool(as.condition(), path + ".condition", as.line(), "assert");
        }
    }

    private void requireBool(Expr condition, String path, int line, String construct) {
        Type type = typeOf(condition, path, line);
        if (type != Type.BOOL) {
            throw new ValidationException(
                    "condition of '" + construct + "' must be a boolean expression",
                    loc(path, line));
        }
    }

    private Type typeOf(Expr expr, String path, int line) {
        if (expr instanceof Expr.Const) {
            return Type.INT;
        } else if (expr instanceof Expr.Var var) {
            if (!variables.containsKey(var.name())) {
                throw new ValidationException(
                        "undefined variable: '" + var.name() + "'",
                        loc(path, line));
            }
            return Type.INT;
        } else if (expr instanceof Expr.Binary b) {
            Expr.BinaryOp op = b.op();
                return switch (op) {
                    case ADD, SUB, MUL -> {
                        Type left = typeOf(b.left(), path + ".left", line);
                        Type right = typeOf(b.right(), path + ".right", line);
                        if (left != Type.INT || right != Type.INT) {
                            throw new ValidationException(
                                    "operator '" + opName(op) + "' requires integer operands",
                                    loc(path, line));
                        }
                        if (op == Expr.BinaryOp.MUL
                                && !(b.left() instanceof Expr.Const)
                                && !(b.right() instanceof Expr.Const)) {
                            throw new ValidationException(
                                    "non-linear arithmetic not supported: multiplication requires "
                                            + "a constant factor on at least one side",
                                    loc(path, line));
                        }
                        yield Type.INT;
                    }
                    case DIV, MOD -> throw new ValidationException(
                            "operator '" + opName(op) + "' is not supported by the integer linear "
                                    + "arithmetic subset",
                            loc(path, line));
                    case EQ, NE, LT, LE, GT, GE -> {
                        Type left = typeOf(b.left(), path + ".left", line);
                        Type right = typeOf(b.right(), path + ".right", line);
                        if (left != Type.INT || right != Type.INT) {
                            throw new ValidationException(
                                    "operator '" + opName(op) + "' requires integer operands",
                                    loc(path, line));
                        }
                        yield Type.BOOL;
                    }
                    case AND, OR -> {
                        Type left = typeOf(b.left(), path + ".left", line);
                        Type right = typeOf(b.right(), path + ".right", line);
                        if (left != Type.BOOL || right != Type.BOOL) {
                            throw new ValidationException(
                                    "operator '" + opName(op) + "' requires boolean operands",
                                    loc(path, line));
                        }
                        yield Type.BOOL;
                    }
                };
        } else if (expr instanceof Expr.Unary u) {
            Type operand = typeOf(u.operand(), path + ".operand", line);
            return switch (u.op()) {
                case NEG -> {
                    if (operand != Type.INT) {
                        throw new ValidationException(
                                "operator 'neg' requires an integer operand", loc(path, line));
                    }
                    yield Type.INT;
                }
                case NOT -> {
                    if (operand != Type.BOOL) {
                        throw new ValidationException(
                                "operator 'not' requires a boolean operand", loc(path, line));
                    }
                    yield Type.BOOL;
                }
            };
        }
        throw new ValidationException("unsupported expression form", loc(path, line));
    }

    private static String opName(Expr.BinaryOp op) {
        return switch (op) {
            case ADD -> "add";
            case SUB -> "sub";
            case MUL -> "mul";
            case DIV -> "div";
            case MOD -> "mod";
            case EQ -> "eq";
            case NE -> "ne";
            case LT -> "lt";
            case LE -> "le";
            case GT -> "gt";
            case GE -> "ge";
            case AND -> "and";
            case OR -> "or";
        };
    }

    private static String loc(String path, int line) {
        return line > 0 ? path + " (line " + line + ")" : path;
    }
}
