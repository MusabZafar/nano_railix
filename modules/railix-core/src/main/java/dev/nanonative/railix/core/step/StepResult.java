package dev.nanonative.railix.core.step;

import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.runtime.RunResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** One declared outcome with output values, or explicit continued/stopped control. */
public sealed class StepResult permits StepResult.Continued, StepResult.Stopped {
    private final String outcome;
    private final Map<String, RailixValue> outputs;
    private final Map<String, RailixValue> writes;

    private StepResult(
            final String outcome,
            final Map<String, RailixValue> outputs,
            final Map<String, RailixValue> writes
    ) {
        if (outcome == null || outcome.isBlank()) {
            throw new IllegalArgumentException("Step outcome must be a non-blank string.");
        }
        this.outcome = outcome;
        this.outputs = Map.copyOf(outputs);
        this.writes = Map.copyOf(writes);
    }

    public static StepResult outcome(final String outcome) {
        return new StepResult(outcome, Map.of(), Map.of());
    }

    /**
     * Finishes this path after the implementation has explicitly executed its continuations.
     * This is not an authored outcome and never follows an additional connection.
     *
     * @return immutable completion marker with no outputs or context writes
     */
    public static StepResult continued() {
        return Continued.INSTANCE;
    }

    /**
     * Propagates an explicit rejection, failure or cancellation on this path only.
     *
     * @param result non-successful path result
     * @return terminal Step result
     * @throws IllegalArgumentException for a successful result
     * @throws NullPointerException for Java null
     */
    public static StepResult stopped(final RunResult result) {
        java.util.Objects.requireNonNull(result, "Stopped result cannot be Java null.");
        if (result instanceof RunResult.Succeeded || result instanceof RunResult.Completed) {
            throw new IllegalArgumentException("A stopped Step requires a non-successful result.");
        }
        return new Stopped(result);
    }

    /** Returns whether explicit continuations have replaced ordinary outcome routing. */
    public boolean isContinued() {
        return this instanceof Continued;
    }

    /** Returns the explicit path rejection/failure/cancellation when present. */
    public Optional<RunResult> stopped() {
        return this instanceof Stopped stopped ? Optional.of(stopped.result) : Optional.empty();
    }

    public StepResult output(final String name, final RailixValue value) {
        if (getClass() != StepResult.class) {
            throw new IllegalStateException("Terminal Step results cannot return outputs.");
        }
        valid(name, value, "output");
        if (outputs.isEmpty()) {
            return new StepResult(outcome, Map.of(name, value), writes);
        }
        final Map<String, RailixValue> next = new LinkedHashMap<>(outputs);
        next.put(name, value);
        return new StepResult(outcome, next, writes);
    }

    /** Writes one value through a declared writable PATH input of the enclosing Step. */
    public StepResult write(final String input, final RailixValue value) {
        if (getClass() != StepResult.class) {
            throw new IllegalStateException("Terminal Step results cannot write context.");
        }
        valid(input, value, "write");
        if (writes.isEmpty()) {
            return new StepResult(outcome, outputs, Map.of(input, value));
        }
        final Map<String, RailixValue> next = new LinkedHashMap<>(writes);
        next.put(input, value);
        return new StepResult(outcome, outputs, next);
    }

    public String outcome() {
        return outcome;
    }

    public Map<String, RailixValue> outputs() {
        return outputs;
    }

    public Map<String, RailixValue> writes() {
        return writes;
    }

    private static final class Continued extends StepResult {
        private static final Continued INSTANCE = new Continued();

        private Continued() {
            super("continued", Map.of(), Map.of());
        }
    }

    private static final class Stopped extends StepResult {
        private final RunResult result;

        private Stopped(final RunResult result) {
            super("stopped", Map.of(), Map.of());
            this.result = result;
        }
    }

    private static void valid(final String name, final RailixValue value, final String label) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Step " + label + " name must be a non-blank string.");
        }
        if (value == null) {
            throw new IllegalArgumentException("Step " + label + " value cannot be Java null.");
        }
    }
}
