package com.github.guoyyit.symbolicexecutionjava.error;

/** Base class for errors that can be localized inside the submitted program. */
public class AnalysisException extends RuntimeException {

    private final String location;

    public AnalysisException(String message, String location) {
        super(message);
        this.location = location;
    }

    /** Human-readable position, e.g. {@code "$.statements[2].condition (line 5)"}. */
    public String location() {
        return location;
    }
}
