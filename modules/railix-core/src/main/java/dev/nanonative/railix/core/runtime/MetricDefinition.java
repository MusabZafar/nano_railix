package dev.nanonative.railix.core.runtime;

import java.util.Objects;

/** Fixed, code-free Step measurement metadata shared by authoring and the generated application. */
public record MetricDefinition(String name, String label, String unit, Kind kind, Aggregation aggregation) {
    /** Supported measurement updates. */
    public enum Kind { COUNTER, GAUGE }

    /** How multiple measured Step placements may be summarized. */
    public enum Aggregation { SUM, MAX, NONE }

    /**
     * Validates one fixed metric declaration. Names are local to the Step definition.
     * @param name stable lowercase ASCII identifier
     * @param label human-readable label
     * @param unit measurement unit, such as count or bytes
     * @param kind counter or gauge
     * @param aggregation explicit grouping behavior
     * @throws IllegalArgumentException for an invalid name or blank label/unit
     * @throws NullPointerException for a missing kind or aggregation
     */
    public MetricDefinition {
        if (name == null || !name.matches("[a-z][a-z0-9_]*") || label == null || label.isBlank()
                || unit == null || unit.isBlank()) {
            throw new IllegalArgumentException("Metrics require a lowercase identifier, label and unit.");
        }
        Objects.requireNonNull(kind, "Metric kind is required.");
        Objects.requireNonNull(aggregation, "Metric aggregation is required.");
    }

    /**
     * Declares an exact, nonnegative integer counter, summed across selected placements.
     * @param name stable local identifier
     * @param label display label
     * @param unit measurement unit
     * @return immutable declaration
     * @throws IllegalArgumentException for an invalid identifier or blank metadata
     */
    public static MetricDefinition counter(final String name, final String label, final String unit) {
        return new MetricDefinition(name, label, unit, Kind.COUNTER, Aggregation.SUM);
    }

    /**
     * Declares a finite numeric gauge, absent until first set and not combined across placements.
     * @param name stable local identifier
     * @param label display label
     * @param unit measurement unit
     * @return immutable declaration
     * @throws IllegalArgumentException for an invalid identifier or blank metadata
     */
    public static MetricDefinition gauge(final String name, final String label, final String unit) {
        return new MetricDefinition(name, label, unit, Kind.GAUGE, Aggregation.NONE);
    }

    /**
     * Selects how this measurement is combined across multiple Step placements.
     * @param value sum, maximum or no aggregation
     * @return a declaration with the selected aggregation
     * @throws NullPointerException for a missing aggregation
     */
    public MetricDefinition aggregate(final Aggregation value) {
        return new MetricDefinition(name, label, unit, kind, value);
    }
}
