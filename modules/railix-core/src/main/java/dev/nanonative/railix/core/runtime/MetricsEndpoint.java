package dev.nanonative.railix.core.runtime;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixValue;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.nanonative.railix.core.runtime.ManagementHttp.*;

/** The shared, authenticated read-only metric HTTP surface. It never invokes application Steps. */
public final class MetricsEndpoint implements HttpHandler, AutoCloseable {
    private final RuntimeMetrics metrics;
    private final byte[] authorization;
    private final Semaphore admission = new Semaphore(2);
    private final Semaphore bodies = new Semaphore(2);
    private final Semaphore unauthorizedBodies = new Semaphore(4);
    private final AtomicBoolean open = new AtomicBoolean(true);

    /**
     * Creates the same bounded metric handler for a standalone or Creator-owned application.
     * The caller owns the HTTP server and supplies the credential through its protected startup path.
     *
     * @param metrics application-owned measurements
     * @param token nonblank bearer credential, never stored in workflow values
     * @throws NullPointerException for a missing metric store
     * @throws IllegalArgumentException for a missing, blank or multiline credential
     */
    public MetricsEndpoint(final RuntimeMetrics metrics, final String token) {
        this.metrics = Objects.requireNonNull(metrics, "Metric store is required.");
        if (token == null || token.isBlank() || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0)
            throw new IllegalArgumentException("Metric access token must be nonblank and single-line.");
        authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Serves a bounded metric read or query, authenticating before admitting metric work.
     * @param exchange incoming request, closed on every exit
     * @throws IOException for a request or response transport failure
     */
    @Override
    public void handle(final HttpExchange exchange) throws IOException {
        try (exchange) {
            final boolean query = "/v1/metrics/query".equals(exchange.getRequestURI().getPath());
            if (!authorized(exchange, authorization)) {
                if (!query || admittedBody(exchange, unauthorizedBodies).isPresent())
                    send(exchange, 401, RailixValue.object(Map.of("status", RailixValue.string("unauthorized"))));
                return;
            }
            if (!(query ? "POST" : "GET").equals(exchange.getRequestMethod())) {
                send(exchange, 405, RailixValue.object(Map.of("status", RailixValue.string("method-not-allowed"))));
                return;
            }
            if (!open.get()) {
                send(exchange, 503, RailixValue.object(Map.of("status", RailixValue.string("unavailable"),
                        "reason", RailixValue.string("closed"))));
                return;
            }
            final Optional<byte[]> body = query ? admittedBody(exchange, bodies) : Optional.empty();
            if (query && body.isEmpty()) return;
            if (!open.get() || !admission.tryAcquire()) {
                send(exchange, 503, RailixValue.object(Map.of("status", RailixValue.string("unavailable"),
                        "reason", RailixValue.string(open.get() ? "saturated" : "closed"))));
                return;
            }
            try {
                if (query) {
                    if (body.orElseThrow().length > RailixData.DEFAULT_MAX_SOURCE_BYTES) {
                        send(exchange, 413, problem("QUERY_TOO_LARGE", "Query exceeds the 1048576-byte limit.", ""));
                        return;
                    }
                    final var normalized = RailixData.normalize(RailixData.Format.JSON, body.orElseThrow());
                    if (!(normalized instanceof RailixData.Normalized data)
                            || !(data.value() instanceof RailixValue.ObjectValue value)) {
                        send(exchange, 400, problem("INVALID_QUERY", normalized instanceof RailixData.Invalid invalid
                                ? invalid.message() : "Query must be an object.", ""));
                        return;
                    }
                    try {
                        send(exchange, 200, metrics.query(value), RailixData.DEFAULT_MAX_SOURCE_BYTES,
                                "QUERY_RESPONSE_TOO_LARGE", "Query response exceeds the 1048576-byte limit.");
                    } catch (final IllegalArgumentException invalid) {
                        send(exchange, 400, problem("INVALID_QUERY", invalid.getMessage(), ""));
                    }
                    return;
                }
                final String path = exchange.getRequestURI().getPath();
                final String node = path.startsWith("/v1/metrics/nodes/")
                        ? path.substring("/v1/metrics/nodes/".length()) : "";
                final boolean json = "/v1/metrics".equals(path) || "/v1/metrics/catalog".equals(path)
                        || "/v1/metrics/application".equals(path) || !node.isBlank();
                if (!json && !"/v1/metrics/prometheus".equals(path) && !"/v1/metrics/influx".equals(path)) {
                    send(exchange, 404, RailixValue.object(Map.of("status", RailixValue.string("not-found"))));
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", json ? "application/json; charset=utf-8"
                        : path.endsWith("/prometheus") ? "text/plain; version=0.0.4; charset=utf-8" : "text/plain; charset=utf-8");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(200, 0);
                try (final var output = new BufferedOutputStream(exchange.getResponseBody(), 16_384)) {
                    if (!node.isBlank()) metrics.writeNodeJson(output, node);
                    else switch (path) {
                        case "/v1/metrics" -> metrics.writeJson(output);
                        case "/v1/metrics/catalog" -> metrics.writeCatalogJson(output);
                        case "/v1/metrics/application" -> metrics.writeApplicationJson(output);
                        case "/v1/metrics/prometheus" -> metrics.writePrometheus(output);
                        default -> metrics.writeInflux(output);
                    }
                }
            } finally {
                admission.release();
            }
        }
    }

    /**
     * Stops new metric admission while preserving authenticated closed-state responses during drain.
     * @return this endpoint
     */
    public MetricsEndpoint stopAdmission() {
        open.set(false);
        return this;
    }

    /** Removes the retained bearer credential after the caller closes its server. */
    @Override
    public void close() {
        stopAdmission();
        Arrays.fill(authorization, (byte) 0);
    }
}
