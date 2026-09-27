package com.github.guoyyit.symbolicexecutionjava.validation;

import com.github.guoyyit.symbolicexecutionjava.model.ast.Cond;
import com.github.guoyyit.symbolicexecutionjava.model.ast.Program;
import com.github.guoyyit.symbolicexecutionjava.model.ast.Stmt;

import java.util.List;

/** A program that passed static validation, with ordered symbols and loop metadata. */
public record ResolvedProgram(Program program, List<String> variables, List<LoopInfo> loops) {

    /** Canonical, validated description of one bounded for-loop. */
    public record LoopInfo(Stmt.For statement,
                           String counter,
                           Stmt initializer,
                           Cond.Rel condition,
                           Stmt.Assign update,
                           Stmt.Block body,
                           boolean ascending) {
    }
}
