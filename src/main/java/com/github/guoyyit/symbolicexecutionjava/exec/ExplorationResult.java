package com.github.guoyyit.symbolicexecutionjava.exec;

import java.util.List;

/** Final, complete result of one analysis task. */
public record ExplorationResult(List<Finding> findings, long statesExplored) {
}
