package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.runtime.MetricDefinition;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thirdparty.conformance.MetricStep;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

final class StepMetricsApplicationE2eTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void manyCustomMeasurementsStillProduceAnExecutableJar(final boolean examples,
                                                          @TempDir final Path directory) throws Exception {
        final var builder = StepDefinition.named("external.metric", "1")
                .receive("value", ValueShape.ANY).returns("value", ValueShape.ANY);
        definition().metrics().forEach(builder::metric);
        for (int metric = 0; metric < 96; metric++)
            builder.metric(MetricDefinition.counter("extra_" + metric, "Extra " + metric, "count"));
        final var catalog = GeneratedApplicationFixture.installedCatalog(directory,
                List.of(builder.implementation(MetricStep.class)), MetricStep.class);
        final var source = new StringBuilder("""
                {"format":1,"id":"metric-scale","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{"name":"input","payload":[]}]}
                """);
        for (int node = 0; node < 96; node++) source.append("""
                ,{"id":"metric-%d","use":"external.metric","inputs":{},
                  "receives":{"value":["context","payload"]},"returns":{"value":["context","payload"]}}
                """.formatted(node));
        source.append("],\"links\":[{\"from\":\"app.start\",\"to\":\"command\"},");
        for (int node = 0; node < 96; node++) source.append("{\"from\":\"%s.next\",\"to\":\"metric-%d\"},"
                .formatted(node == 0 ? "command" : "metric-" + (node - 1), node));
        source.append("{\"from\":\"metric-95.next\",\"to\":\"end\"}]}");
        final var compilation = ProjectCompiler.compileApplication(source.toString(), catalog);
        assertThat(compilation).isInstanceOf(CompileResult.Compiled.class);
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, source);
        final Path jar;
        if (examples) {
            try (final var build = ApplicationBuilder.build(project, (CompileResult.Compiled) compilation)) { jar = build.jar(); }
        } else jar = ApplicationBuilder.buildProduction(project, (CompileResult.Compiled) compilation).jar();
        final String main = "dev.nanonative.railix.core.project.MetricScaleProbe";
        final Path probe = CreatorFirstExecutionArtifacts.compileProbe(directory.resolve("probe"), jar, main, """
                package dev.nanonative.railix.core.project;
                import dev.nanonative.railix.core.runtime.RunResult;
                import dev.nanonative.railix.core.value.RailixValue;
                import java.util.List;
                import java.util.Map;
                public final class MetricScaleProbe {
                    public static void main(final String[] args) {
                        final ObservedApplication app = (ObservedApplication) RailixApplication.runtime();
                        try {
                            if (!(app.runSource("application.arguments", Map.of("arguments", RailixValue.array(List.of())))
                                .result() instanceof RunResult.Succeeded)) throw new AssertionError("ingress");
                        } finally { app.stop(); }
                        final var result = app.metrics().query(RailixValue.object(Map.of(
                            "steps", RailixValue.object(Map.of("all", RailixValue.array(List.of(
                                RailixValue.array(List.of(RailixValue.number(1), RailixValue.number(96))))))),
                            "flows", RailixValue.object(Map.of()),
                            "metrics", RailixValue.array(List.of(RailixValue.string("external.metric:processed"),
                                RailixValue.string("external.metric:starts"), RailixValue.string("external.metric:stops"),
                                RailixValue.string("external.metric:extra_95"))))));
                        final var values = (RailixValue.ObjectValue) ((RailixValue.ObjectValue) result.values().get("groups")).values().get("all");
                        for (final String metric : List.of("processed", "starts", "stops"))
                            if (!RailixValue.number(96).equals(values.values().get("external.metric:" + metric)))
                                throw new AssertionError(values);
                        if (!RailixValue.number(0).equals(values.values().get("external.metric:extra_95")))
                            throw new AssertionError(values);
                        final var catalog = new java.io.ByteArrayOutputStream();
                        try { app.metrics().writeCatalogJson(catalog); }
                        catch (final java.io.IOException failure) { throw new AssertionError(failure); }
                        final var description = (RailixValue.ObjectValue) ((dev.nanonative.railix.core.value.RailixJson.Parsed)
                            dev.nanonative.railix.core.value.RailixJson.parse(catalog.toString(java.nio.charset.StandardCharsets.UTF_8))).value();
                        final var declared = (RailixValue.ObjectValue) description.values().get("metrics");
                        if (declared.values().keySet().stream().filter(id -> id.startsWith("external.metric:")).count() != 100)
                            throw new AssertionError(declared);
                        System.out.println("ok");
                    }
                }
                """);
        final var result = CreatorFirstExecutionArtifacts.runProbe(probe, jar, main);
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).isEqualTo("ok");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void installedStaticStepsPublishMetricsFromLifecycleAndRealIngress(final boolean examples,
                                                                      @TempDir final Path directory) throws Exception {
        final var definition = definition();
        final var catalog = GeneratedApplicationFixture.installedCatalog(directory, List.of(definition), MetricStep.class);
        assertThat(catalog.find("external.metric").orElseThrow().metrics()).isEqualTo(definition.metrics());
        final String source = """
                {"format":1,"id":"custom-metrics","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},
                    "examples":[{"name":"example","payload":["VALUE"]}]},
                  {"id":"first","use":"external.metric","inputs":{},
                    "receives":{"value":["context","payload","arguments",0]},
                    "returns":{"value":["context","value"]}},
                  {"id":"disabled","use":"external.metric","metrics":false,"inputs":{},
                    "receives":{"value":["context","value"]},"returns":{"value":["context","value"]}},
                  {"id":"nested","use":"railix.field-manipulation","inputs":{
                    "field":["context","value"],"steps":[{"use":"external.metric","inputs":{}},
                      {"use":"external.metric","inputs":{}}]}}
                ],"links":[{"from":"app.start","to":"command"},
                  {"from":"command.next","to":"first"},{"from":"first.next","to":"disabled"},
                  {"from":"disabled.next","to":"nested"},{"from":"nested.next","to":"end"}]}
                """;
        final var compiledResult = ProjectCompiler.compileApplication(source, catalog);
        assertThat(compiledResult).isInstanceOf(CompileResult.Compiled.class);
        final var compiled = (CompileResult.Compiled) compiledResult;
        assertThat(compiled.productionApplicationSource()).contains("MetricStep::process")
                .doesNotContain("new thirdparty.conformance.MetricStep", "new MetricsEndpoint", "HttpServer.create");
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, source);
        final Path jar;
        if (examples) {
            try (final var build = ApplicationBuilder.build(project, compiled)) { jar = build.jar(); }
        } else jar = ApplicationBuilder.buildProduction(project, compiled).jar();
        try (final var archive = new JarFile(jar.toFile())) {
            assertThat(archive.getJarEntry("dev/nanonative/railix/core/step/StepDefinition.class")).isNull();
        }
        final String main = "dev.nanonative.railix.core.project.StepMetricProbe";
        final Path probe = CreatorFirstExecutionArtifacts.compileProbe(directory.resolve("probe"), jar, main, """
                package dev.nanonative.railix.core.project;
                import dev.nanonative.railix.core.runtime.RunResult;
                import dev.nanonative.railix.core.value.RailixValue;
                import java.util.List;
                import java.util.Map;
                import java.util.concurrent.Executors;
                public final class StepMetricProbe {
                    public static void main(final String[] arguments) throws Exception {
                        final ObservedApplication app = (ObservedApplication) RailixApplication.runtime();
                        try {
                            if (!(app.start() instanceof RunResult.Succeeded)) throw new AssertionError("start");
                            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                                final var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
                                for (int worker = 0; worker < 8; worker++) futures.add(executor.submit(() -> {
                                    for (int call = 0; call < 100; call++) {
                                        final var result = app.runSource("application.arguments", Map.of("arguments",
                                            RailixValue.array(List.of(RailixValue.string("VALUE")))));
                                        if (!(result.result() instanceof RunResult.Succeeded success)
                                            || !RailixValue.string("VALUE").equals(success.context().values().get("value")))
                                            throw new AssertionError(result);
                                    }
                                }));
                                for (final var future : futures) future.get();
                            }
                        } finally {
                            if (!(app.stop() instanceof RunResult.Succeeded)) throw new AssertionError("stop");
                        }
                        app.metrics().writeJson(System.out);
                    }
                }
                """);
        final var result = CreatorFirstExecutionArtifacts.runProbe(probe, jar, main);
        assertThat(result.exitCode()).as(result.output()).isZero();
        final var metrics = (RailixValue.ObjectValue) ((RailixJson.Parsed) RailixJson.parse(result.output())).value();
        final var steps = (RailixValue.ArrayValue) metrics.values().get("steps");
        assertThat(steps.values()).hasSize(3);
        for (final var value : steps.values()) {
            final var step = (RailixValue.ObjectValue) value;
            if (step.values().get("id").equals(RailixValue.string("command"))) continue;
            final int placements = step.values().get("id").equals(RailixValue.string("nested")) ? 2 : 1;
            assertThat(((RailixValue.ObjectValue) step.values().get("metrics")).values())
                    .containsEntry("external.metric:processed", RailixValue.number(800 * placements))
                    .containsEntry("external.metric:starts", RailixValue.number(placements))
                    .containsEntry("external.metric:stops", RailixValue.number(placements));
            assertThat(((RailixValue.ArrayValue) step.values().get("placements")).values()).hasSize(placements);
        }
    }
    static StepDefinition definition() {
        return StepDefinition.named("external.metric", "1")
                .receive("value", ValueShape.ANY).returns("value", ValueShape.ANY)
                .metric(MetricDefinition.counter("processed", "Processed", "count"))
                .metric(MetricDefinition.counter("starts", "Starts", "count"))
                .metric(MetricDefinition.counter("stops", "Stops", "count"))
                .metric(MetricDefinition.gauge("depth", "Depth", "count"))
                .implementation(MetricStep.class);
    }
}
