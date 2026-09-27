package com.github.guoyyit.symbolicexecutionjava.parser;

import com.github.guoyyit.symbolicexecutionjava.model.ast.Cond;
import com.github.guoyyit.symbolicexecutionjava.model.ast.Expr;
import com.github.guoyyit.symbolicexecutionjava.model.ast.Position;
import com.github.guoyyit.symbolicexecutionjava.model.ast.Program;
import com.github.guoyyit.symbolicexecutionjava.model.ast.Stmt;

import java.util.ArrayList;
import java.util.List;

/** Recursive descent parser for the supported language subset. */
public class Parser {

    private final List<Token> tokens;
    private final String source;
    private int index;

    public Parser(String source) {
        this.source = source;
        this.tokens = new Lexer(source).tokenize();
        this.index = 0;
    }

    /** Convenience entry point: lex and parse in one call. */
    public static Program parseSource(String source) {
        return new Parser(source).parse();
    }

    public Program parse() {
        List<Stmt> statements = new ArrayList<>();
        while (!at(TokenType.EOF)) {
            statements.add(parseStatement());
        }
        return new Program(List.copyOf(statements), source);
    }

    // ----- statements -----

    private Stmt parseStatement() {
        return switch (peek().type()) {
            case INT -> parseDecl();
            case LBRACE -> parseBlock();
            case IF -> parseIf();
            case FOR -> parseFor();
            case ASSERT -> parseAssert();
            default -> parseAssignOrExprStatement();
        };
    }

    private Stmt parseDecl() {
        Position start = expect(TokenType.INT, "expected 'int'").pos();
        Token name = expect(TokenType.IDENT, "expected variable name after 'int'");
        Expr init = null;
        if (accept(TokenType.ASSIGN) != null) {
            init = parseExpr();
        }
        expect(TokenType.SEMI, "expected ';' after declaration of '" + name.text() + "'");
        return new Stmt.Decl(name.text(), init, name.pos(), start);
    }

    private Stmt parseAssignOrExprStatement() {
        Position start = peek().pos();
        Token name = expect(TokenType.IDENT, "expected statement (declaration, assignment, "
                + "if/for/assert or block)");
        expect(TokenType.ASSIGN, "expected '=' in assignment to '" + name.text() + "'");
        Expr value = parseExpr();
        expect(TokenType.SEMI, "expected ';' after assignment to '" + name.text() + "'");
        return new Stmt.Assign(name.text(), value, name.pos(), start);
    }

    private Stmt.Block parseBlock() {
        Position start = peek().pos();
        expect(TokenType.LBRACE, "expected '{' to open block");
        List<Stmt> body = new ArrayList<>();
        while (!at(TokenType.RBRACE) && !at(TokenType.EOF)) {
            body.add(parseStatement());
        }
        expect(TokenType.RBRACE, "expected '}' to close block");
        return new Stmt.Block(List.copyOf(body), start);
    }

    private Stmt parseIf() {
        Position start = expect(TokenType.IF, "expected 'if'").pos();
        expect(TokenType.LPAREN, "expected '(' after 'if'");
        Cond cond = parseCond();
        expect(TokenType.RPAREN, "expected ')' after if-condition");
        Stmt.Block thenBlock = requireBlock(parseBranchedStatement(), "'if' body must be a block '{ ... }'");
        Stmt.Block elseBlock = null;
        if (accept(TokenType.ELSE) != null) {
            elseBlock = requireBlock(parseBranchedStatement(), "'else' body must be a block '{ ... }'");
        }
        return new Stmt.If(cond, thenBlock, elseBlock, start);
    }

    /** Parse the body of if/else/for, requiring a brace-delimited block. */
    private Stmt parseBranchedStatement() {
        if (at(TokenType.IF)) {
            return parseIf();
        }
        return parseBlock();
    }

    private Stmt.Block requireBlock(Stmt stmt, String message) {
        if (stmt instanceof Stmt.Block block) {
            return block;
        }
        throw new ParseException(message, stmt.pos());
    }

    private Stmt parseFor() {
        Position start = expect(TokenType.FOR, "expected 'for'").pos();
        expect(TokenType.LPAREN, "expected '(' after 'for'");
        Stmt init;
        if (accept(TokenType.SEMI) != null) {
            init = null;
        } else if (at(TokenType.INT)) {
            init = parseDeclNoSemi();
            expect(TokenType.SEMI, "expected ';' after for-initialiser");
        } else {
            init = parseAssignNoSemi();
            expect(TokenType.SEMI, "expected ';' after for-initialiser");
        }
        Cond cond;
        if (accept(TokenType.SEMI) != null) {
            throw new ParseException("for-loop is missing a loop-bound condition", start);
        } else {
            cond = parseCond();
            expect(TokenType.SEMI, "expected ';' after for-loop condition");
        }
        Stmt update;
        if (accept(TokenType.RPAREN) != null) {
            update = null;
        } else {
            Token name = expect(TokenType.IDENT, "expected loop-variable update");
            expect(TokenType.ASSIGN, "expected '=' in for-loop update");
            Expr value = parseExpr();
            Position ustart = name.pos();
            update = new Stmt.Assign(name.text(), value, name.pos(), ustart);
            expect(TokenType.RPAREN, "expected ')' at end of for-clause");
        }
        Stmt.Block body = requireBlock(parseBlock(), "for-loop body must be a block '{ ... }'");
        return new Stmt.For(init, cond, update, body, start);
    }

    private Stmt parseDeclNoSemi() {
        Position start = expect(TokenType.INT, "expected 'int'").pos();
        Token name = expect(TokenType.IDENT, "expected variable name after 'int'");
        Expr init = null;
        if (accept(TokenType.ASSIGN) != null) {
            init = parseExpr();
        }
        return new Stmt.Decl(name.text(), init, name.pos(), start);
    }

    private Stmt parseAssignNoSemi() {
        Position start = peek().pos();
        Token name = expect(TokenType.IDENT, "expected loop-variable initialiser");
        expect(TokenType.ASSIGN, "expected '=' in for-initialiser");
        Expr value = parseExpr();
        return new Stmt.Assign(name.text(), value, name.pos(), start);
    }

    private Stmt parseAssert() {
        Position start = expect(TokenType.ASSERT, "expected 'assert'").pos();
        expect(TokenType.LPAREN, "expected '(' after 'assert'");
        Cond cond = parseCond();
        expect(TokenType.RPAREN, "expected ')' after assert condition");
        expect(TokenType.SEMI, "expected ';' after assertion");
        return new Stmt.Assert(cond, start);
    }

    // ----- conditions -----

    private Cond parseCond() {
        return parseOr();
    }

    private Cond parseOr() {
        Cond left = parseAnd();
        while (at(TokenType.OR)) {
            Token op = advance();
            Cond right = parseAnd();
            left = new Cond.Or(left, right, op.pos());
        }
        return left;
    }

    private Cond parseAnd() {
        Cond left = parseAtomCond();
        while (at(TokenType.AND)) {
            Token op = advance();
            Cond right = parseAtomCond();
            left = new Cond.And(left, right, op.pos());
        }
        return left;
    }

    private Cond parseAtomCond() {
        if (at(TokenType.NOT)) {
            Token not = advance();
            return new Cond.Not(parseAtomCond(), not.pos());
        }
        if (accept(TokenType.LPAREN) != null) {
            Cond inner = parseCond();
            expect(TokenType.RPAREN, "expected ')' to close condition");
            return inner;
        }
        return parseRelation();
    }

    // ----- relations / expressions -----

    private Cond parseRelation() {
        Expr left = parseExpr();
        Token op = switch (peek().type()) {
            case LT, LE, GT, GE, EQ, NE -> advance();
            default -> throw new ParseException(
                    "expected a comparison operator (< <= > >= == !=)", peek().pos());
        };
        Expr right = parseExpr();
        Cond.RelOp relOp = switch (op.type()) {
            case LT -> Cond.RelOp.LT;
            case LE -> Cond.RelOp.LE;
            case GT -> Cond.RelOp.GT;
            case GE -> Cond.RelOp.GE;
            case EQ -> Cond.RelOp.EQ;
            case NE -> Cond.RelOp.NE;
            default -> throw new ParseException("invalid comparison operator", op.pos());
        };
        return new Cond.Rel(relOp, left, right, op.pos());
    }

    private Expr parseExpr() {
        return parseTerm();
    }

    private Expr parseTerm() {
        Expr left = parseFactor();
        while (at(TokenType.PLUS) || at(TokenType.MINUS)) {
            Token op = advance();
            Expr right = parseFactor();
            Expr.Op binOp = op.type() == TokenType.PLUS ? Expr.Op.ADD : Expr.Op.SUB;
            left = new Expr.Bin(binOp, left, right, op.pos());
        }
        return left;
    }

    private Expr parseFactor() {
        Expr left = parseUnary();
        while (at(TokenType.STAR)) {
            Token op = advance();
            Expr right = parseUnary();
            left = new Expr.Bin(Expr.Op.MUL, left, right, op.pos());
        }
        return left;
    }

    private Expr parseUnary() {
        if (accept(TokenType.MINUS) != null) {
            Token minus = tokens.get(index - 1);
            Expr inner = parsePrimary();
            return new Expr.Bin(Expr.Op.SUB, new Expr.IntLit(0, minus.pos()), inner, minus.pos());
        }
        if (accept(TokenType.PLUS) != null) {
            return parsePrimary();
        }
        return parsePrimary();
    }

    private Expr parsePrimary() {
        Token t = peek();
        return switch (t.type()) {
            case INT_LIT -> {
                advance();
                yield new Expr.IntLit(t.literalValue(), t.pos());
            }
            case IDENT -> {
                advance();
                yield new Expr.Var(t.text(), t.pos());
            }
            case LPAREN -> {
                advance();
                Expr inner = parseExpr();
                expect(TokenType.RPAREN, "expected ')' to close expression");
                yield inner;
            }
            default -> throw new ParseException(
                    "expected integer literal or variable name", t.pos());
        };
    }

    // ----- token helpers -----

    private Token peek() {
        return tokens.get(index);
    }

    private boolean at(TokenType type) {
        return peek().type() == type;
    }

    private Token advance() {
        Token t = tokens.get(index);
        if (index < tokens.size() - 1) {
            index++;
        }
        return t;
    }

    private Token accept(TokenType type) {
        if (at(type)) {
            return advance();
        }
        return null;
    }

    private Token expect(TokenType type, String message) {
        if (!at(type)) {
            throw new ParseException(message + " but found '" + describe(peek()) + "'", peek().pos());
        }
        return advance();
    }

    private String describe(Token token) {
        return token.type() == TokenType.EOF ? "end of input" : token.text();
    }
}
