package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.FieldRuleFixture.*;
import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.runtime.service.report.ApplicationReportService;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

/**
 * 挑取值来源对象在不经过 RecordService 的入口上的解析口径：报表（运行与预览）按应用固定版本，应用设计器预览按应用草稿固定版本，数据对象设计器预览按来源当前发布版（唯一例外）。
 */
class SelectionContextIntegrationTest {
    private FieldRuleFixture f;
    private DataCenter.Definition source;
    private DataCenter.Definition target;
    private String app;

    @BeforeAll
    static void open() throws Exception {
        connect();
    }

    @AfterAll
    static void shutdown() {
        close();
    }

    @BeforeEach
    void setup() {
        f = new FieldRuleFixture();
        source =
                f.object(
                        "source",
                        List.of(field("status", "状态", "SELECT")),
                        Map.of(
                                "status",
                                options(
                                        List.of(
                                                new DataCenter.Option("A", "甲", false),
                                                new DataCenter.Option("B", "乙", false)))),
                        List.of(),
                        List.of());
        target =
                f.object(
                        "target",
                        List.of(field("pick", "挑取值", "SELECT")),
                        Map.of("pick", placeholder()),
                        List.of(),
                        List.of());
        target =
                patch(
                        target,
                        id(target, "pick"),
                        o -> withOptions(o, List.of()).withSelection(pick()));
        app = f.app(source, target);
        // 这一组用例验证的是「应用按固定的对象版本解析挑取值」：把自动跟随单独关掉，来源发布新版本后应用才会停在旧版本上。
        NocodeIntegrationSupport.stopFollowing(app);
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    private SelectionFields.Source pick() {
        return new SelectionFields.Source(
                "OBJECT_FIELD_OPTIONS",
                null,
                null,
                List.of(),
                false,
                List.of(),
                "NONE",
                null,
                source.objectId(),
                id(source, "status"));
    }

    /** 来源对象发布兼容的新版本（甲改名为新甲、新增丁），应用未同步，仍固定在旧版本。 */
    private void republishSource() {
        f.republish(
                source,
                Map.of(
                        id(source, "status"),
                        withOptions(
                                source.fieldOptions().get(id(source, "status")),
                                List.of(
                                        new DataCenter.Option("A", "新甲", false),
                                        new DataCenter.Option("B", "乙", false),
                                        new DataCenter.Option("D", "丁", false)))));
    }

    private ApplicationReports.Config reportConfig() {
        return new ApplicationReports.Config(
                target.objectId(),
                List.of(new ApplicationReports.Dimension(id(target, "pick"), null, "VALUE")),
                List.of(new ApplicationReports.Metric("count", "记录数", "COUNT", null)),
                Map.of(),
                List.of(),
                null,
                "Asia/Shanghai",
                "TABLE",
                null,
                false,
                30,
                null);
    }

    private static List<String> labels(ApplicationReports.Result result) {
        return result.groups().stream().map(g -> g.labels().getFirst()).sorted().toList();
    }

    /** 报表：维度标签里的挑取值按本应用固定版本解析（运行与草稿预览）；来源发布新版本不影响已发布应用。 */
    @Test
    void reportLabelsUsePinnedVersion() {
        var mapper = servicesContext.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
        var before = f.applications.get(app);
        var saved =
                f.applications.save(
                        new ApplicationCenter.Save(
                                app,
                                before.application().revision(),
                                before.application().code(),
                                before.application().name(),
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        before.draft().objects(),
                                        List.of(
                                                new ApplicationCenter.Resource(
                                                        "pick_report",
                                                        "REPORT",
                                                        "pick_report",
                                                        "挑取值报表",
                                                        mapper.convertValue(
                                                                reportConfig(), Map.class))))),
                        10001);
        f.applications.publish(
                new ApplicationCenter.Revision(app, saved.application().revision(), "报表"), 10001);
        f.save(app, target, values(id(target, "name"), "一", id(target, "pick"), "A"));
        f.save(app, target, values(id(target, "name"), "二", id(target, "pick"), "B"));
        f.save(app, target, values(id(target, "name"), "三", id(target, "pick"), "A"));
        republishSource();
        var reports = servicesContext.getBean(ApplicationReportService.class);
        var result =
                reports.query(
                        new ApplicationReports.Query(
                                app, "pick_report", null, null, null, null, null, 1, 20),
                        10001);
        assertThat(labels(result)).containsExactly("乙", "甲");
        var preview =
                reports.preview(
                        new ApplicationReports.Preview(
                                app,
                                f.applications.get(app).draft().objects(),
                                reportConfig(),
                                List.of()),
                        10001);
        assertThat(labels(preview)).containsExactly("乙", "甲");
    }

    /** 应用设计器预览：按正在设计的应用草稿里固定的对象版本；不带应用时挑取值 fail-closed。 */
    @Test
    void applicationDesignerUsesDraftPinnedVersion() {
        republishSource();
        var controller = new com.richuang.os.nocode.controller.admin.ApplicationController();
        var access = Mockito.mock(com.richuang.os.nocode.web.NocodeAccess.class);
        Mockito.when(access.actor()).thenReturn(10001L);
        ReflectionTestUtils.setField(
                controller,
                "applications",
                servicesContext.getBean(
                        com.richuang.os.nocode.application.service.application.ApplicationService
                                .class));
        ReflectionTestUtils.setField(
                controller, "objects", servicesContext.getBean(DataObjectApi.class));
        ReflectionTestUtils.setField(
                controller,
                "selections",
                servicesContext.getBean(
                        com.richuang.os.nocode.runtime.service.selection.SelectionCatalog.class));
        ReflectionTestUtils.setField(controller, "access", access);
        var pinned =
                controller
                        .selectionOptions(target.objectId(), id(target, "pick"), null, null, app)
                        .getData();
        assertThat(pinned)
                .extracting(SelectionFields.Option::value, SelectionFields.Option::label)
                .containsExactly(tuple("A", "甲"), tuple("B", "乙"));
        var withoutApp =
                controller
                        .selectionOptions(target.objectId(), id(target, "pick"), null, null, null)
                        .getData();
        assertThat(withoutApp).isEmpty();
    }

    /** 数据对象设计器预览：唯一例外，没有应用，按来源对象当前发布版取候选。 */
    @Test
    void objectDesignerUsesCurrentPublishedSource() throws Exception {
        var mapper = servicesContext.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
        int updated =
                jdbc.update(
                        "UPDATE public.nocode_field SET config_json ="
                                + " jsonb_set(jsonb_set(config_json, '{options,selection}',"
                                + " CAST(? AS jsonb)), '{options,options}', '[]'::jsonb) WHERE"
                                + " stable_field_id::text = ? AND object_version_id IN (SELECT id"
                                + " FROM public.nocode_object_version WHERE object_id = ?)",
                        mapper.writeValueAsString(pick()),
                        id(target, "pick"),
                        Long.parseLong(target.objectId()));
        assertThat(updated).isPositive();
        republishSource();
        var controller = new com.richuang.os.nocode.controller.admin.DataCenterController();
        ReflectionTestUtils.setField(controller, "designs", designs);
        ReflectionTestUtils.setField(
                controller,
                "selectionCatalog",
                servicesContext.getBean(
                        com.richuang.os.nocode.runtime.service.selection.SelectionCatalog.class));
        var latest = controller.selectionOptions(target.objectId(), id(target, "pick")).getData();
        assertThat(latest)
                .extracting(SelectionFields.Option::value, SelectionFields.Option::label)
                .containsExactly(tuple("A", "新甲"), tuple("B", "乙"), tuple("D", "丁"));
    }

    /** 导入「code:」解析挑取值时拿得到应用上下文；入口之外仍 fail-closed。 */
    @Test
    void importCodeResolvesInsideApplication() {
        var catalog =
                servicesContext.getBean(
                        com.richuang.os.nocode.runtime.service.selection.SelectionCatalog.class);
        var value =
                f.runtime.selectionImportValue(
                        app,
                        target.objectId(),
                        target.fields().stream()
                                .filter(v -> v.id().equals(id(target, "pick")))
                                .findFirst()
                                .orElseThrow(),
                        "code:B",
                        10001);
        assertThat(value).isEqualTo("B");
        assertThat(
                        catalog.objectFieldOptions(
                                        target.fieldOptions().get(id(target, "pick")).selection())
                                .state())
                .as("入口之外没有上下文仍 fail-closed")
                .isEqualTo("SOURCE_TABLE_MISSING");
    }
}
