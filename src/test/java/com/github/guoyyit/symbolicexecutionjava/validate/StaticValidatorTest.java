package com.github.guoyyit.symbolicexecutionjava.validate;

import com.github.guoyyit.symbolicexecutionjava.error.ValidationException;
import com.github.guoyyit.symbolicexecutionjava.lang.Program;
import com.github.guoyyit.symbolicexecutionjava.parse.ProgramParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StaticValidatorTest {

    private final ProgramParser parser = new ProgramParser();
    private final StaticValidator validator = new StaticValidator();

    private void validate(String json) {
        Program program = parser.parse(json);
        validator.validate(program);
    }

    @Test
    void acceptsWellFormedProgram() {
        assertThatCode(() -> validate("""
                {"variables":[{"name":"x","input":true},{"name":"i"}],"statements":[
                  {"kind":"assign","line":1,"target":"i","expression":{"kind":"const","value":0}},
                  {"kind":"loop","line":2,"condition":{"kind":"binary","op":"lt",
                    "left":{"kind":"var","name":"i"},"right":{"kind":"const","value":3}},
                    "maxIterations":3,"body":[
                      {"kind":"assign","target":"i","expression":{"kind":"binary","op":"add",
                        "left":{"kind":"var","name":"i"},"right":{"kind":"const","value":1}}}
                    ]},
                  {"kind":"assert","line":3,"condition":{"kind":"binary","op":"ge",
                    "left":{"kind":"var","name":"x"},"right":{"kind":"const","value":0}}}
                ]}
                """)).doesNotThrowAnyException();
    }

    @Test
    void rejectsUndefinedVariableInExpressionWithLocation() {
        assertThatThrownBy(() -> validate("""
                {"variables":[],"statements":[
                  {"kind":"assign","line":4,"target":"x",
                   "expression":{"kind":"var","name":"x"}}]}
                """))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("assignment to undefined variable")
                .satisfies(ex -> assertThat(((ValidationException) ex).location())
                        .isEqualTo("$.statements[0].target (line 4)"));
    }

    @Test
    void rejectsUndefinedVariableNestedInExpression() {
        assertThatThrownBy(() -> validate("""
                {"variables":[{"name":"x","input":true}],"statements":[
                  {"kind":"assign","line":2,"target":"x","expression":
                    {"kind":"binary","op":"add",
                     "left":{"kind":"var","name":"x"},
                     "right":{"kind":"var","name":"missing"}}}]}
                """))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("undefined variable: 'missing'")
                .satisfies(ex -> assertThat(((ValidationException) ex).location())
                        .isEqualTo("$.statements[0].expression.right (line 2)"));
    }

    @Test
    void rejectsTypeMismatchArithmeticOnBooleans() {
        assertThatThrownBy(() -> validate("""
                {"variables":[{"name":"x","input":true}],"statements":[
                  {"kind":"assign","line":1,"target":"x","expression":
                    {"kind":"binary","op":"add",
                     "left":{"kind":"binary","op":"eq","left":{"kind":"var","name":"x"},
                       "right":{"kind":"const","value":1}},
                     "right":{"kind":"const","value":1}}}]}
                """))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("'add' requires integer operands");
    }

    @Test
    void rejectsIntegerUsedAsIfCondition() {
        assertThatThrownBy(() -> validate("""
                {"variables":[{"name":"x","input":true}],"statements":[
                  {"kind":"if","line":7,"condition":{"kind":"var","name":"x"},"then":[]}]}
                """))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("condition of 'if' must be a boolean")
                .satisfies(ex -> assertThat(((ValidationException) ex).location())
                        .isEqualTo("$.statements[0].condition (line 7)"));
    }

    @Test
    void rejectsBooleanExpressionAssignedToInteger() {
        assertThatThrownBy(() -> validate("""
                {"variables":[{"name":"x","input":true},{"name":"b"}],"statements":[
                  {"kind":"assign","line":1,"target":"b","expression":
                    {"kind":"binary","op":"gt","left":{"kind":"var","name":"x"},
                     "right":{"kind":"const","value":0}}}]}
                """))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cannot assign boolean expression");
    }

    @Test
    void rejectsMissingLoopBoundWithLine() {
        assertThatThrownBy(() -> validate("""
                {"variables":[{"name":"i"}],"statements":[
                  {"kind":"loop","line":9,"condition":{"kind":"binary","op":"lt",
                    "left":{"kind":"var","name":"i"},"right":{"kind":"const","value":3}},
                    "body":[]}]}
                """))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("loop bound missing")
                .satisfies(ex -> assertThat(((ValidationException) ex).location())
                        .isEqualTo("$.statements[0] (line 9)"));
    }

    @Test
    void rejectsNonLinearMultiplication() {
        assertThatThrownBy(() -> validate("""
                {"variables":[{"name":"x","input":true},{"name":"y","input":true},{"name":"z"}],
                 "statements":[
                   {"kind":"assign","line":1,"target":"z","expression":
                    {"kind":"binary","op":"mul",
                     "left":{"kind":"var","name":"x"},"right":{"kind":"var","name":"y"}}}]}
                """))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("non-linear arithmetic");
    }

    @Test
    void rejectsDivAndModOperators() {
        assertThatThrownBy(() -> validate("""
                {"variables":[{"name":"x","input":true},{"name":"y"}],"statements":[
                  {"kind":"assign","line":1,"target":"y","expression":
                    {"kind":"binary","op":"div",
                     "left":{"kind":"var","name":"x"},"right":{"kind":"const","value":2}}}]}
                """))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("not supported");
    }
}
