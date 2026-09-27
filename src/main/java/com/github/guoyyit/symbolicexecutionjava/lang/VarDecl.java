package com.github.guoyyit.symbolicexecutionjava.lang;

/**
 * Variable declaration. {@code input} marks variables whose initial value is
 * symbolic (program inputs); non-input variables start at 0.
 */
public record VarDecl(String name, boolean input) {
}
