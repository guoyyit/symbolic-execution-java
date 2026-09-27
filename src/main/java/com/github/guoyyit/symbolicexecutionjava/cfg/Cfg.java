package com.github.guoyyit.symbolicexecutionjava.cfg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Complete, branch-explicit control-flow graph of a validated program. */
public final class Cfg {

    private final CfgNode entry;
    private final CfgNode exit;
    private final List<CfgNode> nodes;
    private final List<Edge> edges;
    private final Map<Integer, List<Edge>> outgoing;

    Cfg(CfgNode entry, CfgNode exit, List<CfgNode> nodes, List<Edge> edges,
        Map<Integer, List<Edge>> outgoing) {
        this.entry = entry;
        this.exit = exit;
        this.nodes = List.copyOf(nodes);
        this.edges = List.copyOf(edges);
        this.outgoing = Collections.unmodifiableMap(new LinkedHashMap<>(outgoing));
    }

    public CfgNode entry() {
        return entry;
    }

    public CfgNode exit() {
        return exit;
    }

    public List<CfgNode> nodes() {
        return nodes;
    }

    public List<Edge> edges() {
        return edges;
    }

    public List<Edge> outgoing(CfgNode node) {
        return outgoing.getOrDefault(node.id(), List.of());
    }
}
