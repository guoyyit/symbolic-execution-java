package com.github.guoyyit.symbolicexecutionjava.cfg;

import com.github.guoyyit.symbolicexecutionjava.lang.Program;
import com.github.guoyyit.symbolicexecutionjava.lang.Stmt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lowers a validated {@link Program} AST into a {@link Cfg}.
 *
 * <p>Each statement is compiled into a mini-graph identified by its entry
 * node and pending exits {@code (node, edgeKind)}: blocks wire pending exits
 * into the next statement entry, {@code if} creates a BRANCH with TRUE/FALSE
 * exits, and loops create a BRANCH head carrying the declared bound with every
 * body terminal connected back to the head (the empty body yields a TRUE
 * self-edge). Node ids are issued in deterministic construction order.
 */
public final class CfgBuilder {

    private final List<Cfg.Node> nodes = new ArrayList<>();
    private final List<Cfg.Edge> edges = new ArrayList<>();
    private final Map<Integer, List<Cfg.Edge>> outgoing = new LinkedHashMap<>();
    private int nextId;

    public Cfg build(Program program) {
        nodes.clear();
        edges.clear();
        outgoing.clear();
        nextId = 0;

        Cfg.Node entry = add(Cfg.Node.entry(nextId++));
        Cfg.Node exit = add(Cfg.Node.exit(nextId++));

        for (Exit e : buildBlock(program.statements(), entry.id(), Cfg.EdgeKind.UNCOND)) {
            connect(e.node(), exit.id(), e.kind());
        }

        for (Cfg.Edge edge : edges) {
            outgoing.get(edge.from()).add(edge);
        }
        // Deterministic outgoing order: TRUE, FALSE, then UNCOND.
        outgoing.values().forEach(list -> list.sort((a, b) -> rank(a.kind()) - rank(b.kind())));
        return new Cfg(nodes, edges, outgoing, entry.id(), exit.id());
    }

    private int rank(Cfg.EdgeKind kind) {
        return switch (kind) {
            case TRUE -> 0;
            case FALSE -> 1;
            case UNCOND -> 2;
        };
    }

    private record Exit(int node, Cfg.EdgeKind kind) {
    }

    private record Chain(int entry, List<Exit> exits) {
    }

    /**
     * Compiles a statement list; the first statement entry is reached from
     * {@code predecessor} through {@code initialKind}. Returns the pending
     * exits of the compiled block.
     */
    private List<Exit> buildBlock(List<Stmt> statements, int predecessor,
                                  Cfg.EdgeKind initialKind) {
        List<Exit> exits = new ArrayList<>();
        exits.add(new Exit(predecessor, initialKind));
        for (Stmt stmt : statements) {
            Chain chain = buildStmt(stmt);
            for (Exit e : exits) {
                connect(e.node(), chain.entry(), e.kind());
            }
            exits = new ArrayList<>(chain.exits());
        }
        return exits;
    }

    private Chain buildStmt(Stmt stmt) {
        if (stmt instanceof Stmt.Assign a) {
            Cfg.Node node = add(new Cfg.Node(nextId++, Cfg.NodeKind.ASSIGN,
                    a.target(), a.expression(), null, a.line(), null));
            return new Chain(node.id(), List.of(new Exit(node.id(), Cfg.EdgeKind.UNCOND)));
        }
        if (stmt instanceof Stmt.Assert as) {
            Cfg.Node node = add(new Cfg.Node(nextId++, Cfg.NodeKind.ASSERT,
                    null, null, as.condition(), as.line(), null));
            return new Chain(node.id(), List.of(new Exit(node.id(), Cfg.EdgeKind.UNCOND)));
        }
        if (stmt instanceof Stmt.If f) {
            Cfg.Node branch = add(new Cfg.Node(nextId++, Cfg.NodeKind.BRANCH,
                    null, null, f.condition(), f.line(), null));
            List<Exit> exits = new ArrayList<>();
            if (f.thenBranch().isEmpty()) {
                exits.add(new Exit(branch.id(), Cfg.EdgeKind.TRUE));
            } else {
                exits.addAll(buildBlock(f.thenBranch(), branch.id(), Cfg.EdgeKind.TRUE));
            }
            if (f.elseBranch().isEmpty()) {
                exits.add(new Exit(branch.id(), Cfg.EdgeKind.FALSE));
            } else {
                exits.addAll(buildBlock(f.elseBranch(), branch.id(), Cfg.EdgeKind.FALSE));
            }
            return new Chain(branch.id(), exits);
        }
        Stmt.Loop loop = (Stmt.Loop) stmt;
        Cfg.Node head = add(new Cfg.Node(nextId++, Cfg.NodeKind.BRANCH,
                null, null, loop.condition(), loop.line(), loop.maxIterations()));
        if (loop.body().isEmpty()) {
            // TRUE re-enters the head (re-check); FALSE leaves the loop.
            connect(head.id(), head.id(), Cfg.EdgeKind.TRUE);
        } else {
            List<Exit> bodyExits = buildBlock(loop.body(), head.id(), Cfg.EdgeKind.TRUE);
            for (Exit e : bodyExits) {
                connect(e.node(), head.id(), Cfg.EdgeKind.UNCOND);
            }
        }
        return new Chain(head.id(), List.of(new Exit(head.id(), Cfg.EdgeKind.FALSE)));
    }

    private Cfg.Node add(Cfg.Node node) {
        nodes.add(node);
        outgoing.put(node.id(), new ArrayList<>());
        return node;
    }

    private void connect(int from, int to, Cfg.EdgeKind kind) {
        edges.add(new Cfg.Edge(from, to, kind));
    }
}
