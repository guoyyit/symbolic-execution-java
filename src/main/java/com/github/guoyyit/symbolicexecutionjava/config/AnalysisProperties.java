package com.github.guoyyit.symbolicexecutionjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Tunables for the analysis service. */
@Component
@ConfigurationProperties(prefix = "analysis")
public class AnalysisProperties {

    /** Worker threads executing task quanta. */
    private int workers = 2;

    /** Maximum number of queued+running tasks; submissions beyond this are rejected. */
    private int maxActiveTasks = 64;

    /** Frontier states processed per scheduling quantum. */
    private long quantumStates = 256;

    /** Directory for task records and checkpoints. */
    private String dataDir = "data";

    /** Default per-task wall-clock timeout in milliseconds. */
    private long defaultTimeoutMs = 30_000;

    /** Default per-task cap on explored states. */
    private long defaultMaxStates = 1_000_000;

    /** Default per-task cap on reported paths. */
    private int defaultMaxPaths = 10_000;

    public int getWorkers() {
        return workers;
    }

    public void setWorkers(int workers) {
        this.workers = workers;
    }

    public int getMaxActiveTasks() {
        return maxActiveTasks;
    }

    public void setMaxActiveTasks(int maxActiveTasks) {
        this.maxActiveTasks = maxActiveTasks;
    }

    public long getQuantumStates() {
        return quantumStates;
    }

    public void setQuantumStates(long quantumStates) {
        this.quantumStates = quantumStates;
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public long getDefaultTimeoutMs() {
        return defaultTimeoutMs;
    }

    public void setDefaultTimeoutMs(long defaultTimeoutMs) {
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    public long getDefaultMaxStates() {
        return defaultMaxStates;
    }

    public void setDefaultMaxStates(long defaultMaxStates) {
        this.defaultMaxStates = defaultMaxStates;
    }

    public int getDefaultMaxPaths() {
        return defaultMaxPaths;
    }

    public void setDefaultMaxPaths(int defaultMaxPaths) {
        this.defaultMaxPaths = defaultMaxPaths;
    }
}
