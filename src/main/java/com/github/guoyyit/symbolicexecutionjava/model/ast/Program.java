package com.github.guoyyit.symbolicexecutionjava.model.ast;

import java.util.List;

/** Parsed program: a sequence of top-level statements. */
public record Program(List<Stmt> statements, String source) {
}
