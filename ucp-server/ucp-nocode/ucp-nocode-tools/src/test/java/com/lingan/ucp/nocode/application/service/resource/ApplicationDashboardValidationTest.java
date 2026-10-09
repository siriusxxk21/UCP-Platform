package com.lingan.ucp.nocode.application.service.resource;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.metadata.service.object.DraftValidator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

/** 新看板资源沿用严格页面协议，记录输入和明细跳转不能越出固定应用声明。 */
class ApplicationDashboardValidationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ApplicationResourceContext context = new ApplicationResourceContext();
    private final ApplicationPageValidator pages = new ApplicationPageValidator();
    private final ApplicationResourceValidator decoder = new ApplicationResourceValidator();
    private final ApplicationDashboards.Reference pin =
            new ApplicationDashboards.Reference("17", 5, "a".repeat(64));

    @BeforeEach
    void initialize() {
        ReflectionTestUtils.setField(context, "json", json);
        context.initialize();
        ReflectionTestUtils.setField(pages, "resourceContext", context);
        ReflectionTestUtils.setField(pages, "validator", new DraftValidator());
        ReflectionTestUtils.setField(decoder, "resourceContext", context);
    }

    @Test
    void normalizesEmptyInputsAndRejectsUndeclaredRecordFieldsOrDuplicateParameters() {
        DataCenter.Definition object = object("5915", "TEXT");
        ApplicationDashboards.Config empty =
                ApplicationDashboardValidator.normalize(
                        new ApplicationDashboards.Config(pin, " ", null, null),
                        Map.of("5915", object),
                        Map.of());
        assertThat(empty.contextObjectId()).isNull();
        assertThat(empty.inputBindings()).isEmpty();
        assertThat(empty.detailViews()).isEmpty();
        assertThatThrownBy(
                        () ->
                                ApplicationDashboardValidator.normalize(
                                        config(
                                                "5915",
                                                new ApplicationDashboards.InputBinding(
                                                        "names", "RECORD_FIELD", null, "foreign")),
                                        Map.of("5915", object),
                                        Map.of()))
                .hasMessageContaining("可用标量字段");
        assertThatThrownBy(
                        () ->
                                ApplicationDashboardValidator.normalize(
                                        config(
                                                null,
                                                new ApplicationDashboards.InputBinding(
                                                        "names", "RECORD_ID", null, null)),
                                        Map.of("5915", object),
                                        Map.of()))
                .hasMessageContaining("上下文对象");
        assertThatThrownBy(
                        () ->
                                ApplicationDashboardValidator.normalize(
                                        new ApplicationDashboards.Config(
                                                pin,
                                                null,
                                                List.of(
                                                        new ApplicationDashboards.InputBinding(
                                                                "first",
                                                                "PARAMETER",
                                                                "company",
                                                                null),
                                                        new ApplicationDashboards.InputBinding(
                                                                "second",
                                                                "PARAMETER",
                                                                "company",
                                                                null)),
                                                List.of()),
                                        Map.of(),
                                        Map.of()))
                .hasMessageContaining("唯一参数名");
    }

    @Test
    void dashboardNodeRequiresItsResourceAndMatchingPageContextWithoutLegacyBinding() {
        ApplicationCenter.Resource resource =
                resource(
                        "board",
                        new ApplicationDashboards.Config(pin, "5915", List.of(), List.of()));
        ApplicationUi.Node node =
                node(Map.of("id", "block", "type", "REPORT_DASHBOARD", "resourceId", "board"));
        Map<String, ApplicationCenter.Resource> resources = Map.of("board", resource);
        assertThatCode(
                        () ->
                                pages.nodes(
                                        List.of(node),
                                        resources,
                                        Map.of(),
                                        new HashSet<>(),
                                        false,
                                        0,
                                        new HashSet<>()))
                .doesNotThrowAnyException();
        ApplicationUi.Page valid = page("5915", node);
        assertThatCode(() -> pages.pageContext(valid, valid.nodes(), resources, Map.of()))
                .doesNotThrowAnyException();
        ApplicationUi.Page wrong = page("5914", node);
        assertThatThrownBy(() -> pages.pageContext(wrong, wrong.nodes(), resources, Map.of()))
                .hasMessageContaining("当前对象一致");
        assertThatThrownBy(
                        () ->
                                pages.nodes(
                                        List.of(node),
                                        resources,
                                        Map.of(),
                                        new HashSet<>(),
                                        true,
                                        0,
                                        new HashSet<>()))
                .hasMessageContaining("字段表单");
        ApplicationUi.Node legacy =
                node(
                        Map.of(
                                "id",
                                "block",
                                "type",
                                "REPORT_DASHBOARD",
                                "resourceId",
                                "board",
                                "binding",
                                Map.of("relationId", "company")));
        assertThatThrownBy(
                        () ->
                                pages.nodes(
                                        List.of(legacy),
                                        resources,
                                        Map.of(),
                                        new HashSet<>(),
                                        false,
                                        0,
                                        new HashSet<>()))
                .hasMessageContaining("允许绑定关系");
    }

    @Test
    void fixedContractRejectsUnknownFilterWrongInputTypeAndForeignDetailView() {
        DataCenter.Definition object = object("5915", "TEXT");
        DataObjectApi objects = mock(DataObjectApi.class);
        DataObjectApi.PublishedObject published =
                new DataObjectApi.PublishedObject("5915", 1, "b".repeat(64), object);
        when(objects.getVersion("5915", 1)).thenReturn(published);
        ReportCatalogApi catalog = mock(ReportCatalogApi.class);
        ApplicationCenter.ObjectReference objectRef =
                new ApplicationCenter.ObjectReference("5915", 1, "b".repeat(64));
        ReportDashboards.Dataset dataset = new ReportDashboards.Dataset("1223", 1, "c".repeat(64));
        ReportDatasets.Source source =
                new ReportDatasets.Source(
                        1,
                        new ReportDatasets.ObjectReference("5915", 1, "b".repeat(64)),
                        List.of(),
                        List.of(
                                new ReportDatasets.Field(
                                        "name", List.of(), "name", "名称", "DIMENSION")));
        ReportDatasets.ResolvedSource resolved =
                new ReportDatasets.ResolvedSource(
                        source,
                        List.of(source.root()),
                        List.of(
                                new ReportDatasets.ResolvedField(
                                        "name",
                                        "名称",
                                        "DIMENSION",
                                        "INTEGER",
                                        "5915",
                                        1,
                                        List.of(),
                                        "name")));
        ReportDashboards.Chart chart =
                new ReportDashboards.Chart(
                        "table",
                        "列表",
                        "TABLE",
                        dataset,
                        List.of(new ReportDatasetQueries.Dimension("name", "VALUE")),
                        List.of("count"),
                        0,
                        0,
                        12,
                        4);
        ReportDashboards.Content board =
                new ReportDashboards.Content(
                        1,
                        "看板",
                        "",
                        List.of(chart),
                        List.of(
                                new ReportDashboards.Filter(
                                        "names",
                                        "名称",
                                        "SELECT",
                                        List.of(new ReportDashboards.Mapping("table", "name")))));
        ApplicationDashboards.Catalog contract =
                new ApplicationDashboards.Catalog(
                        pin,
                        board,
                        List.of(
                                new ApplicationDashboards.DatasetContract(
                                        dataset, null, resolved)));
        when(catalog.fixed(pin, List.of(objectRef), 10001)).thenReturn(contract);
        ApplicationDashboards.Config unknown =
                config(
                        null,
                        new ApplicationDashboards.InputBinding(
                                "foreign", "PARAMETER", "name", null));
        assertThatThrownBy(() -> validate(objectRef, resource("board", unknown), catalog, objects))
                .hasMessageContaining("不属于固定仪表板");
        ApplicationDashboards.Config wrongType =
                config(
                        "5915",
                        new ApplicationDashboards.InputBinding(
                                "names", "RECORD_FIELD", null, "name"));
        assertThatThrownBy(
                        () -> validate(objectRef, resource("board", wrongType), catalog, objects))
                .hasMessageContaining("类型不兼容");
        ApplicationCenter.Resource view =
                new ApplicationCenter.Resource(
                        "foreign", "VIEW", "foreign", "异对象视图", Map.of("objectId", "5914"));
        ApplicationCenter.Resource detail =
                resource(
                        "board",
                        new ApplicationDashboards.Config(
                                pin,
                                null,
                                List.of(),
                                List.of(new ApplicationDashboards.DetailView("table", "foreign"))));
        assertThatThrownBy(
                        () ->
                                ApplicationDashboardValidator.validate(
                                        new ApplicationCenter.Definition(
                                                List.of(objectRef), List.of(detail, view)),
                                        catalog,
                                        decoder,
                                        objects,
                                        10001))
                .hasMessageContaining("数据集根对象");
        ApplicationDashboards.Config parameter =
                config(
                        null,
                        new ApplicationDashboards.InputBinding(
                                "names", "PARAMETER", "company", null));
        assertThat(validate(objectRef, resource("board", parameter), catalog, objects))
                .containsExactly(pin);
    }

    private List<ApplicationDashboards.Reference> validate(
            ApplicationCenter.ObjectReference object,
            ApplicationCenter.Resource resource,
            ReportCatalogApi catalog,
            DataObjectApi objects) {
        return ApplicationDashboardValidator.validate(
                new ApplicationCenter.Definition(List.of(object), List.of(resource)),
                catalog,
                decoder,
                objects,
                10001);
    }

    private ApplicationDashboards.Config config(
            String contextObject, ApplicationDashboards.InputBinding input) {
        return new ApplicationDashboards.Config(pin, contextObject, List.of(input), List.of());
    }

    private ApplicationCenter.Resource resource(String id, ApplicationDashboards.Config config) {
        return new ApplicationCenter.Resource(
                id,
                "REPORT_DASHBOARD",
                id,
                "经营看板",
                json.convertValue(config, new TypeReference<Map<String, Object>>() {}));
    }

    private DataCenter.Definition object(String id, String type) {
        return json.convertValue(
                Map.of(
                        "objectId",
                        id,
                        "fields",
                        List.of(Map.of("id", "name", "code", "name", "name", "名称", "type", type)),
                        "fieldOptions",
                        Map.of(),
                        "details",
                        List.of(),
                        "relations",
                        List.of()),
                DataCenter.Definition.class);
    }

    private ApplicationUi.Node node(Map<String, Object> value) {
        return json.convertValue(value, ApplicationUi.Node.class);
    }

    private ApplicationUi.Page page(String object, ApplicationUi.Node node) {
        return json.convertValue(
                Map.of("contextObjectId", object, "nodes", List.of(node)),
                ApplicationUi.Page.class);
    }
}
