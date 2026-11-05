package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import dev.nanonative.railix.core.step.StepDefinition;
import org.junit.jupiter.params.provider.ValueSource;
import thirdparty.conformance.DevelopmentRuntimeConformanceSteps;
import thirdparty.production.BranchingSteps;
import dev.nanonative.railix.core.value.ValueShape;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(120)
final class CreatorAssertionsE2eTest extends CreatorServerE2eSupport {
    private CreatorServer server;
    private Path project;

    @BeforeAll
    void openApplication(@TempDir final Path workspace) throws Exception {
        project = workspace.resolve("railix.project.json");
        Files.writeString(project, """
                {"format":1,"id":"assertions","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},
                    "examples":[{"name":"values","payload":[]}]},
                  {"id":"publish","use":"railix.field-manipulation","inputs":{
                    "field":["context","result"],"value":[{"option":"literal","inputs":{"literal":{
                      "nullable":null,"integer":9007199254740993,"decimal":1.0000000000000001,
                      "nested":{"a":1,"b":2},"array":[1,null,"x"]}}}],"steps":[]}},
                  {"id":"overwrite","use":"railix.field-manipulation","inputs":{
                    "field":["context","result"],"value":[{"option":"literal","inputs":{"literal":"later"}}],"steps":[]}}
                ],"links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"publish"},
                  {"from":"publish.next","to":"overwrite"},{"from":"overwrite.next","to":"end"}]}
                """);
        server = start(project, workspace.resolve("home"));
        assertThat(awaitExampleView(server.baseUri(), "command:0").statusCode()).isEqualTo(200);
    }

    @AfterAll
    void closeApplication() {
        if (server != null) server.close();
    }

    @ParameterizedTest
    @CsvSource(value = {
            "nullable|isEqualTo|null|passed",
            "missing|doesNotExist||passed",
            "nullable|doesNotExist||failed",
            "missing|isEqualTo|null|failed",
            "integer|isEqualTo|9007199254740993|passed",
            "integer|isEqualTo|9007199254740992|failed",
            "decimal|isEqualTo|1.0000000000000001|passed",
            "decimal|isEqualTo|1|failed",
            "nested|isEqualTo|{\"b\":2.00,\"a\":1.0}|passed",
            "array|isEqualTo|[1.00,null,\"x\"]|passed",
            "array|isEqualTo|[null,1,\"x\"]|failed"
    }, delimiter = '|', nullValues = "")
    void checksExactRecordedValuesRatherThanLaterContext(final String field, final String assertion,
                                                         final String expected, final String status) throws Exception {
        final String before = Files.readString(project);
        final var counters = object(request(server.baseUri(), "GET", "/api/metrics", "").body()).values().get("steps");
        final String check = "{\"path\":[\"context\",\"result\",\"" + field + "\"],\"assertion\":\"" + assertion
                + "\"" + (expected == null ? "" : ",\"expected\":" + expected) + "}";
        final var response = request(server.baseUri(), "POST", "/api/assertions/check", command(check));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(string(object(response.body()), "status")).isEqualTo(status);
        assertThat(Files.readString(project)).isEqualTo(before);
        assertThat(object(request(server.baseUri(), "GET", "/api/metrics", "").body()).values().get("steps")).isEqualTo(counters);
    }

    @Test
    void rejectsEvidenceFromAnotherApplication() throws Exception {
        final var fields = new LinkedHashMap<>(object(command("{\"path\":[\"context\"],\"assertion\":\"doesNotExist\"}")).values());
        fields.put("application_pid", RailixValue.number(number(application(server.baseUri()), "pid") + 1));
        assertThat(request(server.baseUri(), "POST", "/api/assertions/check", RailixJson.write(RailixValue.object(fields))).statusCode())
                .isEqualTo(409);
    }

    @Test
    void rejectsUnsupportedAssertionsWithoutExecutingAnything() throws Exception {
        final var response = request(server.baseUri(), "POST", "/api/assertions/check",
                command("{\"path\":[\"context\"],\"assertion\":\"executeJava\"}"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(422);
        assertThat(response.body()).contains("ASSERTION_OPERATOR_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "null", "{}", "{\"path\":[],\"assertion\":\"doesNotExist\"}",
            "{\"path\":[\"context\",-1],\"assertion\":\"doesNotExist\"}",
            "{\"path\":[\"context\",0.5],\"assertion\":\"doesNotExist\"}",
            "{\"path\":[\"context\",2147483648],\"assertion\":\"doesNotExist\"}",
            "{\"path\":[\"context\"],\"assertion\":\"isEqualTo\"}",
            "{\"path\":[\"context\"],\"assertion\":\"doesNotExist\",\"expected\":null}",
            "{\"path\":[\"context\"],\"assertion\":\"doesNotExist\",\"script\":\"run()\"}"
    })
    void invalidChecksAreRejectedAtTheBoundary(final String check) throws Exception {
        assertThat(request(server.baseUri(), "POST", "/api/assertions/check", command(check)).statusCode()).isEqualTo(422);
    }

    @Test
    void arrayPositionsAreCheckedWithoutCoercion() throws Exception {
        final var response = request(server.baseUri(), "POST", "/api/assertions/check", command(
                "{\"path\":[\"context\",\"result\",\"array\",0],\"assertion\":\"isEqualTo\",\"expected\":1.0}"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(string(object(response.body()), "status")).isEqualTo("passed");
    }

    @Test
    void theOperatorCatalogUsesTheSameProtectedBoundary() throws Exception {
        final var catalog = request(server.baseUri(), "GET", "/api/assertions", "");
        assertThat(catalog.statusCode()).isEqualTo(200);
        assertThat(catalog.body()).contains("isEqualTo", "doesNotExist");
        final var anonymous = java.net.URI.create(server.baseUri().toString().split("#")[0]);
        assertThat(request(anonymous, "POST", "/api/assertions/check", command(
                "{\"path\":[\"context\"],\"assertion\":\"doesNotExist\"}")).statusCode()).isEqualTo(401);
    }

    @Test
    void responseSizeRemainsBoundedEvenWhenManyChecksSelectTheWholeContext() throws Exception {
        final String check = "{\"path\":[\"context\"],\"assertion\":\"doesNotExist\"}";
        final var response = request(server.baseUri(), "POST", "/api/assertions/check",
                command(String.join(",", java.util.Collections.nCopies(8_000, check))));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(413);
        assertThat(response.body()).contains("assertion-result-too-large");
    }

    @Test
    void anUntakenBranchDoesNotPassAnAbsenceExpectation() throws Exception {
        final Path file = directory.resolve("railix.project.json");
        Files.writeString(file, """
                {"format":1,"id":"unreached-evidence","nodes":[
                {"id":"app","use":"railix.app","inputs":{}},
                {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{"name":"first","payload":[]}]},
                {"id":"branch","use":"railix.branch","inputs":{"routes":[
                  {"outcome":"left","groups":[],"inputs":{}},{"outcome":"right","groups":[],"inputs":{}}]}},
                {"id":"unused","use":"railix.field-manipulation","inputs":{}}],
                "links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"branch"},
                {"from":"branch.left","to":"end"},{"from":"branch.right","to":"unused"},
                {"from":"branch.otherwise","to":"end"},{"from":"unused.next","to":"end"}]}
                """);
        try (final var other = start(file)) {
            assertThat(awaitExampleView(other.baseUri(), "command:0").statusCode()).isEqualTo(200);
            final var response = request(other.baseUri(), "POST", "/api/assertions/check",
                    command(other, "unused", "{\"path\":[\"context\",\"missing\"],\"assertion\":\"doesNotExist\"}"));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(response.body()).contains("ASSERTION_STEP_NOT_REACHED", "\"status\":\"failed\"");
        }
    }

    @Test
    void installedStepsUseExactlyTheSameChecks() throws Exception {
        final Path file = directory.resolve("railix.project.json");
        GeneratedApplicationFixture.installedCatalog(directory, List.of(StepDefinition.named("test.write", "1")
                .input("field", StepDefinition.Input.path(StepDefinition.PathAccess.WRITE).defaultPath("context", "result"))
                .input("value", StepDefinition.Input.json(ValueShape.ANY).defaultValue(RailixValue.number(42)))
                .implementation(BranchingSteps.Write.class)), BranchingSteps.Write.class);
        Files.writeString(file, """
                {"format":1,"id":"installed-evidence","nodes":[
                {"id":"app","use":"railix.app","inputs":{}},
                {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{"name":"answer","payload":[]}]},
                {"id":"publish","use":"test.write","inputs":{}}],
                "links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"publish"},
                {"from":"publish.next","to":"end"}]}
                """);
        try (final var other = start(file)) {
            assertThat(awaitExampleView(other.baseUri(), "command:0").statusCode()).isEqualTo(200);
            final var response = request(other.baseUri(), "POST", "/api/assertions/check",
                    command(other, "publish", "{\"path\":[\"context\",\"result\"],\"assertion\":\"isEqualTo\",\"expected\":42}"));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(string(object(response.body()), "status")).isEqualTo("passed");
        }
    }

    @Test
    void replacingTheApplicationInvalidatesAnEarlierCheckRequest() throws Exception {
        final Path file = directory.resolve("railix.project.json");
        Files.writeString(file, RailixJson.write(object(request(server.baseUri(), "GET", "/api/project", "").body()).values().get("project")));
        try (final var other = start(file)) {
            assertThat(awaitExampleView(other.baseUri(), "command:0").statusCode()).isEqualTo(200);
            final String earlier = command(other, "publish", "{\"path\":[\"context\"],\"assertion\":\"doesNotExist\"}");
            assertThat(request(other.baseUri(), "POST", "/api/project",
                    Files.readString(file).replace("\"later\"", "\"changed\"")).statusCode()).isEqualTo(200);
            assertThat(request(other.baseUri(), "POST", "/api/assertions/check", earlier).statusCode()).isEqualTo(409);
        }
    }

    @Test
    void aLaterStepFailureDoesNotInvalidateAnEarlierCompletedSnapshot() throws Exception {
        final Path file = directory.resolve("railix.project.json");
        GeneratedApplicationFixture.installedCatalog(directory, List.of(StepDefinition.named("test.fault", "1")
                .implementation(DevelopmentRuntimeConformanceSteps.Fault.class)), DevelopmentRuntimeConformanceSteps.Fault.class);
        final var graph = (RailixValue.ObjectValue) object(request(server.baseUri(), "GET", "/api/project", "").body()).values().get("project");
        final var nodes = new java.util.ArrayList<>(((RailixValue.ArrayValue) graph.values().get("nodes")).values());
        nodes.set(3, object("{\"id\":\"overwrite\",\"use\":\"test.fault\",\"inputs\":{}}"));
        Files.writeString(file, RailixJson.write(CreatorChanges.with(graph, "nodes", RailixValue.array(nodes))));
        try (final var other = start(file)) {
            assertThat(awaitExampleView(other.baseUri(), "command:0").statusCode()).isEqualTo(200);
            final var response = request(other.baseUri(), "POST", "/api/assertions/check",
                    command(other, "publish", "{\"path\":[\"context\",\"result\",\"integer\"],\"assertion\":\"isEqualTo\",\"expected\":9007199254740993}"));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(string(object(response.body()), "status")).isEqualTo("passed");
            assertThat(string(application(other.baseUri()), "state")).isEqualTo("running");
        }
    }

    @Test
    void incompleteTraceCannotProveThatAnExpectedStepWasNotReached() throws Exception {
        final Path file = directory.resolve("railix.project.json");
        GeneratedApplicationFixture.installedCatalog(directory, List.of(StepDefinition.named("test.oversized-trace", "1")
                .input("target", StepDefinition.Input.path(StepDefinition.PathAccess.READ_WRITE)
                        .defaultPath("context", "large"))
                .implementation(DevelopmentRuntimeConformanceSteps.OversizedTrace.class)),
                DevelopmentRuntimeConformanceSteps.OversizedTrace.class);
        Files.writeString(file, """
                {"format":1,"id":"incomplete-evidence","nodes":[
                {"id":"app","use":"railix.app","inputs":{}},
                {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{"name":"large","payload":[]}]},
                {"id":"large","use":"test.oversized-trace","inputs":{}},
                {"id":"downstream","use":"railix.field-manipulation","inputs":{}}],
                "links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"large"},
                {"from":"large.next","to":"downstream"},{"from":"downstream.next","to":"end"}]}
                """);
        try (final var other = start(file)) {
            assertThat(awaitExampleView(other.baseUri(), "command:0").statusCode()).isEqualTo(200);
            final String check = command(other, "downstream", "{\"path\":[\"context\",\"missing\"],\"assertion\":\"doesNotExist\"}");
            final var response = request(other.baseUri(), "POST", "/api/assertions/check", check);
            assertThat(response.statusCode()).as("%s request=%s", response.body(), check).isEqualTo(503);
            assertThat(response.body()).contains("assertion-evidence-incomplete").doesNotContain("ASSERTION_STEP_NOT_REACHED");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sequential", "parallel"})
    void repeatedVisitsNeverSilentlyUseTheLastResult(final String execution) throws Exception {
        final Path file = directory.resolve("railix.project.json");
        Files.writeString(file, """
                {"format":1,"id":"repeated-evidence","nodes":[
                {"id":"app","use":"railix.app","inputs":{}},
                {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{"name":"both","payload":[]}]},
                {"id":"branch","use":"railix.branch","inputs":{"selection":{"option":"all","inputs":{}},
                  "execution":{"option":"%s","inputs":{}},"routes":[
                  {"outcome":"left","groups":[],"inputs":{}},{"outcome":"right","groups":[],"inputs":{}}]}},
                {"id":"publish","use":"railix.field-manipulation","inputs":{}}],
                "links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"branch"},
                {"from":"branch.left","to":"publish"},{"from":"branch.right","to":"publish"},
                {"from":"branch.otherwise","to":"end"},{"from":"publish.next","to":"end"}]}
                """.formatted(execution));
        try (final var other = start(file)) {
            assertThat(awaitExampleView(other.baseUri(), "command:0").statusCode()).isEqualTo(200);
            final var projection = request(other.baseUri(), "GET", "/api/examples/command:0/steps/3", "");
            assertThat(number(object(projection.body()), "visits")).isEqualTo(2);
            final var response = request(other.baseUri(), "POST", "/api/assertions/check",
                    command(other, "publish", "{\"path\":[\"context\",\"missing\"],\"assertion\":\"doesNotExist\"}"));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
            assertThat(response.body()).contains("ASSERTION_STEP_AMBIGUOUS");
        }
    }

    private String command(final String check) throws Exception {
        return command(server, "publish", check);
    }

    private String command(final CreatorServer target, final String step, final String check) throws Exception {
        return RailixJson.write(RailixValue.object(Map.of(
                "revision", object(request(target.baseUri(), "GET", "/api/project", "").body()).values().get("revision"),
                "application_pid", application(target.baseUri()).values().get("pid"),
                "example", RailixValue.string("command:0"),
                "step", RailixValue.string(step),
                "checks", ((RailixJson.Parsed) RailixJson.parse("[" + check + "]")).value()
        )));
    }
}
