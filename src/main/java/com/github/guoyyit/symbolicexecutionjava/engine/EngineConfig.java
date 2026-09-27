package com.github.guoyyit.symbolicexecutionjava.engine;

/** Static engine configuration: solver domain and random seed. */
public record EngineConfig(long seed, long solverLowerBound, long solverUpperBound) {
}
