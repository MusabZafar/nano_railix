package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.stdlib.StandardLibrary;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Port selection and measurement through compiled applications and real HTTP ingress. */
final class HttpPortApplicationE2eTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"false,occupied", "true,occupied", "false,strict", "true,strict",
            "false,zero", "true,zero", "false,address", "true,address",
            "false,boundary", "true,boundary", "false,free", "true,free",
            "false,concurrent", "true,concurrent", "false,host", "true,host",
            "false,disabled", "true,disabled"})
    void generatedApplicationsUseTheConfiguredPortPolicy(final boolean examples, final String scenario) throws Exception {
        final List<ServerSocket> held = new ArrayList<>();
        try {
            int port = 0;
            if (scenario.equals("boundary")) {
                try { held.add(new ServerSocket(65535, 1, InetAddress.getByName("127.0.0.1"))); }
                catch (final java.net.BindException occupied) { /* An existing owner also exercises exhaustion. */ }
                port = 65535;
            } else if (!List.of("zero", "address", "host", "disabled").contains(scenario)) {
                final int count = scenario.equals("concurrent") ? 4 : 3;
                for (int attempt = 0; held.size() != count && attempt < 32; attempt++) {
                    for (final var socket : held) socket.close();
                    held.clear();
                    final var first = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                    held.add(first);
                    port = first.getLocalPort();
                    if (port > 65535 - count) continue;
                    try {
                        while (held.size() < count)
                            held.add(new ServerSocket(port + held.size(), 1, InetAddress.getByName("127.0.0.1")));
                    } catch (final java.net.BindException occupied) { continue; }
                }
                assertThat(held).as("consecutive reserved ports").hasSize(count);
            }
            if (scenario.equals("address") || scenario.equals("host")) port = 8080;
            final String host = scenario.equals("address") ? "192.0.2.1" : scenario.equals("host") ? "[invalid" : "127.0.0.1";
            final int expected = scenario.equals("occupied") ? port + 2
                    : List.of("strict", "address", "boundary", "host").contains(scenario) ? -1 : port;
            final String source = """
                    {"format":1,"id":"port-policy","nodes":[
                      {"id":"app","use":"railix.app","inputs":{}},
                      {"id":"request","use":"railix.trigger.http","inputs":{"host":"%s","port":%d%s}%s,
                       "examples":[{"name":"request","payload":{}}]}
                    ],"links":[{"from":"app.start","to":"request"},{"from":"request.next","to":"end"}]}
                    """.formatted(host, port, scenario.equals("strict") ? ",\"next_free_port\":false" : "",
                    scenario.equals("disabled") ? ",\"metrics\":false" : "");
            final Path project = directory.resolve("railix.project.json");
            Files.writeString(project, source);
            final var result = ProjectCompiler.compileApplication(source, StandardLibrary.catalog());
            assertThat(result).isInstanceOf(CompileResult.Compiled.class);
            final Path jar;
            if (examples) {
                try (var build = ApplicationBuilder.build(project, (CompileResult.Compiled) result)) { jar = build.jar(); }
            } else jar = ApplicationBuilder.buildProduction(project, (CompileResult.Compiled) result).jar();
            final String main = "dev.nanonative.railix.core.project.HttpPortProbe";
            final Path probe = CreatorFirstExecutionArtifacts.compileProbe(directory.resolve("probe"), jar, main, """
                    package dev.nanonative.railix.core.project;
                    import dev.nanonative.railix.core.runtime.RunResult;
                    import dev.nanonative.railix.core.value.RailixValue;
                    import dev.nanonative.railix.core.value.RailixJson;
                    import java.net.*;
                    import java.net.http.*;
                    import java.time.Duration;
                    import java.util.List;
                    public final class HttpPortProbe {
                        public static void main(final String[] args) throws Exception {
                            final int expected = Integer.parseInt(args[0]), preferred = Integer.parseInt(args[1]);
                            final ObservedApplication app = (ObservedApplication) RailixApplication.runtime();
                            int active = 0;
                            try {
                                final var started = app.launch(List.of());
                                final var output = new java.io.ByteArrayOutputStream();
                                app.metrics().writeNodeJson(output, "request");
                                if (args[2].equals("disabled")) {
                                    if (!(started instanceof RunResult.Succeeded)) throw new AssertionError(started);
                                    if (output.toString().contains("railix.trigger.http:")) throw new AssertionError(output);
                                    System.out.println("metrics-disabled");
                                    return;
                                }
                                final var json = (RailixValue.ObjectValue) ((RailixJson.Parsed) RailixJson.parse(output.toString())).value();
                                final var step = (RailixValue.ObjectValue) ((RailixValue.ArrayValue) json.values().get("steps")).values().getFirst();
                                final var values = (RailixValue.ObjectValue) step.values().get("metrics");
                                if (expected < 0) {
                                    if (!(started instanceof RunResult.Failed)) throw new AssertionError(started);
                                    if (!((RunResult.Failed) started).failure().stepId().equals("request")) throw new AssertionError(started);
                                    if (values.values().containsKey("railix.trigger.http:active_port")) throw new AssertionError(values);
                                    System.out.println("failed-cleanly");
                                    return;
                                }
                                if (!(started instanceof RunResult.Succeeded)) throw new AssertionError(started);
                                if (!RailixValue.number(preferred).equals(values.values().get("railix.trigger.http:preferred_port")))
                                    throw new AssertionError(values);
                                active = ((RailixValue.NumberValue) values.values().get("railix.trigger.http:active_port")).value().intValueExact();
                                if (active <= 0 || expected > 0 && (args[2].equals("occupied") || args[2].equals("concurrent")
                                        ? active < expected : active != expected)) throw new AssertionError(values);
                                try (var client = HttpClient.newHttpClient()) {
                                    final var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + active + "/"))
                                            .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
                                    if (response.statusCode() != 200) throw new AssertionError(response);
                                }
                                if (args.length > 3) {
                                    final var marker = java.nio.file.Path.of(args[3]);
                                    java.nio.file.Files.writeString(marker, Integer.toString(active));
                                    while (!java.nio.file.Files.exists(java.nio.file.Path.of(args[3] + ".release"))) Thread.sleep(5);
                                }
                            } finally { app.stop(); }
                            try (var released = new ServerSocket(active, 1, InetAddress.getByName("127.0.0.1"))) {
                                if (!released.isBound()) throw new AssertionError("listener leak");
                            }
                            System.out.println("served-and-released");
                        }
                    }
                    """);
            while (held.size() > 2) held.removeLast().close();
            if (scenario.equals("free")) {
                for (final var socket : held) socket.close();
                held.clear();
            }
            if (scenario.equals("concurrent")) {
                final String preferred = Integer.toString(port);
                final Path first = directory.resolve("first"), second = directory.resolve("second");
                try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                    final String minimum = Integer.toString(port + 2);
                    final var one = executor.submit(() -> CreatorFirstExecutionArtifacts.runProbe(probe, jar, main, minimum, preferred, scenario, first.toString()));
                    final var two = executor.submit(() -> CreatorFirstExecutionArtifacts.runProbe(probe, jar, main, minimum, preferred, scenario, second.toString()));
                    try {
                        LiveApplicationE2eTest.await(() -> {
                            assertThat(first).isRegularFile();
                            assertThat(second).isRegularFile();
                            assertThat(Files.readString(first)).matches("[1-9][0-9]*");
                            assertThat(Files.readString(second)).matches("[1-9][0-9]*");
                        });
                        assertThat(Integer.parseInt(Files.readString(first))).isGreaterThanOrEqualTo(port + 2)
                                .isNotEqualTo(Integer.parseInt(Files.readString(second)));
                    } finally {
                        Files.writeString(Path.of(first + ".release"), "release");
                        Files.writeString(Path.of(second + ".release"), "release");
                    }
                    for (final var invocation : List.of(one, two)) {
                        final var resultOfRun = invocation.get();
                        assertThat(resultOfRun.exitCode()).as(resultOfRun.output()).isZero();
                    }
                }
                return;
            }
            final var execution = CreatorFirstExecutionArtifacts.runProbe(probe, jar, main,
                    Integer.toString(expected), Integer.toString(port), scenario);
            assertThat(execution.exitCode()).as(execution.output()).isZero();
            assertThat(execution.output()).contains(scenario.equals("disabled") ? "metrics-disabled"
                    : expected < 0 ? "failed-cleanly" : "served-and-released");
            if (!examples && List.of("boundary", "address").contains(scenario)) {
                final var mainResult = CreatorFirstExecutionArtifacts.runJar(jar);
                assertThat(mainResult.exitCode()).isNotZero();
                assertThat(mainResult.output()).contains("SOURCE_START_FAILED request");
                if (scenario.equals("boundary")) assertThat(mainResult.output()).contains("No available HTTP port");
                else assertThat(mainResult.output()).containsAnyOf("Cannot assign requested address", "Can't assign requested address");
            }
            assertThat(Files.readString(project)).isEqualTo(source);
        } finally { for (final var socket : held) socket.close(); }
    }
}
