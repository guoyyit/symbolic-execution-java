package com.github.guoyyit.symbolicexecutionjava.tests;

import com.github.guoyyit.symbolicexecutionjava.parser.Parser;
import com.github.guoyyit.symbolicexecutionjava.validation.ResolvedProgram;
import com.github.guoyyit.symbolicexecutionjava.validation.ValidationException;
import com.github.guoyyit.symbolicexecutionjava.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidatorTest {

    private ResolvedProgram validate(String src) {
        return new Validator().validate(Parser.parseSource(src));
    }

    @Test
    void acceptsValidProgramAndCollectsVariablesInOrder() {
        ResolvedProgram r = validate("""
                int x;
                int y = x + 2;
                for (int i = 0; i < 3; i = i + 1) {
                    assert(x + y * 2 >= i);
                }
                """);
        assertEquals(java.util.List.of("x", "y", "i"), r.variables());
        assertEquals(1, r.loops().size());
        assertEquals("i", r.loops().get(0).counter());
        assertTrue(r.loops().get(0).ascending());
    }

    @Test
    void rejectsUndefinedVariableWithPosition() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validate("int x = y + 1;"));
        assertTrue(ex.getMessage().contains("undefined variable 'y'"));
        assertEquals(1, ex.position().line());
        assertEquals(9, ex.position().column());
    }

    @Test
    void rejectsUndefinedInAssertion() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validate("assert(z == 0);"));
        assertTrue(ex.getMessage().contains("undefined variable 'z'"));
    }

    @Test
    void rejectsDuplicateDeclaration() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validate("int x; int x;"));
        assertTrue(ex.getMessage().contains("already declared"));
    }

    @Test
    void rejectsVariableLeavingBlockScope() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validate("""
                        { int t = 1; }
                        int y = t;
                        """));
        assertTrue(ex.getMessage().contains("undefined variable 't'"));
        assertEquals(2, ex.position().line());
    }

    @Test
    void rejectsNonLinearMultiplication() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validate("int x; int y; int z = x * y;"));
        assertTrue(ex.getMessage().contains("non-linear"));
    }

    @Test
    void acceptsConstantFactorMultiplication() {
        ResolvedProgram r = validate("int x; int y = 3 * x + 2;");
        assertEquals(java.util.List.of("x", "y"), r.variables());
    }

    @Test
    void rejectsForWithoutSingleBoundComparison() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validate(
                        "int i; int j; for (i = 0; i < 3 && j > 0; i = i + 1) { }"));
        assertTrue(ex.getMessage().contains("single comparison"), ex.getMessage());
    }

    @Test
    void rejectsForBoundNotReferencingCounter() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validate("int i; int j; for (i = 0; j < 3; i = i + 1) { }"));
        assertTrue(ex.getMessage().contains("counter 'i'"), ex.getMessage());
    }
    @Test
    void rejectsForWithWrongCounterUpdate() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validate("""
                        int i; int j;
                        for (i = 0; i < 3; j = j + 1) { }
                        """));
        assertTrue(ex.getMessage().contains("must update its counter 'i'"));
    }

    @Test
    void rejectsNonPositiveStep() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validate("int i; for (i = 0; i < 3; i = i - 1) { }"));
        assertTrue(ex.getMessage().contains("step must be positive"));
    }

    @Test
    void rejectsDescendingLoopWithPositiveStep() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validate("int i; for (i = 5; i > 0; i = i + 1) { }"));
        assertTrue(ex.getMessage().contains("step must be negative"));
    }

    @Test
    void rejectsCounterReassignmentInBody() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validate("""
                        int i;
                        for (i = 0; i < 3; i = i + 1) { i = 2; }
                        """));
        assertTrue(ex.getMessage().contains("must not be reassigned"));
    }

    @Test
    void rejectsImpossibleConstantBounds() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validate("int i; for (i = 5; i < 3; i = i + 1) { }"));
        assertTrue(ex.getMessage().contains("never execute"));
        assertFalse(ex.getMessage().isBlank());
    }

    @Test
    void supportsDescendingLoop() {
        ResolvedProgram r = validate(
                "int i; for (i = 3; i > 0; i = i - 1) { assert(i >= 1); }");
        assertEquals(1, r.loops().size());
        assertFalse(r.loops().get(0).ascending());
    }
}
