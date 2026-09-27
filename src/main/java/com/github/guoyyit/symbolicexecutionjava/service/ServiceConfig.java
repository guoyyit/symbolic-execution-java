package com.github.guoyyit.symbolicexecutionjava.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the analysis service, bound from {@code symbolicexecution.*}
 * properties with sensible defaults.
 */
@ConfigurationProperties(prefix = "symbolicexecution")
public class ServiceConfig {

    private long defaultSeed = 0L;
    private long solverLowerBound = -1024L;
    private long solverUpperBound = 1024L;
    private long defaultTimeoutMillis = 5000L;
    private int defaultMaxPaths = 1000;
    private int defaultMaxUnroll = 128;
    private int defaultMaxSteps = 1_000_000;
    private int defaultMaxBacktracks = 100_000;
    private int workerThreads = Math.max(2, Runtime.getRuntime().availableProcessors());
    private String checkpointDir = "checkpoints";

    public long getDefaultSeed() {
        return defaultSeed;
    }

    public void setDefaultSeed(long defaultSeed) {
        this.defaultSeed = defaultSeed;
    }

    public long getSolverLowerBound() {
        return solverLowerBound;
    }

    public void setSolverLowerBound(long solverLowerBound) {
        this.solverLowerBound = solverLowerBound;
    }

    public long getSolverUpperBound() {
        return solverUpperBound;
    }

    public void setSolverUpperBound(long solverUpperBound) {
        this.solverUpperBound = solverUpperBound;
    }

    public long getDefaultTimeoutMillis() {
        return defaultTimeoutMillis;
    }

    public void setDefaultTimeoutMillis(long defaultTimeoutMillis) {
        this.defaultTimeoutMillis = defaultTimeoutMillis;
    }

    public int getDefaultMaxPaths() {
        return defaultMaxPaths;
    }

    public void setDefaultMaxPaths(int defaultMaxPaths) {
        this.defaultMaxPaths = defaultMaxPaths;
    }

    public int getDefaultMaxUnroll() {
        return defaultMaxUnroll;
    }

    public void setDefaultMaxUnroll(int defaultMaxUnroll) {
        this.defaultMaxUnroll = defaultMaxUnroll;
    }

    public int getDefaultMaxSteps() {
        return defaultMaxSteps;
    }

    public void setDefaultMaxSteps(int defaultMaxSteps) {
        this.defaultMaxSteps = defaultMaxSteps;
    }

    public int getDefaultMaxBacktracks() {
        return defaultMaxBacktracks;
    }

    public void setDefaultMaxBacktracks(int defaultMaxBacktracks) {
        this.defaultMaxBacktracks = defaultMaxBacktracks;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    public String getCheckpointDir() {
        return checkpointDir;
    }

    public void setCheckpointDir(String checkpointDir) {
        this.checkpointDir = checkpointDir;
    }
}
