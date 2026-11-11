package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.value.RailixValue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns either a compiled local build or its running application, never an observed app. */
sealed interface LocalApplication extends AutoCloseable permits DevelopmentApplication, LocalApplication.Prepared {
    ApplicationBuilder.Artifact artifact();
    RailixValue.ObjectValue snapshot();
    default boolean running() { return false; }
    default DevelopmentApplication.Response metrics() throws IOException {
        return new DevelopmentApplication.Response(503, "{\"status\":\"unavailable\",\"reason\":\"not-running\"}");
    }
    default DevelopmentApplication.Response metrics(final String node) throws IOException { return metrics(); }
    default DevelopmentApplication.Response metricCatalog() throws IOException { return metrics(); }
    default DevelopmentApplication.Response metricCatalog(final long deadline) throws IOException { return metrics(); }
    default DevelopmentApplication.ObservationResponse observationQuery(final String read,
            final RailixValue.ObjectValue query, final long deadline) throws IOException {
        return examples("", deadline);
    }
    default DevelopmentApplication.ObservationResponse examples(final String path) throws IOException {
        return examples(path, 0);
    }
    default DevelopmentApplication.ObservationResponse examples(final String path, final long deadline) throws IOException {
        return new DevelopmentApplication.ObservationResponse(503, "application/json",
                metrics().body().getBytes(StandardCharsets.UTF_8));
    }
    @Override void close();

    /** Holds an independent publication lease without starting a JVM or any Step lifecycle. */
    final class Prepared implements LocalApplication {
        private final ApplicationBuilder.DevelopmentBuild publication;
        private final AtomicBoolean closed = new AtomicBoolean();

        Prepared(final ApplicationBuilder.Artifact artifact) throws IOException {
            publication = ApplicationBuilder.reserve(artifact);
        }

        @Override public ApplicationBuilder.Artifact artifact() { return publication.artifact(); }
        @Override public RailixValue.ObjectValue snapshot() {
            final var artifact = artifact();
            return RailixValue.object(Map.of(
                    "state", RailixValue.string(closed.get() ? "stopped" : "ready"),
                    "fingerprint", RailixValue.string(artifact.fingerprint()),
                    "built_at", RailixValue.number(artifact.builtAt()),
                    "build_path", RailixValue.string(artifact.jar().toString()),
                    "source_path", RailixValue.string(artifact.source().toString()),
                    "classes_path", RailixValue.string(artifact.classes().toString()),
                    "pid", RailixValue.number(0)));
        }
        @Override public void close() {
            try {
                publication.close();
                closed.set(true);
            } catch (final IOException failure) {
                throw new IllegalStateException("Prepared application build could not be released.", failure);
            }
        }
    }
}
