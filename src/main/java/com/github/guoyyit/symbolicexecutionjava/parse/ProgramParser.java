package com.github.guoyyit.symbolicexecutionjava.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.symbolicexecutionjava.error.ParseException;
import com.github.guoyyit.symbolicexecutionjava.lang.Expr;
import com.github.guoyyit.symbolicexecutionjava.lang.Program;
import com.github.guoyyit.symbolicexecutionjava.lang.Stmt;
import com.github.guoyyit.symbolicexecutionjava.lang.VarDecl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parses the JSON program representation into the in-memory {@link Program}
 * AST. Every error carries a JSON-path style location plus the client supplied
 * source line when present, e.g. {@code $.statements[2].condition (line 5)}.
 *
 * <h2>Grammar</h2>
 * <pre>
 * program   := { "variables": [ {"name": id, "input": bool?}* ],
 *                "statements": [ stmt* ] }
 * stmt      := {"kind":"assign","line":n?,"target":id,"expression":expr}
 *            | {"kind":"if","line":n?,"condition":expr,"then":[stmt*],"else":[stmt*]?}
 *            | {"kind":"loop","line":n?,"condition":expr,"maxIterations":n?,"body":[stmt*]}
 *            | {"kind":"assert","line":n?,"condition":expr}
 * expr      := {"kind":"const","value":int}
 *            | {"kind":"var","name":id}
 *            | {"kind":"binary","op":op,"left":expr,"right":expr}
 *            | {"kind":"unary","op":"neg"|"not","operand":expr}
 * op        := add|sub|mul|div|mod|eq|ne|lt|le|gt|ge|and|or
 * </pre>
 */
public final class ProgramParser {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final ObjectMapper mapper = new ObjectMapper();

    public Program parse(String json) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (IOException e) {
            throw new ParseException("malformed JSON: " + e.getMessage(), "$");
        }
        if (root == null || !root.isObject()) {
            throw new ParseException("program must be a JSON object", "$");
        }
        Map<String, VarDecl> variables = parseVariables(root.get("variables"));
        List<Stmt> statements = parseStatementList(required(root, "statements", "$"),
                "$.statements");
        return new Program(variables, statements);
    }

    private Map<String, VarDecl> parseVariables(JsonNode node) {
        Map<String, VarDecl> variables = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return variables;
        }
        if (!node.isArray()) {
            throw new ParseException("'variables' must be an array", "$.variables");
        }
        for (int i = 0; i < node.size(); i++) {
            JsonNode decl = node.get(i);
            String path = "$.variables[" + i + "]";
            if (!decl.isObject()) {
                throw new ParseException("variable declaration must be an object", path);
            }
            String name = requiredText(decl, "name", path);
            if (!IDENTIFIER.matcher(name).matches()) {
                throw new ParseException("invalid variable name: '" + name + "'", path + ".name");
            }
            boolean input = decl.has("input") && decl.get("input").asBoolean(false);
            if (variables.put(name, new VarDecl(name, input)) != null) {
                throw new ParseException("duplicate variable declaration: '" + name + "'",
                        path + ".name");
            }
        }
        return variables;
    }

    private List<Stmt> parseStatementList(JsonNode node, String path) {
        if (!node.isArray()) {
            throw new ParseException("expected an array of statements", path);
        }
        List<Stmt> statements = new ArrayList<>();
        for (int i = 0; i < node.size(); i++) {
            statements.add(parseStatement(node.get(i), path + "[" + i + "]"));
        }
        return statements;
    }

    private Stmt parseStatement(JsonNode node, String path) {
        if (!node.isObject()) {
            throw new ParseException("statement must be an object", path);
        }
        String kind = requiredText(node, "kind", path);
        int line = optionalLine(node, path);
        return switch (kind) {
            case "assign" -> new Stmt.Assign(
                    requiredText(node, "target", path),
                    parseExpr(required(node, "expression", path), path + ".expression"),
                    line);
            case "if" -> new Stmt.If(
                    parseExpr(required(node, "condition", path), path + ".condition"),
                    parseStatementList(required(node, "then", path), path + ".then"),
                    node.has("else")
                            ? parseStatementList(node.get("else"), path + ".else")
                            : List.of(),
                    line);
            case "loop" -> new Stmt.Loop(
                    parseExpr(required(node, "condition", path), path + ".condition"),
                    parseStatementList(required(node, "body", path), path + ".body"),
                    optionalIterations(node, path),
                    line);
            case "assert" -> new Stmt.Assert(
                    parseExpr(required(node, "condition", path), path + ".condition"),
                    line);
            default -> throw new ParseException("unknown statement kind: '" + kind + "'",
                    loc(path + ".kind", line));
        };
    }

    private Expr parseExpr(JsonNode node, String path) {
        if (node == null || !node.isObject()) {
            throw new ParseException("expression must be an object", path);
        }
        String kind = requiredText(node, "kind", path);
        return switch (kind) {
            case "const" -> {
                JsonNode value = required(node, "value", path);
                if (!value.isIntegralNumber()) {
                    throw new ParseException("'value' must be an integer", path + ".value");
                }
                yield new Expr.Const(value.longValue());
            }
            case "var" -> new Expr.Var(requiredText(node, "name", path));
            case "binary" -> new Expr.Binary(
                    parseBinaryOp(requiredText(node, "op", path), path + ".op"),
                    parseExpr(required(node, "left", path), path + ".left"),
                    parseExpr(required(node, "right", path), path + ".right"));
            case "unary" -> new Expr.Unary(
                    parseUnaryOp(requiredText(node, "op", path), path + ".op"),
                    parseExpr(required(node, "operand", path), path + ".operand"));
            default -> throw new ParseException("unknown expression kind: '" + kind + "'",
                    path + ".kind");
        };
    }

    private Expr.BinaryOp parseBinaryOp(String op, String path) {
        return switch (op) {
            case "add" -> Expr.BinaryOp.ADD;
            case "sub" -> Expr.BinaryOp.SUB;
            case "mul" -> Expr.BinaryOp.MUL;
            case "div" -> Expr.BinaryOp.DIV;
            case "mod" -> Expr.BinaryOp.MOD;
            case "eq" -> Expr.BinaryOp.EQ;
            case "ne" -> Expr.BinaryOp.NE;
            case "lt" -> Expr.BinaryOp.LT;
            case "le" -> Expr.BinaryOp.LE;
            case "gt" -> Expr.BinaryOp.GT;
            case "ge" -> Expr.BinaryOp.GE;
            case "and" -> Expr.BinaryOp.AND;
            case "or" -> Expr.BinaryOp.OR;
            default -> throw new ParseException("unknown binary operator: '" + op + "'", path);
        };
    }

    private Expr.UnaryOp parseUnaryOp(String op, String path) {
        return switch (op) {
            case "neg" -> Expr.UnaryOp.NEG;
            case "not" -> Expr.UnaryOp.NOT;
            default -> throw new ParseException("unknown unary operator: '" + op + "'", path);
        };
    }

    private JsonNode required(JsonNode node, String field, String path) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new ParseException("missing required field '" + field + "'",
                    path + "." + field);
        }
        return value;
    }

    private String requiredText(JsonNode node, String field, String path) {
        JsonNode value = required(node, field, path);
        if (!value.isTextual()) {
            throw new ParseException("field '" + field + "' must be a string",
                    path + "." + field);
        }
        return value.asText();
    }

    private int optionalLine(JsonNode node, String path) {
        JsonNode line = node.get("line");
        if (line == null || line.isNull()) {
            return 0;
        }
        if (!line.isIntegralNumber() || line.longValue() < 0) {
            throw new ParseException("'line' must be a non-negative integer", path + ".line");
        }
        return line.intValue();
    }

    private Integer optionalIterations(JsonNode node, String path) {
        JsonNode max = node.get("maxIterations");
        if (max == null || max.isNull()) {
            return null; // the static validator reports the missing loop bound
        }
        if (!max.isIntegralNumber() || max.longValue() < 0) {
            throw new ParseException("'maxIterations' must be a non-negative integer",
                    path + ".maxIterations");
        }
        return max.intValue();
    }

    private static String loc(String path, int line) {
        return line > 0 ? path + " (line " + line + ")" : path;
    }
}
