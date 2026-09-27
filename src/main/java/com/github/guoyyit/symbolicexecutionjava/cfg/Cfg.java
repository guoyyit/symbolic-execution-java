package com.github.guoyyit.symbolicexecutionjava.cfg;

import com.github.guoyyit.symbolicexecutionjava.lang.Expr;

import java.util.List;
import java.util.Map;

/**
 * Explicit control-flow graph. Branch nodes carry their condition on the
 * {@link EdgeKind#TRUE}/{@link EdgeKind#FALSE} edges; loop heads are branch
 * nodes with a positive {@link Node#loopBound()} and a back edge from the end
 * of the loop body.
 */
public final class Cfg {

    public enum NodeKind {
        ENTRY, ASSIGN, BRANCH, ASSERT, EXIT
    }

    public enum EdgeKind {
        UNCOND, TRUE, FALSE
    }

    public record Node(int id, NodeKind kind, String target,
                       Expr expression, Expr condition,
                       int line, Integer loopBound) {

        public static Node entry(int id) {
            return new Node(id, NodeKind.ENTRY, null, null, null, 0, null);
        }

        public static Node exit(int id) {
            return new Node(id, NodeKind.EXIT, null, null, null, 0, null);
        }
    }

    public record Edge(int from, int to, EdgeKind kind) {
    }

    private final List<Node> nodes;
    private final List<Edge> edges;
    private final Map<Integer, List<Edge>> outgoing;
    private final int entry;
    private final int exit;

    public Cfg(List<Node> nodes, List<Edge> edges,
               Map<Integer, List<Edge>> outgoing, int entry, int exit) {
        this.nodes = List.copyOf(nodes);
        this.edges = List.copyOf(edges);
        this.outgoing = Map.copyOf(outgoing);
        this.entry = entry;
        this.exit = exit;
    }

    public List<Node> nodes() {
        return nodes;
    }

    public List<Edge> edges() {
        return edges;
    }

    public List<Edge> outgoing(int nodeId) {
        return outgoing.get(nodeId);
    }

    public int entry() {
        return entry;
    }

    public int exit() {
        return exit;
    }
}
