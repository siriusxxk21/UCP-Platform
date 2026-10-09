package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationRecords.*;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.application.service.sharing.ObjectSharingService;
import com.lingan.ucp.nocode.runtime.service.record.RecordService;

import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

/** 在当前开发库验证业务时点留存，只清理本测试随机前缀的对象与应用。 */
class FormulaCaptureIntegrationTest {
    private NocodeIntegrationSupport fixture;
    private ApplicationService apps;
    private RecordService runtime;
    private DataObjectApi objects;
    private DataCenter.Definition definition;
    private int serial;

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
        apps = servicesContext.getBean(ApplicationService.class);
        runtime = servicesContext.getBean(RecordService.class);
        objects = servicesContext.getBean(DataObjectApi.class);
        SaveObjectDraft request = fixture.createRequest("capture");
        List<FieldDefinition> fields = new ArrayList<>();
        for (String code :
                List.of(
                        "name",
                        "amount",
                        "optional",
                        "live",
                        "maybe",
                        "saved",
                        "other",
                        "false_value",
                        "false_saved")) {
            String type =
                    "name".equals(code)
                            ? "TEXT"
                            : Set.of("live", "maybe", "false_value").contains(code)
                                    ? "FORMULA"
                                    : "DECIMAL";
            fields.add(fixture.field(code, code, type, fields.size()));
        }
        DataCenter.Design design =
                designs.save(
                        new DataCenter.SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        request.objectCode(),
                                        "公式留存验证",
                                        null,
                                        request.tableName(),
                                        "name",
                                        fields,
                                        List.of()),
                                DataCenter.Settings.defaults(),
                                Map.of(
                                        "live",
                                        formula("amount * 2"),
                                        "maybe",
                                        formula("optional"),
                                        "false_value",
                                        formula("IF(false, 1 / 0, 0)")),
                                List.of(),
                                List.of(),
                                List.of()),
                        10001);
        DataCenter.PublishPlan plan =
                publisher.plan(
                        new DataCenter.Revision(
                                design.draft().id(), design.draft().lockVersion(), null),
                        10001);
        assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "公式留存测试"), 10001)
                                .state())
                .isEqualTo("SUCCEEDED");
        definition = objects.getPublished(design.draft().id());
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

    private DataCenter.FieldOptions formula(String expression) {
        return formula(expression, "DECIMAL");
    }

    private DataCenter.FieldOptions formula(String expression, String resultType) {
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
                resultType,
                "NONE",
                null,
                false,
                false,
                null,
                new CalculationOptions(
                        "LOCAL", "LIVE", null, null, null, null, "AND", List.of(), false, List.of(),
                        null));
    }

    private String field(String code) {
        return definition.fields().stream()
                .filter(f -> code.equals(f.code()))
                .findFirst()
                .orElseThrow()
                .id();
    }

    private ApplicationCenter.Resource capture(Map<String, String> mapping) {
        Map<String, String> ids = new LinkedHashMap<>();
        mapping.forEach((target, source) -> ids.put(field(target), field(source)));
        return new ApplicationCenter.Resource(
                "capture",
                "ACTION",
                "capture_value",
                "确认计算结果",
                Map.of(
                        "objectId",
                        definition.objectId(),
                        "kind",
                        "CAPTURE_VALUES",
                        "captures",
                        ids));
    }

    private String application(List<ApplicationCenter.Resource> resources) {
        DataObjectApi.PublishedObject version = objects.getVersion(definition.objectId(), null);
        ApplicationCenter.Detail design =
                apps.save(
                        new ApplicationCenter.Save(
                                null,
                                null,
                                fixture.prefix + "app" + serial++,
                                "公式留存测试",
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        List.of(
                                                new ApplicationCenter.ObjectReference(
                                                        version.objectId(),
                                                        version.versionNo(),
                                                        version.checksum())),
                                        resources)),
                        10001);
        String app = design.application().id();
        grantApplicationObjects(app);
        apps.publish(
                new ApplicationCenter.Revision(app, design.application().revision(), "测试"), 10001);
        return app;
    }

    private Row save(String app, Row before, Map<String, Object> values) {
        Map<String, Object> ids = new LinkedHashMap<>();
        values.forEach((code, value) -> ids.put(field(code), value));
        return runtime.save(
                        new Save(
                                app,
                                definition.objectId(),
                                before == null ? null : before.id(),
                                before == null ? null : before.revision(),
                                ids,
                                null),
                        10001)
                .record();
    }

    private Row confirm(String app, Row row) {
        return runtime.execute(
                        new ApplicationBusiness.Execute(
                                app, definition.objectId(), "capture", row.id(), row.revision()),
                        10001)
                .record();
    }

    private void number(Row row, String code, String expected) {
        assertThat(new BigDecimal(row.values().get(field(code)).toString()))
                .isEqualByComparingTo(expected);
    }

    @Test
    void capturesOnceKeepsLiveIndependentAndProtectsTargetAcrossApplications() {
        String app = application(List.of(capture(Map.of("saved", "live"))));
        Row before = save(app, null, Map.of("name", "首笔", "amount", "12.25"));
        number(before, "live", "24.5");
        Row confirmed = confirm(app, before);
        number(confirmed, "saved", "24.5");
        assertThatThrownBy(() -> confirm(app, before)).hasMessageContaining("已被修改");
        assertThatThrownBy(() -> confirm(app, confirmed)).hasMessageContaining("已经留存");
        Row changed = save(app, confirmed, Map.of("amount", "20"));
        number(changed, "live", "40");
        number(changed, "saved", "24.5");
        assertThatThrownBy(() -> save(app, changed, Map.of("saved", "999")))
                .hasMessageContaining("不能手工");
        Map<String, Object> cleared = new HashMap<>();
        cleared.put("saved", null);
        assertThatThrownBy(() -> save(app, changed, cleared)).hasMessageContaining("不能手工");
        assertThat(runtime.model(app, definition.objectId(), 10001).managedFieldIds())
                .contains(field("saved"));
        String secondApp = application(List.of());
        assertThatThrownBy(() -> save(secondApp, changed, Map.of("saved", "1")))
                .hasMessageContaining("不能手工");
        Row echoed = save(secondApp, changed, Map.of("saved", "24.5000", "name", "修改其他内容"));
        number(echoed, "saved", "24.5");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_record_history WHERE"
                                        + " object_id=? AND record_id=? AND"
                                        + " source_json->>'kind'='CAPTURE_VALUES'",
                                Integer.class,
                                Long.valueOf(definition.objectId()),
                                before.id()))
                .isEqualTo(1);
    }

    @Test
    void missingResultRollsBackEveryMappingAndZeroCanBeCaptured() {
        String app =
                application(
                        List.of(
                                capture(
                                        Map.of(
                                                "saved",
                                                "live",
                                                "other",
                                                "maybe",
                                                "false_saved",
                                                "false_value"))));
        Row before = save(app, null, Map.of("name", "未填写", "amount", "7"));
        assertThatThrownBy(() -> confirm(app, before)).hasMessageContaining("结果为空");
        Row unchanged = runtime.get(app, definition.objectId(), before.id(), 10001).record();
        assertThat(unchanged.revision()).isEqualTo(before.revision());
        assertThat(unchanged.values().get(field("saved"))).isNull();
        Row ready = save(app, unchanged, Map.of("optional", "0"));
        Row confirmed = confirm(app, ready);
        number(confirmed, "saved", "14");
        number(confirmed, "other", "0");
        number(confirmed, "false_saved", "0");
    }

    @Test
    void publicationRejectsOverlappingOwnersAndNonFormulaSources() {
        application(List.of(capture(Map.of("saved", "live"))));
        assertThatThrownBy(() -> application(List.of(capture(Map.of("saved", "maybe")))))
                .hasMessageContaining("留存字段");
        ApplicationCenter.Resource ordinary =
                new ApplicationCenter.Resource(
                        "overwrite",
                        "ACTION",
                        "overwrite",
                        "普通赋值",
                        Map.of(
                                "objectId",
                                definition.objectId(),
                                "kind",
                                "UPDATE_FIELDS",
                                "values",
                                Map.of(field("saved"), "99")));
        assertThatThrownBy(() -> application(List.of(ordinary))).hasMessageContaining("不能写入留存字段");
        assertThatThrownBy(() -> application(List.of(capture(Map.of("other", "amount")))))
                .hasMessageContaining("来源必须");
    }

    private void share(String app, ApplicationAuthorization.ObjectGrant permission) {
        ObjectSharingService sharing = servicesContext.getBean(ObjectSharingService.class);
        ObjectSharing.Grant current =
                sharing.forApplication(app).stream()
                        .filter(g -> g.objectId().equals(definition.objectId()))
                        .findFirst()
                        .orElseThrow();
        sharing.save(
                new ObjectSharing.Save(
                        definition.objectId(), app, current.revision(), permission, "测试权限边界"),
                10001);
    }

    @Test
    void sourceAndTargetPermissionsAreCheckedAgainAtExecution() {
        String app = application(List.of(capture(Map.of("saved", "live"))));
        Row before = save(app, null, Map.of("name", "权限", "amount", "5"));
        ObjectSharingService sharing = servicesContext.getBean(ObjectSharingService.class);
        ApplicationAuthorization.ObjectGrant original =
                NocodeIntegrationSupport.resolvedPermission(definition.objectId(), app);
        Set<String> readable = new HashSet<>(original.readFields());
        readable.remove(field("live"));
        share(
                app,
                new ApplicationAuthorization.ObjectGrant(
                        original.objectId(),
                        original.actions(),
                        "ALL",
                        readable,
                        original.writeFields().stream()
                                .filter(readable::contains)
                                .collect(java.util.stream.Collectors.toSet()),
                        Set.of(),
                        Set.of()));
        assertThatThrownBy(() -> confirm(app, before)).hasMessageContaining("权限");
        Set<String> writable = new HashSet<>(original.writeFields());
        writable.remove(field("saved"));
        share(
                app,
                new ApplicationAuthorization.ObjectGrant(
                        original.objectId(),
                        original.actions(),
                        "ALL",
                        original.readFields(),
                        writable,
                        Set.of(),
                        Set.of()));
        assertThatThrownBy(() -> confirm(app, before)).hasMessageContaining("权限");
        share(app, original);
        number(confirm(app, before), "saved", "10");
    }

    @Test
    void concurrentConfirmationConsumesOneRevision() throws Exception {
        String app = application(List.of(capture(Map.of("saved", "live"))));
        Row before = save(app, null, Map.of("name", "并发", "amount", "3"));
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++)
                results.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    try {
                                        confirm(app, before);
                                        return true;
                                    } catch (
                                            com.lingan.ucp.framework.common.exception
                                                            .ServiceException
                                                    e) {
                                        assertThat(e.getMessage()).contains("已被修改");
                                        return false;
                                    }
                                }));
            start.countDown();
            assertThat(
                            List.of(
                                    results.get(0).get(30, TimeUnit.SECONDS),
                                    results.get(1).get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        number(runtime.get(app, definition.objectId(), before.id(), 10001).record(), "saved", "6");
    }
}
