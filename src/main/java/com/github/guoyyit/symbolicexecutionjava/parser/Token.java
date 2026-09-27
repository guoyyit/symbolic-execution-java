package com.github.guoyyit.symbolicexecutionjava.parser;

import com.github.guoyyit.symbolicexecutionjava.model.ast.Position;

/** A single lexical token. */
public record Token(TokenType type, String text, Position pos, long literalValue) {

    public Token(TokenType type, String text, Position pos) {
        this(type, text, pos, 0L);
    }

    public boolean is(TokenType type) {
        return this.type == type;
    }
}
