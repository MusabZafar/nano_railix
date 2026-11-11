package dev.nanonative.railix.core.step;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thirdparty.portable.PortableSteps;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class StepBundleE2eTest {
    @TempDir
    Path directory;

    @Test
    void exportedProviderImportsAppAndDirectCallableStepsThroughTheOrdinaryLock() throws Exception {
        final Path bundle = export("first");
        final Path store = directory.resolve("store");
        final String lock = StepCatalog.bundleLock(bundle, store);

        final var catalog = StepCatalog.of().installSource(lock, store);

        assertThat(catalog.definitions()).extracting(StepDefinition::id).containsExactly("portable.app", "portable.echo");
        assertThat(catalog.implementation("portable.app")).isEmpty();
        assertThat(catalog.implementation("portable.echo").orElseThrow().processProblem()).isEmpty();
        assertThat(catalog.validationProvider("portable.echo").orElseThrow().definitionsProblem()).isEmpty();
        assertThat(lock).doesNotContain(directory.toString());
    }

    @Test
    void exportAndRepeatedImportAreDeterministicAcrossDifferentLocations() throws Exception {
        final Path first = export("first");
        final Path second = export("second");
        final Path store = directory.resolve("store");

        assertThat(Files.readAllBytes(first)).isEqualTo(Files.readAllBytes(second));
        assertThat(StepCatalog.bundleLock(first, store)).isEqualTo(StepCatalog.bundleLock(second, store));
        try (var files = Files.list(store)) {
            assertThat(files.toList()).hasSize(1);
        }
    }

    @Test
    void concurrentImportsPublishOneImmutableArtifactAndLeaveNoTemporaryFiles() throws Exception {
        final Path bundle = export("first");
        final Path store = directory.resolve("store");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final List<Callable<String>> imports = java.util.stream.IntStream.range(0, 8)
                    .<Callable<String>>mapToObj(ignored -> () -> StepCatalog.bundleLock(bundle, store)).toList();
            final var results = executor.invokeAll(imports);
            final String expected = results.getFirst().get();
            for (final var result : results) assertThat(result.get()).isEqualTo(expected);
        }
        try (var files = Files.list(store)) {
            assertThat(files.toList()).singleElement().satisfies(file -> assertThat(file.getFileName().toString()).matches("[0-9a-f]{64}\\.jar"));
        }
    }

    @Test
    void repeatedRuntimeArtifactIsRejectedWithTheSameDiagnosticBeforePublication() throws Exception {
        final Path bundle = export("first");
        final Path store = directory.resolve("store");
        assertThatThrownBy(() -> StepCatalog.bundleLock(bundle, store, bundle))
                .isInstanceOf(StepCatalog.DependencyException.class).hasMessageStartingWith("DEPENDENCY_LOCK_NON_CANONICAL:");
        try (var files = Files.list(store)) { assertThat(files.toList()).isEmpty(); }
    }

    @Test
    void invalidJarIsRejectedBeforeALockCanBePublished() throws Exception {
        final Path file = directory.resolve("not-a-provider.jar");
        Files.writeString(file, "not a jar");
        assertThatThrownBy(() -> StepCatalog.bundleLock(file, directory.resolve("store")))
                .isInstanceOf(StepCatalog.DependencyException.class).hasMessageStartingWith("DEPENDENCY_ARTIFACT_INVALID_JAR:");
        try (var files = Files.list(directory.resolve("store"))) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @Test
    void damagedCacheIsReportedInsteadOfOverwritingExistingBytes() throws Exception {
        final Path bundle = export("first");
        final Path store = directory.resolve("store");
        StepCatalog.bundleLock(bundle, store);
        final Path cached;
        try (var files = Files.list(store)) { cached = files.findFirst().orElseThrow(); }
        Files.writeString(cached, "damaged");

        assertThatThrownBy(() -> StepCatalog.bundleLock(bundle, store)).isInstanceOf(IOException.class)
                .hasMessageStartingWith("DEPENDENCY_ARTIFACT_DIGEST_MISMATCH:");
        assertThat(Files.readString(cached)).isEqualTo("damaged");
    }

    @Test
    void missingDefinitionProviderIsRejectedWithoutExecutingDiscoveryCode() throws Exception {
        final Path classes = Files.createDirectories(directory.resolve("empty"));
        final Path bundle = StepBundle.write(classes, directory.resolve("missing.jar"), PortableSteps.class, PortableSteps.definitions());
        assertThatThrownBy(() -> StepCatalog.bundleLock(bundle, directory.resolve("store")))
                .isInstanceOf(StepCatalog.DependencyException.class).hasMessageContaining("does not own its definitions provider");
        try (var files = Files.list(directory.resolve("store"))) {
            assertThat(files.toList()).isEmpty();
        }
    }

    private Path export(final String name) throws IOException {
        final Path classes = Files.createDirectories(directory.resolve(name).resolve("classes"));
        final String entry = PortableSteps.class.getName().replace('.', '/') + ".class";
        final Path target = classes.resolve(entry);
        Files.createDirectories(target.getParent());
        try (var input = PortableSteps.class.getClassLoader().getResourceAsStream(entry)) {
            Files.copy(java.util.Objects.requireNonNull(input), target);
        }
        return StepBundle.write(classes, directory.resolve(name).resolve("provider.jar"), PortableSteps.class, PortableSteps.definitions());
    }
}
