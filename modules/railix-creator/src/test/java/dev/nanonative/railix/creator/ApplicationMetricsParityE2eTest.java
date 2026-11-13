package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.stdlib.StandardLibrary;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

/** Operational measurements belong to the application, not to an Example-enabled launcher. */
final class ApplicationMetricsParityE2eTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realIngressPublishesTheSameMetricsWithOrWithoutExamples(final boolean examples,
                                                                @TempDir final Path directory) throws Exception {
        final Path project = directory.resolve("railix.project.json");
        final String source = """
                {"format":1,"id":"metric-parity","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},
                    "examples":[{"name":"example","payload":["EXAMPLE"]}]},
                  {"id":"lower","use":"text.lowercase","inputs":{},
                    "receives":{"value":["context","payload","arguments",0]},
                    "returns":{"value":["context","result"]}}
                ],"links":[{"from":"app.start","to":"command"},
                  {"from":"command.next","to":"lower"},{"from":"lower.ok","to":"end"}]}
                """;
        Files.writeString(project, source);
        final var compiled = (CompileResult.Compiled) ProjectCompiler.compileApplication(source, StandardLibrary.catalog());
        final Path jar;
        if (examples) {
            try (final var build = ApplicationBuilder.build(project, compiled)) {
                jar = build.jar();
            }
        } else jar = ApplicationBuilder.buildProduction(project, compiled).jar();
        Files.copy(jar, Files.createDirectories(Path.of("target", "runtime-parity"))
                .resolve(examples ? "with-examples.jar" : "without-examples.jar"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try (final JarFile archive = new JarFile(jar.toFile())) {
            assertThat(archive.getJarEntry("dev/nanonative/railix/core/runtime/RuntimeMetrics.class")).isNotNull();
            assertThat(archive.getJarEntry("META-INF/railix/examples.json") != null).isEqualTo(examples);
            if (!examples) assertThat(archive.stream().map(java.util.jar.JarEntry::getName))
                    .noneMatch(name -> name.startsWith("dev/nanonative/railix/development/"));
        }
        final String main = "dev.nanonative.railix.core.project.MetricParityProbe";
        final Path probe = CreatorFirstExecutionArtifacts.compileProbe(directory.resolve("probe"), jar, main, """
                package dev.nanonative.railix.core.project;
                import dev.nanonative.railix.core.runtime.RunResult;
                import dev.nanonative.railix.core.runtime.MetricsEndpoint;
                import dev.nanonative.railix.core.value.RailixValue;
                import com.sun.net.httpserver.HttpServer;
                import java.net.InetSocketAddress;
                import java.net.URI;
                import java.net.http.HttpClient;
                import java.net.http.HttpRequest;
                import java.net.http.HttpResponse;
                import java.time.Duration;
                import java.util.List;
                import java.util.Map;
                import java.util.concurrent.Executors;
                public final class MetricParityProbe {
                    public static void main(final String[] arguments) throws Exception {
                        final ObservedApplication application = (ObservedApplication) RailixApplication.runtime();
                        try {
                            for (int index = 0; index < 2; index++) {
                                final var result = application.runSource("application.arguments", Map.of(
                                    "arguments", RailixValue.array(List.of(RailixValue.string("RAILIX")))));
                                if (!(result.result() instanceof RunResult.Succeeded succeeded)
                                    || !RailixValue.string("railix").equals(succeeded.context().values().get("result")))
                                    throw new AssertionError(result);
                            }
                            final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                            try (final var endpoint = new MetricsEndpoint(application.metrics(), "metric-parity-token");
                                 final var executor = Executors.newVirtualThreadPerTaskExecutor();
                                 final var client = HttpClient.newHttpClient()) {
                                server.createContext("/v1/metrics", endpoint);
                                server.setExecutor(executor);
                                server.start();
                                try {
                                    final URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/metrics");
                                    final var denied = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                                        .build(), HttpResponse.BodyHandlers.ofString());
                                    if (denied.statusCode() != 401) throw new AssertionError(denied);
                                    final var invalid = client.send(HttpRequest.newBuilder(URI.create(uri + "/query"))
                                        .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer metric-parity-token")
                                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                        .build(), HttpResponse.BodyHandlers.ofString());
                                    if (invalid.statusCode() != 400) throw new AssertionError(invalid);
                                    final var response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                                        .header("Authorization", "Bearer metric-parity-token").build(), HttpResponse.BodyHandlers.ofString());
                                    if (response.statusCode() != 200) throw new AssertionError(response);
                                    System.out.print(response.body());
                                    endpoint.stopAdmission();
                                    final var draining = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                                        .header("Authorization", "Bearer metric-parity-token").build(), HttpResponse.BodyHandlers.ofString());
                                    if (draining.statusCode() != 503) throw new AssertionError(draining);
                                } finally { server.stop(0); }
                            }
                        } finally {
                            if (!(application.stop() instanceof RunResult.Succeeded)) throw new AssertionError("stop");
                        }
                    }
                }
                """);
        final var result = CreatorFirstExecutionArtifacts.runProbe(probe, jar, main);
        assertThat(result.exitCode()).as(result.output()).isZero();
        final var metrics = (RailixValue.ObjectValue) ((RailixJson.Parsed) RailixJson.parse(result.output())).value();
        for (final String category : new String[]{"flows", "steps"}) {
            final var series = (RailixValue.ArrayValue) metrics.values().get(category);
            assertThat(series.values()).hasSize(category.equals("flows") ? 1 : 2);
            for (final RailixValue entry : series.values()) {
                final var values = (RailixValue.ObjectValue) ((RailixValue.ObjectValue) entry).values().get("metrics");
                assertThat(values.values()).containsEntry("executions", RailixValue.number(2))
                        .containsEntry("errors", RailixValue.number(0));
            }
        }
    }
}
