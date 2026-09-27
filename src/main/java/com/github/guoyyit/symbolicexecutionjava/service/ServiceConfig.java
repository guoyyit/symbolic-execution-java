package com.github.guoyyit.symbolicexecutionjava.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration properties of the analysis service ({@code symbolicexecution.*}). */
@ConfigurationProperties(prefix = "symbolicexecution")
public record ServiceConfig(
        long defaultSeed,
        long solverLowerBound,
        long solverUpperBound,
        long defaultTimeoutMillis,
        int defaultMaxPaths,
        int defaultMaxUnroll,
        int defaultMaxSteps,
        int defaultMaxBacktracks,
        int workerThreads,
        String checkpointDir) {

    public ServiceConfig() {
        this(0L, -1024L, 1024L, 5000L, 1000, 128, 1_000_000, 100_000,
                Math.max(2, Runtime.getRuntime().availableProcessors()), "checkpoints");
    }
}
