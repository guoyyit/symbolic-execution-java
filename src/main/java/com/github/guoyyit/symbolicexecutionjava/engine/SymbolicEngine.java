package com.github.guoyyit.symbolicexecutionjava.engine;

import com.github.guoyyit.symbolicexecutionjava.cfg.Cfg;
import com.github.guoyyit.symbolicexecutionjava.checkpoint.Checkpoint;
import com.github.guoyyit.symbolicexecutionjava.model.ast.Cond;
import com.github.guoyyit.symbolicexecutionjava.model.ast.Expr;
import com.github.guoyyit.symbolicexecutionjava.model.ast.Stmt;
import com.github.guoyyit.symbolicexecutionjava.model.symbolic.AffineExpr;
import com.github.guoyyit.symbolicexecutionjava.model.symbolic.ConstraintEncoder;
import com.github.guoyyit.symbolicexecutionjava.model.symbolic.LinearConstraint;
import com.github.guoyyit.symbolicexecutionjava.model.symbolic.PathCondition;
import com.github.guoyyit.symbolicexecutionjava.model.symbolic.SymbolicStore;
import com.github.guoyyit.symbolicexecutionjava.solver.LinearSolver;
import com.github.guoyyit.symbolicexecutionjava.solver.SolverResult;
import com.github.guoyyit.symbolicexecutionjava.validation.ResolvedProgram;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Depth-first symbolic executor based on a work-list of independent paths.
 *
 * <p>Each suspended path carries its own symbolic store, path condition and
 * statement stack. Branching and assertion evaluation produce zero, one or two
 * child paths; unsatisfiable children are pruned immediately. The work-list is
 * LIFO and the positive side (condition holds) is always enqueued last so it is
 * explored first. This fixes the path enumeration order independently of hash
 * iteration, which together with the seeded solver makes results reproducible.</p>
 *
 * <p>Loops are interpreted one iteration at a time at runtime: entering a loop
 * pauses the path on an explicit {@link LoopStep}; re-entering it forks into
 * "condition false (exit)" and "condition true (body + next iteration)". A
 * global unroll cap bounds even loops with symbolic bounds, and paths that
 * still run after the cap are reported as LIMIT_REACHED.</p>
 */
public class SymbolicEngine {

    private final ResolvedProgram program;
    private final LinearSolver solver;
    private final EngineConfig config;
    private final Map<Stmt, ResolvedProgram.LoopInfo> loopInfos = new IdentityHashMap<>();

    public SymbolicEngine(ResolvedProgram program, Cfg cfg, LinearSolver solver,
                          EngineConfig config) {
        this.program = program;
        this.solver = solver;
        this.config = config;
        for (ResolvedProgram.LoopInfo info : program.loops()) {
            loopInfos.put(info.statement(), info);
        }
    }

    public void run(ExecutionLimits limits, AtomicBoolean cancelled,
                    PathListener listener, Checkpoint resumeFrom) {
        int skip = resumeFrom == null ? 0 : resumeFrom.reportedCount();
        int nextId = resumeFrom == null
                ? 0
                : resumeFrom.paths().stream().mapToInt(com.github.guoyyit.symbolicexecutionjava.checkpoint.PathSnapshot::pathId)
                        .max().orElse(-1) + 1;

        long deadlineNanos = limits.timeoutMillis() <= 0
                ? Long.MAX_VALUE
                : System.nanoTime() + limits.timeoutMillis() * 1_000_000L;

        Context ctx = new Context(limits, cancelled, listener, deadlineNanos);
        ctx.skip = skip;
        ctx.nextId = nextId;

        PathState initial = new PathState(new SymbolicStore(), PathCondition.empty());
        Deque<Frame> initStack = new ArrayDeque<>();
        pushStatements(initStack, program.program().statements());
        ctx.work.push(new Path(initial, initStack));

        while (!ctx.work.isEmpty()) {
            throwIfCancelled(ctx);
            if (ctx.limits.maxPaths() > 0 && ctx.reported >= ctx.limits.maxPaths()) {
                return;
            }
            // A path is processed one frame at a time. stepOne tells us whether
            // the same path remains at the head (CONTINUE) or has been consumed
            // (terminated, or replaced by fork siblings).
            Path path = ctx.work.peek();
            if (stepOne(path, ctx) == StepResult.CONSUMED) {
                ctx.work.pop();
            }
        }
    }

    /**
     * Executes exactly one frame of one path.
     *
     * @return CONTINUE if the same path stays at the head of the work-list,
     *         CONSUMED if it terminated or replaced itself via forking.
     */
    private StepResult stepOne(Path path, Context ctx) {
        if (path.steps++ > ctx.limits.maxSteps() && ctx.limits.maxSteps() > 0) {
            throw new ResourceLimitException("maximum symbolic steps exceeded");
        }
        if (path.stack.isEmpty()) {
            PathOutcome outcome = path.limitReached
                    ? PathOutcome.LIMIT_REACHED : PathOutcome.SUCCESS;
            report(outcome, path.state, "program completed normally", null, ctx);
            return StepResult.CONSUMED;
        }
        Frame frame = path.stack.pop();
        if (frame instanceof Frame.Stmt f) {
            Stmt stmt = f.statement();
            if (stmt instanceof Stmt.Decl decl) {
                if (decl.init() == null) {
                    path.state.store.declareFree(decl.name());
                } else {
                    path.state.store.declare(decl.name(),
                            ConstraintEncoder.linear(decl.init(), path.state.store));
                }
                return StepResult.CONTINUE;
            }
            if (stmt instanceof Stmt.Assign assign) {
                path.state.store.assign(assign.name(),
                        ConstraintEncoder.linear(assign.value(), path.state.store));
                return StepResult.CONTINUE;
            }
            if (stmt instanceof Stmt.Block block) {
                pushStatements(path.stack, block.statements());
                return StepResult.CONTINUE;
            }
            if (stmt instanceof Stmt.If ifStmt) {
                branchIf(ifStmt.cond(), ifStmt.thenBlock().statements(),
                        ifStmt.elseBlock() == null ? List.of()
                                : ifStmt.elseBlock().statements(),
                        path, ctx);
                // branchIf removed and replaced the current path itself
                return StepResult.CONTINUE;
            }
            if (stmt instanceof Stmt.For forStmt) {
                enterLoop(forStmt, path);
                return StepResult.CONTINUE; // top of stack is now a LoopStep
            }
            if (stmt instanceof Stmt.Assert assertStmt) {
                // branchAssertion removes the current path itself and enqueues
                // the failing reports and surviving successors, so no extra pop.
                branchAssertion(assertStmt.cond(),
                        "assert at " + assertStmt.pos(), path, ctx);
                return StepResult.CONTINUE;
            }
            throw new IllegalStateException("unhandled statement: " + stmt);
        }
        if (frame instanceof Frame.LoopStep loopStep) {
            doLoopIteration(loopStep, path, ctx);
            // On cap the path stays (empty stack -> reported next tick); on fork
            // the current path was replaced. In both cases no extra pop here.
            return StepResult.CONTINUE;
        }
        throw new IllegalStateException("unhandled frame: " + frame);
    }

    // ----- branching -----

    /**
     * Expands {@code cond} (with the given negation flag) to DNF and returns one
     * independently committed, feasible state per satisfiable minterm. Infeasible
     * minterms are pruned immediately and counted as backtracks.
     */
    private List<PathState> feasibleChildren(PathState base, Cond cond, boolean negated,
                                             Context ctx) {
        List<List<LinearConstraint>> minterms =
                ConstraintEncoder.dnf(cond, !negated, base.store);
        List<PathState> feasible = new ArrayList<>();
        for (List<LinearConstraint> minterm : minterms) {
            PathState child = base.copy();
            List<LinearConstraint> all = new ArrayList<>(child.condition.constraints());
            for (LinearConstraint c : minterm) {
                all.add(expandToInputs(child, c));
            }
            SolverResult result = solver.checkConstraints(child.store.freeSymbols(), all);
            if (result.satisfiable()) {
                child.condition = new PathCondition(List.copyOf(all));
                feasible.add(child);
            } else {
                ctx.backtracks++;
            }
        }
        return feasible;
    }

    /** Rewrites one constraint's versioned symbols down to free input symbols. */
    private LinearConstraint expandToInputs(PathState state, LinearConstraint c) {
        AffineExpr in = state.store.expandToInputs(new AffineExpr(c.coefficients(), 0L));
        long rhs = c.rhs() - in.constant();
        return new LinearConstraint(in.coefficients(), c.op(), rhs);
    }

    /** Forks an if-statement across the DNF minterms of its condition. */
    private void branchIf(Cond cond, List<Stmt> thenStmts, List<Stmt> elseStmts,
                          Path current, Context ctx) {
        PathState base = current.state;
        Deque<Frame> continuation = new ArrayDeque<>(current.stack);

        List<PathState> negative = feasibleChildren(base, cond, true, ctx);
        List<PathState> positive = feasibleChildren(base, cond, false, ctx);

        ctx.work.removeFirstOccurrence(current);

        // Negative (else) siblings explored after positive ones: push first.
        for (PathState n : negative) {
            Deque<Frame> st = new ArrayDeque<>();
            pushStatements(st, elseStmts);
            st.addAll(continuation);
            ctx.work.push(new Path(n, st));
        }
        // Positive (then) siblings on top; reverse so DNF order is preserved.
        for (int i = positive.size() - 1; i >= 0; i--) {
            Deque<Frame> st = new ArrayDeque<>();
            pushStatements(st, thenStmts);
            st.addAll(continuation);
            ctx.work.push(new Path(positive.get(i), st));
        }
    }

    /**
     * Evaluates an assertion across DNF minterms. Each feasible negated minterm
     * is a terminal ASSERTION_FAILED path; each feasible positive minterm
     * continues as an independent successful path.
     */
    private boolean branchAssertion(Cond cond, String target, Path current, Context ctx) {
        PathState base = current.state;
        Deque<Frame> continuation = new ArrayDeque<>(current.stack);

        List<PathState> failing = feasibleChildren(base, cond, true, ctx);
        List<PathState> succeeding = feasibleChildren(base, cond, false, ctx);

        ctx.work.removeFirstOccurrence(current);

        for (PathState f : failing) {
            SolverResult model = solver.check(f.store.freeSymbols(), f.condition);
            if (model.satisfiable()) {
                report(PathOutcome.ASSERTION_FAILED, f, target, model.model(), ctx);
            }
        }
        for (PathState ok : succeeding) {
            ctx.work.push(new Path(ok, new ArrayDeque<>(continuation)));
        }
        return false;
    }

    // ----- loops -----

    private void enterLoop(Stmt.For forStmt, Path path) {
        ResolvedProgram.LoopInfo info = loopInfo(forStmt);
        // Initialise counter in this path.
        if (info.initializer() instanceof Stmt.Decl decl) {
            AffineExpr init = decl.init() == null ? AffineExpr.constant(0)
                    : ConstraintEncoder.linear(decl.init(), path.state.store);
            path.state.store.declare(decl.name(), init);
        } else if (info.initializer() instanceof Stmt.Assign assign) {
            path.state.store.assign(assign.name(),
                    ConstraintEncoder.linear(assign.value(), path.state.store));
        }
        path.stack.push(new Frame.LoopStep(forStmt, 0));
    }

    private void doLoopIteration(Frame.LoopStep step, Path current, Context ctx) {
        ResolvedProgram.LoopInfo info = loopInfo(step.forStmt());
        Deque<Frame> continuation = new ArrayDeque<>(current.stack);

        // Safety cap: a path that can still enter after the maximum number of
        // unrolls is reported LIMIT_REACHED rather than explored indefinitely.
        if (step.iteration() >= ctx.limits.maxUnroll()) {
            current.limitReached = true;
            current.stack.clear();
            return;
        }

        Cond cond = info.condition();
        List<PathState> exits = feasibleChildren(current.state, cond, true, ctx);
        List<PathState> enters = feasibleChildren(current.state, cond, false, ctx);

        ctx.work.removeFirstOccurrence(current);

        // Exit siblings beneath entered iterations.
        for (PathState exitState : exits) {
            ctx.work.push(new Path(exitState, new ArrayDeque<>(continuation)));
        }
        // Entered iterations on top; body, update, next marker, continuation.
        for (int i = enters.size() - 1; i >= 0; i--) {
            Deque<Frame> full = new ArrayDeque<>();
            pushStatements(full, info.body().statements());
            full.addLast(new Frame.Stmt(info.update()));
            full.addLast(new Frame.LoopStep(step.forStmt(), step.iteration() + 1));
            continuation.forEach(full::addLast);
            ctx.work.push(new Path(enters.get(i), full));
        }
    }

    private ResolvedProgram.LoopInfo loopInfo(Stmt.For forStmt) {
        ResolvedProgram.LoopInfo info = loopInfos.get(forStmt);
        if (info == null) {
            throw new IllegalStateException("unvalidated loop at " + forStmt.pos());
        }
        return info;
    }

    // ----- constraints / reporting -----

    private void report(PathOutcome outcome, PathState state, String target,
                        Map<String, Long> modelHint, Context ctx) {
        SolverResult solved = modelHint == null
                ? solver.check(state.store.freeSymbols(), state.condition)
                : SolverResult.sat(modelHint);
        if (!solved.satisfiable()) {
            return;
        }
        int id = ctx.nextId++;
        if (ctx.reported < ctx.skip) {
            ctx.reported++;
            return;
        }
        List<String> rendered = render(state.condition);
        Map<String, Long> finalValues = state.store.evaluateAll(solved.model());
        PathReport pathReport = new PathReport(id, outcome, rendered, state.condition,
                new LinkedHashMap<>(solved.model()), finalValues, target);
        ctx.listener.onPath(pathReport);
        ctx.reported++;
    }

    private List<String> render(PathCondition condition) {
        List<String> out = new ArrayList<>();
        for (LinearConstraint c : condition.constraints()) {
            out.add(renderOne(c));
        }
        return out;
    }

    private String renderOne(LinearConstraint c) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Long> e : c.coefficients().entrySet()) {
            long v = e.getValue();
            sb.append(first ? (v < 0 ? "-" : "") : (v < 0 ? " - " : " + "));
            long abs = Math.abs(v);
            if (abs != 1) {
                sb.append(abs).append('*');
            }
            sb.append(e.getKey());
            first = false;
        }
        if (first) {
            sb.append('0');
        }
        sb.append(' ').append(switch (c.op()) {
            case LT -> "<";
            case LE -> "<=";
            case GT -> ">";
            case GE -> ">=";
            case EQ -> "==";
            case NE -> "!=";
        }).append(' ').append(c.rhs());
        return sb.toString();
    }

    private void pushStatements(Deque<Frame> stack, List<Stmt> stmts) {
        for (int i = stmts.size() - 1; i >= 0; i--) {
            stack.push(new Frame.Stmt(stmts.get(i)));
        }
    }

    private void throwIfCancelled(Context ctx) {
        if (ctx.cancelled.get()) {
            throw new CancelledException(false);
        }
        if (System.nanoTime() > ctx.deadlineNanos) {
            throw new CancelledException(true);
        }
    }

    // ----- internal types -----

    private enum StepResult { CONTINUE, CONSUMED }

    private static final class Context {
        final ExecutionLimits limits;
        final AtomicBoolean cancelled;
        final PathListener listener;
        final long deadlineNanos;
        final Deque<Path> work = new ArrayDeque<>();
        int skip;
        int nextId;
        int reported;
        int backtracks;

        Context(ExecutionLimits limits, AtomicBoolean cancelled, PathListener listener,
                long deadlineNanos) {
            this.limits = limits;
            this.cancelled = cancelled;
            this.listener = listener;
            this.deadlineNanos = deadlineNanos;
        }
    }

    private static final class Path {
        PathState state;
        Deque<Frame> stack;
        long steps;
        boolean limitReached;

        Path(PathState state, Deque<Frame> stack) {
            this.state = state;
            this.stack = stack;
        }
    }

    private static final class PathState {
        final SymbolicStore store;
        PathCondition condition;

        PathState(SymbolicStore store, PathCondition condition) {
            this.store = store;
            this.condition = condition;
        }

        PathState copy() {
            return new PathState(store.copy(),
                    new PathCondition(new ArrayList<>(condition.constraints())));
        }
    }

    private sealed interface Frame permits Frame.Stmt, Frame.LoopStep {
        record Stmt(com.github.guoyyit.symbolicexecutionjava.model.ast.Stmt statement) implements Frame {}

        record LoopStep(com.github.guoyyit.symbolicexecutionjava.model.ast.Stmt.For forStmt, int iteration) implements Frame {}
    }
}
