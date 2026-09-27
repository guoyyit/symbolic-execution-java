package com.github.guoyyit.symbolicexecutionjava.error;

/** Raised when the submitted JSON does not describe a well-formed program. */
public class ParseException extends AnalysisException {

    public ParseException(String message, String location) {
        super(message, location);
    }
}
