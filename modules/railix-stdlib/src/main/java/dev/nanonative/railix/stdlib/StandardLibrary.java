package dev.nanonative.railix.stdlib;

import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepDefinition.Input;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;

import java.util.ArrayList;
import java.util.List;

import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.READ;
import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.READ_WRITE;
import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.WRITE;

/**
 * Built-in Steps accepted by the current Creator-first checkpoint.
 */
public final class StandardLibrary {
    private StandardLibrary() {
    }

    public static StepCatalog catalog() {
        return StepCatalog.of(StandardLibrary.class, definitions().toArray(StepDefinition[]::new));
    }

    /** Returns the built-in authoring contracts, also used by the isolated configuration validator. */
    public static List<StepDefinition> definitions() {
        final List<StepDefinition> definitions = new ArrayList<>(List.of(
                app(),
                cli(),
                http(),
                httpClient(),
                fieldManipulation(),
                branch()
        ));
        definitions.addAll(PrimitiveSteps.definitions());
        return List.copyOf(definitions);
    }

    private static StepDefinition app() {
        return StepDefinition.named("railix.app", "1")
                .kind(StepDefinition.Kind.APP)
                .define();
    }

    private static StepDefinition cli() {
        return StepDefinition.named("railix.trigger.cli", "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .displayName("CLI")
                .maximumInstances(1)
                .source("application.arguments")
                .receive("arguments", ValueShape.ARRAY)
                .input("target", Input.path(WRITE)
                        .defaultPath("context", "payload", "arguments"))
                .exampleTarget("target")
                .example("no-arguments", RailixValue.array(List.of()))
                .example("one-argument", RailixValue.array(List.of(
                        RailixValue.string("railix")
                )))
                .example("multiple-arguments", RailixValue.array(List.of(
                        RailixValue.string("hello"),
                        RailixValue.string("railix")
                )))
                .result("result", ValueShape.ANY, RailixValue.nullValue())
                .result("exit_code", ValueShape.NUMBER, RailixValue.number(0))
                .response("output", "result")
                .response("status", "exit_code")
                .implementation(StandardStepHandlers.Cli.class);
    }

    private static StepDefinition http() {
        return StepDefinition.named("railix.trigger.http", "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .displayName("HTTP")
                .maximumInstances(1)
                .source("application.http")
                .receive("request", ValueShape.OBJECT)
                .input("host", Input.json(ValueShape.STRING).defaultValue(RailixValue.string("127.0.0.1")))
                .input("port", Input.json(ValueShape.NUMBER).between(RailixValue.number(0), RailixValue.number(65535))
                        .defaultValue(RailixValue.number(8080))
                        .validation(Integer.class, port -> java.util.Optional.empty()))
                .input("target", Input.path(WRITE)
                        .defaultPath("context", "payload", "request"))
                .exampleTarget("target")
                .example("get-root", RailixValue.object(java.util.Map.of(
                        "method", RailixValue.string("GET"),
                        "path", RailixValue.string("/"),
                        "query", RailixValue.string(""),
                        "headers", RailixValue.object(java.util.Map.of()),
                        "body", RailixValue.nullValue()
                )))
                .result("body", ValueShape.ANY, RailixValue.nullValue())
                .result("headers", ValueShape.OBJECT, RailixValue.object(java.util.Map.of()))
                .result("status", ValueShape.NUMBER, RailixValue.number(200))
                .response("body", "body")
                .response("headers", "headers")
                .response("status", "status")
                .implementation(HttpStepHandlers.Trigger.class, "jdk.httpserver");
    }

    private static StepDefinition httpClient() {
        return StepDefinition.named("railix.http.client", "1")
                .displayName("HTTP Client")
                .searchTerms("fetch", "request", "api")
                .receive("body", ValueShape.ANY)
                .input("url", Input.json(ValueShape.STRING))
                .input("method", Input.json(ValueShape.STRING)
                        .defaultValue(RailixValue.string("GET")))
                .input("headers", Input.json(ValueShape.OBJECT)
                        .defaultValue(RailixValue.object(java.util.Map.of())))
                .input("connection", Input.options(Input.option("private"), Input.option("shared"))
                        .defaultOption("private"))
                .returns("response", ValueShape.OBJECT)
                .implementation(HttpStepHandlers.Client.class, "java.net.http");
    }

    private static StepDefinition fieldManipulation() {
        return StepDefinition.named("railix.field-manipulation", "2")
                .input("field", Input.path(READ_WRITE).defaultPath("context", "payload"))
                .input("value", Input.candidates(
                        Input.option("current").fromParent("field"),
                        Input.option("literal")
                                .input("literal", Input.json(ValueShape.ANY)
                                        .defaultValue(RailixValue.nullValue()))
                                .fromOwned("literal"),
                        Input.option("field")
                                .input("source", Input.path(READ)
                                        .defaultPath("context", "payload"))
                                .fromOwned("source")
                ).defaultCandidate("current"))
                .input("steps", Input.steps(StepDefinition.ValueSource.from("value")))
                .implementation(StandardStepHandlers.FieldManipulation.class);
    }

    private static StepDefinition branch() {
        return StepDefinition.named("railix.branch", "1")
                .displayName("Branch")
                .searchTerms("choice", "if", "filter", "switch", "split", "route", "async", "sync")
                .primaryOutcome("otherwise")
                .input("routes", Input.matcherGroups(fieldOrLiteral()).withAuthoredOutcomes()
                        .routeInput("own_context", Input.json(ValueShape.BOOLEAN).defaultValue(RailixValue.bool(false))))
                .input("selection", Input.options(Input.option("first"), Input.option("matching"), Input.option("all"))
                        .defaultOption("first"))
                .input("execution", Input.options(Input.option("sequential"), Input.option("parallel"))
                        .defaultOption("sequential"))
                .withContinuations()
                .implementation(StandardStepHandlers.Branch.class);
    }

    private static StepDefinition.Option[] fieldOrLiteral() {
        return new StepDefinition.Option[]{
                Input.option("field")
                        .input("field", Input.path(READ).defaultPath("context", "payload"))
                        .fromOwned("field"),
                Input.option("literal")
                        .input("value", Input.json(ValueShape.ANY).defaultValue(RailixValue.nullValue()))
                        .fromOwned("value")
        };
    }
}
