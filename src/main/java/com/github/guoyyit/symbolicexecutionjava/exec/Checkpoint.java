package com.github.guoyyit.symbolicexecutionjava.exec;

import java.util.ArrayList;
import java.util.List;

/**
 * Serialisable exploration snapshot: the unprocessed frontier, every finding
 * already committed, the monotonic counters and the terminal outcome.
 *
 * <p>Persisting the outcome matters: a snapshot taken with an empty frontier
 * represents {@link SymbolicExecutor.Outcome#COMPLETED}; without the flag an
 * executor rebuilt after restart would treat that state as still running and
 * spin forever. Restoring an executor from this structure continues exactly
 * where the checkpoint was taken.
 */
public final class Checkpoint {

    private List<ExecutorState> frontier = new ArrayList<>();
    private List<Finding> findings = new ArrayList<>();
    private int nextPathId;
    private long statesExplored;
    private SymbolicExecutor.Outcome outcome = SymbolicExecutor.Outcome.RUNNING;

    public Checkpoint() {
    }

    public Checkpoint(List<ExecutorState> frontier, List<Finding> findings,
                      int nextPathId, long statesExplored,
                      SymbolicExecutor.Outcome outcome) {
        this.frontier = new ArrayList<>(frontier);
        this.findings = new ArrayList<>(findings);
        this.nextPathId = nextPathId;
        this.statesExplored = statesExplored;
        this.outcome = outcome;
    }

    public List<ExecutorState> getFrontier() {
        return frontier;
    }

    public void setFrontier(List<ExecutorState> frontier) {
        this.frontier = frontier;
    }

    public List<Finding> getFindings() {
        return findings;
    }

    public void setFindings(List<Finding> findings) {
        this.findings = findings;
    }

    public int getNextPathId() {
        return nextPathId;
    }

    public void setNextPathId(int nextPathId) {
        this.nextPathId = nextPathId;
    }

    public long getStatesExplored() {
        return statesExplored;
    }

    public void setStatesExplored(long statesExplored) {
        this.statesExplored = statesExplored;
    }

    public SymbolicExecutor.Outcome getOutcome() {
        return outcome;
    }

    public void setOutcome(SymbolicExecutor.Outcome outcome) {
        this.outcome = outcome;
    }
}
