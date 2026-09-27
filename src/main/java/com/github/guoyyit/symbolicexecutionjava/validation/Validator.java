package com.github.guoyyit.symbolicexecutionjava.validation;

import com.github.guoyyit.symbolicexecutionjava.model.ast.Cond;
import com.github.guoyyit.symbolicexecutionjava.model.ast.Expr;
import com.github.guoyyit.symbolicexecutionjava.model.ast.Program;
import com.github.guoyyit.symbolicexecutionjava.model.ast.Stmt;
import com.github.guoyyit.symbolicexecutionjava.model.symbolic.AffineExpr;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Performs name resolution, linearity / type checks and loop-bound checks.
 * Variables have lexical (block) scoping; shadowing is rejected.
 */
public class Validator {

    /** Symbols visible in the current scope stack. */
    private final Deque<Set<String>> scopes = new ArrayDeque<>();
    /** All declared symbols in first-declaration order (program-wide). */
    private final List<String> allVariables = new ArrayList<>();
    /** Validated loop descriptors in source order. */
    private final List<ResolvedProgram.LoopInfo> loops = new ArrayList<>();
    /** Counter variables owned by enclosing loops (read-only inside their bodies). */
    private final Set<String> loopCounters = new HashSet<>();

    public ResolvedProgram validate(Program program) {
        scopes.clear();
        allVariables.clear();
        loops.clear();
        loopCounters.clear();
        pushScope();
        for (Stmt stmt : program.statements()) {
            checkStmt(stmt);
        }
        popScope();
        return new ResolvedProgram(program, List.copyOf(allVariables), List.copyOf(loops));
    }

    // ----- statements -----

    private void checkStmt(Stmt stmt) {
        if (stmt instanceof Stmt.Decl decl) {
            if (decl.init() != null) {
                requireAffine(decl.init());
            }
            declare(decl.name(), decl.namePos());
        } else if (stmt instanceof Stmt.Assign assign) {
            requireDeclared(assign.name(), assign.namePos());
            requireAffine(assign.value());
            if (loopCounters.contains(assign.name())) {
                throw new ValidationException(
                        "loop counter '" + assign.name()
                                + "' must not be reassigned inside its loop body",
                        assign.namePos());
            }
        } else if (stmt instanceof Stmt.Block block) {
            pushScope();
            for (Stmt inner : block.statements()) {
                checkStmt(inner);
            }
            popScope();
        } else if (stmt instanceof Stmt.If ifStmt) {
            requireCond(ifStmt.cond());
            checkStmt(ifStmt.thenBlock());
            if (ifStmt.elseBlock() != null) {
                checkStmt(ifStmt.elseBlock());
            }
        } else if (stmt instanceof Stmt.For forStmt) {
            checkFor(forStmt);
        } else if (stmt instanceof Stmt.Assert assertStmt) {
            requireCond(assertStmt.cond());
        } else {
            throw new ValidationException("unsupported statement: " + stmt,
                    stmt.pos());
        }
    }

    private void checkFor(Stmt.For forStmt) {
        Stmt init = forStmt.init();
        String counter;
        if (init instanceof Stmt.Decl decl) {
            if (decl.init() == null) {
                throw new ValidationException(
                        "for-loop counter '" + decl.name() + "' must have an initial value",
                        decl.namePos());
            }
            requireAffine(decl.init());
            declare(decl.name(), decl.namePos());
            counter = decl.name();
        } else if (init instanceof Stmt.Assign assign) {
            requireDeclared(assign.name(), assign.namePos());
            requireAffine(assign.value());
            counter = assign.name();
        } else {
            throw new ValidationException(
                    "for-loop must initialise a counter variable, e.g. 'for (int i = 0; ...)'",
                    forStmt.pos());
        }

        if (!(forStmt.cond() instanceof Cond.Rel rel)
                || (rel.op() != Cond.RelOp.LT && rel.op() != Cond.RelOp.LE
                && rel.op() != Cond.RelOp.GT && rel.op() != Cond.RelOp.GE)) {
            throw new ValidationException(
                    "for-loop bound must be a single comparison '<', '<=', '>' or '>='",
                    forStmt.cond() == null ? forStmt.pos() : forStmt.cond().pos());
        }
        AffineExpr boundSide;
        boolean ascending;
        if (isVar(rel.left(), counter)) {
            if (!(rel.right() instanceof Expr.IntLit) && !allVariablesUsed(rel.right()).isEmpty()) {
                requireAffine(rel.right());
            }
            boundSide = requireAffine(rel.right());
            ascending = rel.op() == Cond.RelOp.LT || rel.op() == Cond.RelOp.LE;
        } else if (isVar(rel.right(), counter)) {
            requireAffine(rel.left());
            boundSide = requireAffine(rel.left());
            ascending = rel.op() == Cond.RelOp.GT || rel.op() == Cond.RelOp.GE;
        } else {
            throw new ValidationException(
                    "for-loop condition must compare its counter '" + counter
                            + "' with a constant or affine bound",
                    rel.pos());
        }
        AffineExpr start = init instanceof Stmt.Decl d
                ? requireAffine(d.init())
                : requireAffine(((Stmt.Assign) init).value());

        if (!(forStmt.update() instanceof Stmt.Assign update)
                || !update.name().equals(counter)) {
            throw new ValidationException(
                    "for-loop must update its counter '" + counter
                            + "' by a fixed positive/negative constant",
                    forStmt.update() == null ? forStmt.pos() : forStmt.update().pos());
        }
        long step = extractCounterStep(update.value(), counter, update.pos(), ascending);
        if (ascending ? step <= 0 : step >= 0) {
            throw new ValidationException(
                    "for-loop counter step must be " + (ascending ? "positive" : "negative"),
                    update.pos());
        }

        if (start.isConstant() && boundSide.isConstant()) {
            long s = start.constant();
            long b = boundSide.constant();
            boolean never = ascending
                    ? ((rel.op() == Cond.RelOp.LT && s >= b) || (rel.op() == Cond.RelOp.LE && s > b))
                    : ((rel.op() == Cond.RelOp.GT && s <= b) || (rel.op() == Cond.RelOp.GE && s < b));
            if (never) {
                throw new ValidationException(
                        "for-loop with constant bounds can never execute (start " + s
                                + (ascending ? " vs bound " + b : " vs lower bound " + b) + ")",
                        rel.pos());
            }
        }

        loopCounters.add(counter);
        pushScope();
        for (Stmt inner : forStmt.body().statements()) {
            checkStmt(inner);
        }
        popScope();
        loopCounters.remove(counter);

        loops.add(new ResolvedProgram.LoopInfo(forStmt, counter, init, rel, update,
                forStmt.body(), ascending));
    }

    private long extractCounterStep(Expr value, String counter,
                                    com.github.guoyyit.symbolicexecutionjava.model.ast.Position pos,
                                    boolean ascending) {
        AffineExpr e = requireAffine(value);
        long c = e.get(counter);
        if (c != 1 || e.coefficients().size() != 1) {
            throw new ValidationException(
                    "for-loop update must have the form '" + counter + " = " + counter
                            + (ascending ? " + k' (constant k)" : " - k' (constant k)"), pos);
        }
        return e.constant();
    }

    // ----- expressions / conditions -----

    private AffineExpr requireAffine(Expr expr) {
        if (expr instanceof Expr.IntLit lit) {
            return AffineExpr.constant(lit.value());
        }
        if (expr instanceof Expr.Var var) {
            requireDeclared(var.name(), var.pos());
            return AffineExpr.variable(var.name());
        }
        if (expr instanceof Expr.Bin bin) {
            AffineExpr l = requireAffine(bin.left());
            AffineExpr r = requireAffine(bin.right());
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
            throw new ValidationException(
                    "non-linear expression: multiplication requires at least one "
                            + "constant operand (only linear integer arithmetic is supported)",
                    bin.pos());
        }
        throw new ValidationException("unsupported expression: " + expr, expr.pos());
    }

    private void requireCond(Cond cond) {
        if (cond instanceof Cond.Rel rel) {
            requireAffine(rel.left());
            requireAffine(rel.right());
        } else if (cond instanceof Cond.And and) {
            requireCond(and.left());
            requireCond(and.right());
        } else if (cond instanceof Cond.Or or) {
            requireCond(or.left());
            requireCond(or.right());
        } else if (cond instanceof Cond.Not not) {
            requireCond(not.inner());
        } else {
            throw new ValidationException("unsupported condition: " + cond, cond.pos());
        }
    }

    private List<String> allVariablesUsed(Expr expr) {
        List<String> names = new ArrayList<>();
        collectVars(expr, names);
        return names;
    }

    private void collectVars(Expr expr, List<String> out) {
        if (expr instanceof Expr.Var var) {
            out.add(var.name());
        } else if (expr instanceof Expr.Bin bin) {
            collectVars(bin.left(), out);
            collectVars(bin.right(), out);
        }
    }

    private boolean isVar(Expr expr, String name) {
        return expr instanceof Expr.Var v && v.name().equals(name);
    }

    // ----- scope -----

    private void declare(String name,
                         com.github.guoyyit.symbolicexecutionjava.model.ast.Position pos) {
        for (Set<String> scope : scopes) {
            if (scope.contains(name)) {
                throw new ValidationException("variable '" + name + "' is already declared", pos);
            }
        }
        scopes.peek().add(name);
        allVariables.add(name);
    }

    private void requireDeclared(String name,
                                 com.github.guoyyit.symbolicexecutionjava.model.ast.Position pos) {
        for (Set<String> scope : scopes) {
            if (scope.contains(name)) {
                return;
            }
        }
        throw new ValidationException("undefined variable '" + name + "'", pos);
    }

    private void pushScope() {
        scopes.push(new HashSet<>());
    }

    private void popScope() {
        scopes.pop();
    }

    /** Declared symbols map (insertion ordered), mainly useful for diagnostics/tests. */
    public Map<String, Boolean> declaredSymbolMap() {
        Map<String, Boolean> map = new LinkedHashMap<>();
        allVariables.forEach(v -> map.put(v, Boolean.TRUE));
        return map;
    }
}
