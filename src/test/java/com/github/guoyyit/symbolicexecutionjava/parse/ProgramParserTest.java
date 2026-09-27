package com.github.guoyyit.symbolicexecutionjava.parse;

import com.github.guoyyit.symbolicexecutionjava.error.ParseException;
import com.github.guoyyit.symbolicexecutionjava.lang.Expr;
import com.github.guoyyit.symbolicexecutionjava.lang.Program;
import com.github.guoyyit.symbolicexecutionjava.lang.Stmt;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProgramParserTest {

    private final ProgramParser parser = new ProgramParser();

    @Test
    void parsesMinimalProgram() {
        Program program = parser.parse("""
                {"variables":[{"name":"x","input":true},{"name":"y"}],
                 "statements":[
                   {"kind":"assign","line":1,"target":"y",
                    "expression":{"kind":"binary","op":"add",
                      "left":{"kind":"var","name":"x"},"right":{"kind":"const","value":2}}}
                 ]}
                """);
        assertThat(program.variables()).containsOnlyKeys("x", "y");
        assertThat(program.variables().get("x").input()).isTrue();
        assertThat(program.variables().get("y").input()).isFalse();
        Stmt.Assign assign = (Stmt.Assign) program.statements().get(0);
        assertThat(assign.target()).isEqualTo("y");
        assertThat(assign.line()).isEqualTo(1);
        Expr.Binary binary = (Expr.Binary) assign.expression();
        assertThat(binary.op()).isEqualTo(Expr.BinaryOp.ADD);
    }

    @Test
    void parsesAllStatements() {
        Program program = parser.parse("""
                {"variables":[{"name":"i"},{"name":"x","input":true}],"statements":[
                  {"kind":"if","line":1,"condition":{"kind":"binary","op":"lt",
                    "left":{"kind":"var","name":"x"},"right":{"kind":"const","value":0}},
                    "then":[{"kind":"assign","target":"i","expression":{"kind":"const","value":1}}],
                    "else":[]},
                  {"kind":"loop","line":2,"condition":{"kind":"binary","op":"lt",
                    "left":{"kind":"var","name":"i"},"right":{"kind":"const","value":3}},
                    "maxIterations":3,"body":[]},
                  {"kind":"assert","line":3,"condition":{"kind":"binary","op":"ge",
                    "left":{"kind":"var","name":"x"},"right":{"kind":"const","value":0}}}
                ]}
                """);
        List<Stmt> statements = program.statements();
        assertThat(statements).hasSize(3);
        assertThat(statements.get(0)).isInstanceOf(Stmt.If.class);
        Stmt.Loop loop = (Stmt.Loop) statements.get(1);
        assertThat(loop.maxIterations()).isEqualTo(3);
        assertThat(statements.get(2)).isInstanceOf(Stmt.Assert.class);
    }

    @Test
    void reportsLocationOfUnknownStatementKind() {
        assertThatThrownBy(() -> parser.parse("""
                {"variables":[],"statements":[{"kind":"goto"}]}
                """))
                .isInstanceOf(ParseException.class)
                .satisfies(ex -> {
                    ParseException pe = (ParseException) ex;
                    assertThat(pe.location()).contains("$.statements[0]");
                    assertThat(pe.getMessage()).contains("goto");
                });
    }

    @Test
    void reportsMissingLoopFieldAsJsonPath() {
        // Missing maxIterations is accepted by the parser (validator rejects
        // it), but a missing body must be pinpointed:
        assertThatThrownBy(() -> parser.parse("""
                {"variables":[],"statements":[
                  {"kind":"loop","condition":{"kind":"binary","op":"lt",
                    "left":{"kind":"const","value":1},"right":{"kind":"const","value":2}}}]
                }
                """))
                .isInstanceOf(ParseException.class)
                .hasMessageContaining("body")
                .satisfies(ex -> assertThat(((ParseException) ex).location())
                        .isEqualTo("$.statements[0].body"));
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> parser.parse("{not json"))
                .isInstanceOf(ParseException.class)
                .satisfies(ex -> assertThat(((ParseException) ex).location()).isEqualTo("$"));
    }

    @Test
    void rejectsDuplicateVariableWithLocation() {
        assertThatThrownBy(() -> parser.parse("""
                {"variables":[{"name":"x"},{"name":"x"}],"statements":[]}
                """))
                .isInstanceOf(ParseException.class)
                .hasMessageContaining("duplicate variable")
                .satisfies(ex -> assertThat(((ParseException) ex).location())
                        .isEqualTo("$.variables[1].name"));
    }

    @Test
    void rejectsUnknownOperatorWithFieldLocation() {
        assertThatThrownBy(() -> parser.parse("""
                {"variables":[{"name":"x","input":true}],"statements":[
                  {"kind":"assign","target":"x","expression":
                    {"kind":"binary","op":"xor",
                     "left":{"kind":"const","value":1},"right":{"kind":"const","value":2}}}]}
                """))
                .isInstanceOf(ParseException.class)
                .satisfies(ex -> assertThat(((ParseException) ex).location())
                        .contains("$.statements[0].expression.op"));
    }
}
