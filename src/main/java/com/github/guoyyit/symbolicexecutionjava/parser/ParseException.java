package com.github.guoyyit.symbolicexecutionjava.parser;

import com.github.guoyyit.symbolicexecutionjava.model.ast.Position;

/** Raised when program source cannot be parsed; always carries a source position. */
public class ParseException extends RuntimeException {

    private final Position position;

    public ParseException(String message, Position position) {
        super(message);
        this.position = position;
    }

    public Position position() {
        return position;
    }
}
