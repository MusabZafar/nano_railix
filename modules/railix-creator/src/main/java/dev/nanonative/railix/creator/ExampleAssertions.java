package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixValue;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Creator-only checks over immutable recorded JSON values. Never invokes a Step. */
final class ExampleAssertions {
    enum Assertion {
        EQUAL("isEqualTo", "is equal to", true),
        ABSENT("doesNotExist", "does not exist", false);

        final String id;
        final String label;
        final boolean expected;

        Assertion(final String id, final String label, final boolean expected) {
            this.id = id;
            this.label = label;
            this.expected = expected;
        }
    }

    private ExampleAssertions() { }

    static RailixValue.ObjectValue catalog() {
        return RailixValue.object(Map.of("assertions", RailixValue.array(
                java.util.Arrays.stream(Assertion.values()).<RailixValue>map(assertion -> RailixValue.object(Map.of(
                        "id", RailixValue.string(assertion.id), "label", RailixValue.string(assertion.label),
                        "expected", RailixValue.bool(assertion.expected)))).toList())));
    }

    static Read parse(final RailixValue value) {
        if (!(value instanceof RailixValue.ArrayValue array) || array.values().isEmpty())
            return Read.rejected("ASSERTIONS_REQUIRED", "Choose at least one expectation.", "checks");
        final List<Check> checks = new ArrayList<>();
        for (int index = 0; index < array.values().size(); index++) {
            final String location = "checks[" + index + "]";
            if (!(array.values().get(index) instanceof RailixValue.ObjectValue object)
                    || !Set.of("path", "assertion", "expected").containsAll(object.values().keySet()))
                return Read.rejected("ASSERTION_INVALID", "An expectation needs a field path and a supported check.", location);
            final Optional<Assertion> assertion = java.util.Arrays.stream(Assertion.values())
                    .filter(candidate -> RailixValue.string(candidate.id).equals(object.values().get("assertion"))).findFirst();
            if (assertion.isEmpty())
                return Read.rejected("ASSERTION_OPERATOR_INVALID", "Choose a supported check.", location + ".assertion");
            if (!(object.values().get("path") instanceof RailixValue.ArrayValue path)
                    || path.values().isEmpty() || path.values().size() > RailixData.DEFAULT_MAX_DEPTH
                    || !RailixValue.string("context").equals(path.values().getFirst()))
                return Read.rejected("ASSERTION_PATH_INVALID", "Select a field in the recorded context.", location + ".path");
            for (final RailixValue part : path.values()) {
                if (part instanceof RailixValue.StringValue) continue;
                if (!(part instanceof RailixValue.NumberValue number)
                        || number.value().signum() < 0 || number.value().stripTrailingZeros().scale() > 0
                        || number.value().compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0)
                    return Read.rejected("ASSERTION_PATH_INVALID", "Array positions must be nonnegative integers.", location + ".path");
            }
            if (assertion.orElseThrow().expected != object.values().containsKey("expected"))
                return Read.rejected("ASSERTION_EXPECTED_INVALID", assertion.orElseThrow().expected
                        ? "Enter an expected JSON value." : "An absence check has no expected value.", location + ".expected");
            checks.add(new Check(path.values(), assertion.orElseThrow(),
                    object.values().getOrDefault("expected", RailixValue.nullValue())));
        }
        return new Read(checks, List.of());
    }

    static RailixValue.ObjectValue evaluate(final List<Check> checks, final RailixValue.ObjectValue context,
                                           final long deadline) {
        final RailixValue.ObjectValue root = RailixValue.object(Map.of("context", context));
        final List<RailixValue> results = new ArrayList<>(checks.size());
        boolean succeeded = true;
        for (final Check check : checks) {
            if (Thread.currentThread().isInterrupted() || deadline - System.nanoTime() <= 0)
                return RailixValue.object(Map.of("status", RailixValue.string("unavailable"),
                        "code", RailixValue.string(Thread.currentThread().isInterrupted() ? "ASSERTION_CANCELLED" : "ASSERTION_TIMEOUT")));
            Optional<RailixValue> actual = Optional.of(root);
            for (final RailixValue part : check.path) {
                if (actual.isEmpty()) break;
                final RailixValue current = actual.orElseThrow();
                if (current instanceof RailixValue.ObjectValue object && part instanceof RailixValue.StringValue field)
                    actual = Optional.ofNullable(object.values().get(field.value()));
                else if (current instanceof RailixValue.ArrayValue array && part instanceof RailixValue.NumberValue number) {
                    final int index = number.value().intValueExact();
                    actual = index < array.values().size() ? Optional.of(array.values().get(index)) : Optional.empty();
                } else actual = Optional.empty();
            }
            boolean passed = actual.isEmpty();
            if (check.assertion == Assertion.EQUAL) {
                passed = actual.isPresent();
                final ArrayDeque<RailixValue[]> pending = new ArrayDeque<>();
                if (passed) pending.push(new RailixValue[]{actual.orElseThrow(), check.expected});
                while (passed && !pending.isEmpty()) {
                    if (Thread.currentThread().isInterrupted() || deadline - System.nanoTime() <= 0)
                        return RailixValue.object(Map.of("status", RailixValue.string("unavailable"),
                                "code", RailixValue.string(Thread.currentThread().isInterrupted() ? "ASSERTION_CANCELLED" : "ASSERTION_TIMEOUT")));
                    final RailixValue[] pair = pending.pop();
                    if (pair[0] instanceof RailixValue.NumberValue left && pair[1] instanceof RailixValue.NumberValue right)
                        passed = left.value().compareTo(right.value()) == 0;
                    else if (pair[0] instanceof RailixValue.ObjectValue left && pair[1] instanceof RailixValue.ObjectValue right) {
                        passed = left.values().keySet().equals(right.values().keySet());
                        if (passed) left.values().forEach((key, item) -> pending.push(new RailixValue[]{item, right.values().get(key)}));
                    } else if (pair[0] instanceof RailixValue.ArrayValue left && pair[1] instanceof RailixValue.ArrayValue right) {
                        passed = left.values().size() == right.values().size();
                        if (passed) for (int index = 0; index < left.values().size(); index++)
                            pending.push(new RailixValue[]{left.values().get(index), right.values().get(index)});
                    } else passed = pair[0].equals(pair[1]);
                }
            }
            final Map<String, RailixValue> result = new LinkedHashMap<>();
            result.put("path", RailixValue.array(check.path));
            result.put("assertion", RailixValue.string(check.assertion.id));
            result.put("status", RailixValue.string(passed ? "passed" : "failed"));
            if (check.assertion.expected) result.put("expected", check.expected);
            actual.ifPresent(item -> result.put("actual", item));
            if (!passed) result.put("code", RailixValue.string(check.assertion == Assertion.ABSENT
                    ? "ASSERTION_FIELD_PRESENT" : actual.isEmpty() ? "ASSERTION_FIELD_MISSING" : "ASSERTION_NOT_EQUAL"));
            results.add(RailixValue.object(result));
            succeeded &= passed;
        }
        return RailixValue.object(Map.of("status", RailixValue.string(succeeded ? "passed" : "failed"),
                "checks", RailixValue.array(results)));
    }

    record Check(List<RailixValue> path, Assertion assertion, RailixValue expected) {
        Check { path = List.copyOf(path); }
    }

    record Read(List<Check> checks, List<Diagnostic> diagnostics) {
        Read { checks = List.copyOf(checks); diagnostics = List.copyOf(diagnostics); }

        static Read rejected(final String code, final String message, final String path) {
            return new Read(List.of(), List.of(Diagnostic.atPath(code, message, path)));
        }
    }
}
