package dev.nanonative.railix.core.runtime;

import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.value.RailixValue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLongArray;

/** Fixed application-owned custom series. Registration allocates once; updates only touch atomics. */
final class CustomMetrics {
    static final CustomMetrics EMPTY = new CustomMetrics(List.of(), 0);
    final Map<String, RuntimeMetrics.MetricDescriptor> descriptors;
    final List<List<Series>> steps;
    final Map<String, StepInput.Metrics> bindings;
    final long counterBytes;

    CustomMetrics(final List<RuntimeMetrics.Registration> registrations, final int stepCount) {
        final Map<String, RuntimeMetrics.MetricDescriptor> definitions = new LinkedHashMap<>();
        final Map<String, StepInput.Metrics> registered = new LinkedHashMap<>();
        final List<List<Series>> indexed = new ArrayList<>();
        for (int index = 0; index < stepCount; index++) indexed.add(new ArrayList<>());
        long bytes = 0;
        for (final var registration : registrations) {
            if (registration.step() < -1 || registration.step() >= stepCount)
                throw new IllegalArgumentException("Custom metric Step index is outside the application.");
            final boolean enabled = registration.step() >= 0;
            final List<RuntimeMetrics.MetricDescriptor> metrics = new ArrayList<>();
            final Map<String, StepInput.Counter> counters = new LinkedHashMap<>();
            final Map<String, StepInput.Gauge> gauges = new LinkedHashMap<>();
            for (final var definition : registration.definitions()) {
                final String id = registration.namespace() + ":" + definition.name();
                final var metric = new RuntimeMetrics.MetricDescriptor(id, definition.label(), definition.unit(),
                        definition.kind().name().toLowerCase(java.util.Locale.ROOT),
                        definition.aggregation().name().toLowerCase(java.util.Locale.ROOT), -1,
                        "custom_" + definition.kind().name().toLowerCase(java.util.Locale.ROOT) + "_"
                                + registration.namespace().chars().mapToObj(character ->
                                character >= 'a' && character <= 'z' || character >= 'A' && character <= 'Z'
                                        || character >= '0' && character <= '9' ? Character.toString(character)
                                        : "_u%04x".formatted(character)).collect(java.util.stream.Collectors.joining())
                                + "_metric_" + definition.name()
                                + (definition.kind() == MetricDefinition.Kind.COUNTER ? "_total" : ""),
                        false, 0, null, "step");
                if (enabled) {
                    final var previous = definitions.putIfAbsent(id, metric);
                    if (previous != null && (!previous.label().equals(metric.label())
                            || !previous.unit().equals(metric.unit()) || !previous.kind().equals(metric.kind())
                            || !previous.aggregation().equals(metric.aggregation())))
                        throw new IllegalArgumentException("Conflicting custom metric declaration: " + id);
                    metrics.add(previous == null ? metric : previous);
                }
            }
            final Series series = enabled ? new Series(registration.placement(), registration.namespace(), List.copyOf(metrics),
                    new AtomicLongArray(metrics.size())) : null;
            for (int index = 0; index < registration.definitions().size(); index++) {
                final var definition = registration.definitions().get(index);
                if (definition.kind() == MetricDefinition.Kind.COUNTER) {
                    counters.put(definition.name(), enabled ? new Counter(series, index) : DisabledCounter.INSTANCE);
                } else {
                    if (enabled) series.values.set(index, Double.doubleToRawLongBits(Double.NaN));
                    gauges.put(definition.name(), enabled ? new Gauge(series, index) : DisabledGauge.INSTANCE);
                }
            }
            if (registered.putIfAbsent(registration.placement(), new Binding(Map.copyOf(counters), Map.copyOf(gauges))) != null)
                throw new IllegalArgumentException("Duplicate custom metric placement: " + registration.placement());
            if (enabled) {
                indexed.get(registration.step()).add(series);
                bytes += (long) metrics.size() * Long.BYTES;
            }
        }
        descriptors = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(definitions));
        bindings = Map.copyOf(registered);
        steps = indexed.stream().map(List::copyOf).toList();
        counterBytes = bytes;
    }

    Map<String, RailixValue> values(final int[] ranges, final List<RuntimeMetrics.MetricDescriptor> requested) {
        if (descriptors.isEmpty()) return Map.of();
        final Map<RuntimeMetrics.MetricDescriptor, Aggregate> combined = new LinkedHashMap<>();
        for (int range = 0; range < ranges.length; range += 2) {
            for (int step = ranges[range]; step <= ranges[range + 1]; step++) {
                for (final var series : steps.get(step)) {
                    for (int index = 0; index < series.metrics.size(); index++) {
                        final var metric = series.metrics.get(index);
                        if (requested != null && !requested.contains(metric)) continue;
                        final long raw = series.values.get(index);
                        if (metric.kind().equals("gauge") && Double.isNaN(Double.longBitsToDouble(raw))) continue;
                        final BigDecimal current = metric.kind().equals("counter") ? BigDecimal.valueOf(raw)
                                : BigDecimal.valueOf(Double.longBitsToDouble(raw));
                        final var prior = combined.get(metric);
                        final BigDecimal reduced = prior == null ? current : switch (metric.aggregation()) {
                            case "sum" -> prior.value.add(current);
                            case "max" -> prior.value.max(current);
                            default -> current;
                        };
                        combined.put(metric, new Aggregate(reduced, prior == null ? 1 : prior.count + 1));
                    }
                }
            }
        }
        final Map<String, RailixValue> values = new LinkedHashMap<>();
        combined.forEach((metric, aggregate) -> {
            if (!metric.aggregation().equals("none") || aggregate.count == 1)
                values.put(metric.id(), RailixValue.number(aggregate.value));
        });
        return values;
    }

    List<RailixValue> placements(final int step) {
        if (descriptors.isEmpty()) return List.of();
        final List<RailixValue> result = new ArrayList<>();
        for (final var series : steps.get(step)) {
            final Map<String, RailixValue> values = new LinkedHashMap<>();
            for (int index = 0; index < series.metrics.size(); index++) {
                final var metric = series.metrics.get(index);
                final long raw = series.values.get(index);
                if (metric.kind().equals("gauge") && Double.isNaN(Double.longBitsToDouble(raw))) continue;
                values.put(metric.id(), metric.kind().equals("counter") ? RailixValue.number(raw)
                        : RailixValue.number(BigDecimal.valueOf(Double.longBitsToDouble(raw))));
            }
            result.add(RailixValue.object(Map.of("id", RailixValue.string(series.placement),
                    "definition", RailixValue.string(series.namespace), "metrics", RailixValue.object(values))));
        }
        return result;
    }

    private record Aggregate(BigDecimal value, int count) { }
    private record Series(String placement, String namespace, List<RuntimeMetrics.MetricDescriptor> metrics,
                          AtomicLongArray values) { }

    private record Binding(Map<String, StepInput.Counter> counters,
                           Map<String, StepInput.Gauge> gauges) implements StepInput.Metrics {
        @Override public StepInput.Counter counter(final String name) {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Counter name must be nonblank.");
            final var counter = counters.get(name);
            if (counter == null) throw new IllegalArgumentException("Counter is not declared: " + name);
            return counter;
        }
        @Override public StepInput.Gauge gauge(final String name) {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Gauge name must be nonblank.");
            final var gauge = gauges.get(name);
            if (gauge == null) throw new IllegalArgumentException("Gauge is not declared: " + name);
            return gauge;
        }
    }

    private record Counter(Series series, int index) implements StepInput.Counter {
        @Override public StepInput.Counter add(final long amount) {
            if (amount < 0) throw new IllegalArgumentException("Counter increments must be nonnegative.");
            long current = series.values.get(index);
            while (!series.values.compareAndSet(index, current,
                    current > Long.MAX_VALUE - amount ? Long.MAX_VALUE : current + amount))
                current = series.values.get(index);
            return this;
        }
    }

    private record Gauge(Series series, int index) implements StepInput.Gauge {
        @Override public StepInput.Gauge set(final double value) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Gauge values must be finite.");
            series.values.set(index, Double.doubleToRawLongBits(value));
            return this;
        }
    }

    private enum DisabledCounter implements StepInput.Counter {
        INSTANCE;
        @Override public StepInput.Counter add(final long amount) {
            if (amount < 0) throw new IllegalArgumentException("Counter increments must be nonnegative.");
            return this;
        }
    }

    private enum DisabledGauge implements StepInput.Gauge {
        INSTANCE;
        @Override public StepInput.Gauge set(final double value) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Gauge values must be finite.");
            return this;
        }
    }
}
