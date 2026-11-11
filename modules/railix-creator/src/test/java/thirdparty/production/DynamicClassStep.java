package thirdparty.production;

import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.project.Diagnostic;
import java.util.List;

/** An external integration loading its selected target from runtime input. */
public final class DynamicClassStep {
    private DynamicClassStep() { }

    public static StepResult process(final StepInput configuration, final StepInput input) {
        try {
            final Class<?> type = Class.forName(input.string("class"));
            return StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.string(type.getName()));
        } catch (final ReflectiveOperationException failedLookup) {
            return StepResult.stopped(new RunResult.Rejected(List.of(Diagnostic.atPath(
                    "DYNAMIC_LOOKUP_FAILED", "The selected class cannot supply a value.", ""))));
        }
    }

    public record Value(String text) { }
}
