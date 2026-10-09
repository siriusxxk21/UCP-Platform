package com.richuang.os.nocode.application.service.resource;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.nocode.api.ApplicationCenter;
import com.richuang.os.nocode.api.ApplicationUi;
import com.richuang.os.nocode.metadata.service.object.DraftValidator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

/** 旧导航 JSON 兼容与新页面入口约束，独立于菜单标题及资源数组顺序。 */
class ApplicationNavigationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ApplicationResourceContext context = new ApplicationResourceContext();
    private final ApplicationNavigation navigation = new ApplicationNavigation();
    private final ApplicationPageValidator pages = new ApplicationPageValidator();

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(context, "json", json);
        context.initialize();
        ReflectionTestUtils.setField(navigation, "resources", context);
        ReflectionTestUtils.setField(pages, "resourceContext", context);
        ReflectionTestUtils.setField(pages, "validator", new DraftValidator());
    }

    @Test
    void legacyJsonKeepsItsExactShapeAndDoesNotBecomeManaged() {
        ApplicationUi.Menu legacy =
                context.decode(Map.of("targetId", "home"), ApplicationUi.Menu.class);
        assertThat(legacy.navigationVersion()).isNull();
        assertThat(json.convertValue(legacy, new TypeReference<Map<String, Object>>() {}))
                .containsExactlyEntriesOf(Map.of("targetId", "home"));
        assertThatCode(
                        () ->
                                navigation.validate(
                                        graph(
                                                page("home", null),
                                                resource("menu", "MENU", legacy))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMultipleHomesDuplicatePageSettingsAndMissingDirectory() {
        assertThatThrownBy(
                        () ->
                                navigation.validate(
                                        graph(
                                                page("home", null),
                                                page("other", null),
                                                setting("first", "home", "91", true),
                                                setting("second", "other", "91", true))))
                .hasMessageContaining("一个首页");
        assertThatThrownBy(
                        () ->
                                navigation.validate(
                                        graph(
                                                page("home", null),
                                                setting("first", "home", "91", false),
                                                setting("second", "home", "91", false))))
                .hasMessageContaining("一份菜单设置");
        assertThatThrownBy(
                        () ->
                                navigation.validate(
                                        graph(
                                                page("home", null),
                                                setting("first", "home", null, false))))
                .hasMessageContaining("选择平台一级目录");
        assertThatThrownBy(
                        () ->
                                navigation.validate(
                                        graph(
                                                page("detail", "17"),
                                                setting("first", "detail", "91", false))))
                .hasMessageContaining("不依赖当前记录");
    }

    @Test
    void hiddenHomeWorksWithoutPlatformDirectoryAndRequiresNoMenu() {
        ApplicationCenter.Resource home =
                resource(
                        "setting",
                        "MENU",
                        new ApplicationUi.Menu("home", 2, false, null, null, null, null, true));
        assertThatCode(() -> navigation.validate(graph(page("home", null), home)))
                .doesNotThrowAnyException();
    }

    @Test
    void navigateAcceptsPageOrViewDirectlyAndLegacyMenuButRejectsRecordPageAndWrongKinds() {
        ApplicationCenter.Resource page = page("home", null);
        ApplicationCenter.Resource view = resource("view", "VIEW", Map.of());
        ApplicationCenter.Resource menu = resource("menu", "MENU", new ApplicationUi.Menu("home"));
        Map<String, ApplicationCenter.Resource> graph =
                graph(page, view, menu, page("detail", "17"), resource("form", "FORM", Map.of()));
        for (String target : List.of("home", "view", "menu"))
            assertThatCode(() -> navigate(target, graph)).doesNotThrowAnyException();
        assertThatThrownBy(() -> navigate("detail", graph)).hasMessageContaining("不依赖当前记录");
        assertThatThrownBy(() -> navigate("form", graph)).hasMessageContaining("资源");
        assertThatThrownBy(() -> navigate("missing", graph)).hasMessageContaining("资源");
    }

    private void navigate(String target, Map<String, ApplicationCenter.Resource> graph) {
        ApplicationUi.Node node =
                json.convertValue(
                        Map.of(
                                "id",
                                "jump",
                                "type",
                                "BUTTON",
                                "text",
                                "打开",
                                "action",
                                Map.of("kind", "NAVIGATE", "resourceId", target)),
                        ApplicationUi.Node.class);
        ApplicationUi.Page page = new ApplicationUi.Page(List.of(node));
        pages.pageActions(page, page.nodes(), graph, Map.of());
    }

    private ApplicationCenter.Resource setting(
            String id, String target, String parent, boolean home) {
        return resource(
                id,
                "MENU",
                new ApplicationUi.Menu(target, 2, true, parent, null, null, null, home));
    }

    private ApplicationCenter.Resource page(String id, String context) {
        return resource(id, "PAGE", new ApplicationUi.Page(List.of(), context, 2));
    }

    private ApplicationCenter.Resource resource(String id, String kind, Object config) {
        return new ApplicationCenter.Resource(
                id,
                kind,
                id,
                id,
                json.convertValue(config, new TypeReference<Map<String, Object>>() {}));
    }

    private Map<String, ApplicationCenter.Resource> graph(ApplicationCenter.Resource... resources) {
        Map<String, ApplicationCenter.Resource> graph = new LinkedHashMap<>();
        for (ApplicationCenter.Resource resource : resources) graph.put(resource.id(), resource);
        return graph;
    }
}
