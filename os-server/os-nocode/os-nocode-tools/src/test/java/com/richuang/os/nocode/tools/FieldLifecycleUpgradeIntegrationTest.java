package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.framework.common.biz.system.permission.PermissionCommonApi;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.DataCenter.*;
import com.richuang.os.nocode.application.service.application.ApplicationService;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** 真实开发库验证 A/B 分别升级、明确暂停确认和原子回滚；只操作随机前缀夹具。 */
class FieldLifecycleUpgradeIntegrationTest {
    private NocodeIntegrationSupport fixture;
    private ApplicationService apps;
    private final List<String> appIds = new ArrayList<>();

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
        Mockito.when(
                        servicesContext
                                .getBean(PermissionCommonApi.class)
                                .hasAnyPermissions(10001L, "nocode:app:manage"))
                .thenReturn(true);
    }

    @AfterEach
    void cleanup() {
        writeFailure.clear();
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        tx -> {
                            for (String appId : appIds) {
                                Long id = Long.valueOf(appId);
                                String code =
                                        jdbc.queryForObject(
                                                "SELECT app_code FROM public.nocode_application"
                                                        + " WHERE id=?",
                                                String.class,
                                                id);
                                assertThat(code).startsWith(fixture.prefix);
                                jdbc.update(
                                        "DELETE FROM public.nocode_resource_dependency WHERE"
                                                + " source_kind='APP' AND source_key=?",
                                        appId);
                                for (String table :
                                        List.of(
                                                "nocode_object_application_grant_log",
                                                "nocode_object_application_grant",
                                                "nocode_task_entry_access",
                                                "nocode_application_access",
                                                "nocode_application_version"))
                                    jdbc.update(
                                            "DELETE FROM public."
                                                    + table
                                                    + " WHERE application_id=?",
                                            id);
                                jdbc.update(
                                        "DELETE FROM public.nocode_operation_log WHERE app_id=?",
                                        id);
                                jdbc.update("DELETE FROM public.nocode_application WHERE id=?", id);
                            }
                        });
        fixture.clean();
    }

    private Design create() {
        SaveObjectDraft request = fixture.createRequest("upgrade");
        Design d =
                designs.save(
                        new SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        request.objectCode(),
                                        request.objectName(),
                                        null,
                                        request.tableName(),
                                        request.titleFieldKey(),
                                        List.of(
                                                request.fields().getFirst(),
                                                fixture.field("value", "value", "TEXT", 1)),
                                        List.of()),
                                Settings.defaults(),
                                Map.of(),
                                List.of(),
                                List.of(),
                                List.of()),
                        10001);
        publisher.execute(new ExecutePlan(plan(d).id(), "升级夹具初始发布"), 10001);
        return designs.get(d.draft().id());
    }

    private PublishPlan plan(Design d) {
        return publisher.plan(new Revision(d.draft().id(), d.draft().lockVersion(), null), 10001);
    }

    private ApplicationCenter.ObjectReference reference(String objectId) {
        DataObjectApi.PublishedObject p =
                servicesContext.getBean(DataObjectApi.class).getVersion(objectId, null);
        return new ApplicationCenter.ObjectReference(objectId, p.versionNo(), p.checksum());
    }

    private String app(Design d, String suffix) {
        ApplicationCenter.Detail detail =
                apps.save(
                        new ApplicationCenter.Save(
                                null,
                                null,
                                fixture.prefix + suffix,
                                "字段升级" + suffix,
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        List.of(reference(d.draft().id())), List.of())),
                        10001);
        String id = detail.application().id();
        appIds.add(id);
        apps.publish(
                new ApplicationCenter.Revision(id, detail.application().revision(), "夹具发布"), 10001);
        return id;
    }

    private Design change(Design source, String type, Integer length, boolean remove) {
        Design d =
                designs.editPublished(
                        new Revision(source.draft().id(), source.draft().lockVersion(), null),
                        10001);
        FieldDefinition selected =
                d.draft().fields().stream()
                        .filter(f -> f.code().equals("value"))
                        .findFirst()
                        .orElseThrow();
        List<FieldDefinition> fields =
                d.draft().fields().stream()
                        .filter(f -> !remove || !f.id().equals(selected.id()))
                        .map(
                                f ->
                                        f.id().equals(selected.id())
                                                ? new FieldDefinition(
                                                        f.key(), f.id(), f.code(), f.name(), type,
                                                        length, null, null, false, false, f.sort())
                                                : f)
                        .toList();
        Map<String, FieldOptions> options = new HashMap<>(d.fieldOptions());
        if (remove) options.remove(selected.id());
        ObjectDraft draft = d.draft();
        return designs.save(
                new SaveDesign(
                        new SaveObjectDraft(
                                draft.id(),
                                draft.lockVersion(),
                                draft.objectCode(),
                                draft.objectName(),
                                draft.description(),
                                draft.tableName(),
                                draft.titleFieldId(),
                                fields,
                                remove ? List.of(selected.id()) : List.of()),
                        d.settings(),
                        options,
                        d.relations(),
                        d.indexes(),
                        d.details()),
                10001);
    }

    private List<String> affected(PublishPlan p) {
        return p.applicationUpgrades().stream()
                .map(ObjectApplicationUpgrade.Impact::applicationId)
                .toList();
    }

    @Test
    void incompatibleColumnRequiresConfirmationThenBResumesWithoutA() {
        Design d = create();
        String a = app(d, "a"), b = app(d, "b");
        Design changed = change(d, "INTEGER", null, false);
        PublishPlan p = plan(changed);
        assertThat(p.checks()).noneMatch(Check::blocking);
        assertThat(affected(p)).containsExactlyInAnyOrder(a, b);
        assertThatThrownBy(() -> publisher.execute(new ExecutePlan(p.id(), "遗漏暂停确认"), 10001))
                .hasMessageContaining("确认暂停");
        assertThat(apps.published(a).application().status()).isEqualTo("ACTIVE");
        PublishPlan confirmed = plan(changed);
        publisher.execute(
                new ExecutePlan(confirmed.id(), "确认维护升级", List.of(), affected(confirmed)), 10001);
        assertThat(apps.get(a).application().status()).isEqualTo("DISABLED");
        assertThatThrownBy(() -> apps.published(b)).hasMessageContaining("停用");
        ApplicationCenter.Detail old = apps.get(a);
        assertThatThrownBy(
                        () ->
                                apps.status(
                                        new ApplicationCenter.Revision(
                                                a, old.application().revision(), "旧版本不能直接启用"),
                                        "ACTIVE",
                                        10001))
                .hasMessageContaining("同步");
        ApplicationCenter.Detail next = apps.get(b);
        next =
                apps.save(
                        new ApplicationCenter.Save(
                                b,
                                next.application().revision(),
                                next.application().code(),
                                next.application().name(),
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        List.of(reference(d.draft().id())), List.of())),
                        10001);
        apps.publishAndEnable(
                new ApplicationCenter.Revision(b, next.application().revision(), "B 单独完成升级"),
                10001);
        assertThat(apps.published(b).definition().objects().getFirst().versionNo()).isEqualTo(2);
        assertThat(apps.get(a).application().status()).isEqualTo("DISABLED");
    }

    @Test
    void wideningTextKeepsOldApplicationRunning() {
        Design d = create();
        String a = app(d, "wide");
        PublishPlan p = plan(change(d, "TEXT", 500, false));
        assertThat(p.applicationUpgrades()).isEmpty();
        publisher.execute(new ExecutePlan(p.id(), "兼容扩宽"), 10001);
        // 2026-10 起：兼容改动发布后应用自动跟到新版本（默认开着自动跟随），不停、不用人去同步。
        assertThat(apps.published(a).definition().objects().getFirst().versionNo()).isEqualTo(2);
        assertThat(apps.published(a).application().status()).isEqualTo("ACTIVE");
    }

    @Test
    void failedConversionRollsBackApplicationPauseAndValues() {
        Design d = create();
        String a = app(d, "rollback");
        jdbc.update(
                "INSERT INTO public.\""
                        + d.draft().tableName()
                        + "\" (name,value) VALUES ('测试','保留旧值')");
        PublishPlan p = plan(change(d, "INTEGER", null, false));
        writeFailure.failAfter(
                "UPDATE \"public\".\"" + d.draft().tableName() + "\" SET \"value\" = NULL");
        assertThatThrownBy(
                        () ->
                                publisher.execute(
                                        new ExecutePlan(
                                                p.id(),
                                                "故障注入",
                                                List.of(p.conversions().getFirst().fieldId()),
                                                affected(p)),
                                        10001))
                .hasMessageContaining("回滚");
        writeFailure.clear();
        assertThat(apps.published(a).application().status()).isEqualTo("ACTIVE");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT value FROM public.\"" + d.draft().tableName() + "\"",
                                String.class))
                .isEqualTo("保留旧值");
        assertThat(designs.get(d.draft().id()).publishedVersion()).isEqualTo(1);
    }

    @Test
    void changedApplicationRevisionInvalidatesPreviousConfirmation() {
        Design d = create();
        String a = app(d, "stale");
        PublishPlan p = plan(change(d, "INTEGER", null, false));
        ApplicationCenter.Detail app = apps.get(a);
        apps.save(
                new ApplicationCenter.Save(
                        a,
                        app.application().revision(),
                        app.application().code(),
                        app.application().name(),
                        "草稿变化",
                        null,
                        app.draft()),
                10001);
        assertThatThrownBy(
                        () ->
                                publisher.execute(
                                        new ExecutePlan(p.id(), "旧计划", List.of(), affected(p)),
                                        10001))
                .hasMessageContaining("已变化");
        assertThat(apps.published(a).application().status()).isEqualTo("ACTIVE");
    }

    @Test
    void disablingFieldRetainsColumnAndRequiresOldApplicationMaintenance() {
        Design d = create();
        String a = app(d, "retire");
        jdbc.update(
                "INSERT INTO public.\""
                        + d.draft().tableName()
                        + "\" (name,value,deleted) VALUES ('测试','保留',1)");
        PublishPlan p = plan(change(d, "TEXT", 200, true));
        assertThat(p.conversions()).isEmpty();
        assertThat(affected(p)).containsExactly(a);
        publisher.execute(new ExecutePlan(p.id(), "停用保留", List.of(), affected(p)), 10001);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT value FROM public.\"" + d.draft().tableName() + "\"",
                                String.class))
                .isEqualTo("保留");
        assertThat(designs.inactiveFields(d.draft().id(), null)).hasSize(1);
        Design stopped = designs.get(d.draft().id());
        Design editable =
                designs.editPublished(
                        new Revision(stopped.draft().id(), stopped.draft().lockVersion(), null),
                        10001);
        InactiveField retained = designs.inactiveFields(d.draft().id(), null).getFirst();
        List<FieldDefinition> restored = new ArrayList<>(editable.draft().fields());
        restored.add(retained.field());
        Map<String, FieldOptions> restoredOptions = new HashMap<>(editable.fieldOptions());
        restoredOptions.put(retained.field().id(), FieldOptions.defaults());
        ObjectDraft draft = editable.draft();
        Design restoredDesign =
                designs.save(
                        new SaveDesign(
                                new SaveObjectDraft(
                                        draft.id(),
                                        draft.lockVersion(),
                                        draft.objectCode(),
                                        draft.objectName(),
                                        draft.description(),
                                        draft.tableName(),
                                        draft.titleFieldId(),
                                        restored,
                                        List.of()),
                                editable.settings(),
                                restoredOptions,
                                editable.relations(),
                                editable.indexes(),
                                editable.details(),
                                editable.mainBinding(),
                                List.of(retained.field().id())),
                        10001);
        PublishPlan restorePlan = plan(restoredDesign);
        publisher.execute(new ExecutePlan(restorePlan.id(), "恢复同一字段"), 10001);
        assertThat(designs.published(d.draft().id()).fields())
                .anyMatch(f -> f.id().equals(retained.field().id()));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT value FROM public.\"" + d.draft().tableName() + "\"",
                                String.class))
                .isEqualTo("保留");
        ApplicationCenter.Detail old = apps.get(a);
        apps.status(
                new ApplicationCenter.Revision(a, old.application().revision(), "旧契约恢复兼容"),
                "ACTIVE",
                10001);
        assertThat(apps.published(a).definition().objects().getFirst().versionNo()).isEqualTo(1);
    }
}
