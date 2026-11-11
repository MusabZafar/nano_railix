package dev.nanonative.railix.core.runtime;

import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.value.RailixValue;

/** Canonical trigger-independent run result without exception-driven user control flow. */
public sealed interface RunResult permits
        RunResult.Succeeded,
        RunResult.Completed,
        RunResult.Rejected,
        RunResult.Failed,
        RunResult.Cancelled {
    record Succeeded(RailixValue.ObjectValue context) implements RunResult {
    }

    /**
     * Completed orchestration with independently reported paths, not one synthetic success/failure.
     * The context is the shared parent context. Isolated path changes remain in their own results.
     *
     * @param context non-null final parent context
     * @param paths immutable results of the explicitly started paths
     */
    record Completed(RailixValue.ObjectValue context, java.util.List<PathResult> paths) implements RunResult {
        public Completed {
            java.util.Objects.requireNonNull(context, "Completed context cannot be Java null.");
            paths = java.util.List.copyOf(paths);
        }
    }

    /**
     * One explicitly executed outcome and its independent result.
     *
     * @param stepId Step placement that started the path
     * @param outcome declared outgoing route of that placement
     * @param result non-null terminal path result
     * @throws IllegalArgumentException for a blank identity or outcome
     * @throws NullPointerException for a Java-null result
     */
    record PathResult(String stepId, String outcome, RunResult result) {
        public PathResult {
            if (stepId == null || stepId.isBlank() || outcome == null || outcome.isBlank()) {
                throw new IllegalArgumentException("Path identity and outcome must be non-blank.");
            }
            java.util.Objects.requireNonNull(result, "Path result cannot be Java null.");
        }
    }

    record Rejected(java.util.List<Diagnostic> diagnostics) implements RunResult {
        public Rejected {
            diagnostics = java.util.List.copyOf(diagnostics);
        }
    }

    record Failed(RunFailure failure) implements RunResult {
    }

    /** Cancellation observed at an execution boundary. */
    record Cancelled() implements RunResult {
    }
}
