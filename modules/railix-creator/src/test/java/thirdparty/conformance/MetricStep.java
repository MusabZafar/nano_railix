package thirdparty.conformance;

import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;

/** A real static bundle Step with application-owned metrics and no transport or instance. */
public final class MetricStep {
    private MetricStep() { throw new AssertionError("Steps are functions."); }

    public static StepResult pre(final StepInput configuration, final StepInput context) {
        context.counter("starts").increment();
        context.gauge("depth").set(1234.56789);
        return StepResult.outcome(context.primaryOutcome());
    }

    public static StepResult process(final StepInput configuration, final StepInput context) {
        context.counter("processed").increment();
        return StepResult.outcome(context.primaryOutcome()).output("value", context.value("value"));
    }

    public static StepResult post(final StepInput configuration, final StepInput context) {
        context.counter("stops").increment();
        return StepResult.outcome(context.primaryOutcome());
    }
}
