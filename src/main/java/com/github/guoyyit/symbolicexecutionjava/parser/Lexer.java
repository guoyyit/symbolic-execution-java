package com.github.guoyyit.symbolicexecutionjava.parser;

import com.github.guoyyit.symbolicexecutionjava.model.ast.Position;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Splits program source into a token list. */
public class Lexer {

    private static final Map<String, TokenType> KEYWORDS = Map.of(
            "int", TokenType.INT,
            "for", TokenType.FOR,
            "if", TokenType.IF,
            "else", TokenType.ELSE,
            "assert", TokenType.ASSERT);

    private final String source;
    private final List<Token> tokens = new ArrayList<>();
    private int offset;
    private int line = 1;
    private int column = 1;

    public Lexer(String source) {
        this.source = source == null ? "" : source;
    }

    public List<Token> tokenize() {
        tokens.clear();
        offset = 0;
        line = 1;
        column = 1;
        while (offset < source.length()) {
            char c = source.charAt(offset);
            if (Character.isWhitespace(c)) {
                advance();
            } else if (c == '/' && peek(1) == '/') {
                skipLineComment();
            } else if (c == '/' && peek(1) == '*') {
                skipBlockComment();
            } else if (Character.isLetter(c) || c == '_') {
                readIdentifier();
            } else if (Character.isDigit(c)) {
                readNumber();
            } else {
                readOperator();
            }
        }
        tokens.add(new Token(TokenType.EOF, "", new Position(line, column, offset)));
        return List.copyOf(tokens);
    }

    public String source() {
        return source;
    }

    private void skipLineComment() {
        advance();
        advance();
        while (offset < source.length() && source.charAt(offset) != '\n') {
            advance();
        }
    }

    private void skipBlockComment() {
        Position start = position();
        advance();
        advance();
        while (offset < source.length()
                && !(source.charAt(offset) == '*' && peek(1) == '/')) {
            advance();
        }
        if (offset >= source.length()) {
            throw new ParseException("unterminated block comment", start);
        }
        advance();
        advance();
    }

    private void readIdentifier() {
        Position start = position();
        int begin = offset;
        while (offset < source.length()
                && (Character.isLetterOrDigit(source.charAt(offset))
                || source.charAt(offset) == '_')) {
            advance();
        }
        String text = source.substring(begin, offset);
        TokenType type = KEYWORDS.getOrDefault(text, TokenType.IDENT);
        tokens.add(new Token(type, text, start));
    }

    private void readNumber() {
        Position start = position();
        int begin = offset;
        while (offset < source.length() && Character.isDigit(source.charAt(offset))) {
            advance();
        }
        String text = source.substring(begin, offset);
        long value;
        try {
            value = Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new ParseException("integer literal '" + text + "' is out of range", start);
        }
        tokens.add(new Token(TokenType.INT_LIT, text, start, value));
    }

    private void readOperator() {
        Position start = position();
        char c = source.charAt(offset);
        switch (c) {
            case '(' -> single(TokenType.LPAREN);
            case ')' -> single(TokenType.RPAREN);
            case '{' -> single(TokenType.LBRACE);
            case '}' -> single(TokenType.RBRACE);
            case ';' -> single(TokenType.SEMI);
            case '+' -> single(TokenType.PLUS);
            case '-' -> single(TokenType.MINUS);
            case '*' -> single(TokenType.STAR);
            case '<' -> doubleChar('=', TokenType.LE, TokenType.LT);
            case '>' -> doubleChar('=', TokenType.GE, TokenType.GT);
            case '=' -> doubleChar('=', TokenType.EQ, TokenType.ASSIGN);
            case '!' -> doubleChar('=', TokenType.NE, TokenType.NOT);
            case '&' -> {
                if (peek(1) == '&') {
                    advance();
                    advance();
                    tokens.add(new Token(TokenType.AND, "&&", start));
                } else {
                    throw new ParseException("unexpected character '&'; did you mean '&&'?", start);
                }
            }
            case '|' -> {
                if (peek(1) == '|') {
                    advance();
                    advance();
                    tokens.add(new Token(TokenType.OR, "||", start));
                } else {
                    throw new ParseException("unexpected character '|'; did you mean '||'?", start);
                }
            }
            default -> throw new ParseException("unexpected character '" + c + "'", start);
        }
    }

    private void single(TokenType type) {
        Position start = position();
        String text = String.valueOf(source.charAt(offset));
        advance();
        tokens.add(new Token(type, text, start));
    }

    private void doubleChar(char next, TokenType twoChar, TokenType oneChar) {
        Position start = position();
        char c = source.charAt(offset);
        advance();
        if (offset < source.length() && source.charAt(offset) == next) {
            advance();
            tokens.add(new Token(twoChar, "" + c + next, start));
        } else {
            tokens.add(new Token(oneChar, String.valueOf(c), start));
        }
    }

    private char peek(int lookahead) {
        int idx = offset + lookahead;
        return idx < source.length() ? source.charAt(idx) : '\0';
    }

    private void advance() {
        if (offset < source.length()) {
            if (source.charAt(offset) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
            offset++;
        }
    }

    private Position position() {
        return new Position(line, column, offset);
    }
}
