package dev.nanonative.railix.creator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(120)
final class ProjectSourceArtifactsE2eTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void buildsExecutableAndOpensTheSameProjectThroughCreator(final boolean include) throws Exception {
        final Path source = directory.resolve("source/railix.project.json");
        Files.createDirectories(source.getParent());
        Files.writeString(source, CreatorProjects.nestedLowercase());
        final List<String> arguments = new ArrayList<>(List.of("build", source.toString()));
        if (include) arguments.add("--include-project");
        final Result build = run(arguments);
        assertThat(build.status()).as(build.output()).isZero();
        final Path artifact = Path.of(build.output().strip());
        try (JarFile jar = new JarFile(artifact.toFile())) {
            assertThat(jar.getManifest().getMainAttributes().getValue("Railix-Project-Id"))
                    .matches("sha256-[a-f0-9]{64}");
            assertThat(jar.getJarEntry("META-INF/railix/project/railix.project.json") != null).isEqualTo(include);
            assertThat(jar.stream().map(e -> e.getName()))
                    .noneMatch(name -> name.endsWith("topology.json") || name.endsWith("ExampleAssertions.class"));
        }
        final Process app = process(List.of("-jar", artifact.toString()), false);
        try {
            assertThat(app.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(app.exitValue()).isZero();
        } finally { stop(app); }

        final Path local = include ? directory.resolve("restored/railix.project.json") : source;
        if (!include) Files.writeString(source, Files.readString(source).replace("\"name\":\"hello\"", "\"name\":\"renamed-example\""));
        final Process creator = process(List.of("creator", local.toString(), "0", "--application", artifact.toString()), true);
        try (BufferedReader output = new BufferedReader(new InputStreamReader(creator.getInputStream()))) {
            final String line = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { return output.readLine(); } catch (final java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            }).get(45, TimeUnit.SECONDS);
            assertThat(line).startsWith("Railix Creator ");
            final URI uri = URI.create(line.substring("Railix Creator ".length()));
            try (HttpClient client = HttpClient.newHttpClient()) {
                final var response = client.send(HttpRequest.newBuilder(uri.resolve("/api/project"))
                        .header("X-Railix-Creator-Token", uri.getFragment().substring("token=".length()))
                        .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                assertThat(response.body()).contains("nested-lowercase", "lowercase-text");
            }
            assertThat(Files.readString(local)).contains("nested-lowercase");
        } finally { stop(creator); }
    }

    @Test
    void rejectsMissingOrChangedLocalProjectWithoutCreatingOrOverwritingFiles() throws Exception {
        final Path source = directory.resolve("source/railix.project.json");
        Files.createDirectories(source.getParent());
        Files.writeString(source, CreatorProjects.nestedLowercase());
        final Result build = run(List.of("build", source.toString()));
        assertThat(build.status()).as(build.output()).isZero();
        final String jar = build.output().strip();
        final Path absent = directory.resolve("absent/railix.project.json");
        final Result missing = run(List.of("creator", absent.toString(), "0", "--application", jar));
        assertThat(missing.status()).isEqualTo(2);
        assertThat(missing.output()).contains("PROJECT_SOURCE_REQUIRED");
        assertThat(absent.getParent()).doesNotExist();
        final String changed = CreatorProjects.nestedLowercase().replace("text.lowercase", "text.uppercase");
        Files.writeString(source, changed);
        final Result mismatch = run(List.of("creator", source.toString(), "0", "--application", jar));
        assertThat(mismatch.status()).isEqualTo(2);
        assertThat(mismatch.output()).contains("PROJECT_SOURCE_MISMATCH");
        assertThat(Files.readString(source)).isEqualTo(changed);
        assertThat(source.resolveSibling("railix.creator.json")).doesNotExist();
    }

    @Test
    void packagesOriginalSeparateDocumentsAndKeepsBothBuildOptionsIndependent() throws Exception {
        final Path source = directory.resolve("source/railix.project.json");
        Files.createDirectories(source.getParent());
        final var documents = ProjectFiles.split(ProjectFiles.identify(CreatorProjects.nestedLowercase(), true), ProjectFiles.EMPTY_EXAMPLES);
        Files.writeString(source, documents.project());
        Files.writeString(source.resolveSibling("railix.examples.json"), documents.examples());
        Files.writeString(source.resolveSibling("railix.creator.json"), CreatorDocument.EMPTY);
        final Result included = run(List.of("build", source.toString(), "--include-project"));
        final Result ordinary = run(List.of("build", source.toString()));
        final Result repeat = run(List.of("build", source.toString(), "--include-project"));
        assertThat(included.status()).as(included.output()).isZero();
        assertThat(ordinary.status()).as(ordinary.output()).isZero();
        assertThat(repeat).isEqualTo(included);
        assertThat(included.output()).isNotEqualTo(ordinary.output());
        try (JarFile embedded = new JarFile(included.output().strip()); JarFile lean = new JarFile(ordinary.output().strip())) {
            for (final String name : List.of("railix.project.json", "railix.examples.json", "railix.creator.json")) {
                final String entry = "META-INF/railix/project/" + name;
                assertThat(new String(embedded.getInputStream(embedded.getJarEntry(entry)).readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(Files.readString(source.resolveSibling(name)));
                assertThat(lean.getJarEntry(entry)).isNull();
            }
            assertThat(embedded.getJarEntry("META-INF/railix/examples.json")).isNull();
            assertThat(embedded.stream().map(e -> e.getName())).noneMatch(name -> name.startsWith("dev/nanonative/railix/development/"));
            for (final var entry : embedded.stream().filter(item -> item.getName().endsWith(".class")).toList()) {
                try (var first = embedded.getInputStream(entry); var second = lean.getInputStream(lean.getJarEntry(entry.getName()))) {
                    assertThat(first.readAllBytes()).as(entry.getName()).isEqualTo(second.readAllBytes());
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"changed", "traversal", "utf8", "partial"})
    void rejectsCorruptSourceArchivesWithoutPublishingAWorkspace(final String scenario) throws Exception {
        final Path source = directory.resolve("source/railix.project.json");
        Files.createDirectories(source.getParent());
        Files.writeString(source, CreatorProjects.nestedLowercase());
        final Result built = run(List.of("build", source.toString(), "--include-project"));
        assertThat(built.status()).as(built.output()).isZero();
        final Path corrupt = directory.resolve("corrupt.jar");
        try (JarFile original = new JarFile(built.output().strip());
             JarOutputStream output = new JarOutputStream(Files.newOutputStream(corrupt))) {
            for (final var entry : original.stream().toList()) {
                if (scenario.equals("partial") && entry.getName().equals("META-INF/railix/project/railix.project.json")) continue;
                output.putNextEntry(new JarEntry(entry.getName()));
                if (entry.getName().equals("META-INF/railix/project/railix.project.json") && scenario.equals("changed"))
                    output.write(CreatorProjects.nestedLowercase().replace("text.lowercase", "text.uppercase")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                else if (entry.getName().equals("META-INF/railix/project/railix.project.json") && scenario.equals("utf8"))
                    output.write(new byte[] {(byte) 0xc3, (byte) 0x28});
                else try (var input = original.getInputStream(entry)) { input.transferTo(output); }
                output.closeEntry();
            }
            if (scenario.equals("traversal") || scenario.equals("partial")) {
                output.putNextEntry(new JarEntry("META-INF/railix/project/" +
                        (scenario.equals("traversal") ? "../escape.json" : "railix.creator.json")));
                output.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)); output.closeEntry();
            }
        }
        final Path destination = directory.resolve("restored/railix.project.json");
        final Result opened = run(List.of("creator", destination.toString(), "--application", corrupt.toString()));
        assertThat(opened.status()).isEqualTo(2);
        assertThat(opened.output()).contains(scenario.equals("changed") ? "PROJECT_SOURCE_MISMATCH"
                : scenario.equals("utf8") ? "not valid UTF-8" : "PROJECT_SOURCE_INVALID");
        assertThat(destination.getParent()).doesNotExist();
        assertThat(directory.resolve("escape.json")).doesNotExist();
        try (var files = Files.list(directory)) {
            assertThat(files.map(path -> path.getFileName().toString())).noneMatch(name -> name.startsWith(".railix-project-"));
        }
    }

    @Test
    void refusesToExtractIntoAnExistingWorkspace() throws Exception {
        final Path source = directory.resolve("source/railix.project.json");
        Files.createDirectories(source.getParent()); Files.writeString(source, CreatorProjects.nestedLowercase());
        final Result built = run(List.of("build", source.toString(), "--include-project"));
        assertThat(built.status()).as(built.output()).isZero();
        final Path target = directory.resolve("existing/railix.project.json");
        Files.createDirectories(target.getParent());
        Files.writeString(target.resolveSibling("notes.txt"), "keep");
        final Result opened = run(List.of("creator", target.toString(), "--application", built.output().strip()));
        assertThat(opened.status()).isEqualTo(2);
        assertThat(opened.output()).contains("PROJECT_DESTINATION_EXISTS");
        assertThat(Files.readString(target.resolveSibling("notes.txt"))).isEqualTo("keep");
        assertThat(target).doesNotExist();
    }

    @Test
    void rejectsOversizedLocalAndCompressedDocumentsBeforeReadingThem() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        try (var file = java.nio.channels.FileChannel.open(project, java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE)) {
            file.position(ProjectFiles.MAX_DOCUMENT_BYTES);
            file.write(java.nio.ByteBuffer.wrap(new byte[] {' '}));
        }
        final Result local = run(List.of("build", project.toString()));
        assertThat(local.status()).isEqualTo(2);
        assertThat(local.output()).contains("PROJECT_DOCUMENT_TOO_LARGE");
        final Path jar = directory.resolve("oversized.jar");
        final var manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Railix-Project-Id", "sha256-" + "0".repeat(64));
        try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            output.putNextEntry(new JarEntry(ProjectFiles.ARCHIVE_ROOT + "railix.project.json"));
            final byte[] buffer = new byte[8192];
            for (int bytes = 0; bytes <= ProjectFiles.MAX_DOCUMENT_BYTES; bytes += buffer.length) output.write(buffer);
            output.closeEntry();
        }
        final Path target = directory.resolve("restored/railix.project.json");
        final Result archive = run(List.of("creator", target.toString(), "--application", jar.toString()));
        assertThat(archive.status()).isEqualTo(2);
        assertThat(archive.output()).contains("PROJECT_SOURCE_INVALID").doesNotContain("OutOfMemoryError");
        assertThat(target.getParent()).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "truncated", "no-identity"})
    void rejectsUnusableApplicationReferencesWithAnActionableCode(final String scenario) throws Exception {
        final Path jar = directory.resolve("invalid.jar");
        if (scenario.equals("truncated")) Files.writeString(jar, "not a JAR");
        if (scenario.equals("no-identity")) try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("file.txt")); output.closeEntry();
        }
        final Path target = directory.resolve("missing/deeper/railix.project.json");
        final Result result = run(List.of("creator", target.toString(), "--application", jar.toString()));
        assertThat(result.status()).isEqualTo(2);
        assertThat(result.output()).contains(scenario.equals("no-identity") ? "PROJECT_SOURCE_ID_REQUIRED" : "PROJECT_SOURCE_INVALID");
        assertThat(target.getParent().getParent()).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unfinishedEditorActionsBlockBuildsAndArtifactOpening(final boolean build) throws Exception {
        final Path project = directory.resolve("source/railix.project.json");
        Files.createDirectories(project.getParent()); Files.writeString(project, CreatorProjects.nestedLowercase());
        final Result artifact = run(List.of("build", project.toString()));
        assertThat(artifact.status()).as(artifact.output()).isZero();
        final Path journal = ProjectFiles.journal(project);
        Files.createDirectories(journal.getParent()); Files.writeString(journal, "unfinished");
        final Result blocked = run(build ? List.of("build", project.toString(), "--include-project")
                : List.of("creator", project.toString(), "--application", artifact.output().strip()));
        assertThat(blocked.status()).isEqualTo(2);
        assertThat(blocked.output()).contains("PROJECT_EDIT_PENDING");
        assertThat(Files.readString(journal)).isEqualTo("unfinished");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 1})
    void rejectsAnEntryWhoseInflatedSizeDoesNotMatchItsDirectory(final int adjustment) throws Exception {
        final Path source = directory.resolve("source/railix.project.json");
        Files.createDirectories(source.getParent()); Files.writeString(source, CreatorProjects.nestedLowercase());
        final Result built = run(List.of("build", source.toString(), "--include-project"));
        assertThat(built.status()).as(built.output()).isZero();
        final byte[] bytes = Files.readAllBytes(Path.of(built.output().strip()));
        final var data = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        boolean changed = false;
        for (int offset = 0; offset + 46 < bytes.length; offset++) {
            if (data.getInt(offset) != 0x02014b50) continue;
            final int length = Short.toUnsignedInt(data.getShort(offset + 28));
            if (offset + 46 + length > bytes.length) continue;
            final String name = new String(bytes, offset + 46, length, java.nio.charset.StandardCharsets.UTF_8);
            if (name.equals(ProjectFiles.ARCHIVE_ROOT + "railix.project.json")) {
                data.putInt(offset + 24, data.getInt(offset + 24) + adjustment); changed = true; break;
            }
        }
        assertThat(changed).isTrue();
        final Path corrupt = directory.resolve("size.jar"); Files.write(corrupt, bytes);
        final Path target = directory.resolve("restored/railix.project.json");
        final Result opened = run(List.of("creator", target.toString(), "--application", corrupt.toString()));
        assertThat(opened.status()).isEqualTo(2);
        assertThat(opened.output()).contains("PROJECT_SOURCE_INVALID");
        assertThat(target.getParent()).doesNotExist();
    }

    @Test
    void validEmbeddedSourceRequiresAnExistingParentAndDistinctProjectFilename() throws Exception {
        final Path source = directory.resolve("source/railix.project.json");
        Files.createDirectories(source.getParent()); Files.writeString(source, CreatorProjects.nestedLowercase());
        final Result built = run(List.of("build", source.toString(), "--include-project"));
        assertThat(built.status()).as(built.output()).isZero();
        final Result parent = run(List.of("creator", directory.resolve("missing/deeper/railix.project.json").toString(),
                "--application", built.output().strip()));
        assertThat(parent.status()).isEqualTo(2);
        assertThat(parent.output()).contains("PROJECT_DESTINATION_PARENT_REQUIRED");
        assertThat(directory.resolve("missing")).doesNotExist();
        final Result collision = run(List.of("creator", directory.resolve("restored/railix.creator.json").toString(),
                "--application", built.output().strip()));
        assertThat(collision.status()).isEqualTo(2);
        assertThat(collision.output()).contains("PROJECT_DESTINATION_INVALID");
        assertThat(directory.resolve("restored")).doesNotExist();
    }

    @Test
    void rejectsAnEmbeddedDocumentChangedAfterJarSigning() throws Exception {
        final Path source = directory.resolve("source/railix.project.json");
        Files.createDirectories(source.getParent()); Files.writeString(source, CreatorProjects.nestedLowercase());
        final Result built = run(List.of("build", source.toString(), "--include-project"));
        assertThat(built.status()).as(built.output()).isZero();
        final Path signed = directory.resolve("signed.jar"); Files.copy(Path.of(built.output().strip()), signed);
        final Path keys = directory.resolve("test.p12");
        final Path bin = Path.of(System.getProperty("java.home"), "bin");
        for (final var command : List.of(List.of(bin.resolve("keytool").toString(), "-genkeypair", "-alias", "source",
                "-keystore", keys.toString(), "-storepass", "test-password", "-keypass", "test-password", "-dname",
                "CN=Project Source Test", "-keyalg", "RSA", "-validity", "1", "-noprompt"),
                List.of(bin.resolve("jarsigner").toString(), "-keystore", keys.toString(), "-storepass", "test-password",
                        signed.toString(), "source"))) {
            final Process tool = new ProcessBuilder(command).redirectErrorStream(true).start();
            try {
                assertThat(tool.waitFor(30, TimeUnit.SECONDS)).isTrue();
                assertThat(tool.exitValue()).as(new String(tool.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).isZero();
            } finally { stop(tool); }
        }
        final Path tampered = directory.resolve("tampered.jar");
        rewrite(signed, tampered, ProjectFiles.ARCHIVE_ROOT + "railix.project.json",
                CreatorProjects.nestedLowercase().replace("text.lowercase", "text.uppercase"));
        final Path target = directory.resolve("restored/railix.project.json");
        final Result opened = run(List.of("creator", target.toString(), "--application", tampered.toString()));
        assertThat(opened.status()).isEqualTo(2);
        assertThat(opened.output()).contains("PROJECT_SOURCE_INVALID", "signature verification failed");
        assertThat(target.getParent()).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothSourceVariantsReportTheSamePhysicalExampleField(final boolean embedded) throws Exception {
        final Path source = directory.resolve("source/railix.project.json");
        Files.createDirectories(source.getParent());
        final var documents = ProjectFiles.split(ProjectFiles.identify(CreatorProjects.nestedLowercase(), true), ProjectFiles.EMPTY_EXAMPLES);
        Files.writeString(source, documents.project());
        final String invalid = documents.examples().replace("\"payload\":[],", "");
        final Result result;
        if (embedded) {
            Files.writeString(source.resolveSibling("railix.examples.json"), documents.examples());
            final Result built = run(List.of("build", source.toString(), "--include-project"));
            assertThat(built.status()).as(built.output()).isZero();
            final Path corrupt = directory.resolve("invalid-example.jar");
            rewrite(Path.of(built.output().strip()), corrupt, ProjectFiles.ARCHIVE_ROOT + "railix.examples.json", invalid);
            result = run(List.of("creator", directory.resolve("restored/railix.project.json").toString(), "--application", corrupt.toString()));
            assertThat(directory.resolve("restored")).doesNotExist();
        } else {
            Files.writeString(source.resolveSibling("railix.examples.json"), invalid);
            result = run(List.of("build", source.toString()));
        }
        assertThat(result.status()).isEqualTo(2);
        assertThat(result.output()).contains("railix.examples.json.examples[0].payload");
    }

    static void rewrite(final Path original, final Path target, final String name, final String replacement) throws Exception {
        try (JarFile input = new JarFile(original.toFile(), false);
             JarOutputStream output = new JarOutputStream(Files.newOutputStream(target))) {
            for (final var entry : input.stream().toList()) {
                output.putNextEntry(new JarEntry(entry.getName()));
                if (entry.getName().equals(name)) output.write(replacement.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                else try (var bytes = input.getInputStream(entry)) { bytes.transferTo(output); }
                output.closeEntry();
            }
        }
    }

    Result run(final List<String> arguments) throws Exception {
        final Process process = process(arguments, true);
        try {
            assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
            return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } finally { stop(process); }
    }

    Process process(final List<String> arguments, final boolean creator) throws Exception {
        final var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString()));
        if (creator) command.addAll(List.of("-Duser.home=" + directory.resolve("home"), "-cp",
                System.getProperty("java.class.path"), RailixMain.class.getName()));
        command.addAll(arguments);
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }

    static void stop(final Process process) throws Exception {
        process.destroy();
        if (!process.waitFor(35, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    record Result(int status, String output) { }
}
