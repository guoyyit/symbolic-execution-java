package com.github.guoyyit.symbolicexecutionjava.error;

/** Raised when the service has too many active tasks and rejects admission. */
public class OverloadedException extends RuntimeException {

    public OverloadedException(String message) {
        super(message);
    }
}
