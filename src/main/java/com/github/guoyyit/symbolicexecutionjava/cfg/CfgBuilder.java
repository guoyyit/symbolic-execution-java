package com.github.guoyyit.symbolicexecutionjava.cfg;

import com.github.guoyyit.symbolicexecutionjava.model.ast.Stmt;
import com.github.guoyyit.symbolicexecutionjava.validation.ResolvedProgram;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a complete branch-explicit control-flow graph.
 *
 * <p>Construction is classic continuation-passing style:
 * {@code build(stmt, successor)} creates nodes for a statement with the given
 * successor and returns the statement's entry node. A BRANCH node has exactly
 * two outgoing guarded edges: the positive condition and its negation. An ASSERT
 * node has an ASSERT_FAIL edge (self-loop marking the terminal failure outcome)
 * and an ASSERT_OK edge (negated guard) to the successor.</p>
 *
 * <p>Loops are represented in the graph by counter-initialiser plus a JOIN
 * marker only; the {@link com.github.guoyyit.symbolicexecutionjava.engine.SymbolicEngine}
 * performs bounded unrolling directly from the AST loop metadata, guaranteeing
 * termination without any back edge to follow.</p>
 */
public class CfgBuilder {

    private final List<CfgNode> nodes = new ArrayList<>();
    private final List<Edge> edges = new ArrayList<>();
    private final Map<Integer, List<Edge>> outgoing = new HashMap<>();
    private int nextId;

    public Cfg build(ResolvedProgram program) {
        nodes.clear();
        edges.clear();
        outgoing.clear();
        nextId = 0;

        CfgNode exit = node(CfgNode.Kind.EXIT, null);
        CfgNode successor = exit;
        List<Stmt> top = program.program().statements();
        for (int i = top.size() - 1; i >= 0; i--) {
            successor = build(top.get(i), successor);
        }
        CfgNode entry = node(CfgNode.Kind.ENTRY, null);
        addEdge(Edge.normal(entry, successor));
        return new Cfg(entry, exit, nodes, edges, outgoing);
    }

    /** Builds {@code stmt} given its successor; returns the entry node of the statement. */
    private CfgNode build(Stmt stmt, CfgNode successor) {
        if (stmt instanceof Stmt.Decl decl) {
            CfgNode n = node(CfgNode.Kind.DECL, decl);
            addEdge(Edge.normal(n, successor));
            return n;
        }
        if (stmt instanceof Stmt.Assign assign) {
            CfgNode n = node(CfgNode.Kind.ASSIGN, assign);
            addEdge(Edge.normal(n, successor));
            return n;
        }
        if (stmt instanceof Stmt.Block block) {
            CfgNode next = successor;
            List<Stmt> stmts = block.statements();
            for (int i = stmts.size() - 1; i >= 0; i--) {
                next = build(stmts.get(i), next);
            }
            // Empty blocks collapse to their successor (the entry equals successor).
            return next;
        }
        if (stmt instanceof Stmt.If ifStmt) {
            CfgNode thenEntry = build(ifStmt.thenBlock(), successor);
            CfgNode falseEntry = ifStmt.elseBlock() == null
                    ? successor
                    : build(ifStmt.elseBlock(), successor);
            CfgNode branch = node(CfgNode.Kind.BRANCH, ifStmt);
            addEdge(Edge.branchTrue(branch, thenEntry, ifStmt.cond()));
            addEdge(Edge.branchFalse(branch, falseEntry, ifStmt.cond()));
            return branch;
        }
        if (stmt instanceof Stmt.Assert assertStmt) {
            CfgNode a = node(CfgNode.Kind.ASSERT, assertStmt);
            addEdge(Edge.assertionFail(a, a, assertStmt.cond()));
            addEdge(Edge.assertionOk(a, successor, assertStmt.cond()));
            return a;
        }
        if (stmt instanceof Stmt.For forStmt) {
            // Static placeholder: counter initialiser flows to the continuation.
            CfgNode init = node(
                    forStmt.init() instanceof Stmt.Decl ? CfgNode.Kind.DECL : CfgNode.Kind.ASSIGN,
                    forStmt.init());
            addEdge(Edge.normal(init, successor));
            return init;
        }
        throw new IllegalStateException("unexpected statement in CFG: " + stmt);
    }

    private void addEdge(Edge edge) {
        edges.add(edge);
        outgoing.computeIfAbsent(edge.from().id(), k -> new ArrayList<>()).add(edge);
    }

    private CfgNode node(CfgNode.Kind kind, Stmt stmt) {
        CfgNode n = new CfgNode(nextId++, kind, stmt);
        nodes.add(n);
        return n;
    }
}
