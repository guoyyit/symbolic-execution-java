package com.github.guoyyit.symbolicexecutionjava.engine;

/** Receives paths in deterministic discovery order; used for incremental checkpoints. */
public interface PathListener {

    void onPath(PathReport report);
}
