package com.github.guoyyit.symbolicexecutionjava.lang;

import java.util.List;
import java.util.Map;

/**
 * A fully parsed program: declared variables (insertion-ordered) plus the
 * top-level statement list.
 */
public record Program(Map<String, VarDecl> variables, List<Stmt> statements) {
}
