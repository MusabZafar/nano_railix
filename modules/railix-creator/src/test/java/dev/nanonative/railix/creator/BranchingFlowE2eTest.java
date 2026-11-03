package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.step.StepDefinition.Input;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import thirdparty.production.BranchingSteps;

import java.util.List;
import java.nio.file.Files;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.WRITE;
import static org.assertj.core.api.Assertions.assertThat;

@Timeout(90)
final class BranchingFlowE2eTest extends CreatorServerE2eSupport {
    @Test
    void nestedProgramKeepsTheOwningStepsEntrySnapshotAfterASharedChildWrites() throws Exception {
        final String source = """
                {"format":1,"id":"entry-snapshot","nodes":[
                {"id":"app","use":"railix.app","inputs":{}},
                {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{"name":"order","payload":[]}]},
                {"id":"fork","use":"test.program-fork","inputs":{"steps":[{"use":"test.read","inputs":{"source":["context","original"]}}]}},
                {"id":"update","use":"test.write","inputs":{"field":["context","original"],"value":"CHANGED"}}],
                "links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"fork"},
                {"from":"fork.next","to":"end"},{"from":"fork.update","to":"update"},{"from":"update.next","to":"end"}]}
                """;
        try (var app = GeneratedApplicationFixture.start(directory, source, definitions(), classes())) {
            final var response = app.runTest("command", "{\"payload\":[],\"original\":\"INITIAL\"}");
            final var context = (RailixValue.ObjectValue) object(response.body()).values().get("context");
            assertThat(context.values().get("original")).isEqualTo(RailixValue.string("CHANGED"));
            assertThat(context.values().get("observed")).isEqualTo(RailixValue.string("INITIAL"));
        }
    }

    @Test
    void installedAuthoredRoutesValidateTheirOwnConfigurationAndExecuteTheirOwnPolicy() throws Exception {
        final var declarations = new java.util.ArrayList<>(definitions());
        declarations.addAll(BranchingSteps.definitions());
        final var catalog = GeneratedApplicationFixture.installedCatalog(directory, BranchingSteps.class,
                declarations, classes());
        final String source = """
                {"format":1,"id":"installed-routes","nodes":[
                {"id":"app","use":"railix.app","inputs":{}},
                {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{"name":"order","payload":[]}]},
                {"id":"branch","use":"test.authored-routes","inputs":{"destinations":[{"outcome":"send","groups":[],"inputs":{"copies":0}}]}},
                {"id":"write","use":"test.write","inputs":{"field":["context","result"],"value":"sent"}}],
                "links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"branch"},
                {"from":"branch.otherwise","to":"end"},{"from":"branch.send","to":"write"},{"from":"write.next","to":"end"}]}
                """;
        assertThat(ProjectCompiler.compileApplication(source, catalog))
                .isInstanceOfSatisfying(CompileResult.Rejected.class, rejected -> assertThat(rejected.diagnostics())
                        .anySatisfy(issue -> {
                            assertThat(issue.code()).isEqualTo("PROJECT_INPUT_VALIDATION_INVALID");
                            assertThat(issue.path()).isEqualTo("nodes[2].inputs.destinations[0].inputs.copies");
                        }));
        final var file = directory.resolve("railix.project.json");
        Files.writeString(file, source.replace("\"copies\":0", "\"copies\":2"));
        try (var creator = start(file)) {
            final var view = awaitExampleView(creator.baseUri(), "command:0");
            assertThat(view.statusCode()).as(view.body()).isEqualTo(200);
            assertThat(view.body()).contains("\"succeeded\"");
            final var write = request(creator.baseUri(), "GET", "/api/examples/command:0/steps/3", "");
            assertThat(write.statusCode()).as(write.body()).isEqualTo(200);
            assertThat(write.body()).contains("sent", "\"occurrence\":1");
        }
    }

    @Test
    void aRetainedContinuationCannotRunOnTheNextRequest() throws Exception {
        final String source = """
                {"format":1,"id":"stale","nodes":[
                {"id":"app","use":"railix.app","inputs":{}},
                {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{"name":"order","payload":[]}]},
                {"id":"fork","use":"test.late-fork","inputs":{},"receives":{"mode":["context","mode"]}}],
                "links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"fork"},{"from":"fork.next","to":"end"}]}
                """;
        try (var app = GeneratedApplicationFixture.start(directory, source, definitions(), classes())) {
            assertThat(app.runTest("command", "{\"payload\":[],\"mode\":\"capture\"}").status()).isEqualTo(200);
            final var response = app.runTest("command", "{\"payload\":[],\"mode\":\"reuse\"}");
            assertThat(response.status()).as(response.body()).isEqualTo(500);
            assertThat(response.body()).contains("STEP_IMPLEMENTATION_FAULT", "\"fork\"");
        }
    }

    @Test
    void creatorLocatesOnlyTheFailedPathAndKeepsBranchMetricsSuccessful() throws Exception {
        final String source = project("parallel", false, """
                {"id":"left","use":"test.invalid-output","inputs":{},"returns":{"value":["context","bad"]}},
                {"id":"right","use":"test.write","inputs":{"field":["context","good"],"value":"done"}}
                """);
        final var file = directory.resolve("railix.project.json");
        Files.writeString(file, source);
        GeneratedApplicationFixture.installedCatalog(directory, definitions(), classes());
        try (var creator = start(file)) {
            final var view = awaitExampleView(creator.baseUri(), "command:0");
            assertThat(view.statusCode()).as(view.body()).isEqualTo(200);
            assertThat(view.body()).contains("STEP_OUTPUT_INVALID");
            final var right = request(creator.baseUri(), "GET", "/api/examples/command:0/steps/4", "");
            assertThat(right.statusCode()).as(right.body()).isEqualTo(200);
            assertThat(right.body()).contains("\"succeeded\"", "\"done\"");
            final var scene = object(request(creator.baseUri(), "GET", "/api/scene?scale=100", "").body());
            final var observation = request(creator.baseUri(), "GET", "/api/scene/observations?scale=100&revision="
                    + string(scene, "revision"), "");
            assertThat(observation.statusCode()).as(observation.body()).isEqualTo(200);
            final var entries = (RailixValue.ArrayValue) object(observation.body()).values().get("nodes");
            final var branch = entries.values().stream().map(RailixValue.ObjectValue.class::cast)
                    .filter(node -> RailixValue.string("branch").equals(node.values().get("id"))).findFirst().orElseThrow();
            assertThat(number((RailixValue.ObjectValue) branch.values().get("metrics"), "errors")).isZero();
            assertThat(string(application(creator.baseUri()), "state")).isEqualTo("running");
        }
    }

    @Test
    void cancelledFutureDoesNotReleaseResourcesWhileItsWorkerStillRuns() throws Exception {
        final String source = project("parallel", false, """
                {"id":"left","use":"test.hold","inputs":{}},
                {"id":"right","use":"test.write","inputs":{"field":["context","right"],"value":2}}
                """).replace("\"use\":\"railix.branch\"", "\"use\":\"test.cancel-fork\"")
                .replace("\"selection\":{\"option\":\"all\",\"inputs\":{}},", "")
                .replace("\"execution\":{\"option\":\"parallel\",\"inputs\":{}},", "")
                .replace("\"routes\":[{\"outcome\":\"left\",\"groups\":[],\"inputs\":{\"own_context\":false}},{\"outcome\":\"right\",\"groups\":[],\"inputs\":{\"own_context\":false}}]", "");
        final var file = directory.resolve("railix.project.json");
        Files.writeString(file, source);
        final var catalog = GeneratedApplicationFixture.installedCatalog(directory, definitions(), classes());
        final var compiled = ProjectCompiler.compileApplication(source, catalog);
        assertThat(compiled).isInstanceOf(CompileResult.Compiled.class);
        final var jar = ApplicationBuilder.buildProduction(file, (CompileResult.Compiled) compiled).jar();
        final String name = "dev.nanonative.railix.core.project.DrainProbe";
        final var probe = CreatorFirstExecutionArtifacts.compileProbe(directory.resolve("probe"), jar, name, """
                package dev.nanonative.railix.core.project;
                import dev.nanonative.railix.core.runtime.RunResult;
                import dev.nanonative.railix.core.value.RailixValue;
                import thirdparty.production.BranchingSteps.Hold;
                import java.util.*;
                import java.util.concurrent.*;
                import java.time.Duration;
                public final class DrainProbe {
                    public static void main(String[] arguments) throws Exception {
                        final RuntimeApplication app = RailixApplication.runtime();
                        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                            final var result = pool.submit(() -> app.runSource("application.arguments", Map.of("arguments", RailixValue.array(List.of()))));
                            try {
                                if (!Hold.CANCELLED.await(10, TimeUnit.SECONDS)) throw new AssertionError("Work was not cancelled");
                                final var stopped = app.stop(Duration.ZERO);
                                if (!(stopped instanceof RunResult.Failed failure)
                                        || !failure.failure().code().equals("APPLICATION_DRAIN_TIMEOUT")) throw new AssertionError(stopped);
                                if (result.isDone() || Hold.CLOSED.get()) throw new AssertionError("Premature cleanup");
                            } finally { Hold.RELEASE.countDown(); }
                            result.get(10, TimeUnit.SECONDS);
                            if (!(app.stop() instanceof RunResult.Succeeded) || !Hold.CLOSED.get()) throw new AssertionError("Cleanup missing");
                            System.out.print("CANCEL|JOIN|DRAIN|CLEANUP");
                        } finally { Hold.RELEASE.countDown(); app.stop(); }
                    }
                }
                """);
        final var observed = CreatorFirstExecutionArtifacts.runProbe(probe, jar, name);
        assertThat(observed.exitCode()).as(observed.output()).isZero();
        assertThat(observed.output()).isEqualTo("CANCEL|JOIN|DRAIN|CLEANUP");
    }

    @ParameterizedTest
    @CsvSource({"first,false", "matching,true"})
    void matchingRoutesAreSelectedBeforeTheIndependentPathsRun(final String mode, final boolean both) throws Exception {
        final String source = project("sequential", false, """
                {"id":"left","use":"test.write","inputs":{"field":["context","left"],"value":1}},
                {"id":"right","use":"test.write","inputs":{"field":["context","right"],"value":2}}
                """).replace("\"option\":\"all\"", "\"option\":\"" + mode + "\"")
                .replace("\"groups\":[]", "\"groups\":[[{\"option\":\"literal\",\"inputs\":{\"value\":true}}]]");
        try (var app = GeneratedApplicationFixture.start(directory, source, definitions(), classes())) {
            final var response = app.runTest("command", "{\"payload\":[]}");
            final var context = (RailixValue.ObjectValue) object(response.body()).values().get("context");
            assertThat(context.values().get("left")).isEqualTo(RailixValue.number(1));
            assertThat(context.values().containsKey("right")).isEqualTo(both);
        }
    }

    @Test
    void laterCopiedPathSeesTheSplitSnapshotNotEarlierSiblingWrites() throws Exception {
        final String source = project("sequential", false, """
                {"id":"left","use":"test.write","inputs":{"field":["context","original"],"value":"changed"}},
                {"id":"right","use":"text.lowercase","inputs":{},"receives":{"value":["context","original"]},"returns":{"value":["context","observed"]}}
                """).replace("\"outcome\":\"right\",\"groups\":[],\"inputs\":{\"own_context\":false}",
                        "\"outcome\":\"right\",\"groups\":[],\"inputs\":{\"own_context\":true}")
                .replace("\"right.next\"", "\"right.ok\"");
        try (var app = GeneratedApplicationFixture.start(directory, source, definitions(), classes())) {
            final var response = app.runTest("command", "{\"payload\":[],\"original\":\"INITIAL\"}");
            assertThat(response.body()).contains("\"observed\":\"initial\"");
            final var context = (RailixValue.ObjectValue) object(response.body()).values().get("context");
            assertThat(context.values().get("original")).isEqualTo(RailixValue.string("changed"));
            assertThat(context.values()).doesNotContainKey("observed");
        }
    }

    @ParameterizedTest
    @CsvSource({"first,false,false", "matching,true,false", "all,false,true"})
    void selectionEvaluatesOnlyTheConditionsItNeeds(final String selection, final boolean rejected,
                                                   final boolean both) throws Exception {
        final String source = project("sequential", false, """
                {"id":"left","use":"test.write","inputs":{"field":["context","left"],"value":1}},
                {"id":"right","use":"test.write","inputs":{"field":["context","right"],"value":2}}
                """).replace("\"option\":\"all\"", "\"option\":\"" + selection + "\"")
                .replace("\"outcome\":\"left\",\"groups\":[]", """
                        "outcome":"left","groups":[[{"option":"literal","inputs":{"value":true}}]]
                        """.strip())
                .replace("\"outcome\":\"right\",\"groups\":[]", """
                        "outcome":"right","groups":[[{"option":"field","inputs":{"field":["context","probe"]},
                        "when":{"transforms":[],"all":[[{"use":"text.is-empty","inputs":{}}]]}}]]
                        """.strip());
        try (var app = GeneratedApplicationFixture.start(directory, source, definitions(), classes())) {
            final var response = app.runTest("command", "{\"payload\":[],\"probe\":42}");
            if (rejected) {
                assertThat(response.status()).as(response.body()).isNotEqualTo(200);
                assertThat(response.body()).contains("RUN_NESTED_INPUT_INCOMPATIBLE");
            } else {
                assertThat(response.status()).as(response.body()).isEqualTo(200);
                final var context = (RailixValue.ObjectValue) object(response.body()).values().get("context");
                assertThat(context.values().get("left")).isEqualTo(RailixValue.number(1));
                assertThat(context.values().containsKey("right")).isEqualTo(both);
            }
        }
    }

    @Test
    void parallelTraceKeepsSuccessfulAndFailedStepStatesSeparate() throws Exception {
        final String source = project("parallel", false, """
                {"id":"left","use":"test.invalid-output","inputs":{},"returns":{"value":["context","bad"]}},
                {"id":"right","use":"test.write","inputs":{"field":["context","good"],"value":"done"}}
                """);
        try (var app = GeneratedApplicationFixture.start(directory, source, definitions(), classes())) {
            final var trace = app.trace("command", "{\"payload\":[]}");
            assertThat(trace.stream().filter(event -> RailixValue.string("path_start").equals(event.values().get("type"))))
                    .hasSize(2);
            assertThat(trace.stream().filter(event -> RailixValue.string("path_result").equals(event.values().get("type"))))
                    .hasSize(2);
            final var success = trace.stream().filter(event -> RailixValue.string("right").equals(event.values().get("id"))
                    && RailixValue.string("step_result").equals(event.values().get("type"))).findFirst().orElseThrow();
            assertThat(success.values().get("status")).isEqualTo(RailixValue.string("succeeded"));
            final var failure = trace.stream().filter(event -> RailixValue.string("left").equals(event.values().get("id"))
                    && RailixValue.string("step_result").equals(event.values().get("type"))).findFirst().orElseThrow();
            assertThat(failure.values().get("status")).isEqualTo(RailixValue.string("failed"));
            assertThat(failure.values()).containsKey("failure");
        }
    }

    @Test
    void productionJarExecutesThirdPartyParallelPathsWithoutDevelopmentRuntime() throws Exception {
        final String source = project("parallel", false, """
                {"id":"left","use":"test.atomic-write","inputs":{"left":["context","a"],"right":["context","b"],"unique":["context","left"],"value":1}},
                {"id":"right","use":"test.atomic-write","inputs":{"left":["context","a"],"right":["context","b"],"unique":["context","right"],"value":2}}
                """);
        final var file = directory.resolve("railix.project.json");
        Files.writeString(file, source);
        final var catalog = GeneratedApplicationFixture.installedCatalog(directory, definitions(), classes());
        final var compiled = ProjectCompiler.compileApplication(source, catalog);
        assertThat(compiled).isInstanceOf(CompileResult.Compiled.class);
        final var jar = ApplicationBuilder.buildProduction(file, (CompileResult.Compiled) compiled).jar();
        final String name = "dev.nanonative.railix.core.project.BranchProbe";
        final var probe = CreatorFirstExecutionArtifacts.compileProbe(directory.resolve("probe"), jar, name, """
                package dev.nanonative.railix.core.project;
                import dev.nanonative.railix.core.runtime.RunResult;
                import dev.nanonative.railix.core.value.RailixValue;
                import java.util.*;
                public final class BranchProbe {
                    public static void main(String[] arguments) {
                        final RuntimeApplication app = RailixApplication.runtime();
                        try {
                            final var response = app.runSource("application.arguments", Map.of("arguments", RailixValue.array(List.of())));
                            if (!(response.result() instanceof RunResult.Completed complete) || complete.paths().size() != 2)
                                throw new AssertionError(response);
                            final var context = complete.context().values();
                            if (!context.get("a").equals(context.get("b")) || !context.containsKey("left") || !context.containsKey("right"))
                                throw new AssertionError(context);
                            System.out.print("PARALLEL|ATOMIC|PRODUCTION");
                        } finally { app.stop(); }
                    }
                }
                """);
        final var observed = CreatorFirstExecutionArtifacts.runProbe(probe, jar, name);
        assertThat(observed.exitCode()).as(observed.output()).isZero();
        assertThat(observed.output()).isEqualTo("PARALLEL|ATOMIC|PRODUCTION");
        try (var entries = new java.util.jar.JarFile(jar.toFile())) {
            assertThat(entries.stream().map(java.util.zip.ZipEntry::getName))
                    .noneMatch(entry -> entry.contains("development/") || entry.contains("ExampleSuite"));
        }
    }

    @Test
    void sequentialFailureStaysOnItsPathAndTheOtherPathStillRuns() throws Exception {
        final String source = project("sequential", false, """
                {"id":"left","use":"test.invalid-output","inputs":{},"returns":{"value":["context","bad"]}},
                {"id":"right","use":"test.write","inputs":{"field":["context","good"],"value":"done"}}
                """);
        try (var app = GeneratedApplicationFixture.start(directory, source, definitions(), classes())) {
            final var response = app.runTest("command", "{\"payload\":[]}");
            assertThat(response.status()).as(response.body()).isEqualTo(200);
            final var body = object(response.body());
            assertThat(body.values().get("status")).isEqualTo(RailixValue.string("completed"));
            final var context = (RailixValue.ObjectValue) body.values().get("context");
            assertThat(context.values().get("good")).isEqualTo(RailixValue.string("done"));
            final var paths = (RailixValue.ArrayValue) body.values().get("paths");
            assertThat(paths.values()).hasSize(2);
            assertThat(response.body()).contains("STEP_OUTPUT_INVALID", "\"failed\"", "\"succeeded\"");
        }
    }

    @Test
    void parallelSharedPathsCommitWholeWritesAndPreserveUnrelatedFields() throws Exception {
        final String source = project("parallel", false, """
                {"id":"left","use":"test.atomic-write","inputs":{"left":["context","a"],"right":["context","b"],"unique":["context","left"],"value":1}},
                {"id":"right","use":"test.atomic-write","inputs":{"left":["context","a"],"right":["context","b"],"unique":["context","right"],"value":2}}
                """);
        try (var app = GeneratedApplicationFixture.start(directory, source, definitions(), classes())) {
            final var response = app.runTest("command", "{\"payload\":[]}");
            assertThat(response.status()).as(response.body()).isEqualTo(200);
            final var context = (RailixValue.ObjectValue) object(response.body()).values().get("context");
            assertThat(context.values().get("a")).isEqualTo(context.values().get("b"));
            assertThat(context.values().get("a")).isIn(RailixValue.number(1), RailixValue.number(2));
            assertThat(context.values().get("left")).isEqualTo(RailixValue.number(1));
            assertThat(context.values().get("right")).isEqualTo(RailixValue.number(2));
            final var trace = app.trace("command", "{\"payload\":[]}");
            for (final String id : List.of("left", "right")) {
                final var step = trace.stream().filter(event -> RailixValue.string(id).equals(event.values().get("id"))
                        && RailixValue.string("step_result").equals(event.values().get("type"))).findFirst().orElseThrow();
                assertThat(step.values()).containsKey("own_changes");
                assertThat(RailixJson.write(step.values().get("own_changes")))
                        .contains("\"" + id + "\"").doesNotContain("\"" + (id.equals("left") ? "right" : "left") + "\"");
            }
            final var branch = trace.stream().filter(event -> RailixValue.string("branch").equals(event.values().get("id"))
                    && RailixValue.string("step_result").equals(event.values().get("type"))).findFirst().orElseThrow();
            assertThat(branch.values().get("own_changes")).isEqualTo(RailixValue.array(List.of()));
        }
    }

    @Test
    void invalidWriteRollsBackTheWholeStepWithoutDiscardingASiblingsCommit() throws Exception {
        final String source = project("parallel", false, """
                {"id":"left","use":"test.atomic-write","inputs":{"left":["context","uncommitted"],"right":["context","locked","child"],"unique":["context","left"],"value":1}},
                {"id":"right","use":"test.atomic-write","inputs":{"left":["context","a"],"right":["context","b"],"unique":["context","right"],"value":2}}
                """);
        try (var app = GeneratedApplicationFixture.start(directory, source, definitions(), classes())) {
            final var response = app.runTest("command", "{\"payload\":[],\"locked\":4}");
            final var context = (RailixValue.ObjectValue) object(response.body()).values().get("context");
            assertThat(context.values()).doesNotContainKeys("uncommitted", "left");
            assertThat(context.values().get("locked")).isEqualTo(RailixValue.number(4));
            assertThat(context.values().get("right")).isEqualTo(RailixValue.number(2));
            assertThat(context.values().get("a")).isEqualTo(RailixValue.number(2));
        }
    }

    @Test
    void copiedPathsKeepTheirWritesOutOfTheParentContext() throws Exception {
        final String source = project("parallel", true, """
                {"id":"left","use":"test.write","inputs":{"field":["context","left"],"value":1}},
                {"id":"right","use":"test.write","inputs":{"field":["context","right"],"value":2}}
                """);
        try (var app = GeneratedApplicationFixture.start(directory, source, definitions(), classes())) {
            final var response = app.runTest("command", "{\"payload\":[],\"initial\":7}");
            assertThat(response.status()).as(response.body()).isEqualTo(200);
            final var context = (RailixValue.ObjectValue) object(response.body()).values().get("context");
            assertThat(context.values()).doesNotContainKeys("left", "right");
            assertThat(context.values().get("initial")).isEqualTo(RailixValue.number(7));
            assertThat(response.body()).contains("\"left\":1", "\"right\":2");
        }
    }

    @Test
    void installedStepCanExecuteTheSameContinuationsWithoutBuiltInBranchLogic() throws Exception {
        final String source = project("parallel", false, """
                {"id":"left","use":"test.write","inputs":{"field":["context","left"],"value":1}},
                {"id":"right","use":"test.write","inputs":{"field":["context","right"],"value":2}}
                """).replace("\"use\":\"railix.branch\"", "\"use\":\"test.fork\"")
                .replace("\"selection\":{\"option\":\"all\",\"inputs\":{}},", "")
                .replace("\"routes\":[{\"outcome\":\"left\",\"groups\":[],\"inputs\":{\"own_context\":false}},{\"outcome\":\"right\",\"groups\":[],\"inputs\":{\"own_context\":false}}]",
                        "\"context\":{\"option\":\"shared\",\"inputs\":{}}");
        try (var app = GeneratedApplicationFixture.start(directory, source, definitions(), classes())) {
            final var response = app.runTest("command", "{\"payload\":[]}");
            assertThat(response.status()).as(response.body()).isEqualTo(200);
            assertThat(response.body()).contains("\"left\":1", "\"right\":2", "\"completed\"");
        }
    }

    static List<StepDefinition> definitions() {
        return List.of(
                StepDefinition.named("test.write", "1")
                        .input("field", Input.path(WRITE)).input("value", Input.json(ValueShape.ANY))
                        .implementation(BranchingSteps.Write.class),
                StepDefinition.named("test.read", "1").receive("value", ValueShape.STRING).returns("value", ValueShape.STRING)
                        .input("source", Input.path(StepDefinition.PathAccess.READ)).implementation(BranchingSteps.Read.class),
                StepDefinition.named("test.program-fork", "1").outcome("update")
                        .input("seed", Input.json(ValueShape.STRING).defaultValue(RailixValue.string("seed")))
                        .input("field", Input.path(WRITE).defaultPath("context", "observed"))
                        .input("steps", Input.steps(StepDefinition.ValueSource.from("seed")))
                        .withContinuations().implementation(BranchingSteps.ProgramFork.class),
                StepDefinition.named("test.invalid-output", "1").returns("value", ValueShape.STRING)
                        .implementation(BranchingSteps.InvalidOutput.class),
                StepDefinition.named("test.atomic-write", "1")
                        .input("left", Input.path(WRITE)).input("right", Input.path(WRITE))
                        .input("unique", Input.path(WRITE)).input("value", Input.json(ValueShape.NUMBER))
                        .implementation(BranchingSteps.AtomicWrite.class),
                StepDefinition.named("test.hold", "1").implementation(BranchingSteps.Hold.class),
                StepDefinition.named("test.cancel-fork", "1").primaryOutcome("otherwise").outcome("left").outcome("right")
                        .withContinuations().implementation(BranchingSteps.CancelFork.class),
                StepDefinition.named("test.late-fork", "1").receive("mode", ValueShape.STRING)
                        .withContinuations().implementation(BranchingSteps.LateFork.class),
                StepDefinition.named("test.fork", "1").primaryOutcome("otherwise").outcome("left").outcome("right")
                        .input("execution", Input.options(Input.option("sequential"), Input.option("parallel")).defaultOption("sequential"))
                        .input("context", Input.options(Input.option("shared"), Input.option("copy")).defaultOption("shared"))
                        .withContinuations().implementation(BranchingSteps.Fork.class));
    }

    static Class<?>[] classes() {
        return new Class<?>[]{BranchingSteps.Write.class, BranchingSteps.InvalidOutput.class,
                BranchingSteps.AtomicWrite.class, BranchingSteps.Fork.class, BranchingSteps.Hold.class,
                BranchingSteps.CancelFork.class, BranchingSteps.LateFork.class, BranchingSteps.AuthoredRoutes.class,
                BranchingSteps.Read.class, BranchingSteps.ProgramFork.class};
    }

    static String project(final String execution, final boolean copy, final String children) {
        return """
                {"format":1,"id":"branching","nodes":[
                {"id":"app","use":"railix.app","inputs":{}},
                {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{"name":"order","payload":[]}]},
                {"id":"branch","use":"railix.branch","inputs":{"selection":{"option":"all","inputs":{}},"execution":{"option":"%s","inputs":{}},"routes":[{"outcome":"left","groups":[],"inputs":{"own_context":%s}},{"outcome":"right","groups":[],"inputs":{"own_context":%s}}]}},
                %s],"links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"branch"},
                {"from":"branch.otherwise","to":"end"},{"from":"branch.left","to":"left"},{"from":"branch.right","to":"right"},
                {"from":"left.next","to":"end"},{"from":"right.next","to":"end"}]}
                """.formatted(execution, copy, copy, children);
    }
}
