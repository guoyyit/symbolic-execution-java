package com.github.guoyyit.symbolicexecutionjava.validation;

import com.github.guoyyit.symbolicexecutionjava.model.ast.Position;

/** Raised when a parsed program fails static semantic validation; carries a source position. */
public class ValidationException extends RuntimeException {

    private final Position position;

    public ValidationException(String message, Position position) {
        super(message);
        this.position = position;
    }

    public Position position() {
        return position;
    }
}
