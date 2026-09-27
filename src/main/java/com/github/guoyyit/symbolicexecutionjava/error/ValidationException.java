package com.github.guoyyit.symbolicexecutionjava.error;

/** Raised when a program is structurally parseable but semantically invalid. */
public class ValidationException extends AnalysisException {

    public ValidationException(String message, String location) {
        super(message, location);
    }
}
