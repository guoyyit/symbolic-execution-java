package com.github.guoyyit.symbolicexecutionjava.tests;

import com.github.guoyyit.symbolicexecutionjava.model.ast.Cond;
import com.github.guoyyit.symbolicexecutionjava.model.ast.Expr;
import com.github.guoyyit.symbolicexecutionjava.model.ast.Program;
import com.github.guoyyit.symbolicexecutionjava.model.ast.Stmt;
import com.github.guoyyit.symbolicexecutionjava.parser.ParseException;
import com.github.guoyyit.symbolicexecutionjava.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParserTest {

    @Test
    void parsesDeclarationsAssignmentsAndAssert() {
        Program p = Parser.parseSource("""
                int x = 3;
                int y;
                y = x + 2 * 4;
                assert(y >= 10);
                """);
        assertEquals(4, p.statements().size());
        assertInstanceOf(Stmt.Decl.class, p.statements().get(0));
        assertInstanceOf(Stmt.Decl.class, p.statements().get(1));
        assertInstanceOf(Stmt.Assign.class, p.statements().get(2));
        Stmt.Assert a = assertInstanceOf(Stmt.Assert.class, p.statements().get(3));
        Cond.Rel rel = assertInstanceOf(Cond.Rel.class, a.cond());
        assertEquals(Cond.RelOp.GE, rel.op());
    }

    @Test
    void parsesIfElseAndForWithPositions() {
        Program p = Parser.parseSource("""
                int i;
                for (i = 0; i < 5; i = i + 1) {
                    if (i == 2) {
                        assert(i < 3);
                    } else {
                        assert(i > 0);
                    }
                }
                """);
        Stmt.For f = assertInstanceOf(Stmt.For.class, p.statements().get(1));
        assertNotNull(f.init());
        assertNotNull(f.cond());
        assertNotNull(f.update());
        assertEquals(1, f.body().statements().size());
        Stmt.If ifStmt = assertInstanceOf(Stmt.If.class, f.body().statements().get(0));
        assertNotNull(ifStmt.elseBlock());
        assertEquals(2, f.pos().line());
        assertEquals(3, ifStmt.pos().line());
    }

    @Test
    void parsesBooleanCombinationsAndNegation() {
        Program p = Parser.parseSource(
                "int x; assert(!(x < 0) && (x > 1 || x == 0));");
        Stmt.Assert a = (Stmt.Assert) p.statements().get(1);
        assertInstanceOf(Cond.And.class, a.cond());
    }

    @Test
    void rejectsMissingSemicolonWithPosition() {
        ParseException ex = assertThrows(ParseException.class,
                () -> Parser.parseSource("int x = 1\nint y;"));
        assertEquals(2, ex.position().line());
    }

    @Test
    void rejectsForWithoutCondition() {
        ParseException ex = assertThrows(ParseException.class,
                () -> Parser.parseSource("for (int i = 0;; i = i + 1) { }"));
        assertEquals(1, ex.position().line());
        assertNotNull(ex.getMessage());
    }

    @Test
    void rejectsUnknownCharacterWithPosition() {
        ParseException ex = assertThrows(ParseException.class,
                () -> Parser.parseSource("int x = @;"));
        assertEquals(1, ex.position().line());
    }

    @Test
    void parsesUninitializedDeclWithNullInit() {
        Program p = Parser.parseSource("int z;");
        Stmt.Decl d = (Stmt.Decl) p.statements().get(0);
        assertNull(d.init());
        assertEquals("z", d.name());
    }

    @Test
    void unaryMinusBecomesSubtractionFromZero() {
        Program p = Parser.parseSource("int x = -5;");
        Stmt.Decl d = (Stmt.Decl) p.statements().get(0);
        Expr.Bin bin = assertInstanceOf(Expr.Bin.class, d.init());
        assertEquals(Expr.Op.SUB, bin.op());
        assertInstanceOf(Expr.IntLit.class, bin.left());
    }
}
