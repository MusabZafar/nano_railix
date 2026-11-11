package thirdparty.portable;

import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.ValueShape;

import java.util.List;

public final class PortableSteps {
    private PortableSteps() { }

    public static List<StepDefinition> definitions() {
        return List.of(StepDefinition.named("portable.app", "1").kind(StepDefinition.Kind.APP).define(),
                StepDefinition.named("portable.echo", "1").receive("value", ValueShape.STRING)
                        .returns("value", ValueShape.STRING).implementation(PortableSteps.class));
    }

    public static StepResult process(final StepInput configuration, final StepInput input) {
        return StepResult.outcome(input.primaryOutcome()).output("value", input.value("value"));
    }
}
