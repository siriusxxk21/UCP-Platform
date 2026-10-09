package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationRecords.*;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.application.service.sharing.ObjectSharingService;
import com.lingan.ucp.nocode.metadata.service.form.DocumentPolicies;
import com.lingan.ucp.nocode.runtime.service.record.DocumentCalculations;
import com.lingan.ucp.nocode.runtime.service.record.RecordCalculations;
import com.lingan.ucp.nocode.runtime.service.record.RecordService;

import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.*;

/** 在当前开发库验证明细汇总、本行公式与时点留存一致；仅清理本测试随机前缀的夹具。 */
class SummaryFormulaIntegrationTest {
    private NocodeIntegrationSupport fixture;
    private RecordService runtime;
    private RecordCalculations calculations;
    private DataCenter.Definition definition;
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
        fixture = new NocodeIntegrationSupport();
        fixture.name();
        runtime = servicesContext.getBean(RecordService.class);
        calculations = servicesContext.getBean(RecordCalculations.class);
        SaveObjectDraft request = fixture.createRequest("summary_formula");
        List<FieldDefinition> fields = new ArrayList<>();
        for (String code :
                List.of(
                        "name",
                        "shipping",
                        "sum_items",
                        "avg_items",
                        "total",
                        "twice",
                        "on_save",
                        "confirmed")) {
            String type =
                    "name".equals(code)
                            ? "TEXT"
                            : Set.of("sum_items", "avg_items").contains(code)
                                    ? "SUMMARY"
                                    : Set.of("total", "twice", "on_save").contains(code)
                                            ? "FORMULA"
                                            : "DECIMAL";
            fields.add(fixture.field(code, code, type, fields.size()));
        }
        DataCenter.Detail detail =
                new DataCenter.Detail(
                        null,
                        "items",
                        "金额明细",
                        "biz_" + fixture.prefix + "items",
                        "ACTIVE",
                        List.of(fixture.field("amount", "amount", "DECIMAL", 0)),
                        Map.of(),
                        List.of());
        DataCenter.Design design =
                designs.save(
                        new DataCenter.SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        request.objectCode(),
                                        "汇总与公式验证",
                                        null,
                                        request.tableName(),
                                        "name",
                                        fields,
                                        List.of()),
                                DataCenter.Settings.defaults(),
                                Map.of(
                                        "sum_items",
                                        options("sum(items.amount)", null),
                                        "avg_items",
                                        options("avg(items.amount)", null),
                                        "total",
                                        options(
                                                "IF(sum_items >= 100, ROUND(sum_items * 0.9, 2),"
                                                        + " sum_items) + COALESCE(shipping, 0)",
                                                "LIVE"),
                                        "twice",
                                        options("total * 2", "LIVE"),
                                        "on_save",
                                        options("total * 3", "ON_SAVE")),
                                List.of(),
                                List.of(),
                                List.of(detail)),
                        10001);
        DataCenter.PublishPlan plan =
                publisher.plan(
                        new DataCenter.Revision(
                                design.draft().id(), design.draft().lockVersion(), null),
                        10001);
        assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "汇总公式测试"), 10001)
                                .state())
                .isEqualTo("SUCCEEDED");
        DataObjectApi objects = servicesContext.getBean(DataObjectApi.class);
        definition = objects.getPublished(design.draft().id());
        DataObjectApi.PublishedObject version = objects.getVersion(definition.objectId(), null);
        ApplicationService applications = servicesContext.getBean(ApplicationService.class);
        ApplicationCenter.Resource action =
                new ApplicationCenter.Resource(
                        "confirm",
                        "ACTION",
                        "confirm",
                        "确认金额",
                        Map.of(
                                "objectId",
                                definition.objectId(),
                                "kind",
                                "CAPTURE_VALUES",
                                "captures",
                                Map.of(field("confirmed"), field("total"))));
        ApplicationCenter.Detail application =
                applications.save(
                        new ApplicationCenter.Save(
                                null,
                                null,
                                fixture.prefix + "app",
                                "汇总公式测试",
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        List.of(
                                                new ApplicationCenter.ObjectReference(
                                                        version.objectId(),
                                                        version.versionNo(),
                                                        version.checksum())),
                                        List.of(action))),
                        10001);
        app = application.application().id();
        grantApplicationObjects(app);
        applications.publish(
                new ApplicationCenter.Revision(app, application.application().revision(), "测试"),
                10001);
    }

    @AfterEach
    void cleanup() {
        for (Long id :
                jdbc.queryForList(
                        "SELECT id FROM public.nocode_application WHERE app_code LIKE ?",
                        Long.class,
                        fixture.prefix + "%")) {
            jdbc.update(
                    "DELETE FROM public.nocode_object_application_grant_log WHERE application_id=?",
                    id);
            jdbc.update(
                    "DELETE FROM public.nocode_object_application_grant WHERE application_id=?",
                    id);
            jdbc.update("DELETE FROM public.nocode_application_access WHERE application_id=?", id);
            jdbc.update("DELETE FROM public.nocode_application_version WHERE application_id=?", id);
            jdbc.update("DELETE FROM public.nocode_application WHERE id=?", id);
        }
        fixture.clean();
    }

    private DataCenter.FieldOptions options(String expression, String update) {
        return new DataCenter.FieldOptions(
                null,
                "NORMAL",
                null,
                null,
                null,
                null,
                null,
                "ACTIVE",
                List.of(),
                expression,
                "DECIMAL",
                "NONE",
                null,
                false,
                false,
                null,
                update == null
                        ? null
                        : new CalculationOptions(
                                "LOCAL", update, null, null, null, null, "AND", List.of(), false,
                                List.of(), null));
    }

    private String field(String code) {
        return definition.fields().stream()
                .filter(f -> code.equals(f.code()))
                .findFirst()
                .orElseThrow()
                .id();
    }

    private String detailId() {
        return definition.details().getFirst().id();
    }

    private String amount() {
        return definition.details().getFirst().fields().getFirst().id();
    }

    private void number(Object actual, String expected) {
        assertThat(new BigDecimal(actual.toString())).isEqualByComparingTo(expected);
    }

    private Aggregate create(String shipping, String... amounts) {
        List<Row> details =
                Arrays.stream(amounts)
                        .map(value -> new Row(null, null, Map.<String, Object>of(amount(), value)))
                        .toList();
        return runtime.save(
                new Save(
                        app,
                        definition.objectId(),
                        null,
                        null,
                        Map.of(field("name"), "汇总公式", field("shipping"), shipping),
                        Map.of(detailId(), details)),
                10001);
    }

    private Row confirm(Row row) {
        return runtime.execute(
                        new ApplicationBusiness.Execute(
                                app, definition.objectId(), "confirm", row.id(), row.revision()),
                        10001)
                .record();
    }

    @Test
    void summaryLocalReadSaveAndCaptureUseSameFreshValue() {
        Aggregate created = create("5", "80", "40");
        Row first = created.record();
        number(first.values().get(field("sum_items")), "120");
        number(first.values().get(field("total")), "113");
        number(first.values().get(field("twice")), "226");
        number(first.values().get(field("on_save")), "339");
        Row page =
                runtime.page(
                                new Query(
                                        app,
                                        definition.objectId(),
                                        1,
                                        20,
                                        null,
                                        Map.of(),
                                        null,
                                        false),
                                10001)
                        .getList()
                        .getFirst();
        number(page.values().get(field("total")), "113");
        Map<String, Object> fresh =
                calculations.freshValues(
                        app,
                        definition,
                        first.id(),
                        Set.of(field("total"), field("on_save")),
                        10001);
        number(fresh.get(field("total")), "113");
        number(fresh.get(field("on_save")), "339");
        Row confirmed = confirm(first);
        number(confirmed.values().get(field("confirmed")), "113");
        Row line = created.details().get(detailId()).getFirst();
        List<Row> changedLines = new ArrayList<>(created.details().get(detailId()));
        changedLines.set(0, new Row(line.id(), line.revision(), Map.of(amount(), "100")));
        Row changed =
                runtime.save(
                                new Save(
                                        app,
                                        definition.objectId(),
                                        confirmed.id(),
                                        confirmed.revision(),
                                        Map.of(),
                                        Map.of(detailId(), changedLines)),
                                10001)
                        .record();
        number(changed.values().get(field("total")), "131");
        number(changed.values().get(field("on_save")), "393");
        number(changed.values().get(field("confirmed")), "113");
    }

    @Test
    void emptyDetailsAndLargeAmountsKeepSummarySemanticsAndPrecision() {
        Row empty = create("3").record();
        number(empty.values().get(field("sum_items")), "0");
        assertThat(empty.values().get(field("avg_items"))).isNull();
        number(empty.values().get(field("total")), "3");
        number(confirm(empty).values().get(field("confirmed")), "3");
        Row large = create("0", "9007199254740993", "7").record();
        number(large.values().get(field("sum_items")), "9007199254741000");
        number(large.values().get(field("total")), "8106479329266900");
    }

    @Test
    void candidateDetailsFeedLocalPreviewWithoutReadingOldSummary() {
        Row stored = create("0", "10").record();
        DocumentPolicies.Input candidate =
                new DocumentPolicies.Input(
                        stored.values(),
                        Map.of(
                                detailId(),
                                List.of(
                                        new DocumentPolicies.InputRow(
                                                null,
                                                "new-line",
                                                Map.of(amount(), new BigDecimal("200"))))));
        DocumentPolicies.Input preview =
                DocumentCalculations.calculate(
                        definition,
                        candidate,
                        values ->
                                calculations.preview(
                                        app, definition, stored.id(), values, Map.of(), 10001));
        number(preview.values().get(field("total")), "180");
        number(
                runtime.get(app, definition.objectId(), stored.id(), 10001)
                        .record()
                        .values()
                        .get(field("total")),
                "10");
    }

    private void share(ApplicationAuthorization.ObjectGrant permission) {
        ObjectSharingService sharing = servicesContext.getBean(ObjectSharingService.class);
        ObjectSharing.Grant existing =
                sharing.forApplication(app).stream()
                        .filter(g -> g.objectId().equals(definition.objectId()))
                        .findFirst()
                        .orElseThrow();
        sharing.save(
                new ObjectSharing.Save(
                        definition.objectId(), app, existing.revision(), permission, "测试汇总权限"),
                10001);
    }

    @Test
    void hiddenSummaryOrDetailCannotBeReadOrCapturedThroughLocalFormula() {
        Row row = create("0", "12").record();
        ObjectSharingService sharing = servicesContext.getBean(ObjectSharingService.class);
        ApplicationAuthorization.ObjectGrant original =
                NocodeIntegrationSupport.resolvedPermission(definition.objectId(), app);
        share(
                new ApplicationAuthorization.ObjectGrant(
                        original.objectId(),
                        original.actions(),
                        "ALL",
                        original.readFields(),
                        original.writeFields(),
                        Set.of(),
                        Set.of()));
        assertThatThrownBy(() -> runtime.get(app, definition.objectId(), row.id(), 10001))
                .hasMessageContaining("权限");
        assertThatThrownBy(() -> confirm(row)).hasMessageContaining("权限");
        assertThatThrownBy(
                        () ->
                                calculations.freshValues(
                                        app, definition, row.id(), Set.of(field("total")), 10001))
                .hasMessageContaining("权限");
        Set<String> readable = new HashSet<>(original.readFields());
        readable.remove(field("sum_items"));
        share(
                new ApplicationAuthorization.ObjectGrant(
                        original.objectId(),
                        original.actions(),
                        "ALL",
                        readable,
                        original.writeFields().stream()
                                .filter(readable::contains)
                                .collect(java.util.stream.Collectors.toSet()),
                        original.readDetails(),
                        original.writeDetails()));
        assertThatThrownBy(() -> confirm(row)).hasMessageContaining("权限");
        share(original);
        Row unchanged = runtime.get(app, definition.objectId(), row.id(), 10001).record();
        assertThat(unchanged.values().get(field("confirmed"))).isNull();
        assertThat(unchanged.revision()).isEqualTo(row.revision());
        number(confirm(unchanged).values().get(field("confirmed")), "12");
    }

    @Test
    void captureRecomputesSummaryEvenWhenStoredLocalSnapshotIsOlder() {
        Row row = create("0", "10").record();
        jdbc.update(
                "UPDATE public.\""
                        + definition.details().getFirst().tableName()
                        + "\" SET amount = 20 WHERE parent_id::text = ?",
                row.id());
        Row visible = runtime.get(app, definition.objectId(), row.id(), 10001).record();
        number(visible.values().get(field("total")), "20");
        number(visible.values().get(field("on_save")), "30");
        number(
                calculations
                        .freshValues(app, definition, row.id(), Set.of(field("on_save")), 10001)
                        .get(field("on_save")),
                "60");
        Row confirmed = confirm(visible);
        number(confirmed.values().get(field("confirmed")), "20");
        number(confirmed.values().get(field("on_save")), "60");
    }
}
