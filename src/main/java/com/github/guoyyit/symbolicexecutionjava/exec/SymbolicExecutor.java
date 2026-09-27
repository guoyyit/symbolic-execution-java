package com.github.guoyyit.symbolicexecutionjava.exec;

import com.github.guoyyit.symbolicexecutionjava.cfg.Cfg;
import com.github.guoyyit.symbolicexecutionjava.lang.Expr;
import com.github.guoyyit.symbolicexecutionjava.solver.IntegerLinearSolver;
import com.github.guoyyit.symbolicexecutionjava.solver.LinearConstraint;
import com.github.guoyyit.symbolicexecutionjava.solver.LinearExpr;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;

/**
 * Deterministic symbolic-execution engine over an explicit CFG.
 *
 * <h2>Exploration order</h2>
 * The frontier is a FIFO queue (breadth-first). At a branch the TRUE
 * successors are enqueued before the FALSE successors, and alternatives of a
 * disjunctive condition keep their textual order. Together with the fixed
 * solver value order ({@code 0, 1, -1, 2, -2, ...}), identical inputs always
 * produce identical path numbering, constraints, models and final values.
 *
 * <h2>Quanta and recovery</h2>
 * {@link #step(long)} processes up to {@code maxStates} states; the
 * TaskManager snapshots ({@link #snapshot()}) after every quantum. Cancellation
 * and deadline are checked before every expansion, and terminal outcomes
 * other than {@link Outcome#COMPLETED} never expose findings.
 */
public final class SymbolicExecutor {

    public enum Outcome {
        RUNNING, COMPLETED, CANCELLED, TIMEOUT, LIMIT_EXCEEDED
    }

    public record Limits(long maxStates, int maxPaths, long deadlineNanos) {
        public static Limits unlimited() {
            return new Limits(Long.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE);
        }
    }

    private final Cfg cfg;
    private final List<String> variables;
    private final List<String> inputVariables;
    private final IntegerLinearSolver solver;
    private final long seed;

    private final Deque<ExecutorState> frontier = new ArrayDeque<>();
    private final List<Finding> findings = new ArrayList<>();
    private int nextPathId;
    private long statesExplored;
    private Outcome outcome = Outcome.RUNNING;
    private volatile boolean cancelRequested;
    private long deadlineNanos;
    private long maxStates;
    private int maxPaths;

    public SymbolicExecutor(Cfg cfg, List<String> variables, List<String> inputVariables,
                            IntegerLinearSolver solver, long seed, Limits limits) {
        this.cfg = cfg;
        this.variables = List.copyOf(variables);
        this.inputVariables = List.copyOf(inputVariables);
        this.solver = solver;
        this.seed = seed;
        this.maxStates = limits.maxStates();
        this.maxPaths = limits.maxPaths();
        this.deadlineNanos = limits.deadlineNanos();
        frontier.add(new ExecutorState(cfg.entry(), initialEnvironment(),
                List.of(), new TreeMap<>(), 0));
    }

    public static SymbolicExecutor fromCheckpoint(Cfg cfg, List<String> variables,
                                                  List<String> inputVariables,
                                                  IntegerLinearSolver solver, long seed,
                                                  Limits limits,
                                                  Checkpoint checkpoint) {
        SymbolicExecutor executor = new SymbolicExecutor(cfg, variables, inputVariables,
                solver, seed, limits);
        executor.frontier.clear();
        executor.frontier.addAll(checkpoint.getFrontier());
        executor.findings.clear();
        executor.findings.addAll(checkpoint.getFindings());
        executor.nextPathId = checkpoint.getNextPathId();
        executor.statesExplored = checkpoint.getStatesExplored();
        executor.outcome = checkpoint.getOutcome() == null
                ? Outcome.RUNNING : checkpoint.getOutcome();
        return executor;
    }

    private Map<String, LinearExpr> initialEnvironment() {
        TreeMap<String, LinearExpr> env = new TreeMap<>();
        for (String name : variables) {
            env.put(name, inputVariables.contains(name)
                    ? LinearExpr.variable(name)
                    : LinearExpr.constant(0));
        }
        return env;
    }

    public Outcome outcome() {
        return outcome;
    }

    public boolean isDone() {
        return outcome != Outcome.RUNNING;
    }

    public void requestCancel() {
        cancelRequested = true;
    }

    public Checkpoint snapshot() {
        return new Checkpoint(new ArrayList<>(frontier), new ArrayList<>(findings),
                nextPathId, statesExplored, outcome);
    }

    public ExplorationResult result() {
        if (outcome != Outcome.COMPLETED) {
            throw new IllegalStateException("result only available after completion");
        }
        return new ExplorationResult(List.copyOf(findings), statesExplored);
    }

    public void step(long maxStatesPerQuantum) {
        if (isDone()) {
            return;
        }
        long processed = 0;
        while (processed < maxStatesPerQuantum) {
            if (cancelRequested) {
                outcome = Outcome.CANCELLED;
                return;
            }
            if (System.nanoTime() >= deadlineNanos) {
                outcome = Outcome.TIMEOUT;
                return;
            }
            if (statesExplored >= maxStates) {
                outcome = Outcome.LIMIT_EXCEEDED;
                return;
            }
            if (findings.size() >= maxPaths) {
                outcome = Outcome.LIMIT_EXCEEDED;
                return;
            }
            ExecutorState state = frontier.pollFirst();
            if (state == null) {
                outcome = Outcome.COMPLETED;
                return;
            }
            statesExplored++;
            processed++;
            expand(state);
        }
    }

    private void expand(ExecutorState state) {
        Cfg.Node node = node(state.nodeId());
        switch (node.kind()) {
            case ENTRY -> followUnconditional(state, node);
            case EXIT -> reportCompleted(state);
            case ASSIGN -> doAssign(state, node);
            case BRANCH -> doBranch(state, node);
            case ASSERT -> doAssert(state, node);
        }
    }

    private void followUnconditional(ExecutorState state, Cfg.Node node) {
        Cfg.Edge edge = singleEdge(node.id());
        enqueue(edge.to(), state, state.pathCondition(), state.loopIterations());
    }

    private void reportCompleted(ExecutorState state) {
        Map<String, Long> model = requireModel(state.pathCondition());
        findings.add(new Finding(nextPathId++, Finding.Kind.PATH_COMPLETED, 0,
                List.copyOf(state.pathCondition()), model,
                evaluateEnvironment(state.environment(), model)));
    }

    private void doAssign(ExecutorState state, Cfg.Node node) {
        LinearExpr value = evalInt(node.expression(), state.environment());
        Map<String, LinearExpr> env = new TreeMap<>(state.environment());
        env.put(node.target(), value);
        Cfg.Edge edge = singleEdge(node.id());
        enqueue(edge.to(), state, state.pathCondition(), state.loopIterations(), env,
                state.depth() + 1);
    }

    private void doBranch(ExecutorState state, Cfg.Node node) {
        boolean loopHead = node.loopBound() != null;
        int iterations = state.loopIterations().getOrDefault(node.id(), 0);
        if (loopHead && iterations >= node.loopBound()) {
            // Declared bound reached: forced exit, no condition is asserted.
            Cfg.Edge falseEdge = findEdge(node.id(), Cfg.EdgeKind.FALSE);
            enqueue(falseEdge.to(), state, state.pathCondition(), state.loopIterations());
            return;
        }

        List<List<LinearConstraint>> trueAlts = expandBoolean(node.condition(), false,
                state.environment());
        List<List<LinearConstraint>> falseAlts = expandBoolean(node.condition(), true,
                state.environment());

        List<ExecutorState> successors = new ArrayList<>();
        collectSuccessors(node.id(), state, Cfg.EdgeKind.TRUE, trueAlts,
                loopHead, successors);
        collectSuccessors(node.id(), state, Cfg.EdgeKind.FALSE, falseAlts,
                false, successors);
        // With the default seed 0 the canonical exploration order is fixed:
        // TRUE alternatives before FALSE alternatives (breadth-first). Any
        // other seed deterministically shuffles the feasible successors. The
        // shuffle RNG is derived purely from (seed, node, depth) rather than
        // a carried stream, so an executor rebuilt after a restart makes the
        // same choice without persisting RNG state.
        if (seed != 0L) {
            Collections.shuffle(successors,
                    new Random(branchShuffleSeed(seed, node.id(), state.depth())));
        }
        successors.forEach(frontier::addLast);
    }

    /** Splitmix64-style mixing for an even spread from tiny seed differences. */
    private static long branchShuffleSeed(long seed, int nodeId, int depth) {
        long z = seed ^ (long) nodeId * 0x9E3779B97F4A7C15L
                ^ (long) depth * 0xC2B2AE3D27D4EB4FL;
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private void collectSuccessors(int nodeId, ExecutorState state, Cfg.EdgeKind kind,
                                   List<List<LinearConstraint>> alternatives,
                                   boolean incrementLoop, List<ExecutorState> out) {
        int target = findEdge(nodeId, kind).to();
        Map<Integer, Integer> loopIters = state.loopIterations();
        if (incrementLoop) {
            loopIters = new TreeMap<>(loopIters);
            loopIters.merge(nodeId, 1, Integer::sum);
        }
        for (List<LinearConstraint> alternative : alternatives) {
            List<LinearConstraint> added = simplify(alternative, state.pathCondition());
            if (added == null) {
                continue; // concretely false alternative
            }
            List<LinearConstraint> extended = added.isEmpty()
                    ? state.pathCondition()
                    : extend(state.pathCondition(), added);
            // An empty delta cannot change feasibility: the parent state was
            // only enqueued because its path condition was already satisfiable.
            if (added.isEmpty() || solver.isSatisfiable(extended)) {
                out.add(new ExecutorState(target, state.environment(), extended,
                        loopIters, state.depth() + 1));
            }
        }
    }

    /**
     * Constant folding and de-duplication of one conjunctive alternative.
     *
     * @return the constraints actually added to the path condition, or
     *         {@code null} when the alternative is concretely impossible
     */
    private List<LinearConstraint> simplify(List<LinearConstraint> alternative,
                                            List<LinearConstraint> pc) {
        List<LinearConstraint> added = new ArrayList<>(alternative.size());
        for (LinearConstraint c : alternative) {
            if (c.expression().isConstant()) {
                long value = c.expression().eval(Map.of());
                if (!c.holds(Map.of())) {
                    return null;
                }
                // Concretely true: no constraint needs recording.
                continue;
            }
            if (!pc.contains(c) && !added.contains(c)) {
                added.add(c);
            }
        }
        return added;
    }

    private void doAssert(ExecutorState state, Cfg.Node node) {
        // An assert forks the current path: negated alternatives are the
        // failure paths (terminate at the assert), positive alternatives are
        // the success paths (record a passed finding and keep executing).
        // Both kinds must be reported, so the negated side never returns early.
        List<List<LinearConstraint>> negated = expandBoolean(node.condition(), true,
                state.environment());
        for (List<LinearConstraint> alternative : negated) {
            List<LinearConstraint> added = simplify(alternative, state.pathCondition());
            if (added == null) {
                continue; // concretely impossible failure alternative
            }
            List<LinearConstraint> violating = added.isEmpty()
                    ? state.pathCondition()
                    : extend(state.pathCondition(), added);
            Optional<Map<String, Long>> model = completeModel(solver.solve(violating));
            if (model.isPresent()) {
                findings.add(new Finding(nextPathId++, Finding.Kind.ASSERT_VIOLATED,
                        node.line(), List.copyOf(violating), model.get(),
                        evaluateEnvironment(state.environment(), model.get())));
            }
        }
        List<List<LinearConstraint>> positive = expandBoolean(node.condition(), false,
                state.environment());
        Cfg.Edge edge = singleEdge(node.id());
        boolean continued = false;
        for (List<LinearConstraint> alternative : positive) {
            List<LinearConstraint> added = simplify(alternative, state.pathCondition());
            if (added == null) {
                continue;
            }
            List<LinearConstraint> extended = added.isEmpty()
                    ? state.pathCondition()
                    : extend(state.pathCondition(), added);
            Optional<Map<String, Long>> model = added.isEmpty()
                    ? requireModelOpt(state.pathCondition())
                    : completeModel(solver.solve(extended));
            if (model.isPresent()) {
                findings.add(new Finding(nextPathId++, Finding.Kind.ASSERT_PASSED,
                        node.line(), List.copyOf(extended), model.get(),
                        evaluateEnvironment(state.environment(), model.get())));
                if (!continued) {
                    enqueue(edge.to(), state, extended, state.loopIterations());
                    continued = true;
                }
            }
        }
    }

    private Optional<Map<String, Long>> requireModelOpt(List<LinearConstraint> pc) {
        return completeModel(solver.solve(pc));
    }

    // ---- Condition expansion (NNF / deterministic disjunction splitting) ----

    private List<List<LinearConstraint>> expandBoolean(Expr expr, boolean negated,
                                                       Map<String, LinearExpr> env) {
        if (expr instanceof Expr.Binary b) {
            return switch (b.op()) {
                case AND -> combine(b, negated, false, env);
                case OR -> combine(b, negated, true, env);
                case EQ, NE, LT, LE, GT, GE ->
                        List.of(List.of(atom(b, negated, env)));
                default -> throw new IllegalStateException(
                        "non-boolean operator used as condition: " + b.op());
            };
        }
        if (expr instanceof Expr.Unary u && u.op() == Expr.UnaryOp.NOT) {
            return expandBoolean(u.operand(), !negated, env);
        }
        throw new IllegalStateException("condition is not a boolean expression: " + expr);
    }

    private List<List<LinearConstraint>> combine(Expr.Binary b, boolean negated,
                                                 boolean disjunction,
                                                 Map<String, LinearExpr> env) {
        boolean isUnion = negated != disjunction; // De Morgan swaps and/or
        List<List<LinearConstraint>> left = expandBoolean(b.left(), negated, env);
        List<List<LinearConstraint>> right = expandBoolean(b.right(), negated, env);
        if (isUnion) {
            List<List<LinearConstraint>> union = new ArrayList<>(left.size() + right.size());
            union.addAll(left);
            union.addAll(right);
            return union;
        }
        List<List<LinearConstraint>> product = new ArrayList<>();
        for (List<LinearConstraint> l : left) {
            for (List<LinearConstraint> r : right) {
                product.add(concat(l, r));
            }
        }
        return product;
    }

    private LinearConstraint atom(Expr.Binary comparison, boolean negated,
                                  Map<String, LinearExpr> env) {
        LinearExpr difference = evalInt(comparison.left(), env)
                .subtract(evalInt(comparison.right(), env));
        LinearConstraint.Relation relation = switch (comparison.op()) {
            case EQ -> negated ? LinearConstraint.Relation.NE : LinearConstraint.Relation.EQ;
            case NE -> negated ? LinearConstraint.Relation.EQ : LinearConstraint.Relation.NE;
            case LT -> negated ? LinearConstraint.Relation.GE : LinearConstraint.Relation.LT;
            case LE -> negated ? LinearConstraint.Relation.GT : LinearConstraint.Relation.LE;
            case GT -> negated ? LinearConstraint.Relation.LE : LinearConstraint.Relation.GT;
            case GE -> negated ? LinearConstraint.Relation.LT : LinearConstraint.Relation.GE;
            default -> throw new IllegalStateException("not a comparison: " + comparison.op());
        };
        return LinearConstraint.of(difference, relation);
    }

    // ---- Symbolic integer evaluation ----

    private LinearExpr evalInt(Expr expr, Map<String, LinearExpr> env) {
        if (expr instanceof Expr.Const c) {
            return LinearExpr.constant(c.value());
        }
        if (expr instanceof Expr.Var v) {
            LinearExpr value = env.get(v.name());
            if (value == null) {
                throw new IllegalStateException("uninitialised variable: " + v.name());
            }
            return value;
        }
        if (expr instanceof Expr.Unary u && u.op() == Expr.UnaryOp.NEG) {
            return evalInt(u.operand(), env).negate();
        }
        if (expr instanceof Expr.Binary b) {
            LinearExpr left = evalInt(b.left(), env);
            LinearExpr right = evalInt(b.right(), env);
            return switch (b.op()) {
                case ADD -> left.add(right);
                case SUB -> left.subtract(right);
                case MUL -> {
                    if (b.left() instanceof Expr.Const k) {
                        yield right.scale(k.value());
                    }
                    if (b.right() instanceof Expr.Const k) {
                        yield left.scale(k.value());
                    }
                    throw new IllegalStateException(
                            "non-linear multiplication cannot be symbolically evaluated");
                }
                default -> throw new IllegalStateException(
                        "unsupported integer operator: " + b.op());
            };
        }
        throw new IllegalStateException("not an integer expression: " + expr);
    }

    // ---- Helpers ----

    private Cfg.Node node(int id) {
        return cfg.nodes().get(id);
    }

    private Cfg.Edge singleEdge(int nodeId) {
        return cfg.outgoing(nodeId).get(0);
    }

    private Cfg.Edge findEdge(int nodeId, Cfg.EdgeKind kind) {
        for (Cfg.Edge edge : cfg.outgoing(nodeId)) {
            if (edge.kind() == kind) {
                return edge;
            }
        }
        throw new IllegalStateException("missing " + kind + " edge from node " + nodeId);
    }

    private void enqueue(int target, ExecutorState state, List<LinearConstraint> pc,
                         Map<Integer, Integer> loopIters) {
        enqueue(target, state, pc, loopIters, state.environment(), state.depth() + 1);
    }

    private void enqueue(int target, ExecutorState state, List<LinearConstraint> pc,
                         Map<Integer, Integer> loopIters,
                         Map<String, LinearExpr> env, int depth) {
        frontier.addLast(new ExecutorState(target, env, pc, loopIters, depth));
    }

    private static List<LinearConstraint> extend(List<LinearConstraint> pc,
                                                 List<LinearConstraint> added) {
        return concat(pc, added);
    }

    private static List<LinearConstraint> concat(List<LinearConstraint> a,
                                                 List<LinearConstraint> b) {
        List<LinearConstraint> all = new ArrayList<>(a.size() + b.size());
        all.addAll(a);
        all.addAll(b);
        return all;
    }

    private Map<String, Long> requireModel(List<LinearConstraint> pc) {
        return completeModel(solver.solve(pc)).orElseThrow(() ->
                new IllegalStateException("reachable state with unsatisfiable path condition"));
    }

    /**
     * Inputs that never occur in the path condition are unconstrained; the
     * solver omits them. Bind them to the deterministic default 0 so every
     * reported model covers all declared inputs and final values can always
     * be evaluated.
     */
    private Optional<Map<String, Long>> completeModel(Optional<Map<String, Long>> solved) {
        if (solved.isEmpty()) {
            return Optional.empty();
        }
        TreeMap<String, Long> model = new TreeMap<>(solved.get());
        for (String name : inputVariables) {
            model.putIfAbsent(name, 0L);
        }
        return Optional.of(model);
    }

    private Map<String, Long> evaluateEnvironment(Map<String, LinearExpr> env,
                                                  Map<String, Long> model) {
        TreeMap<String, Long> values = new TreeMap<>();
        for (Map.Entry<String, LinearExpr> e : env.entrySet()) {
            values.put(e.getKey(), e.getValue().eval(model));
        }
        return values;
    }
}
