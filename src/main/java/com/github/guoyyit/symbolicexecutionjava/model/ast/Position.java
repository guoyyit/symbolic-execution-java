package com.github.guoyyit.symbolicexecutionjava.model.ast;

/** Source position of a syntactic element. */
public record Position(int line, int column, int offset) {

    public static final Position UNKNOWN = new Position(0, 0, 0);

    @Override
    public String toString() {
        return line + ":" + column;
    }
}
