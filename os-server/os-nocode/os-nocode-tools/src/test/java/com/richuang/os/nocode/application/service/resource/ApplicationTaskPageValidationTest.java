package com.richuang.os.nocode.application.service.resource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.nocode.api.ApplicationCenter;
import com.richuang.os.nocode.api.ApplicationUi;
import com.richuang.os.nocode.api.DataCenter;
import com.richuang.os.nocode.metadata.service.object.DraftValidator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** 任务区块属于受控页面协议；不能嵌进字段表单或绑定错误的当前记录对象。 */
class ApplicationTaskPageValidationTest {
    private final ApplicationPageValidator validator = new ApplicationPageValidator();
    private final ApplicationResourceContext resources = new ApplicationResourceContext();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void initialize() {
        ReflectionTestUtils.setField(resources, "json", json);
        resources.initialize();
        ReflectionTestUtils.setField(validator, "resourceContext", resources);
        ReflectionTestUtils.setField(validator, "validator", new DraftValidator());
        ReflectionTestUtils.setField(validator, "reports", new ApplicationReportValidator());
        ReflectionTestUtils.setField(validator, "pageBindings", new ApplicationPageBindings());
    }

    @Test
    void acceptsApplicationTaskBlockAndRefreshWithoutBusinessForm() {
        ApplicationUi.Node tasks =
                json.convertValue(
                        Map.of(
                                "id",
                                "tasks",
                                "type",
                                "TASKS",
                                "taskView",
                                Map.of(
                                        "columnKeys", List.of("title", "owner", "status"),
                                        "taskFilter", Map.of("statuses", List.of("PENDING")),
                                        "sort", Map.of("field", "createdAt", "descending", true))),
                        ApplicationUi.Node.class);
        ApplicationUi.Node refresh =
                json.convertValue(
                        Map.of(
                                "id",
                                "refresh",
                                "type",
                                "BUTTON",
                                "text",
                                "刷新任务",
                                "action",
                                Map.of("kind", "REFRESH", "targetNodeId", "tasks")),
                        ApplicationUi.Node.class);
        ApplicationUi.Page page = new ApplicationUi.Page(List.of(tasks, refresh));
        assertThatCode(
                        () -> {
                            validator.nodes(
                                    page.nodes(),
                                    Map.of(),
                                    Map.of(),
                                    new HashSet<>(),
                                    false,
                                    0,
                                    new HashSet<>());
                            validator.pageContext(page, page.nodes(), Map.of(), Map.of());
                            validator.pageActions(page, page.nodes(), Map.of(), Map.of());
                        })
                .doesNotThrowAnyException();
        ApplicationUi.Node invalid =
                json.convertValue(
                        Map.of(
                                "id",
                                "tasks",
                                "type",
                                "TASKS",
                                "taskView",
                                Map.of("columnKeys", List.of("business:amount"))),
                        ApplicationUi.Node.class);
        assertThatThrownBy(
                        () ->
                                validator.pageContext(
                                        new ApplicationUi.Page(List.of(invalid)),
                                        List.of(invalid),
                                        Map.of(),
                                        Map.of()))
                .hasMessageContaining("选择任务业务表单");
        ApplicationUi.Page contextual = new ApplicationUi.Page(List.of(tasks), "project", 2);
        assertThatCode(
                        () ->
                                validator.pageContext(
                                        contextual, contextual.nodes(), Map.of(), Map.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsTaskBlockBesideFinanceAndFiles() {
        List<ApplicationUi.Node> nodes =
                List.of(
                        node("tasks", "TASKS", "project"),
                        node("finance", "VIEW", "finance"),
                        node("files", "ATTACHMENTS", "project"));
        Map<String, ApplicationCenter.Resource> bindings =
                Map.of(
                        "project",
                        form("project", "project-object"),
                        "finance",
                        new ApplicationCenter.Resource(
                                "finance", "VIEW", "finance", "财务", Map.of()));
        assertThatCode(
                        () ->
                                validator.nodes(
                                        nodes,
                                        bindings,
                                        Map.of(),
                                        new HashSet<>(),
                                        false,
                                        0,
                                        new HashSet<>()))
                .doesNotThrowAnyException();
        ApplicationUi.Page page =
                json.convertValue(
                        Map.of("contextObjectId", "project-object", "nodes", nodes),
                        ApplicationUi.Page.class);
        assertThatCode(() -> validator.pageContext(page, nodes, bindings, Map.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsWrongResourceAndCurrentRecordObject() {
        ApplicationUi.Node task = node("tasks", "TASKS", "project");
        Map<String, ApplicationCenter.Resource> bindings =
                Map.of("project", form("project", "other-object"));
        ApplicationUi.Page page =
                json.convertValue(
                        Map.of("contextObjectId", "project-object", "nodes", List.of(task)),
                        ApplicationUi.Page.class);
        assertThatThrownBy(() -> validator.pageContext(page, List.of(task), bindings, Map.of()))
                .hasMessageContaining("当前对象");
        Map<String, ApplicationCenter.Resource> wrong =
                Map.of(
                        "project",
                        new ApplicationCenter.Resource(
                                "project", "VIEW", "project", "项目列表", Map.of()));
        assertThatThrownBy(
                        () ->
                                validator.nodes(
                                        List.of(task),
                                        wrong,
                                        Map.of(),
                                        new HashSet<>(),
                                        false,
                                        0,
                                        new HashSet<>()))
                .hasMessageContaining("类型不匹配");
        assertThatThrownBy(
                        () ->
                                validator.nodes(
                                        List.of(task),
                                        bindings,
                                        Map.of(),
                                        new HashSet<>(),
                                        true,
                                        0,
                                        new HashSet<>()))
                .hasMessageContaining("字段表单");
    }

    @Test
    void acceptsBusinessTaskTableWithoutRecordContext() {
        List<ApplicationUi.Node> nodes = List.of(node("tasks", "TASKS", "work"));
        Map<String, ApplicationCenter.Resource> bindings =
                Map.of("work", form("work", "construction-object"));
        ApplicationUi.Page page =
                json.convertValue(Map.of("nodes", nodes), ApplicationUi.Page.class);
        assertThatCode(() -> validator.pageContext(page, nodes, bindings, Map.of()))
                .doesNotThrowAnyException();
    }

    private ApplicationUi.Node node(String id, String type, String resourceId) {
        return json.convertValue(
                Map.of("id", id, "type", type, "resourceId", resourceId, "children", List.of()),
                ApplicationUi.Node.class);
    }

    @Test
    void taskBusinessFieldsAreIndependentOfCurrentRecordAndInvalidPublishedRulesAreRejected() {
        Map<String, ApplicationCenter.Resource> bindings =
                Map.of(
                        "project",
                        form("project", "project-object"),
                        "work",
                        form("work", "work-object"));
        DataCenter.Definition work =
                json.convertValue(
                        Map.of(
                                "objectId",
                                "work-object",
                                "fields",
                                List.of(
                                        Map.of(
                                                "id", "amount", "code", "amount", "name", "工程量",
                                                "type", "INTEGER")),
                                "details",
                                List.of(),
                                "fieldOptions",
                                Map.of(),
                                "relations",
                                List.of()),
                        DataCenter.Definition.class);
        Map<String, DataCenter.Definition> definitions = Map.of("work-object", work);
        ApplicationUi.Node node =
                json.convertValue(
                        Map.of(
                                "id",
                                "tasks",
                                "type",
                                "TASKS",
                                "resourceId",
                                "project",
                                "taskView",
                                Map.of(
                                        "businessFormId",
                                        "work",
                                        "columnKeys",
                                        List.of("title", "business:amount"),
                                        "sort",
                                        Map.of("field", "business:amount", "descending", true),
                                        "conditions",
                                        Map.of(
                                                "logic",
                                                "AND",
                                                "items",
                                                List.of(
                                                        Map.of(
                                                                "type",
                                                                "condition",
                                                                "field",
                                                                "amount",
                                                                "operator",
                                                                "gte",
                                                                "value",
                                                                10))))),
                        ApplicationUi.Node.class);
        ApplicationUi.Page page =
                json.convertValue(
                        Map.of("contextObjectId", "project-object", "nodes", List.of(node)),
                        ApplicationUi.Page.class);
        assertThatCode(() -> validator.pageContext(page, page.nodes(), bindings, definitions))
                .doesNotThrowAnyException();
        // 当前记录由页面提供；实际任务业务字段仍独立绑定工作表单。
        ApplicationUi.Node unbound =
                json.convertValue(
                        Map.of("id", "tasks", "type", "TASKS", "taskView", node.taskView()),
                        ApplicationUi.Node.class);
        assertThatCode(() -> validator.pageContext(page, List.of(unbound), bindings, definitions))
                .doesNotThrowAnyException();
        for (Map<String, Object> invalid :
                List.<Map<String, Object>>of(
                        Map.of("businessFormId", "work", "columnKeys", List.of("business:missing")),
                        Map.of(
                                "businessFormId",
                                "work",
                                "sort",
                                Map.of("field", "raw sql", "descending", false)),
                        Map.of(
                                "businessFormId",
                                "work",
                                "taskFilter",
                                Map.of("statuses", List.of("INVALID"))),
                        Map.of("columnKeys", List.of("business:amount")))) {
            ApplicationUi.Node bad =
                    json.convertValue(
                            Map.of(
                                    "id",
                                    "tasks",
                                    "type",
                                    "TASKS",
                                    "resourceId",
                                    "project",
                                    "taskView",
                                    invalid),
                            ApplicationUi.Node.class);
            assertThatThrownBy(
                            () -> validator.pageContext(page, List.of(bad), bindings, definitions))
                    .isInstanceOf(RuntimeException.class);
        }
        ApplicationUi.Node text =
                json.convertValue(
                        Map.of("id", "text", "type", "TEXT", "taskView", Map.of()),
                        ApplicationUi.Node.class);
        assertThatThrownBy(
                        () ->
                                validator.nodes(
                                        List.of(text),
                                        bindings,
                                        Map.of(),
                                        new HashSet<>(),
                                        false,
                                        0,
                                        new HashSet<>()))
                .hasMessageContaining("仅任务区块");
    }

    private ApplicationCenter.Resource form(String id, String objectId) {
        return new ApplicationCenter.Resource(
                id,
                "FORM",
                id,
                "表单",
                Map.of("objectId", objectId, "nodes", List.of(), "detailIds", List.of()));
    }
}
