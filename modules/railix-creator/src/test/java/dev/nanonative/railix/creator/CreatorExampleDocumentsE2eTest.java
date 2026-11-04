package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.BeforeEach;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(120)
final class CreatorExampleDocumentsE2eTest extends CreatorServerE2eSupport {
    @BeforeEach
    void writeProject() throws Exception {
        Files.writeString(directory.resolve("railix.project.json"), """
                {"format":1,"id":"example-files","nodes":[
                {"id":"app","use":"railix.app","inputs":{}},
                {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{"name":"Hello","payload":[]}]},
                {"id":"next","use":"railix.field-manipulation","inputs":{}}],
                "links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"next"},
                {"from":"next.next","to":"end"}]}
                """);
    }

    @Test
    void importsEmbeddedCasesOnceAndReopensFromTheirOnlyFile() throws Exception {
        final var file = directory.resolve("railix.project.json");
        try (final var creator = start(file)) {
            assertThat(awaitExampleView(creator.baseUri(), "command:0").statusCode()).isEqualTo(200);
            assertThat(Files.readString(file)).doesNotContain("\"examples\"");
            final var examples = object(Files.readString(directory.resolve("railix.examples.json")));
            final var cases = (RailixValue.ArrayValue) examples.values().get("examples");
            assertThat(cases.values()).hasSize(1);
            assertThat(((RailixValue.ObjectValue) cases.values().getFirst()).values())
                    .containsEntry("id", RailixValue.string("command:0"))
                    .containsEntry("trigger", RailixValue.string("command"));
        }
        try (final var creator = start(file)) {
            assertThat(awaitExampleView(creator.baseUri(), "command:0").statusCode()).isEqualTo(200);
            assertThat(Files.readString(file)).doesNotContain("\"examples\"");
        }
    }

    @Test
    void invalidExternalCasesAreNotOverwrittenWithTemplates() throws Exception {
        final var file = directory.resolve("railix.project.json");
        try (final var ignored = start(file)) { }
        final var examples = directory.resolve("railix.examples.json");
        final String project = Files.readString(file);
        Files.writeString(examples, "{broken");
        assertThatThrownBy(() -> start(file)).isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(examples)).isEqualTo("{broken");
        assertThat(Files.readString(file)).isEqualTo(project);
    }

    @Test
    void aMissingProjectCannotOverwriteAnOrphanedExampleFile() throws Exception {
        final var project = directory.resolve("missing.json");
        final var examples = directory.resolve("railix.examples.json");
        final String authored = "{\"format\":1,\"examples\":[{\"id\":\"saved\",\"trigger\":\"command\",\"name\":\"Saved\",\"payload\":[]}] }";
        Files.writeString(examples, authored);
        assertThatThrownBy(() -> start(project)).isInstanceOf(java.io.IOException.class).hasMessageContaining("Example file already exists");
        assertThat(Files.readString(examples)).isEqualTo(authored);
        assertThat(project).doesNotExist();
    }

    @Test
    void clearingAlreadyEmptyChecksDoesNotCreateAnEditOrChangeRevision() throws Exception {
        try (final var creator = start(directory.resolve("railix.project.json"))) {
            final var file = directory.resolve("railix.examples.json");
            final String formatted = "\n " + Files.readString(file) + "\n";
            Files.writeString(file, formatted);
            final String path = "/api/assertions/expectations/command:0/next";
            final var before = object(request(creator.baseUri(), "GET", path, "").body());
            final var response = request(creator.baseUri(), "POST", path, RailixJson.write(RailixValue.object(Map.of(
                    "revision", before.values().get("revision"), "source_revision", before.values().get("source_revision"),
                    "checks", RailixValue.array(java.util.List.of())))));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(object(response.body()).values().get("revision")).isEqualTo(before.values().get("revision"));
            assertThat(Files.readString(file)).isEqualTo(formatted);
            assertThat(((RailixValue.ObjectValue) object(request(creator.baseUri(), "GET", "/api/project", "").body())
                    .values().get("history")).values().get("undo")).isEqualTo(RailixValue.bool(false));
        }
    }

    @Test
    void invalidCaseDiagnosticsNameTheActualExampleFile() throws Exception {
        final var project = directory.resolve("railix.project.json");
        try (final var ignored = start(project)) { }
        final var examples = directory.resolve("railix.examples.json");
        Files.writeString(examples, Files.readString(examples).replace("\"payload\":[],", ""));
        assertThatThrownBy(() -> start(project)).isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("railix.examples.json.examples[0].payload");
    }

    @Test
    void reorderingAndRenamingDoesNotChangeCaseIdentity() throws Exception {
        final var file = directory.resolve("railix.project.json");
        try (final var creator = start(file)) {
            final var response = object(request(creator.baseUri(), "GET", "/api/project", "").body());
            final var project = (RailixValue.ObjectValue) response.values().get("project");
            final var nodes = new ArrayList<>(((RailixValue.ArrayValue) project.values().get("nodes")).values());
            final var trigger = (RailixValue.ObjectValue) nodes.get(1);
            final var original = (RailixValue.ObjectValue) ((RailixValue.ArrayValue) trigger.values().get("examples")).values().getFirst();
            final var renamed = CreatorChanges.with(original, "name", RailixValue.string("Renamed"));
            final var added = object("{\"name\":\"Another\",\"payload\":[\"Different\"]}");
            nodes.set(1, CreatorChanges.with(trigger, "examples", RailixValue.array(java.util.List.of(added, renamed))));
            final var saved = request(creator.baseUri(), "POST", "/api/project",
                    RailixJson.write(CreatorChanges.with(project, "nodes", RailixValue.array(nodes))));
            assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
            final var cases = (RailixValue.ArrayValue) object(Files.readString(directory.resolve("railix.examples.json"))).values().get("examples");
            assertThat(((RailixValue.ObjectValue) cases.values().get(1)).values())
                    .containsEntry("id", RailixValue.string("command:0"))
                    .containsEntry("name", RailixValue.string("Renamed"));
            assertThat(((RailixValue.ObjectValue) cases.values().getFirst()).values().get("id"))
                    .isNotEqualTo(RailixValue.string("command:0"));
            assertThat(awaitExampleView(creator.baseUri(), "command:0").statusCode()).isEqualTo(200);
        }
    }

    @Test
    void anExternalEditIsPreservedInsteadOfBeingSilentlyReplaced() throws Exception {
        final var file = directory.resolve("railix.project.json");
        try (final var creator = start(file)) {
            final var response = object(request(creator.baseUri(), "GET", "/api/project", "").body());
            final String external = Files.readString(directory.resolve("railix.examples.json")).replace("Hello", "External");
            Files.writeString(directory.resolve("railix.examples.json"), external);
            final var changed = CreatorChanges.with((RailixValue.ObjectValue) response.values().get("project"),
                    "id", RailixValue.string("changed"));
            assertThat(request(creator.baseUri(), "POST", "/api/project", RailixJson.write(changed)).statusCode()).isEqualTo(409);
            assertThat(Files.readString(directory.resolve("railix.examples.json"))).isEqualTo(external);
        }
    }

    @Test
    void malformedExternalExamplesBlockAnEditWithoutStoppingTheApp() throws Exception {
        try (final var creator = start(directory.resolve("railix.project.json"))) {
            final var before = object(request(creator.baseUri(), "GET", "/api/editor", "").body());
            final long pid = number(application(creator.baseUri()), "pid");
            final var file = directory.resolve("railix.examples.json");
            Files.writeString(file, "{broken");
            final var response = request(creator.baseUri(), "POST", "/api/editor", RailixJson.write(RailixValue.object(Map.of(
                    "revision", before.values().get("revision"), "creator_revision", before.values().get("creator_revision"),
                    "command", object("{\"kind\":\"patch\",\"project\":{\"id\":\"cannot-apply\"}}")))));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
            assertThat(Files.readString(file)).isEqualTo("{broken");
            assertThat(number(application(creator.baseUri()), "pid")).isEqualTo(pid);
            assertThat(string(application(creator.baseUri()), "state")).isEqualTo("running");
        }
    }

    @Test
    void concurrentCheckEditsHaveExactlyOneWinner() throws Exception {
        try (final var creator = start(directory.resolve("railix.project.json"));
             final var requests = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            final String path = "/api/assertions/expectations/command:0/next";
            final var before = object(request(creator.baseUri(), "GET", path, "").body());
            final var start = new java.util.concurrent.CountDownLatch(1);
            final var results = new ArrayList<java.util.concurrent.Future<java.net.http.HttpResponse<String>>>();
            for (final String value : java.util.List.of("first", "second")) {
                final var checks = RailixValue.array(java.util.List.of(RailixValue.object(Map.of(
                        "path", RailixValue.array(java.util.List.of(RailixValue.string("context"), RailixValue.string("result"))),
                        "assertion", RailixValue.string("isEqualTo"), "expected", RailixValue.string(value)))));
                results.add(requests.submit(() -> {
                    start.await();
                    return request(creator.baseUri(), "POST", path, RailixJson.write(RailixValue.object(Map.of(
                            "revision", before.values().get("revision"), "source_revision", before.values().get("source_revision"),
                            "checks", checks))));
                }));
            }
            start.countDown();
            final var first = results.getFirst().get(20, java.util.concurrent.TimeUnit.SECONDS);
            final var second = results.getLast().get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(java.util.List.of(first.statusCode(), second.statusCode())).containsExactlyInAnyOrder(200, 409);
            final var winner = object(first.statusCode() == 200 ? first.body() : second.body());
            assertThat(object(request(creator.baseUri(), "GET", path, "").body()).values().get("checks"))
                    .isEqualTo(winner.values().get("checks"));
        }
    }

    @Test
    void savesChecksWithoutRebuildAndRestoresThemAfterReopening() throws Exception {
        final var file = directory.resolve("railix.project.json");
        final String path = "/api/assertions/expectations/command:0/next";
        final var checks = object("{\"checks\":[{\"path\":[\"context\",\"missing\"],\"assertion\":\"doesNotExist\"}]}").values().get("checks");
        try (final var creator = start(file)) {
            final var before = object(request(creator.baseUri(), "GET", path, "").body());
            final long pid = number(application(creator.baseUri()), "pid");
            final String project = Files.readString(file);
            final String body = RailixJson.write(RailixValue.object(Map.of("revision", before.values().get("revision"),
                    "source_revision", before.values().get("source_revision"), "checks", checks)));
            final var saved = request(creator.baseUri(), "POST", path, body);
            assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
            assertThat(object(saved.body()).values().get("checks")).isEqualTo(checks);
            assertThat(number(application(creator.baseUri()), "pid")).isEqualTo(pid);
            assertThat(Files.readString(file)).isEqualTo(project);
            assertThat(Files.readString(directory.resolve("railix.examples.json"))).contains("doesNotExist");
            assertThat(request(creator.baseUri(), "POST", path, body).statusCode()).isEqualTo(409);
        }
        try (final var creator = start(file)) {
            assertThat(object(request(creator.baseUri(), "GET", path, "").body()).values().get("checks")).isEqualTo(checks);
            final var project = object(request(creator.baseUri(), "GET", "/api/project", "").body());
            final var changed = CreatorChanges.with((RailixValue.ObjectValue) project.values().get("project"), "id", RailixValue.string("renamed"));
            assertThat(request(creator.baseUri(), "POST", "/api/project", RailixJson.write(changed)).statusCode()).isEqualTo(200);
            assertThat(object(request(creator.baseUri(), "GET", path, "").body()).values().get("checks")).isEqualTo(checks);
            try (final var jar = new java.util.zip.ZipFile(java.nio.file.Path.of(string(application(creator.baseUri()), "build_path")).toFile());
                 final var input = jar.getInputStream(jar.getEntry("META-INF/railix/examples.json"))) {
                assertThat(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                        .doesNotContain("expectations", "doesNotExist");
                assertThat(jar.getEntry("dev/nanonative/railix/creator/ExampleAssertions.class")).isNull();
            }
        }
    }

    @Test
    void theBuildCommandRefusesAnInterruptedEditorTransactionWithoutChangingFiles() throws Exception {
        final var project = directory.resolve("railix.project.json");
        try (final var ignored = start(project)) { }
        final String before = Files.readString(project);
        final String examples = Files.readString(directory.resolve("railix.examples.json"));
        final var journal = directory.resolve(".railix/run/editor-transaction.json");
        Files.writeString(journal, "{\"interrupted\":true}");
        final var process = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), RailixMain.class.getName(), "run")
                .directory(directory.toFile()).redirectErrorStream(true).start();
        try {
            assertThat(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isNotZero();
            assertThat(new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .contains("PROJECT_EDIT_PENDING");
        } finally { if (process.isAlive()) process.destroyForcibly(); }
        assertThat(Files.readString(project)).isEqualTo(before);
        assertThat(Files.readString(directory.resolve("railix.examples.json"))).isEqualTo(examples);
        assertThat(journal).exists();
    }

    @Test
    void savingChecksCanBeUndoneAndRedoneWithoutRestartingTheApp() throws Exception {
        try (final var creator = start(directory.resolve("railix.project.json"))) {
            final String path = "/api/assertions/expectations/command:0/next";
            final var before = object(request(creator.baseUri(), "GET", path, "").body());
            final var checks = object("{\"checks\":[{\"path\":[\"context\",\"missing\"],\"assertion\":\"doesNotExist\"}]}").values().get("checks");
            final long pid = number(application(creator.baseUri()), "pid");
            assertThat(request(creator.baseUri(), "POST", path, RailixJson.write(RailixValue.object(Map.of(
                    "revision", before.values().get("revision"), "source_revision", before.values().get("source_revision"),
                    "checks", checks)))).statusCode()).isEqualTo(200);
            action(creator, object("{\"kind\":\"undo\"}"));
            assertThat(object(request(creator.baseUri(), "GET", path, "").body()).values().get("checks"))
                    .isEqualTo(RailixValue.array(java.util.List.of()));
            action(creator, object("{\"kind\":\"redo\"}"));
            assertThat(object(request(creator.baseUri(), "GET", path, "").body()).values().get("checks")).isEqualTo(checks);
            assertThat(number(application(creator.baseUri()), "pid")).isEqualTo(pid);
        }
    }

    @Test
    void undoRestoresTheExpectationsOfADeletedCase() throws Exception {
        try (final var creator = start(directory.resolve("railix.project.json"))) {
            final var current = (RailixValue.ObjectValue) object(request(creator.baseUri(), "GET", "/api/project", "").body()).values().get("project");
            final var nodes = new ArrayList<>(((RailixValue.ArrayValue) current.values().get("nodes")).values());
            final var trigger = (RailixValue.ObjectValue) nodes.get(1);
            final var original = ((RailixValue.ArrayValue) trigger.values().get("examples")).values().getFirst();
            final var other = object("{\"id\":\"case-second\",\"name\":\"Second\",\"payload\":[]}");
            nodes.set(1, CreatorChanges.with(trigger, "examples", RailixValue.array(java.util.List.of(original, other))));
            assertThat(request(creator.baseUri(), "POST", "/api/project", RailixJson.write(CreatorChanges.with(current,
                    "nodes", RailixValue.array(nodes)))).statusCode()).isEqualTo(200);
            final String path = "/api/assertions/expectations/command:0/next";
            final var before = object(request(creator.baseUri(), "GET", path, "").body());
            final var checks = object("{\"checks\":[{\"path\":[\"context\",\"missing\"],\"assertion\":\"doesNotExist\"}]}").values().get("checks");
            assertThat(request(creator.baseUri(), "POST", path, RailixJson.write(RailixValue.object(Map.of(
                    "revision", before.values().get("revision"), "source_revision", before.values().get("source_revision"),
                    "checks", checks)))).statusCode()).isEqualTo(200);
            final var remaining = CreatorChanges.with(trigger, "examples", RailixValue.array(java.util.List.of(other)));
            action(creator, RailixValue.object(Map.of("kind", RailixValue.string("patch"), "project",
                    RailixValue.object(Map.of("nodes", RailixValue.object(Map.of("command", remaining)))))));
            assertThat(request(creator.baseUri(), "GET", path, "").statusCode()).isEqualTo(404);
            action(creator, object("{\"kind\":\"undo\"}"));
            assertThat(object(request(creator.baseUri(), "GET", path, "").body()).values().get("checks")).isEqualTo(checks);
            action(creator, object("{\"kind\":\"redo\"}"));
            assertThat(request(creator.baseUri(), "GET", path, "").statusCode()).isEqualTo(404);
        }
    }

    @Test
    void interruptedRecoveryDoesNotOverwriteLaterExternalEdits() throws Exception {
        final var project = directory.resolve("railix.project.json");
        final String original, creator, examples;
        try (final var ignored = start(project)) {
            original = Files.readString(project);
            creator = Files.readString(directory.resolve("railix.creator.json"));
            examples = Files.readString(directory.resolve("railix.examples.json"));
        }
        final String candidate = original.replace("example-files", "candidate");
        final String outside = original.replace("example-files", "outside-edit");
        final var before = RailixValue.object(Map.of("project", RailixValue.string(original),
                "creator", RailixValue.string(creator), "examples", RailixValue.string(examples)));
        final var after = CreatorChanges.with(before, "project", RailixValue.string(candidate));
        Files.writeString(directory.resolve(".railix/run/editor-transaction.json"), RailixJson.write(RailixValue.object(Map.of(
                "before", before, "after", after))));
        Files.writeString(project, outside);
        assertThatThrownBy(() -> start(project)).isInstanceOf(java.io.IOException.class).hasMessageContaining("preserved");
        assertThat(Files.readString(project)).isEqualTo(outside);
        assertThat(Files.readString(directory.resolve("railix.examples.json"))).isEqualTo(examples);
    }

    private void action(final CreatorServer creator, final RailixValue.ObjectValue command) throws Exception {
        final var current = object(request(creator.baseUri(), "GET", "/api/editor", "").body());
        final var response = request(creator.baseUri(), "POST", "/api/editor", RailixJson.write(RailixValue.object(Map.of(
                "revision", current.values().get("revision"), "creator_revision", current.values().get("creator_revision"), "command", command))));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }
}
