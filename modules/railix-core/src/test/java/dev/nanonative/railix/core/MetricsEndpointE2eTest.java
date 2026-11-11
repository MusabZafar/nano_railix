package dev.nanonative.railix.core;

import com.sun.net.httpserver.HttpServer;
import dev.nanonative.railix.core.runtime.MetricsEndpoint;
import dev.nanonative.railix.core.runtime.RuntimeMetrics;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

final class MetricsEndpointE2eTest {
    @Test
    void unfinishedQueryBodiesDoNotOccupyMetricReaders() throws Exception {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (final var endpoint = new MetricsEndpoint(new RuntimeMetrics("observation", new String[0], new String[0]), "test-token");
             final var executor = Executors.newVirtualThreadPerTaskExecutor();
             final var client = HttpClient.newHttpClient()) {
            server.createContext("/v1/metrics", endpoint);
            server.setExecutor(executor);
            server.start();
            try (final Socket first = new Socket("127.0.0.1", server.getAddress().getPort());
                 final Socket second = new Socket("127.0.0.1", server.getAddress().getPort())) {
                for (final Socket socket : new Socket[]{first, second}) {
                    socket.setSoTimeout(5_000);
                    socket.getOutputStream().write(("POST /v1/metrics/query HTTP/1.1\r\n"
                            + "Host: 127.0.0.1\r\nAuthorization: Bearer test-token\r\n"
                            + "Expect: 100-continue\r\nContent-Length: 2000\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                    final var response = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    assertThat(response.readLine()).contains("100 Continue");
                    for (String line = response.readLine(); line != null && !line.isEmpty(); line = response.readLine()) { }
                    socket.getOutputStream().write('{');
                    socket.getOutputStream().flush();
                }
                final URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/metrics");
                for (int read = 0; read < 32; read++) {
                    final var response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                                    .header("Authorization", "Bearer test-token").build(),
                            HttpResponse.BodyHandlers.ofString());
                    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                }
            } finally {
                endpoint.stopAdmission();
                server.stop(0);
            }
        }
    }
}
