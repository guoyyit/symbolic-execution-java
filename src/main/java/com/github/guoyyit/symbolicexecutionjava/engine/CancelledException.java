package com.github.guoyyit.symbolicexecutionjava.engine;

/** Thrown inside exploration when a task was cancelled or its deadline elapsed. */
public class CancelledException extends RuntimeException {

    private final boolean timeout;

    public CancelledException(boolean timeout) {
        super(timeout ? "task timed out" : "task cancelled");
        this.timeout = timeout;
    }

    public boolean timeout() {
        return timeout;
    }
}
