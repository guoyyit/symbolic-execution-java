package com.github.guoyyit.symbolicexecutionjava.engine;

/**
 * Raised when exploration reaches the configured maximum number of reported
 * paths. The committed paths are durable and a later run can resume to report
 * the remainder.
 */
public class PathLimitException extends RuntimeException {

    public PathLimitException(String message) {
        super(message);
    }
}
