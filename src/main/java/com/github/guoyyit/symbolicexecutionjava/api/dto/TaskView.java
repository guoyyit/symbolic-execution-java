package com.github.guoyyit.symbolicexecutionjava.api.dto;

import com.github.guoyyit.symbolicexecutionjava.exec.ExplorationResult;
import com.github.guoyyit.symbolicexecutionjava.task.TaskStatus;

/** Read model for a task. {@code result} is null unless the task completed. */
public record TaskView(String id,
                       TaskStatus status,
                       String error,
                       ExplorationResult result,
                       long createdAt,
                       long finishedAt) {
}
