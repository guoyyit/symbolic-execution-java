package com.github.guoyyit.symbolicexecutionjava.task;

/** Immutable client request: raw program JSON, random seed, and per-task limits. */
public final class TaskSpec {

    private String programJson;
    private long seed;
    private Long timeoutMs;
    private Long maxStates;
    private Integer maxPaths;

    public TaskSpec() {
    }

    public TaskSpec(String programJson, long seed, Long timeoutMs, Long maxStates, Integer maxPaths) {
        this.programJson = programJson;
        this.seed = seed;
        this.timeoutMs = timeoutMs;
        this.maxStates = maxStates;
        this.maxPaths = maxPaths;
    }

    public String getProgramJson() {
        return programJson;
    }

    public void setProgramJson(String programJson) {
        this.programJson = programJson;
    }

    public long getSeed() {
        return seed;
    }

    public void setSeed(long seed) {
        this.seed = seed;
    }

    public Long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public Long getMaxStates() {
        return maxStates;
    }

    public void setMaxStates(Long maxStates) {
        this.maxStates = maxStates;
    }

    public Integer getMaxPaths() {
        return maxPaths;
    }

    public void setMaxPaths(Integer maxPaths) {
        this.maxPaths = maxPaths;
    }
}
