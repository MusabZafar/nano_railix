package dev.nanonative.railix.core;

import com.sun.net.httpserver.HttpServer;
import dev.nanonative.railix.core.runtime.MetricDefinition;
import dev.nanonative.railix.core.runtime.MetricsEndpoint;
import dev.nanonative.railix.core.runtime.RuntimeMetrics;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class StepMetricsE2eTest {
    @Test
    void customMeasurementsUseTheExistingAuthenticatedEndpointAndExports() throws Exception {
        final var definitions = List.of(MetricDefinition.counter("processed", "Processed", "count"),
                MetricDefinition.gauge("depth", "Queue depth", "count"));
        final var metrics = new RuntimeMetrics("metrics", new String[0], new String[]{"first", "second"}, List.of(
                new RuntimeMetrics.Registration(0, "first", "example.queue", definitions),
                new RuntimeMetrics.Registration(1, "second", "example.queue", definitions)));
        metrics.step("first").counter("processed").add(3);
        metrics.step("second").counter("processed").increment();
        metrics.step("first").gauge("depth").set(1.5);
        metrics.step("second").gauge("depth").set(7);
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (final var endpoint = new MetricsEndpoint(metrics, "secret");
             final var executor = Executors.newVirtualThreadPerTaskExecutor();
             final var client = HttpClient.newHttpClient()) {
            server.createContext("/v1/metrics", endpoint);
            server.setExecutor(executor);
            server.start();
            try {
                final URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/metrics");
                assertThat(client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString())
                        .statusCode()).isEqualTo(401);
                for (final String path : List.of("/catalog", "/nodes/first", "/prometheus", "/influx")) {
                    final var response = client.send(HttpRequest.newBuilder(URI.create(uri + path))
                            .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer secret").build(),
                            HttpResponse.BodyHandlers.ofString());
                    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                    assertThat(response.body()).contains(path.equals("/prometheus") ? "railix_step_custom_" : "example.queue:processed");
                    if (path.equals("/nodes/first")) assertThat(response.body())
                            .contains("\"example.queue:processed\":3", "\"example.queue:depth\":1.5");
                    if (path.equals("/prometheus")) assertThat(response.body()).contains(
                            "# HELP railix_step_custom_counter_example_u002equeue_metric_processed_total example.queue:processed - Processed (count)",
                            "railix_step_custom_counter_example_u002equeue_metric_processed_total{project=\"metrics\",step=\"first\",placement=\"first\"} 3");
                }
                final var response = client.send(HttpRequest.newBuilder(URI.create(uri + "/query"))
                        .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer secret")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"steps":{"both":[[0,1]],"one":[[0,0]]},"flows":{},
                                 "metrics":["example.queue:processed","example.queue:depth"]}
                                """)).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                final var result = (RailixValue.ObjectValue) ((RailixJson.Parsed) RailixJson.parse(response.body())).value();
                final var groups = (RailixValue.ObjectValue) result.values().get("groups");
                assertThat(((RailixValue.ObjectValue) groups.values().get("both")).values())
                        .containsEntry("example.queue:processed", RailixValue.number(4))
                        .doesNotContainKey("example.queue:depth");
                assertThat(((RailixValue.ObjectValue) groups.values().get("one")).values())
                        .containsEntry("example.queue:depth", RailixValue.number(new java.math.BigDecimal("1.5")));
            } finally { server.stop(0); }
        }
    }

    @Test
    void repeatedNestedGaugesRemainIndividuallyAvailableWithoutInventingAnAggregate() throws Exception {
        final var definition = List.of(MetricDefinition.gauge("depth", "Depth", "items"));
        final var metrics = new RuntimeMetrics("nested", new String[0], new String[]{"owner"}, List.of(
                new RuntimeMetrics.Registration(0, "owner.steps[0]", "example.queue", definition),
                new RuntimeMetrics.Registration(0, "owner.steps[1]", "example.queue", definition)));
        metrics.step("owner.steps[0]").gauge("depth").set(1.5);
        metrics.step("owner.steps[1]").gauge("depth").set(7.5);
        final var output = new java.io.ByteArrayOutputStream();
        metrics.writeNodeJson(output, "owner");
        final var json = (RailixValue.ObjectValue) ((RailixJson.Parsed) RailixJson.parse(output.toString(java.nio.charset.StandardCharsets.UTF_8))).value();
        final var owner = (RailixValue.ObjectValue) ((RailixValue.ArrayValue) json.values().get("steps")).values().getFirst();
        assertThat(((RailixValue.ObjectValue) owner.values().get("metrics")).values()).doesNotContainKey("example.queue:depth");
        assertThat(((RailixValue.ArrayValue) owner.values().get("placements")).values()).hasSize(2);
        output.reset();
        metrics.writePrometheus(output);
        assertThat(output.toString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("placement=\"owner.steps[0]\"} 1.5", "placement=\"owner.steps[1]\"} 7.5");
        output.reset();
        metrics.writeInflux(output);
        assertThat(output.toString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("placement=owner.steps[0],metric=example.queue:depth value=1.5",
                        "placement=owner.steps[1],metric=example.queue:depth value=7.5");
    }

    @Test
    void countersAreExactUnderConcurrencyAndDisabledMeasurementsStayAbsent() throws Exception {
        final var definitions = List.of(MetricDefinition.counter("processed", "Processed", "count"),
                MetricDefinition.gauge("depth", "Depth", "count"));
        final var metrics = new RuntimeMetrics("metrics", new String[0], new String[]{"first"}, List.of(
                new RuntimeMetrics.Registration(0, "enabled", "example.queue", definitions),
                new RuntimeMetrics.Registration(-1, "disabled", "example.disabled", definitions)));
        final var counter = metrics.step("enabled").counter("processed");
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int worker = 0; worker < 16; worker++) executor.submit(() -> {
                for (int call = 0; call < 10_000; call++) counter.increment();
            });
        }
        metrics.step("disabled").counter("processed").add(500);
        metrics.step("disabled").gauge("depth").set(10);
        final var output = new java.io.ByteArrayOutputStream();
        metrics.writeJson(output);
        assertThat(output.toString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("\"example.queue:processed\":160000")
                .doesNotContain("example.disabled:", "example.queue:depth");
        output.reset();
        metrics.writePrometheus(output);
        assertThat(output.toString(java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("railix_step_custom_gauge_example_u002equeue_metric_depth{");
        output.reset();
        metrics.writeInflux(output);
        assertThat(output.toString(java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("metric=example.queue:depth value");
        assertThatThrownBy(() -> counter.add(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metrics.step("enabled").gauge("depth").set(Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metrics.step("enabled").counter("depth"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metrics.step("enabled").counter("typo"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Bad", "1value", "has space", "path.value"})
    void metricNamesCannotBeInvocationPathsOrDynamicLabels(final String name) {
        assertThatThrownBy(() -> MetricDefinition.counter(name, "Count", "count"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void declarationsRejectDuplicatesAndConflictsBeforeAnyStepRuns() {
        final var count = MetricDefinition.counter("calls", "Calls", "count");
        assertThatThrownBy(() -> dev.nanonative.railix.core.step.StepDefinition.named("test.metrics", "1")
                .metric(count).metric(count).define()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuntimeMetrics("test", new String[0], new String[]{"step"}, List.of(
                new RuntimeMetrics.Registration(0, "first", "test.step", List.of(count)),
                new RuntimeMetrics.Registration(0, "second", "test.step", List.of(
                        MetricDefinition.gauge("calls", "Calls", "count"))))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void counterSuffixesCannotCollideWithGaugeNamesInPrometheus() throws Exception {
        final var metrics = new RuntimeMetrics("test", new String[0], new String[]{"step"}, List.of(
                new RuntimeMetrics.Registration(0, "step", "test.step", List.of(
                        MetricDefinition.counter("requests", "Requests", "count"),
                        MetricDefinition.gauge("requests_total", "Current requests", "count")))));
        metrics.step("step").counter("requests").increment();
        metrics.step("step").gauge("requests_total").set(2);
        final var output = new java.io.ByteArrayOutputStream();
        metrics.writePrometheus(output);
        assertThat(output.toString(java.nio.charset.StandardCharsets.UTF_8)).contains(
                "# TYPE railix_step_custom_counter_test_u002estep_metric_requests_total counter",
                "# TYPE railix_step_custom_gauge_test_u002estep_metric_requests_total gauge");
    }

    @Test
    void countersSaturateRatherThanBecomingNegativeAndGaugesSupportExplicitAggregation() throws Exception {
        final var metrics = new RuntimeMetrics("test", new String[0], new String[]{"first", "second"}, List.of(
                new RuntimeMetrics.Registration(0, "first", "test.step", List.of(
                        MetricDefinition.counter("calls", "Calls", "count"),
                        MetricDefinition.gauge("load", "Load", "ratio").aggregate(MetricDefinition.Aggregation.MAX))),
                new RuntimeMetrics.Registration(1, "second", "test.step", List.of(
                        MetricDefinition.gauge("load", "Load", "ratio").aggregate(MetricDefinition.Aggregation.MAX)))));
        metrics.step("first").counter("calls").add(Long.MAX_VALUE).increment();
        metrics.step("first").gauge("load").set(-1.5);
        metrics.step("second").gauge("load").set(2.5);
        final var query = (RailixValue.ObjectValue) ((RailixJson.Parsed) RailixJson.parse(
                "{\"steps\":{\"all\":[[0,1]]},\"flows\":{},\"metrics\":[\"test.step:load\",\"test.step:calls\"]}")).value();
        final var values = (RailixValue.ObjectValue) ((RailixValue.ObjectValue) metrics.query(query).values().get("groups")).values().get("all");
        assertThat(values.values()).containsEntry("test.step:calls", RailixValue.number(Long.MAX_VALUE))
                .containsEntry("test.step:load", RailixValue.number(new java.math.BigDecimal("2.5")));
    }
}
