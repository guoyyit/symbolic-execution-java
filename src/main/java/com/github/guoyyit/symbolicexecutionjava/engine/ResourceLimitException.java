package com.github.guoyyit.symbolicexecutionjava.engine;

/** Raised when an exploration exceeds a configured resource limit (steps/paths). */
public class ResourceLimitException extends RuntimeException {

    public ResourceLimitException(String message) {
        super(message);
    }
}
