package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded, deadline-controlled reads shared by owned and independently running applications. */
final class ApplicationReads {
    private ApplicationReads() { }

    static DevelopmentApplication.ObservationResponse response(final HttpClient client, final HttpRequest request,
                                                               final int limit, final String subject)
            throws InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException();
        final var pending = client.sendAsync(request,
                HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), limit));
        final var timeout = request.timeout().orElse(DevelopmentApplication.REQUEST_TIMEOUT);
        try {
            final var response = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            return new DevelopmentApplication.ObservationResponse(response.statusCode(),
                    response.headers().firstValue("Content-Type").orElse("application/json; charset=utf-8"), response.body());
        } catch (final ExecutionException | TimeoutException failure) {
            final String reason = failure instanceof TimeoutException ? "timed out after " + timeout.toMillis() + " ms."
                    : "was incomplete or exceeded " + limit + " bytes.";
            return new DevelopmentApplication.ObservationResponse(502, "application/json; charset=utf-8",
                    ("{\"message\":\"" + subject + " response " + reason + "\",\"status\":\"invalid\"}")
                            .getBytes(StandardCharsets.UTF_8));
        } finally { pending.cancel(true); }
    }

    static DevelopmentApplication.Response catalog(final DevelopmentApplication.Response response, final long pid)
            throws IOException {
        if (response.status() != 200) return response;
        if (!(RailixJson.parse(response.body()) instanceof RailixJson.Parsed json)
                || !(json.value() instanceof RailixValue.ObjectValue document)
                || !RailixValue.number(pid).equals(document.values().get("application_pid"))
                || !(document.values().get("metrics") instanceof RailixValue.ObjectValue definitions))
            throw new IOException("Metric catalog does not identify the captured application.");
        for (final var entry : definitions.values().entrySet()) {
            if (entry.getKey().isBlank() || !(entry.getValue() instanceof RailixValue.ObjectValue definition)
                    || !(definition.values().get("aggregation") instanceof RailixValue.StringValue aggregation)
                    || !java.util.Set.of("sum", "max", "none").contains(aggregation.value()))
                throw new IOException("Metric definition has an invalid identifier or aggregation.");
        }
        return response;
    }
}
