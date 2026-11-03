package dev.nanonative.railix.creator;

import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

final class RailixCreatorBranchBrowserIT extends RailixCreatorBrowserSupport {
    @Test
    @Tag("responsive")
    void resettingAnotherFieldDiscardsTheInvalidDraftBeforeSaving() {
        openProject(branchProject("reset-draft", "choice", """
                [[{"option":"literal","inputs":{"value":true},"when":{"transforms":[],
                  "all":[[{"use":"value.equals","inputs":{"expected":false}}]]}}]]
                """));
        selectWorldNode("choice");
        page.locator(".candidate-source [data-input-json]").first().fill("{");
        page.locator(".candidate-source [data-input-json]").first().press("Tab");
        page.locator(".condition-predicate [data-reset-input]").first().click();
        waitForText("#build-state", "Built");
        assertThat(page.locator(".candidate-source [data-input-json]").first()).hasValue("true");
        assertThat(page.locator(".condition-predicate [data-input-json]").first()).hasValue("null");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void resettingALiteralRestoresItsDefaultWithoutTreatingEmptyStringsAsMissing() {
        openProject(branchProject("literal-default", "choice", """
                [[{"option":"literal","inputs":{"value":true},"when":{"transforms":[],"all":[]}}]]
                """));
        selectWorldNode("choice");
        final var source = page.locator(".candidate-source").first();
        source.locator("[data-input-json]").fill("");
        source.locator("[data-input-json]").press("Tab");
        source.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Reset").setExact(true)).click();
        waitForText("#build-state", "Built");
        assertThat(source.locator("[data-input-json]")).hasValue("null");
        assertThat(page.evaluate("""
                async () => Object.keys((await (await fetch('/api/project')).json()).project.nodes
                  .find(node => node.id==='choice').inputs.routes[0].groups[0][0].inputs)
                """)).isEqualTo(java.util.List.of());
        clickAndWaitForCreatorSave(() -> {
            source.locator("[data-input-json]").fill("\"\"");
            source.locator("[data-input-json]").press("Tab");
        });
        assertThat(page.evaluate("""
                async () => (await (await fetch('/api/project')).json()).project.nodes
                  .find(node => node.id==='choice').inputs.routes[0].groups[0][0].inputs.value
                """)).isEqualTo("");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void branchingSettingsRoutesAndContextOwnershipSurviveSaveUndoAndReopen() throws Exception {
        openProject(project());
        selectWorldNode("dispatch");
        assertThat(page.locator("#selection-option")).hasValue("all");
        assertThat(page.locator("#execution-option")).hasValue("parallel");
        assertThat(page.locator(".route-input > .candidate-list > .route")).hasCount(4);
        clickAndWaitForCreatorSave(() -> page.locator("#execution-option").selectOption("sequential"));
        clickAndWaitForCreatorSave(() -> page.locator("[data-input-name='own_context'] input").first().check());
        clickAndWaitForCreatorSave(() -> page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Add route").setExact(true)).click());
        assertThat(page.locator(".route-input > .candidate-list > .route")).hasCount(5);
        clickAndWaitForCreatorSave(() -> page.locator("#editor-undo").click());
        assertThat(page.locator(".route-input > .candidate-list > .route")).hasCount(4);
        clickAndWaitForCreatorSave(() -> page.locator("#editor-redo").click());
        assertThat(page.locator(".route-input > .candidate-list > .route")).hasCount(5);
        page.reload();
        waitForText("#build-state", "Built");
        selectWorldNode("dispatch");
        assertThat(page.locator("#execution-option")).hasValue("sequential");
        assertThat(page.locator("[data-input-name='own_context'] input").first()).isChecked();
        assertThat(page.locator("[data-input-name='own_context'] select")).hasCount(0);
        assertThat(page.locator(".route-input > .candidate-list > .route")).hasCount(5);
        chooseVisibleExample("command:0");
        assertThat(page.locator("#visible-example")).hasValue("command:0");
        assertThat(page.locator("[data-world-id='dispatch']")).hasAttribute("data-coverage", "selected");
        final var inspector = page.locator("#inspector").boundingBox();
        assertThat(inspector.x).isGreaterThanOrEqualTo(0);
        assertThat(inspector.x + inspector.width).isLessThanOrEqualTo(page.viewportSize().width + 1);
        page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(Path.of("target", "screenshots"))
                .resolve("unified-branch-" + page.viewportSize().width + ".png")));
        assertThat(pageErrors).isEmpty();
    }

    static String project() {
        final var nodes = new StringBuilder("""
                {"format":1,"id":"fulfilment","nodes":[
                {"id":"app","use":"railix.app","inputs":{}},
                {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{"name":"order","payload":[]}]},
                {"id":"dispatch","use":"railix.branch","inputs":{"selection":{"option":"all","inputs":{}},
                "execution":{"option":"parallel","inputs":{}},"routes":[
                {"outcome":"stock","groups":[],"inputs":{}},{"outcome":"billing","groups":[],"inputs":{}},
                {"outcome":"shipping","groups":[],"inputs":{}},{"outcome":"audit","groups":[],"inputs":{}}]}}
                """);
        final var links = new StringBuilder("""
                ],"links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"dispatch"},
                {"from":"dispatch.otherwise","to":"end"}
                """);
        for (final String area : new String[]{"stock", "billing", "shipping", "audit"}) {
            nodes.append(",{\"id\":\"").append(area).append("\",\"use\":\"railix.branch\",\"inputs\":{\"selection\":{\"option\":\"all\",\"inputs\":{}},\"routes\":[{\"outcome\":\"prepare\",\"groups\":[],\"inputs\":{}},{\"outcome\":\"record\",\"groups\":[],\"inputs\":{}}]}}");
            links.append(",{\"from\":\"dispatch.").append(area).append("\",\"to\":\"").append(area).append("\"}")
                    .append(",{\"from\":\"").append(area).append(".otherwise\",\"to\":\"end\"}");
            for (final String route : new String[]{"prepare", "record"}) {
                final String prefix = area + "-" + route + "-";
                links.append(",{\"from\":\"").append(area).append('.').append(route).append("\",\"to\":\"").append(prefix).append("0\"}");
                for (int index = 0; index < 20; index++) {
                    nodes.append(",{\"id\":\"").append(prefix).append(index).append("\",\"use\":\"railix.field-manipulation\",\"inputs\":{}}");
                    links.append(",{\"from\":\"").append(prefix).append(index).append(".next\",\"to\":\"")
                            .append(index == 19 ? "end" : prefix + (index + 1)).append("\"}");
                }
            }
        }
        return nodes.append(links).append("]}").toString();
    }
}
