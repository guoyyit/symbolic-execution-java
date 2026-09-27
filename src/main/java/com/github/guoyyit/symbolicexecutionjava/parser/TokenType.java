package com.github.guoyyit.symbolicexecutionjava.parser;

/** Lexical token categories. */
public enum TokenType {
    INT, FOR, IF, ELSE, ASSERT,
    IDENT, INT_LIT,
    LPAREN, RPAREN, LBRACE, RBRACE, SEMI, ASSIGN,
    PLUS, MINUS, STAR,
    LT, LE, GT, GE, EQ, NE,
    AND, OR, NOT,
    EOF
}
