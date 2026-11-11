package thirdparty.production;

import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;

import java.util.List;
import java.util.function.UnaryOperator;
import java.util.ServiceLoader;

/** Exercises real runtime references separately from a sibling's authoring-only dependencies. */
public final class PackagingSteps {
    private PackagingSteps() { }

    private static String prefix() { return "packaged-"; }

    public static List<StepDefinition> definitions() {
        return List.of(StepDefinition.named("portable.dynamic", "1")
                .receive("class", dev.nanonative.railix.core.value.ValueShape.STRING)
                .returns("value", dev.nanonative.railix.core.value.ValueShape.STRING)
                .implementation(PackagingSteps.class));
    }

    public static StepResult process(final StepInput configuration, final StepInput input) {
        return Echo.process(configuration, input);
    }

    public static final class Echo {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            final UnaryOperator<String> format = value -> prefix() + Helper.suffix(value);
            return StepResult.outcome(input.primaryOutcome()).output("value",
                    RailixValue.string(format.apply(input.string("class"))));
        }
    }

    public static final class Helper {
        private static String suffix(final String value) {
            return switch ((Object) value) {
                case String text -> text + "-nested";
                default -> throw new IllegalArgumentException("Expected text.");
            };
        }
    }

    public interface Text { String value(String input); }

    public static final class TextProvider implements Text {
        @Override public String value(final String input) { return "service-" + input; }
    }

    public static final class Services {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            try (var resource = Services.class.getResourceAsStream("banner.txt")) {
                if (resource == null) throw new java.io.IOException("Runtime resource missing.");
                final String value = ServiceLoader.load(Text.class).findFirst().orElseThrow().value(input.string("class"));
                return StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.string(value
                        + new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
            } catch (final java.io.IOException failure) {
                throw new IllegalStateException("Packaged fixture resource is unreadable.", failure);
            }
        }
    }

    public static final class Reflection {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            try {
                return StepResult.outcome(input.primaryOutcome()).output("value",
                        RailixValue.string((String) Reflection.class.getDeclaredMethod("message").invoke(null)));
            } catch (final ReflectiveOperationException failure) {
                throw new IllegalStateException("Declared dynamic entrypoint missing.", failure);
            }
        }

        public static String message() { return "reflected"; }
    }

    public enum Mode { ON }

    public static class Base {
        public static String decorate(final String value) { return "inherited-" + value; }
    }

    public static final class Derived extends Base { }

    public static final class Inherited {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.string(
                    Derived.decorate(input.string("class")) + "-" + Mode.class.getEnumConstants()[0].name()));
        }
    }

    public static final class AuthoringState {
        private static final List<StepDefinition> DEFINITIONS = PackagingSteps.definitions();

        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.string(input.string("class")));
        }
    }

    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    public @interface Marker { String value(); }

    public static class GenericOuter<T> { public class Inner<U> { } }

    @Marker("Lnot/a/Class;")
    public static final class Metadata {
        public static List<GenericOuter<String>.Inner<Integer>> types;

        public static StepResult process(final StepInput configuration, final StepInput input) {
            try {
                final String value = Metadata.class.getAnnotation(Marker.class).value() + "|"
                        + Metadata.class.getField("types").getGenericType().getTypeName();
                return StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.string(value));
            } catch (final ReflectiveOperationException failure) {
                throw new IllegalStateException("Static type metadata missing.", failure);
            }
        }
    }

    public static final class SerializedLambda {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            final UnaryOperator<String> function = (UnaryOperator<String> & java.io.Serializable) text -> "serialized-" + text;
            try (var bytes = new java.io.ByteArrayOutputStream()) {
                try (var output = new java.io.ObjectOutputStream(bytes)) { output.writeObject(function); }
                try (var objects = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
                    @SuppressWarnings("unchecked") final var restored = (UnaryOperator<String>) objects.readObject();
                    return StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.string(restored.apply(input.string("class"))));
                }
            } catch (final java.io.IOException | ClassNotFoundException failure) {
                throw new IllegalStateException("Serialized lambda could not be restored.", failure);
            }
        }
    }

    public static final class DynamicOuter {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.string("dynamic-" + input.string("class")));
        }

        public static final class Definitions {
            private static final List<StepDefinition> ALL = PackagingSteps.definitions();
        }
    }

    public enum EnumProvider {
        ONLY;

        public static List<StepDefinition> definitions() { return PackagingSteps.definitions(); }

        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).output("value",
                    RailixValue.string(EnumProvider.class.getEnumConstants()[0].name()));
        }
    }

    /** Must not become reachable merely because it belongs to the same Java nest. */
    public static final class Authoring {
        public static List<StepDefinition> definitions() { return List.of(); }
    }
}
