package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.application.service.resource.ApplicationPageValidator;
import com.lingan.ucp.nocode.application.service.resource.ApplicationResourceContext;
import com.lingan.ucp.nocode.metadata.service.form.DetailForms;
import com.lingan.ucp.nocode.runtime.service.application.ApplicationRuntimeService;
import com.lingan.ucp.nocode.runtime.service.work.WorkDraftQueryServiceImpl;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

/** 内部明细统一布局必须与旧表单、预览边界、运行权限投影同时兼容。 */
class InternalDetailLayoutTest {
    private static ApplicationUi.Node detail(String id, String mode) {
        return new ApplicationUi.Node(
                "node_" + id,
                "INTERNAL_DETAIL",
                null,
                null,
                "明细标题",
                24,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                new ApplicationUi.InternalDetailBinding(id, mode));
    }

    private static ApplicationUi.Node container(String type, ApplicationUi.Node child) {
        return new ApplicationUi.Node(
                "container_" + type, type, null, null, "区块", 24, List.of(child));
    }

    private static ApplicationUi.Form form(List<ApplicationUi.Node> nodes, String... ids) {
        return new ApplicationUi.Form("root", nodes, List.of(ids));
    }

    private static DataCenter.Definition definition() {
        return new DataCenter.Definition(
                "root",
                "root",
                "主对象",
                null,
                "public",
                "t_root",
                "GENERATED",
                false,
                null,
                DataCenter.Settings.defaults(),
                List.of(),
                Map.of(),
                List.of(),
                List.of(),
                List.of(
                        new DataCenter.Detail(
                                "a", "a", "明细一", "t_a", "ACTIVE", List.of(), Map.of(), List.of()),
                        new DataCenter.Detail(
                                "b", "b", "明细二", "t_b", "ACTIVE", List.of(), Map.of(), List.of()),
                        new DataCenter.Detail(
                                "stopped",
                                "stopped",
                                "停用明细",
                                "t_stopped",
                                "INACTIVE",
                                List.of(),
                                Map.of(),
                                List.of())));
    }

    @Test
    void oldJsonAndAllOldConstructorsKeepAbsentDetailBinding() throws Exception {
        var mapper = new ObjectMapper();
        var old =
                mapper.readValue(
                        "{\"id\":\"legacy\",\"type\":\"FIELD\",\"fieldId\":\"name\",\"children\":[]}",
                        ApplicationUi.Node.class);
        assertThat(old.detail()).isNull();
        assertThat(new ApplicationUi.Node("a", "TEXT", null, null, null, null, List.of()).detail())
                .isNull();
        assertThat(
                        new ApplicationUi.Node(
                                        "a", "TEXT", null, null, null, null, List.of(), null, null)
                                .detail())
                .isNull();
        assertThat(
                        new ApplicationUi.Node(
                                        "a", "TEXT", null, null, null, null, List.of(), null, null,
                                        null, null, null)
                                .detail())
                .isNull();
        assertThatCode(() -> DetailForms.validateLayout(form(List.of(old), "a", "b"), definition()))
                .doesNotThrowAnyException();
        var modern = detail("a", "CARDS");
        assertThat(mapper.readValue(mapper.writeValueAsString(modern), ApplicationUi.Node.class))
                .isEqualTo(modern);
    }

    @Test
    void detailCanOccupyRootCardTabOrColumnInBothModes() {
        for (String mode : List.of("GRID", "CARDS")) {
            var block = detail("a", mode);
            for (var layout :
                    List.of(
                            block,
                            container("CARD", block),
                            container("TABS", container("TAB", block)),
                            container("ROW", container("COLUMN", block))))
                assertThatCode(
                                () ->
                                        DetailForms.validateLayout(
                                                form(List.of(layout), "a"), definition()))
                        .doesNotThrowAnyException();
        }
    }

    @Test
    void explicitCanvasMustCoverEverySelectedDetailExactlyOnce() {
        assertThatThrownBy(
                        () ->
                                DetailForms.validateLayout(
                                        form(
                                                List.of(
                                                        detail("a", "GRID"),
                                                        container("CARD", detail("a", "CARDS"))),
                                                "a"),
                                        definition()))
                .hasMessageContaining("只能放置一次");
        assertThatThrownBy(
                        () ->
                                DetailForms.validateLayout(
                                        form(List.of(detail("a", "GRID")), "a", "b"), definition()))
                .hasMessageContaining("全部明细放入画布");
        assertThatThrownBy(
                        () ->
                                DetailForms.validateLayout(
                                        form(List.of(detail("a", "GRID")), "b"), definition()))
                .hasMessageContaining("当前对象已选");
        assertThatThrownBy(
                        () ->
                                DetailForms.validateLayout(
                                        form(List.of(detail("foreign", "GRID")), "foreign"),
                                        definition()))
                .hasMessageContaining("不存在");
        assertThatThrownBy(
                        () ->
                                DetailForms.validateLayout(
                                        form(List.of(detail("stopped", "GRID")), "stopped"),
                                        definition()))
                .hasMessageContaining("已停用");
    }

    @Test
    void invalidModeMissingBindingAndLayoutNestingAreRejected() {
        for (String mode : Arrays.asList(null, "", "TABLE"))
            assertThatThrownBy(
                            () ->
                                    DetailForms.validateLayout(
                                            form(List.of(detail("a", mode)), "a"), definition()))
                    .hasMessageContaining("InternalDetailModeEnum");
        assertThatThrownBy(
                        () ->
                                DetailForms.validateLayout(
                                        form(
                                                List.of(
                                                        new ApplicationUi.Node(
                                                                "missing",
                                                                "INTERNAL_DETAIL",
                                                                null,
                                                                null,
                                                                null,
                                                                null,
                                                                List.of())),
                                                "a"),
                                        definition()))
                .hasMessageContaining("必须绑定");
        assertThatThrownBy(
                        () ->
                                DetailForms.validateLayout(
                                        form(List.of(container("ROW", detail("a", "GRID"))), "a"),
                                        definition()))
                .hasMessageContaining("只能放在");
        var nested =
                new ApplicationUi.Node(
                        "outer",
                        "INTERNAL_DETAIL",
                        null,
                        null,
                        null,
                        null,
                        List.of(detail("b", "GRID")),
                        null,
                        null,
                        null,
                        null,
                        null,
                        new ApplicationUi.InternalDetailBinding("a", "GRID"));
        assertThatThrownBy(
                        () ->
                                DetailForms.validateLayout(
                                        form(List.of(nested), "a", "b"), definition()))
                .hasMessageContaining("不能包含子节点");
        var rowNested =
                new ApplicationUi.Form(
                        "root",
                        List.of(detail("a", "GRID")),
                        List.of("a"),
                        null,
                        Map.of("a", List.of(container("CARD", detail("a", "GRID")))));
        assertThatThrownBy(() -> DetailForms.validateLayout(rowNested, definition()))
                .hasMessageContaining("列配置不能再嵌套");
    }

    @Test
    void detailBindingsCannotMasqueradeAsFieldsResourcesOrPages() {
        var mapper = new ObjectMapper();
        for (String property : List.of("fieldId", "resourceId")) {
            var json = mapper.convertValue(detail("a", "GRID"), Map.class);
            json.put(property, "unexpected");
            var malformed = mapper.convertValue(json, ApplicationUi.Node.class);
            assertThatThrownBy(
                            () ->
                                    DetailForms.validateLayout(
                                            form(List.of(malformed), "a"), definition()))
                    .hasMessageContaining("不能绑定字段");
        }
        var disguised =
                new ApplicationUi.Node(
                        "disguised",
                        "TEXT",
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        new ApplicationUi.InternalDetailBinding("a", "GRID"));
        assertThatThrownBy(
                        () ->
                                DetailForms.validateLayout(
                                        form(List.of(disguised), "a"), definition()))
                .hasMessageContaining("仅内部明细节点");
        var pages = new ApplicationPageValidator();
        ReflectionTestUtils.setField(pages, "resourceContext", new ApplicationResourceContext());
        assertThatThrownBy(
                        () ->
                                ReflectionTestUtils.invokeMethod(
                                        pages,
                                        "nodes",
                                        List.of(detail("a", "GRID")),
                                        Map.of(),
                                        Map.of(),
                                        new HashSet<>(),
                                        false,
                                        0,
                                        new HashSet<>()))
                .hasMessageContaining("不能放入业务页面");
    }

    @Test
    void businessPathHintPresentationOnlyAppliesToAttachmentAndImageFields() {
        var pages = new ApplicationPageValidator();
        ReflectionTestUtils.setField(pages, "resourceContext", new ApplicationResourceContext());
        Map<String, FieldDefinition> fields =
                Map.of(
                        "att_id",
                        new FieldDefinition(
                                "att",
                                "att_id",
                                "att",
                                "附件",
                                "ATTACHMENT",
                                null,
                                null,
                                null,
                                false,
                                false,
                                0),
                        "text_id",
                        new FieldDefinition(
                                "text", "text_id", "text", "说明", "TEXT", null, null, null, false,
                                false, 1));
        var hidden =
                new ApplicationUi.FieldPresentation(
                        "附件", null, null, null, null, null, null, false);
        assertThatCode(
                        () ->
                                ReflectionTestUtils.invokeMethod(
                                        pages,
                                        "nodes",
                                        List.of(field("file_node", "att_id", hidden)),
                                        Map.of(),
                                        fields,
                                        new HashSet<>(),
                                        true,
                                        0,
                                        new HashSet<>()))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                ReflectionTestUtils.invokeMethod(
                                        pages,
                                        "nodes",
                                        List.of(field("text_node", "text_id", hidden)),
                                        Map.of(),
                                        fields,
                                        new HashSet<>(),
                                        true,
                                        0,
                                        new HashSet<>()))
                .hasMessageContaining("只有附件或图片字段可以设置保存位置提示");
    }

    private static ApplicationUi.Node field(
            String id, String fieldId, ApplicationUi.FieldPresentation presentation) {
        return new ApplicationUi.Node(
                id,
                "FIELD",
                fieldId,
                null,
                null,
                null,
                List.of(),
                null,
                presentation,
                null,
                null,
                null,
                null);
    }

    @Test
    void runtimeAndWorkDraftProjectionPreserveAllowedLayoutAndPruneUnauthorizedDetails() {
        var nodes = List.of(container("CARD", detail("a", "CARDS")), detail("b", "GRID"));
        var runtime = new ApplicationRuntimeService();
        List<ApplicationUi.Node> projected =
                ReflectionTestUtils.invokeMethod(
                        runtime, "nodes", nodes, Set.of(), Set.of(), Set.of("a"));
        assertThat(projected).hasSize(1);
        assertThat(projected.getFirst().children().getFirst().detail())
                .isEqualTo(new ApplicationUi.InternalDetailBinding("a", "CARDS"));
        var drafts = new WorkDraftQueryServiceImpl();
        List<ApplicationUi.Node> draftNodes =
                ReflectionTestUtils.invokeMethod(
                        drafts, "readableNodes", nodes, Set.of(), Set.of("a"));
        assertThat(draftNodes).isEqualTo(projected);
    }
}
