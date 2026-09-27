package com.github.guoyyit.symbolicexecutionjava.checkpoint;

import java.util.List;

/**
 * Latest committed exploration state for one job.
 * Committed paths are durable; resume continues from {@code frontierCount}.
 */
public record Checkpoint(String jobId, String programHash, int reportedCount,
                         List<PathSnapshot> paths, boolean complete) {
}
