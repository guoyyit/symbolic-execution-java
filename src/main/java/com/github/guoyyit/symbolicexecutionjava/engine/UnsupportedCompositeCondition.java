package com.github.guoyyit.symbolicexecutionjava.engine;

/** Raised when a branch condition cannot be encoded as a single linear conjunction. */
public class UnsupportedCompositeCondition extends RuntimeException {

    public UnsupportedCompositeCondition(String message) {
        super(message);
    }
}
