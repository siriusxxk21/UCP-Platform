package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationRecords.*;
import com.richuang.os.nocode.application.service.application.ApplicationService;
import com.richuang.os.nocode.enums.FieldConversionActionEnum;
import com.richuang.os.nocode.enums.FieldTypeEnum;
import com.richuang.os.nocode.runtime.service.record.RecordService;

import org.junit.jupiter.api.*;

import java.util.*;

/** 当前开发库的真实业务读写回归：无模拟 Mapper，每例只清理由本测试随机前缀拥有的数据。 */
class RuntimeIntegrationTest {
    private NocodeIntegrationSupport fixture;
    private RecordService runtime;
    private ApplicationService applications;
    private final List<String> ownedTables = new ArrayList<>();
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
        org.mockito.Mockito.when(
                        servicesContext
                                .getBean(
                                        com.richuang.os.framework.common.biz.system.permission
                                                .PermissionCommonApi.class)
                                .hasAnyPermissions(
                                        10001L,
                                        com.richuang.os.nocode.application.service.sharing
                                                .ObjectSharingService.MANAGE_PERMISSION))
                .thenReturn(true);
        org.mockito.Mockito.reset(bpm());
        org.mockito.Mockito.reset(
                servicesContext.getBean(com.richuang.os.module.system.api.user.AdminUserApi.class),
                servicesContext.getBean(com.richuang.os.module.system.api.dept.DeptApi.class),
                servicesContext.getBean(com.richuang.os.module.system.api.dept.PostApi.class),
                servicesContext.getBean(
                        com.richuang.os.module.bpm.api.definition.BpmUserGroupApi.class),
                servicesContext.getBean(
                        com.richuang.os.module.infra.service.file.FileService.class));
        org.mockito.Mockito.reset(
                servicesContext.getBean(
                        com.richuang.os.module.bpm.api.definition.BpmProcessDefinitionApi.class));
        fixture = new NocodeIntegrationSupport();
        fixture.name();
        runtime = servicesContext.getBean(RecordService.class);
        applications = servicesContext.getBean(ApplicationService.class);
    }

    @AfterEach
    void cleanup() {
        List<Long> ids =
                jdbc.queryForList(
                        "SELECT id FROM public.nocode_application WHERE app_code LIKE ?",
                        Long.class,
                        fixture.prefix + "%");
        for (Long id : ids) {
            jdbc.update(
                    "DELETE FROM public.nocode_object_application_grant_log WHERE application_id=?",
                    id);
            jdbc.update(
                    "DELETE FROM public.nocode_object_application_grant WHERE application_id=?",
                    id);
            jdbc.update("DELETE FROM public.nocode_record_process WHERE application_id=?", id);
            jdbc.update("DELETE FROM public.nocode_application_access WHERE application_id=?", id);
            jdbc.update("DELETE FROM public.nocode_application_version WHERE application_id=?", id);
            jdbc.update("DELETE FROM public.nocode_application WHERE id=?", id);
        }
        jdbc.update(
                "DELETE FROM public.nocode_business_counter WHERE object_id IN (SELECT id FROM"
                        + " public.nocode_object WHERE object_code LIKE ?)",
                fixture.prefix + "%");
        fixture.clean();
        for (String name : ownedTables) {
            if (!name.startsWith("biz_" + fixture.prefix))
                throw new IllegalStateException("Fixture ownership mismatch");
            jdbc.execute("DROP TABLE IF EXISTS public.\"" + name + "\"");
        }
    }

    private DataCenter.Definition object(boolean detail) {
        List<DataCenter.Detail> ds =
                detail
                        ? List.of(
                                new DataCenter.Detail(
                                        null,
                                        "items",
                                        "订单明细",
                                        "biz_" + fixture.prefix + "items",
                                        "ACTIVE",
                                        List.of(
                                                new FieldDefinition(
                                                        "qty",
                                                        null,
                                                        "quantity",
                                                        "数量",
                                                        "INTEGER",
                                                        null,
                                                        null,
                                                        null,
                                                        true,
                                                        false,
                                                        0)),
                                        Map.of(),
                                        List.of()))
                        : List.<DataCenter.Detail>of();
        DataCenter.Design design =
                designs.save(
                        new DataCenter.SaveDesign(
                                fixture.createRequest("object" + serial++),
                                DataCenter.Settings.defaults(),
                                null,
                                List.of(),
                                List.of(),
                                ds),
                        10001);
        return publish(design);
    }

    private DataCenter.Definition publish(DataCenter.Design design) {
        DataCenter.PublishPlan p =
                publisher.plan(
                        new DataCenter.Revision(
                                design.draft().id(), design.draft().lockVersion(), null),
                        10001);
        assertThat(p.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(publisher.execute(new DataCenter.ExecutePlan(p.id(), "运行验证"), 10001).state())
                .isEqualTo("SUCCEEDED");
        return servicesContext.getBean(DataObjectApi.class).getPublished(design.draft().id());
    }

    private String app(DataCenter.Definition d) {
        return app(d, List.of());
    }

    private String app(DataCenter.Definition d, List<ApplicationCenter.Resource> resources) {
        DataObjectApi.PublishedObject v =
                servicesContext.getBean(DataObjectApi.class).getVersion(d.objectId(), null);
        ApplicationCenter.Detail a =
                applications.save(
                        new ApplicationCenter.Save(
                                null,
                                null,
                                fixture.prefix + "app" + serial++,
                                "运行验证",
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        List.of(
                                                new ApplicationCenter.ObjectReference(
                                                        v.objectId(), v.versionNo(), v.checksum())),
                                        resources)),
                        10001);
        grantApplicationObjects(a.application().id());
        applications.publish(
                new ApplicationCenter.Revision(a.application().id(), 0, "运行测试"), 10001);
        return a.application().id();
    }

    private String field(DataCenter.Definition d, String code) {
        return d.fields().stream()
                .filter(
                        f ->
                                f.code().equals(code)
                                        || code.equals(
                                                d.fieldOptions()
                                                        .getOrDefault(
                                                                f.id(),
                                                                DataCenter.FieldOptions.defaults())
                                                        .columnName()))
                .findFirst()
                .orElseThrow()
                .id();
    }

    private Save create(String app, DataCenter.Definition d, String name) {
        return new Save(app, d.objectId(), null, null, Map.of(field(d, "name"), name), null);
    }

    private com.richuang.os.nocode.api.RecordHistory.Result history(
            String app, String start, String end, String employee, long actor) {
        com.richuang.os.nocode.runtime.service.history.RecordHistoryService service =
                servicesContext.getBean(
                        com.richuang.os.nocode.runtime.service.history.RecordHistoryService.class);
        RecordHistory.Summary summary =
                service.query(new RecordHistory.Query(start, end, app, employee), actor);
        RecordHistory.Query scope =
                new RecordHistory.Query(summary.start(), summary.end(), app, employee);
        List<RecordHistory.Table> tables = new ArrayList<>();
        // 既有语义断言通过真实分页/详情接口组装小型测试结果，不保留生产全量查询路径。
        for (RecordHistory.TableSummary table : summary.tables()) {
            RecordHistory.Page all =
                    service.page(
                            new RecordHistory.PageQuery(
                                    scope, summary.visibility(), table.objectId(), false, 1, 100),
                            actor);
            RecordHistory.Page changed =
                    service.page(
                            new RecordHistory.PageQuery(
                                    scope, summary.visibility(), table.objectId(), true, 1, 100),
                            actor);
            Map<String, RecordHistory.Row> rows = new LinkedHashMap<>();
            for (RecordHistory.Row row :
                    java.util.stream.Stream.concat(
                                    all.table().rows().stream(), changed.table().rows().stream())
                            .toList())
                rows.put(
                        row.id(),
                        service.detail(
                                        new RecordHistory.DetailQuery(
                                                scope,
                                                summary.visibility(),
                                                table.objectId(),
                                                row.id()),
                                        actor)
                                .row());
            tables.add(
                    new RecordHistory.Table(
                            table.objectId(),
                            table.name(),
                            table.applicationIds(),
                            table.applicationNames(),
                            table.coveredFrom(),
                            table.complete(),
                            all.table().fields(),
                            List.copyOf(rows.values())));
        }
        return new RecordHistory.Result(summary.start(), summary.end(), tables);
    }

    @Test
    void historySummaryIsCompactAndWholeTableUsesServerPagesAcrossBatches() {
        DataCenter.Definition d = object(false);
        String app = app(d);
        jdbc.update(
                "INSERT INTO public.\""
                        + d.tableName()
                        + "\"(name,creator,updater,create_time,update_time,deleted) SELECT"
                        + " '分页记录'||n,'10001','10001',now(),now(),0 FROM generate_series(1,2005)"
                        + " n");
        com.richuang.os.nocode.runtime.service.history.RecordHistoryService service =
                servicesContext.getBean(
                        com.richuang.os.nocode.runtime.service.history.RecordHistoryService.class);
        RecordHistory.Summary summary =
                service.query(
                        new RecordHistory.Query(
                                databaseNow().minusSeconds(60).toString(), null, app, null),
                        10001);
        assertThat(summary.tables().getFirst().counts().operations()).isZero();
        assertThat(
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .valueToTree(summary)
                                .toString())
                .doesNotContain("分页记录", "definition_json", "startValues");
        RecordHistory.Query scope =
                new RecordHistory.Query(summary.start(), summary.end(), app, null);
        RecordHistory.Page first =
                service.page(
                        new RecordHistory.PageQuery(
                                scope, summary.visibility(), d.objectId(), false, 1, 10),
                        10001);
        RecordHistory.Page last =
                service.page(
                        new RecordHistory.PageQuery(
                                scope, summary.visibility(), d.objectId(), false, 201, 10),
                        10001);
        assertThat(first.total()).isEqualTo(2005);
        assertThat(first.table().rows()).hasSize(10);
        assertThat(last.table().rows())
                .hasSize(5)
                .doesNotContainAnyElementsOf(first.table().rows());
        assertThat(first.table().rows())
                .allMatch(r -> r.changes().isEmpty() && r.changeCount() == 0);
    }

    @Test
    void historyQueryDoesNotWaitForWriterAndLateCommitCannotChangeExistingPages() throws Exception {
        DataCenter.Definition d = object(false);
        String app = app(d);
        ApplicationRecords.Row row = runtime.save(create(app, d, "提交前"), 10001).record();
        String start = java.time.Instant.now().toString();
        com.richuang.os.nocode.runtime.service.history.RecordHistoryService service =
                servicesContext.getBean(
                        com.richuang.os.nocode.runtime.service.history.RecordHistoryService.class);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try (java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Future<?> writer =
                    executor.submit(
                            () ->
                                    new org.springframework.transaction.support.TransactionTemplate(
                                                    manager)
                                            .executeWithoutResult(
                                                    status -> {
                                                        runtime.save(
                                                                new Save(
                                                                        app,
                                                                        d.objectId(),
                                                                        row.id(),
                                                                        row.revision(),
                                                                        Map.of(
                                                                                field(d, "name"),
                                                                                "提交后"),
                                                                        null),
                                                                10001);
                                                        ready.countDown();
                                                        try {
                                                            if (!release.await(
                                                                    8,
                                                                    java.util.concurrent.TimeUnit
                                                                            .SECONDS))
                                                                throw new IllegalStateException(
                                                                        "查询阻塞保存");
                                                        } catch (InterruptedException e) {
                                                            Thread.currentThread().interrupt();
                                                            throw new IllegalStateException(e);
                                                        }
                                                    }));
            try {
                assertThat(ready.await(8, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                java.util.concurrent.Future<RecordHistory.Summary> query =
                        executor.submit(
                                () ->
                                        service.query(
                                                new RecordHistory.Query(start, null, app, null),
                                                10001));
                RecordHistory.Summary summary = query.get(3, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(summary.tables().getFirst().counts().operations()).isZero();
                release.countDown();
                writer.get(5, java.util.concurrent.TimeUnit.SECONDS);
                RecordHistory.Query scope =
                        new RecordHistory.Query(summary.start(), summary.end(), app, null);
                RecordHistory.Page page =
                        service.page(
                                new RecordHistory.PageQuery(
                                        scope, summary.visibility(), d.objectId(), false, 1, 10),
                                10001);
                assertThat(page.table().rows().getFirst().values().get(field(d, "name")))
                        .isEqualTo("提交前");
                assertThat(
                                service.detail(
                                                new RecordHistory.DetailQuery(
                                                        scope,
                                                        summary.visibility(),
                                                        d.objectId(),
                                                        row.id()),
                                                10001)
                                        .row()
                                        .changes())
                        .isEmpty();
                assertThat(
                                service.query(
                                                new RecordHistory.Query(start, null, app, null),
                                                10001)
                                        .tables()
                                        .getFirst()
                                        .counts()
                                        .update())
                        .isEqualTo(1);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void historyExistingQueryContextCannotBypassRevokedAccess() {
        DataCenter.Definition d = object(false);
        String app = app(d);
        ApplicationRecords.Row row = runtime.save(create(app, d, "授权内"), 10001).record();
        authorize(app, member(grant(d, "ALL", Set.of("READ"), Set.of(field(d, "name")))));
        com.richuang.os.nocode.runtime.service.history.RecordHistoryService service =
                servicesContext.getBean(
                        com.richuang.os.nocode.runtime.service.history.RecordHistoryService.class);
        RecordHistory.Summary summary =
                service.query(
                        new RecordHistory.Query(
                                databaseNow().minusSeconds(60).toString(), null, app, null),
                        20002);
        RecordHistory.Query scope =
                new RecordHistory.Query(summary.start(), summary.end(), app, null);
        authorize(app);
        assertThatThrownBy(
                        () ->
                                service.page(
                                        new RecordHistory.PageQuery(
                                                scope,
                                                summary.visibility(),
                                                d.objectId(),
                                                false,
                                                1,
                                                10),
                                        20002))
                .hasMessageContaining("无权");
        assertThatThrownBy(
                        () ->
                                service.detail(
                                        new RecordHistory.DetailQuery(
                                                scope,
                                                summary.visibility(),
                                                d.objectId(),
                                                row.id()),
                                        20002))
                .hasMessageContaining("无权");
    }

    @Test
    void historyEventCursorKeepsEveryEditAtTheSameTimestamp() {
        DataCenter.Definition d = object(false);
        String app = app(d);
        ApplicationRecords.Row row = runtime.save(create(app, d, "起始值"), 10001).record();
        String start = databaseNow().toString();
        // 自有夹具批量模拟同一时刻的 1005 次有效修改，跨越 1000 条游标批次。
        jdbc.update(
                "INSERT INTO"
                    + " public.nocode_record_history(object_id,record_id,application_id,operation,occurred_at,before_json,after_json,definition_json,creator,updater)"
                    + " SELECT object_id,record_id,application_id,'UPDATE',CAST(? AS"
                    + " timestamptz),after_json,"
                    + "jsonb_set(after_json,ARRAY['values',?],to_jsonb('变更'||n)),definition_json,CASE"
                    + " WHEN n%2=0 THEN '20002' ELSE '10001' END,'10001' FROM"
                    + " public.nocode_record_history CROSS JOIN generate_series(1,1005) n WHERE"
                    + " object_id=? AND operation='CREATE'",
                start, field(d, "name"), Long.parseLong(d.objectId()));
        com.richuang.os.nocode.runtime.service.history.RecordHistoryService service =
                servicesContext.getBean(
                        com.richuang.os.nocode.runtime.service.history.RecordHistoryService.class);
        RecordHistory.Summary summary =
                service.query(new RecordHistory.Query(start, null, app, null), 10001);
        assertThat(summary.tables().getFirst().counts())
                .isEqualTo(new RecordHistory.Counts(1, 1005, 0, 1005, 0, 2));
        RecordHistory.Query scope =
                new RecordHistory.Query(summary.start(), summary.end(), app, "10001");
        RecordHistory.Page page =
                service.page(
                        new RecordHistory.PageQuery(
                                scope, summary.visibility(), d.objectId(), true, 1, 10),
                        10001);
        assertThat(page.table().rows().getFirst().changeCount()).isEqualTo(503);
        assertThat(page.table().rows().getFirst().changes()).isEmpty();
        RecordHistory.Detail detail =
                service.detail(
                        new RecordHistory.DetailQuery(
                                scope, summary.visibility(), d.objectId(), row.id()),
                        10001);
        assertThat(detail.row().changes()).hasSize(1005);
        assertThat(detail.row().changes().stream().map(RecordHistory.Change::id).distinct().count())
                .isEqualTo(1005);
    }

    @Test
    void historyTracksMultipleEditorsAndRestorationWithinRange() {
        DataCenter.Definition d = object(false);
        String app = app(d);
        String name = field(d, "name");
        authorize(app, member(grant(d, "ALL", Set.of("READ", "UPDATE"), Set.of(name))));
        ApplicationRecords.Row first = runtime.save(create(app, d, "原值"), 10001).record();
        String start = java.time.Instant.now().toString();
        ApplicationRecords.Row second =
                runtime.save(
                                new Save(
                                        app,
                                        d.objectId(),
                                        first.id(),
                                        first.revision(),
                                        Map.of(name, "中间值"),
                                        null),
                                10001)
                        .record();
        runtime.save(
                new Save(
                        app,
                        d.objectId(),
                        second.id(),
                        second.revision(),
                        Map.of(name, "原值"),
                        null),
                20002);
        RecordHistory.Result result =
                history(app, start, java.time.Instant.now().toString(), "10001", 10001);
        RecordHistory.Row row = result.tables().getFirst().rows().getFirst();
        assertThat(row.changes()).hasSize(2);
        assertThat(row.changes())
                .extracting(com.richuang.os.nocode.api.RecordHistory.Change::employeeId)
                .containsExactly("10001", "20002");
        assertThat(row.restored()).isTrue();
        assertThat(row.startValues().get(name)).isEqualTo("原值");
        assertThat(row.endValues().get(name)).isEqualTo("原值");
    }

    @Test
    void historyKeepsDeletedRowsAndHistoricalWholeTableStable() {
        DataCenter.Definition d = object(false);
        String app = app(d);
        String name = field(d, "name");
        ApplicationRecords.Row first = runtime.save(create(app, d, "保留原值"), 10001).record();
        ApplicationRecords.Row unchanged = runtime.save(create(app, d, "未变更"), 10001).record();
        String start = java.time.Instant.now().toString();
        ApplicationRecords.Row second =
                runtime.save(
                                new Save(
                                        app,
                                        d.objectId(),
                                        first.id(),
                                        first.revision(),
                                        Map.of(name, "截止时值"),
                                        null),
                                10001)
                        .record();
        String cutoff = java.time.Instant.now().toString();
        runtime.delete(new Delete(app, d.objectId(), second.id(), second.revision()), 10001);
        RecordHistory.Table old = history(app, start, cutoff, null, 10001).tables().getFirst();
        assertThat(old.rows()).hasSize(2);
        assertThat(
                        old.rows().stream()
                                .filter(r -> r.id().equals(first.id()))
                                .findFirst()
                                .orElseThrow()
                                .values()
                                .get(name))
                .isEqualTo("截止时值");
        assertThat(old.rows()).noneMatch(com.richuang.os.nocode.api.RecordHistory.Row::deleted);
        RecordHistory.Table now =
                history(app, start, java.time.Instant.now().toString(), null, 10001)
                        .tables()
                        .getFirst();
        RecordHistory.Row deleted =
                now.rows().stream()
                        .filter(r -> r.id().equals(first.id()))
                        .findFirst()
                        .orElseThrow();
        assertThat(deleted.deleted()).isTrue();
        assertThat(deleted.changes()).hasSize(2);
        assertThat(deleted.values().get(name)).isEqualTo("截止时值");
        assertThat(
                        now.rows().stream()
                                .filter(r -> r.id().equals(unchanged.id()))
                                .findFirst()
                                .orElseThrow()
                                .changes())
                .isEmpty();
    }

    @Test
    void historyNoopAndFailedWritesDoNotCreateEvents() {
        DataCenter.Definition d = object(false);
        String app = app(d);
        ApplicationRecords.Row record = runtime.save(create(app, d, "相同值"), 10001).record();
        String start = java.time.Instant.now().toString();
        ApplicationRecords.Row same =
                runtime.save(
                                new Save(
                                        app,
                                        d.objectId(),
                                        record.id(),
                                        record.revision(),
                                        Map.of(field(d, "name"), "相同值"),
                                        null),
                                10001)
                        .record();
        assertThatThrownBy(
                () ->
                        runtime.save(
                                new Save(
                                        app,
                                        d.objectId(),
                                        record.id(),
                                        "stale",
                                        Map.of(field(d, "name"), "不应出现"),
                                        null),
                                10001));
        RecordHistory.Row row =
                history(app, start, java.time.Instant.now().toString(), null, 10001)
                        .tables()
                        .getFirst()
                        .rows()
                        .getFirst();
        assertThat(row.changes()).isEmpty();
        assertThat(row.values().get(field(d, "name"))).isEqualTo("相同值");
        assertThat(same.id()).isEqualTo(record.id());
    }

    @Test
    void historyBaselineIsNotAnUpdateAndPermissionsAreRechecked() {
        DataCenter.Definition d = object(false);
        String app = app(d);
        // 独立测试夹具模拟能力启用前已有的业务记录。
        jdbc.update(
                "INSERT INTO public.\""
                        + d.tableName()
                        + "\"(name,creator,updater,create_time,update_time,deleted)"
                        + " VALUES('存量记录','10001','10001',now(),now(),0)");
        String start = java.time.Instant.now().minusSeconds(86400).toString();
        history(app, start, null, null, 10001);
        RecordHistory.Table table = history(app, start, null, null, 10001).tables().getFirst();
        assertThat(table.complete()).isFalse();
        assertThat(table.rows()).hasSize(1);
        assertThat(table.rows().getFirst().changes()).isEmpty();
        assertThat(history(app, start, null, null, 99999).tables()).isEmpty();
        assertThatThrownBy(() -> history(app, "invalid", null, null, 10001))
                .hasMessageContaining("有效");
    }

    @Test
    void historyRangeIncludesEveryDayAndExcludesLaterEdits() {
        DataCenter.Definition d = object(false);
        String app = app(d);
        String name = field(d, "name");
        ApplicationRecords.Row first = runtime.save(create(app, d, "第一天"), 10001).record();
        ApplicationRecords.Row second =
                runtime.save(
                                new Save(
                                        app,
                                        d.objectId(),
                                        first.id(),
                                        first.revision(),
                                        Map.of(name, "第二天"),
                                        null),
                                10001)
                        .record();
        java.time.Instant base = java.time.Instant.now().minusSeconds(172800);
        jdbc.update(
                "UPDATE public.nocode_record_history SET occurred_at=CAST(? AS timestamptz) WHERE"
                        + " object_id=? AND operation='CREATE'",
                base.toString(),
                Long.parseLong(d.objectId()));
        jdbc.update(
                "UPDATE public.nocode_record_history SET occurred_at=CAST(? AS timestamptz) WHERE"
                        + " object_id=? AND operation='UPDATE'",
                base.plusSeconds(86400).toString(),
                Long.parseLong(d.objectId()));
        assertThat(
                        history(
                                        app,
                                        base.toString(),
                                        base.plusSeconds(86400).toString(),
                                        null,
                                        10001)
                                .tables()
                                .getFirst()
                                .rows()
                                .getFirst()
                                .changes())
                .hasSize(2);
        RecordHistory.Row oneDay =
                history(app, base.toString(), base.plusSeconds(86399).toString(), null, 10001)
                        .tables()
                        .getFirst()
                        .rows()
                        .getFirst();
        assertThat(oneDay.changes()).hasSize(1);
        assertThat(oneDay.values().get(name)).isEqualTo("第一天");
        assertThat(runtime.get(app, d.objectId(), second.id(), 10001).record().values().get(name))
                .isEqualTo("第二天");
    }

    @Test
    void historyCurrentFieldGrantsAndRevocationApplyToPastEvents() {
        DataCenter.Definition d = object(false);
        String app = app(d);
        String name = field(d, "name");
        runtime.save(create(app, d, "允许查看"), 10001);
        String start = java.time.Instant.now().minusSeconds(60).toString();
        authorize(app, member(grant(d, "ALL", Set.of("READ"), Set.of(name))));
        RecordHistory.Row row =
                history(app, start, null, null, 20002).tables().getFirst().rows().getFirst();
        assertThat(row.values()).containsOnlyKeys(name);
        assertThat(row.changes().getFirst().after()).containsOnlyKeys(name);
        authorize(app);
        assertThat(history(app, start, null, null, 20002).tables()).isEmpty();
    }

    @Test
    void selectionSourceRoundTripValidatesOrganizationsAndPreservesHistoricalReferences() {
        com.richuang.os.module.system.controller.admin.organization.vo.OrganizationTreeRespVO
                rootOrg =
                        new com.richuang.os.module.system.controller.admin.organization.vo
                                .OrganizationTreeRespVO();
        rootOrg.setId("91001");
        rootOrg.setOrgCode("ORG_A");
        rootOrg.setOrgName("总部");
        rootOrg.setStatus(1);
        rootOrg.setOrgType(1);
        com.richuang.os.module.system.controller.admin.organization.vo.OrganizationTreeRespVO
                child =
                        new com.richuang.os.module.system.controller.admin.organization.vo
                                .OrganizationTreeRespVO();
        child.setId("91002");
        child.setOrgCode("ORG_B");
        child.setOrgName("分公司");
        child.setStatus(1);
        child.setOrgType(2);
        child.setParentId(rootOrg.getId());
        rootOrg.setChildren(new ArrayList<>(List.of(child)));
        com.richuang.os.module.system.service.organization.OrganizationService orgService =
                servicesContext.getBean(
                        com.richuang.os.module.system.service.organization.OrganizationService
                                .class);
        org.mockito.Mockito.when(orgService.getCurrentTenantTree(null, null))
                .thenReturn(List.of(rootOrg));
        SelectionFields.Source source =
                new SelectionFields.Source(
                        "DIRECTORY",
                        "ORGANIZATION",
                        null,
                        List.of("91001"),
                        true,
                        List.of(),
                        "NONE");
        SaveObjectDraft request = fixture.createRequest("selection");
        ArrayList<FieldDefinition> fields = new ArrayList<>(request.fields());
        fields.add(fixture.field("organization", "organization", "SELECT", 1));
        DataCenter.FieldOptions options = DataCenter.FieldOptions.defaults().withSelection(source);
        DataCenter.Design design =
                designs.save(
                        new DataCenter.SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        request.objectCode(),
                                        request.objectName(),
                                        null,
                                        request.tableName(),
                                        request.titleFieldKey(),
                                        fields,
                                        List.of()),
                                DataCenter.Settings.defaults(),
                                Map.of("organization", options),
                                List.of(),
                                List.of(),
                                List.of()),
                        10001);
        String orgField =
                design.draft().fields().stream()
                        .filter(f -> f.code().equals("organization"))
                        .findFirst()
                        .orElseThrow()
                        .id();
        assertThat(designs.get(design.draft().id()).fieldOptions().get(orgField).selection())
                .isEqualTo(source);
        DataCenter.Definition d = publish(design);
        String app = app(d);
        assertThat(
                        runtime.model(app, d.objectId(), 10001)
                                .object()
                                .fieldOptions()
                                .get(orgField)
                                .selection())
                .isEqualTo(source);
        SelectionFields.Result result =
                runtime.selection(
                        new SelectionFields.Query(
                                app, d.objectId(), null, orgField, null, 1, 10, List.of(), null),
                        10001);
        assertThat(result.tree()).isTrue();
        assertThat(result.options())
                .extracting(SelectionFields.Option::value)
                .containsExactly("91001", "91002");
        assertThat(result.options().getLast().path()).isEqualTo("总部 / 分公司");
        assertThat(result.options()).noneMatch(SelectionFields.Option::disabled);
        ApplicationRecords.Aggregate saved =
                runtime.save(
                        new Save(
                                app,
                                d.objectId(),
                                null,
                                null,
                                Map.of(field(d, "name"), "选择验收", orgField, "91002"),
                                null),
                        10001);
        assertThat(saved.record().values()).containsEntry(orgField, "91002");
        assertThat(saved.record().displayValues()).containsEntry(orgField, "分公司");
        assertThat(
                        runtime.page(
                                        new Query(
                                                app,
                                                d.objectId(),
                                                1,
                                                10,
                                                null,
                                                Map.of(orgField, "91002"),
                                                null,
                                                false),
                                        10001)
                                .getList()
                                .getFirst()
                                .displayValues())
                .containsEntry(orgField, "分公司");
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                d.objectId(),
                                                null,
                                                null,
                                                Map.of(field(d, "name"), "伪造", orgField, "999999"),
                                                null),
                                        10001))
                .hasMessageContaining("选择值");
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                d.objectId(),
                                                null,
                                                null,
                                                Map.of(
                                                        field(d, "name"),
                                                        "多值",
                                                        orgField,
                                                        List.of("91001", "91002")),
                                                null),
                                        10001))
                .hasMessageContaining("选择");
        child.setOrgName("新名称");
        child.setStatus(0);
        assertThat(
                        runtime.get(app, d.objectId(), saved.record().id(), 10001)
                                .record()
                                .displayValues()
                                .get(orgField))
                .contains("新名称", "停用");
        ApplicationRecords.Aggregate edited =
                runtime.save(
                        new Save(
                                app,
                                d.objectId(),
                                saved.record().id(),
                                saved.record().revision(),
                                Map.of(field(d, "name"), "仅改名称", orgField, "91002"),
                                null),
                        10001);
        assertThat(edited.record().values()).containsEntry(orgField, "91002");
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                d.objectId(),
                                                null,
                                                null,
                                                Map.of(field(d, "name"), "停用新选", orgField, "91002"),
                                                null),
                                        10001))
                .hasMessageContaining("停用");
        rootOrg.setChildren(List.of());
        assertThat(
                        runtime.get(app, d.objectId(), edited.record().id(), 10001)
                                .record()
                                .displayValues()
                                .get(orgField))
                .contains("失效");
        authorize(app, member(grant(d, "ALL", Set.of("READ"), Set.of(field(d, "name")))));
        assertThatThrownBy(
                        () ->
                                runtime.selection(
                                        new SelectionFields.Query(
                                                app,
                                                d.objectId(),
                                                null,
                                                orgField,
                                                null,
                                                1,
                                                10,
                                                List.of(),
                                                null),
                                        20002))
                .hasMessageContaining("字段");
    }

    private DataCenter.Definition selectionFixture(
            boolean multiple, boolean required, boolean detail) {
        com.richuang.os.module.system.api.dict.DictDataApi dict =
                servicesContext.getBean(com.richuang.os.module.system.api.dict.DictDataApi.class);
        ArrayList<com.richuang.os.framework.common.biz.system.dict.dto.DictDataRespDTO> entries =
                new ArrayList<
                        com.richuang.os.framework.common.biz.system.dict.dto.DictDataRespDTO>();
        for (String code : List.of("A", "B")) {
            com.richuang.os.framework.common.biz.system.dict.dto.DictDataRespDTO entry =
                    new com.richuang.os.framework.common.biz.system.dict.dto.DictDataRespDTO();
            entry.setValue(code);
            entry.setLabel("候选" + code);
            entry.setStatus(0);
            entries.add(entry);
        }
        org.mockito.Mockito.when(dict.getDictDataList("selection_repair")).thenReturn(entries);
        SelectionFields.Source source =
                new SelectionFields.Source(
                        "SYSTEM_DICTIONARY",
                        null,
                        "selection_repair",
                        List.of(),
                        false,
                        List.of(),
                        "NONE");
        DataCenter.FieldOptions option = DataCenter.FieldOptions.defaults().withSelection(source);
        SaveObjectDraft request = fixture.createRequest("repair" + serial++);
        ArrayList<FieldDefinition> fields = new ArrayList<>(request.fields());
        fields.add(
                new FieldDefinition(
                        "choice",
                        null,
                        "choice",
                        "选择",
                        multiple ? "MULTI_SELECT" : "SELECT",
                        null,
                        null,
                        null,
                        required,
                        false,
                        1));
        List<DataCenter.Detail> ds =
                detail
                        ? List.of(
                                new DataCenter.Detail(
                                        null,
                                        "items",
                                        "明细",
                                        "biz_" + fixture.prefix + "items",
                                        "ACTIVE",
                                        List.of(
                                                new FieldDefinition(
                                                        "choices",
                                                        null,
                                                        "choices",
                                                        "明细选择",
                                                        "MULTI_SELECT",
                                                        null,
                                                        null,
                                                        null,
                                                        true,
                                                        false,
                                                        0)),
                                        Map.of("choices", option),
                                        List.of()))
                        : List.<DataCenter.Detail>of();
        return publish(
                designs.save(
                        new DataCenter.SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        request.objectCode(),
                                        request.objectName(),
                                        null,
                                        request.tableName(),
                                        request.titleFieldKey(),
                                        fields,
                                        List.of()),
                                DataCenter.Settings.defaults(),
                                Map.of("choice", option),
                                List.of(),
                                List.of(),
                                ds),
                        10001));
    }

    private ApplicationUi.Form selectionFormFixture(DataCenter.Definition d, Object defaultValue) {
        return new ApplicationUi.Form(
                d.objectId(),
                List.of(
                        new ApplicationUi.Node(
                                "name_node",
                                "FIELD",
                                field(d, "name"),
                                null,
                                null,
                                null,
                                List.of()),
                        new ApplicationUi.Node(
                                "choice_node",
                                "FIELD",
                                field(d, "choice"),
                                null,
                                null,
                                null,
                                List.of(),
                                null,
                                new ApplicationUi.FieldPresentation(
                                        null,
                                        null,
                                        null,
                                        false,
                                        new SelectionFields.Presentation(
                                                "MODAL",
                                                List.of(),
                                                false,
                                                null,
                                                null,
                                                defaultValue)))),
                d.details().stream().map(DataCenter.Detail::id).toList());
    }

    private ApplicationCenter.Resource selectionResource(ApplicationUi.Form form) {
        return new ApplicationCenter.Resource(
                "selection_form",
                "FORM",
                "selection_form",
                "选择表单",
                servicesContext
                        .getBean(com.fasterxml.jackson.databind.ObjectMapper.class)
                        .convertValue(form, Map.class));
    }

    /** 选项类字段不得有表单默认值（业务方裁定：未作答与选中无法区分）；表单保存即拒绝，文案逐字。 */
    private void assertOptionFormDefaultRejected(Object defaultValue) {
        var option = selectionFixture(false, false, false);
        assertThatThrownBy(
                        () ->
                                app(
                                        option,
                                        List.of(
                                                selectionResource(
                                                        selectionFormFixture(
                                                                option, defaultValue)))))
                .hasMessage(OPTION_DEFAULT_REJECTED);
    }

    private static final String OPTION_DEFAULT_REJECTED = "「选择」是选项类字段，不能设置表单默认值（未作答与选中无法区分，影响统计）";

    /** 目录候选：两个平级组织，候选A=93001、候选B=93002；aEnabled=false 时候选A停用。 */
    private void mockOrganizations(boolean aEnabled) {
        var tree =
                new ArrayList<
                        com.richuang.os.module.system.controller.admin.organization.vo
                                .OrganizationTreeRespVO>();
        for (String code : List.of("A", "B")) {
            var node =
                    new com.richuang.os.module.system.controller.admin.organization.vo
                            .OrganizationTreeRespVO();
            node.setId(code.equals("A") ? "93001" : "93002");
            node.setOrgCode("ORG_" + code);
            node.setOrgName("候选" + code);
            node.setStatus(code.equals("A") && !aEnabled ? 0 : 1);
            node.setOrgType(1);
            tree.add(node);
        }
        org.mockito.Mockito.when(
                        servicesContext
                                .getBean(
                                        com.richuang.os.module.system.service.organization
                                                .OrganizationService.class)
                                .getCurrentTenantTree(null, null))
                .thenReturn(tree);
    }

    /**
     * 与 selectionFixture 同形，但“选择”改为组织目录字段：表单默认值的行为（仅新建生效、拒绝客户端覆盖、显式 null 不恢复、预览不写库）在非选项类字段上继续覆盖。
     */
    private DataCenter.Definition directoryFixture() {
        mockOrganizations(true);
        var request = fixture.createRequest("directory" + serial++);
        var fields = new ArrayList<>(request.fields());
        fields.add(
                new FieldDefinition(
                        "choice",
                        null,
                        "choice",
                        "选择",
                        "ORGANIZATION",
                        null,
                        null,
                        null,
                        false,
                        false,
                        1));
        return publish(
                designs.save(
                        new DataCenter.SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        request.objectCode(),
                                        request.objectName(),
                                        null,
                                        request.tableName(),
                                        request.titleFieldKey(),
                                        fields,
                                        List.of()),
                                DataCenter.Settings.defaults(),
                                Map.of(
                                        "choice",
                                        DataCenter.FieldOptions.defaults()
                                                .withSelection(
                                                        new SelectionFields.Source(
                                                                "DIRECTORY",
                                                                "ORGANIZATION",
                                                                null,
                                                                List.of(),
                                                                false,
                                                                List.of(),
                                                                "NONE"))),
                                List.of(),
                                List.of(),
                                List.of()),
                        10001));
    }

    @Test
    void readOnlySelectionUsesPublishedDefaultOnlyOnCreateAndRejectsClientOverrides() {
        assertOptionFormDefaultRejected("B");
        var d = directoryFixture();
        String name = field(d, "name"), choice = field(d, "choice");
        var form = selectionFormFixture(d, "93002");
        var nodes = new ArrayList<>(form.nodes());
        var node = nodes.getLast();
        nodes.set(
                nodes.size() - 1,
                new ApplicationUi.Node(
                        node.id(),
                        node.type(),
                        node.fieldId(),
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        new ApplicationUi.FieldPresentation(
                                null, null, null, true, node.presentation().selection())));
        String app =
                app(
                        d,
                        List.of(
                                selectionResource(
                                        new ApplicationUi.Form(d.objectId(), nodes, List.of()))));
        ApplicationRecords.Row created =
                runtime.save(
                                new Save(
                                        app,
                                        d.objectId(),
                                        null,
                                        null,
                                        Map.of(name, "默认值"),
                                        null,
                                        null,
                                        null,
                                        "selection_form"),
                                10001)
                        .record();
        assertThat(String.valueOf(created.values().get(choice))).isEqualTo("93002");
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                d.objectId(),
                                                null,
                                                null,
                                                Map.of(name, "伪造默认值", choice, "93001"),
                                                null,
                                                null,
                                                null,
                                                "selection_form"),
                                        10001))
                .hasMessageContaining("无权修改");
        ApplicationRecords.Row changed =
                runtime.save(
                                new Save(
                                        app,
                                        d.objectId(),
                                        created.id(),
                                        created.revision(),
                                        Map.of(choice, "93001"),
                                        null),
                                10001)
                        .record();
        ApplicationRecords.Row updated =
                runtime.save(
                                new Save(
                                        app,
                                        d.objectId(),
                                        changed.id(),
                                        changed.revision(),
                                        Map.of(name, "仅改名称"),
                                        null,
                                        null,
                                        null,
                                        "selection_form"),
                                10001)
                        .record();
        assertThat(String.valueOf(updated.values().get(choice))).isEqualTo("93001");
        share(
                app,
                d,
                new ApplicationAuthorization.ObjectGrant(
                        d.objectId(),
                        Set.of("READ", "CREATE"),
                        "ALL",
                        Set.of(name, choice),
                        Set.of(name),
                        Set.of(),
                        Set.of()));
        ApplicationRecords.Row restricted =
                runtime.save(
                                new Save(
                                        app,
                                        d.objectId(),
                                        null,
                                        null,
                                        Map.of(name, "无字段写权限"),
                                        null,
                                        null,
                                        null,
                                        "selection_form"),
                                10001)
                        .record();
        assertThat(restricted.values().get(choice)).isNull();
    }

    @Test
    void adoptedBooleanDefaultIsPreservedForNewAndLegacyPublishedMetadata() {
        DataCenter.Definition d =
                adopted(
                        "legacy_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, active boolean"
                                + " NOT NULL DEFAULT true",
                        true);
        String app = app(d), active = field(d, "active");
        assertThat(d.fieldOptions().get(active).defaultValue()).isEqualTo("true");
        assertThat(
                        runtime.model(app, d.objectId(), 10001)
                                .object()
                                .fieldOptions()
                                .get(active)
                                .defaultValue())
                .isEqualTo("true");
        ApplicationRecords.Row row = runtime.save(create(app, d, "沿用默认"), 10001).record();
        assertThat(row.values().get(active)).isEqualTo(true);
        ApplicationRecords.Row explicit =
                runtime.save(
                                new Save(
                                        app,
                                        d.objectId(),
                                        null,
                                        null,
                                        Map.of(field(d, "name"), "明确关闭", active, false),
                                        null),
                                10001)
                        .record();
        assertThat(explicit.values().get(active)).isEqualTo(false);
        LinkedHashMap<String, DataCenter.FieldOptions> options =
                new LinkedHashMap<>(d.fieldOptions());
        options.put(active, options.get(active).withDefaultValue(null));
        DataCenter.Definition legacy =
                new DataCenter.Definition(
                        d.objectId(),
                        d.objectCode(),
                        d.objectName(),
                        d.description(),
                        d.schemaName(),
                        d.tableName(),
                        d.source(),
                        d.readOnly(),
                        d.titleFieldId(),
                        d.settings(),
                        d.fields(),
                        options,
                        d.relations(),
                        d.indexes(),
                        d.details(),
                        d.mainBinding());
        DataCenter.Definition decorated =
                servicesContext
                        .getBean(
                                com.richuang.os.nocode.runtime.service.application
                                        .ApplicationBusinessRules.class)
                        .decorate(
                                app,
                                legacy,
                                servicesContext
                                        .getBean(
                                                com.richuang.os.nocode.runtime.service.record
                                                        .RuntimeSchema.class)
                                        .main(legacy));
        assertThat(decorated.fieldOptions().get(active).defaultValue()).isEqualTo("true");
    }

    @Test
    void databaseUniqueAndPatternErrorsIdentifyTheBusinessFieldWithoutLeakingSql() {
        SaveObjectDraft request = fixture.createRequest("constraint" + serial++);
        FieldDefinition f = request.fields().getFirst();
        DataCenter.FieldOptions option =
                new DataCenter.FieldOptions(
                        null,
                        "NORMAL",
                        null,
                        null,
                        "^QA-[0-9]+$",
                        null,
                        null,
                        "ACTIVE",
                        List.of(),
                        null,
                        null,
                        "NONE",
                        null,
                        false,
                        false);
        DataCenter.Definition d =
                publish(
                        designs.save(
                                new DataCenter.SaveDesign(
                                        new SaveObjectDraft(
                                                null,
                                                null,
                                                request.objectCode(),
                                                request.objectName(),
                                                null,
                                                request.tableName(),
                                                f.key(),
                                                List.of(
                                                        new FieldDefinition(
                                                                f.key(), null, f.code(), "验收编码",
                                                                "TEXT", 100, null, null, true, true,
                                                                0)),
                                                List.of()),
                                        DataCenter.Settings.defaults(),
                                        Map.of(f.key(), option),
                                        List.of(),
                                        List.of(),
                                        List.of()),
                                10001));
        String app = app(d);
        runtime.save(create(app, d, "QA-101"), 10001);
        assertThatThrownBy(() -> runtime.save(create(app, d, "QA-101"), 10001))
                .hasMessageContaining("唯一规则：验收编码")
                .hasMessageNotContaining("INSERT");
        assertThatThrownBy(() -> runtime.save(create(app, d, "bad"), 10001))
                .hasMessageContaining("校验规则：验收编码")
                .hasMessageNotContaining("nocode_c_");
        assertThat(
                        runtime.page(
                                        new Query(
                                                app, d.objectId(), 1, 10, null, null, null, false),
                                        10001)
                                .getTotal())
                .isEqualTo(1);
    }

    @Test
    void reportFixedSelectionRequiresCodeAndDrilldownPreservesDisplayLabels() {
        DataCenter.Definition d = selectionFixture(false, false, false);
        String choice = field(d, "choice");
        ApplicationReports.Config config =
                new ApplicationReports.Config(
                        d.objectId(),
                        List.of(),
                        List.of(new ApplicationReports.Metric("count", "记录数", "COUNT", null)),
                        Map.of(choice, "候选A"),
                        List.of(),
                        null,
                        "Asia/Shanghai",
                        "METRIC",
                        null,
                        false,
                        20,
                        null);
        ApplicationCenter.Resource report =
                new ApplicationCenter.Resource(
                        "report", "REPORT", "report", "统计", mapper.convertValue(config, Map.class));
        assertThatThrownBy(() -> app(d, List.of(report))).hasMessageContaining("统计固定筛选");
        ApplicationReports.Config valid =
                new ApplicationReports.Config(
                        d.objectId(),
                        List.of(),
                        config.metrics(),
                        Map.of(choice, "A"),
                        List.of(),
                        null,
                        "Asia/Shanghai",
                        "METRIC",
                        null,
                        false,
                        20,
                        null);
        String app =
                app(
                        d,
                        List.of(
                                new ApplicationCenter.Resource(
                                        "report",
                                        "REPORT",
                                        "report",
                                        "统计",
                                        mapper.convertValue(valid, Map.class))));
        runtime.save(
                new Save(
                        app,
                        d.objectId(),
                        null,
                        null,
                        Map.of(field(d, "name"), "报表样本", choice, "A"),
                        null),
                10001);
        com.richuang.os.nocode.runtime.service.report.ApplicationReportService reports =
                servicesContext.getBean(
                        com.richuang.os.nocode.runtime.service.report.ApplicationReportService
                                .class);
        com.richuang.os.framework.common.pojo.PageResult<ApplicationRecords.Row> result =
                reports.details(
                        new ApplicationReports.Query(
                                app, "report", Map.of(), null, null, null, null, 1, 10),
                        10001);
        assertThat(result.getList()).hasSize(1);
        assertThat(result.getList().getFirst().displayValues().get(choice)).isEqualTo("候选A");
    }

    @Test
    void requiredMultiSelectionRejectsEmptyMainDetailAndImportAtomically() {
        DataCenter.Definition d = selectionFixture(true, true, true);
        String app = app(d, List.of());
        String name = field(d, "name"), choice = field(d, "choice");
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                d.objectId(),
                                                null,
                                                null,
                                                Map.of(name, "空主表", choice, List.of()),
                                                null),
                                        10001))
                .hasMessageContaining("必填");
        DataCenter.Detail dt = d.details().getFirst();
        String df =
                dt.fields().stream()
                        .filter(f -> f.code().equals("choices"))
                        .findFirst()
                        .orElseThrow()
                        .id();
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                d.objectId(),
                                                null,
                                                null,
                                                Map.of(name, "空明细", choice, List.of("A")),
                                                Map.of(
                                                        dt.id(),
                                                        List.of(
                                                                new Row(
                                                                        null,
                                                                        null,
                                                                        Map.of(df, List.of()))))),
                                        10001))
                .hasMessageContaining("必填");
        assertThatThrownBy(
                        () ->
                                runtime.importRecords(
                                        app,
                                        d.objectId(),
                                        List.of(
                                                Map.of(name, "合法导入", choice, List.of("A")),
                                                Map.of(name, "空导入", choice, List.of())),
                                        10001))
                .hasMessageContaining("必填");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.\"" + d.tableName() + "\"",
                                Long.class))
                .isZero();
    }

    @Test
    void optionalMultiEmptyArraysConvertToNullEvenWithoutExpandedIds() {
        for (String targetType : List.of("SELECT", "ORGANIZATION"))
            for (boolean mixed : List.of(false, true)) {
                if (targetType.equals("ORGANIZATION") && mixed) continue;
                DataCenter.Definition d = selectionFixture(true, false, false);
                jdbc.update(
                        "INSERT INTO public.\""
                                + d.tableName()
                                + "\" (name, choice) VALUES ('empty','[]'), ('null',NULL)");
                if (mixed)
                    jdbc.update(
                            "INSERT INTO public.\""
                                    + d.tableName()
                                    + "\" (name, choice) VALUES ('one','[\"A\"]')");
                DataCenter.Design design =
                        designs.editPublished(
                                new DataCenter.Revision(
                                        d.objectId(),
                                        designs.get(d.objectId()).draft().lockVersion(),
                                        "空值转换回归"),
                                10001);
                List<FieldDefinition> changedFields =
                        design.draft().fields().stream()
                                .map(
                                        f ->
                                                f.id().equals(field(d, "choice"))
                                                        ? new FieldDefinition(
                                                                f.key(),
                                                                f.id(),
                                                                f.code(),
                                                                f.name(),
                                                                targetType,
                                                                null,
                                                                null,
                                                                null,
                                                                f.required(),
                                                                f.unique(),
                                                                f.sort())
                                                        : f)
                                .toList();
                Map<String, DataCenter.FieldOptions> options =
                        new LinkedHashMap<>(design.fieldOptions());
                options.put(
                        field(d, "choice"),
                        options.get(field(d, "choice"))
                                .withSelection(
                                        new SelectionFields.Source(
                                                targetType.equals("SELECT")
                                                        ? "SYSTEM_DICTIONARY"
                                                        : "DIRECTORY",
                                                targetType.equals("SELECT") ? null : "ORGANIZATION",
                                                targetType.equals("SELECT")
                                                        ? "selection_repair"
                                                        : null,
                                                List.of(),
                                                false,
                                                List.of(),
                                                "NONE",
                                                mixed ? Map.of("A", List.of("A")) : Map.of())));
                DataCenter.Design changed =
                        designs.save(
                                new DataCenter.SaveDesign(
                                        fixture.edit(
                                                design.draft(),
                                                changedFields,
                                                List.of(),
                                                design.draft().titleFieldId()),
                                        design.settings(),
                                        options,
                                        design.relations(),
                                        design.indexes(),
                                        design.details(),
                                        design.mainBinding()),
                                10001);
                if (mixed) {
                    // 显式旧值映射继续沿用原迁移协议；空数组同步整理为 NULL。
                    publish(changed);
                } else {
                    // 同来源多选改单选可保留语义，将 [] 归为 NULL；跨来源才需逐列清空确认。
                    DataCenter.PublishPlan emptyArrayPlan =
                            publisher.plan(
                                    new DataCenter.Revision(
                                            d.objectId(), changed.draft().lockVersion(), null),
                                    10001);
                    assertThat(emptyArrayPlan.checks()).noneMatch(DataCenter.Check::blocking);
                    assertThat(emptyArrayPlan.conversions())
                            .singleElement()
                            .satisfies(c -> assertThat(c.affectedRows()).isEqualTo(1));
                    FieldConversions.Change conversion = emptyArrayPlan.conversions().getFirst();
                    List<String> clearFieldIds;
                    if (FieldTypeEnum.SELECT.matches(targetType)) {
                        assertThat(conversion.action())
                                .isEqualTo(FieldConversionActionEnum.PRESERVE_VALUES.getCode());
                        assertThat(conversion.failedRows()).isZero();
                        assertThat(conversion.conversionRule()).contains("空集合归为空值");
                        clearFieldIds = List.of();
                    } else {
                        assertThat(conversion.action())
                                .isEqualTo(FieldConversionActionEnum.CLEAR_COLUMN.getCode());
                        clearFieldIds = List.of(field(d, "choice"));
                    }
                    publisher.execute(
                            new DataCenter.ExecutePlan(
                                    emptyArrayPlan.id(), "确认空数组整理为单值空列", clearFieldIds),
                            10001);
                }
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT count(*) FROM public.\""
                                                + d.tableName()
                                                + "\" WHERE choice IS NULL",
                                        Long.class))
                        .isEqualTo(2);
                if (mixed)
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT choice FROM public.\""
                                                    + d.tableName()
                                                    + "\" WHERE name='one'",
                                            String.class))
                            .isEqualTo("A");
            }
    }

    @Test
    void requiredSingleTargetBlocksEmptyHistoryBeforeChangingPhysicalValues() {
        DataCenter.Definition d = selectionFixture(true, false, false);
        jdbc.update(
                "INSERT INTO public.\""
                        + d.tableName()
                        + "\" (name, choice) VALUES ('empty','[]'), ('null',NULL)");
        DataCenter.Design design =
                designs.editPublished(
                        new DataCenter.Revision(
                                d.objectId(),
                                designs.get(d.objectId()).draft().lockVersion(),
                                "必填转换回归"),
                        10001);
        List<FieldDefinition> fields =
                design.draft().fields().stream()
                        .map(
                                f ->
                                        f.id().equals(field(d, "choice"))
                                                ? new FieldDefinition(
                                                        f.key(), f.id(), f.code(), f.name(),
                                                        "SELECT", null, null, null, true, false,
                                                        f.sort())
                                                : f)
                        .toList();
        DataCenter.Design changed =
                designs.save(
                        new DataCenter.SaveDesign(
                                fixture.edit(
                                        design.draft(),
                                        fields,
                                        List.of(),
                                        design.draft().titleFieldId()),
                                design.settings(),
                                design.fieldOptions(),
                                design.relations(),
                                design.indexes(),
                                design.details(),
                                design.mainBinding()),
                        10001);
        DataCenter.PublishPlan plan =
                publisher.plan(
                        new DataCenter.Revision(d.objectId(), changed.draft().lockVersion(), null),
                        10001);
        assertThat(plan.checks()).anyMatch(c -> c.blocking() && c.message().contains("转换"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT choice::text FROM public.\""
                                        + d.tableName()
                                        + "\" WHERE name='empty'",
                                String.class))
                .isEqualTo("[]");
    }

    @Test
    void fixedDefaultsAreCheckedAndExplicitNullDoesNotRestoreDefault() {
        assertOptionFormDefaultRejected("A");
        var d = directoryFixture();
        assertThatThrownBy(
                        () -> app(d, List.of(selectionResource(selectionFormFixture(d, "93999")))))
                .hasMessageContaining("默认值");
        var app = app(d, List.of(selectionResource(selectionFormFixture(d, "93001"))));
        var formId = applications.published(app).definition().resources().getFirst().id();
        var input = new LinkedHashMap<String, Object>();
        input.put(field(d, "name"), "主动清空");
        input.put(field(d, "choice"), null);
        ApplicationRecords.Aggregate saved =
                runtime.save(
                        new Save(app, d.objectId(), null, null, input, null, null, null, formId),
                        10001);
        assertThat(saved.record().values().get(field(d, "choice"))).isNull();
        ApplicationRecords.Aggregate defaulted =
                runtime.save(
                        new Save(
                                app,
                                d.objectId(),
                                null,
                                null,
                                Map.of(field(d, "name"), "未提供"),
                                null,
                                null,
                                null,
                                formId),
                        10001);
        assertThat(String.valueOf(defaulted.record().values().get(field(d, "choice"))))
                .isEqualTo("93001");
        // 默认组织停用后，默认值失效：候选接口给出提示，新建不再带出。
        mockOrganizations(false);
        var staleDefault =
                runtime.selection(
                        new SelectionFields.Query(
                                app,
                                d.objectId(),
                                null,
                                field(d, "choice"),
                                null,
                                1,
                                10,
                                List.of(),
                                null,
                                formId,
                                Map.of()),
                        10001);
        assertThat(staleDefault.defaultValue()).isNull();
        assertThat(staleDefault.defaultWarning()).contains("默认值已失效");
        ApplicationRecords.Aggregate withoutStaleDefault =
                runtime.save(
                        new Save(
                                app,
                                d.objectId(),
                                null,
                                null,
                                Map.of(field(d, "name"), "默认值停用后"),
                                null,
                                null,
                                null,
                                formId),
                        10001);
        assertThat(withoutStaleDefault.record().values().get(field(d, "choice"))).isNull();
    }

    @Test
    void presentationDoesNotPreventRetainingDisabledSelectionAndAddingValidValue() {
        DataCenter.Definition d = selectionFixture(true, false, false);
        String app = app(d, List.of(selectionResource(selectionFormFixture(d, null))));
        String formId = applications.published(app).definition().resources().getFirst().id();
        ApplicationRecords.Aggregate saved =
                runtime.save(
                        new Save(
                                app,
                                d.objectId(),
                                null,
                                null,
                                Map.of(field(d, "name"), "历史", field(d, "choice"), List.of("A")),
                                null,
                                null,
                                null,
                                formId),
                        10001);
        servicesContext
                .getBean(com.richuang.os.module.system.api.dict.DictDataApi.class)
                .getDictDataList("selection_repair")
                .getFirst()
                .setStatus(1);
        ApplicationRecords.Aggregate edited =
                runtime.save(
                        new Save(
                                app,
                                d.objectId(),
                                saved.record().id(),
                                saved.record().revision(),
                                Map.of(field(d, "choice"), List.of("A", "B")),
                                null,
                                null,
                                null,
                                formId),
                        10001);
        assertThat(edited.record().values()).containsEntry(field(d, "choice"), List.of("A", "B"));
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                d.objectId(),
                                                null,
                                                null,
                                                Map.of(
                                                        field(d, "name"),
                                                        "非法新增",
                                                        field(d, "choice"),
                                                        List.of("A")),
                                                null,
                                                null,
                                                null,
                                                formId),
                                        10001))
                .hasMessageContaining("停用");
    }

    @Test
    void unpublishedDraftPreviewUsesVerifiedReferencesAndDoesNotWriteRecords() {
        assertOptionFormDefaultRejected("B");
        var d = directoryFixture();
        var version = servicesContext.getBean(DataObjectApi.class).getVersion(d.objectId(), null);
        var refs =
                List.of(
                        new ApplicationCenter.ObjectReference(
                                version.objectId(), version.versionNo(), version.checksum()));
        var form = selectionFormFixture(d, "93002");
        var app =
                applications.save(
                        new ApplicationCenter.Save(
                                null,
                                null,
                                fixture.prefix + "preview",
                                "未发布预览",
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        refs, List.of(selectionResource(form)))),
                        10001);
        grantApplicationObjects(app.application().id());
        SelectionFields.Query query =
                new SelectionFields.Query(
                        app.application().id(),
                        d.objectId(),
                        null,
                        field(d, "choice"),
                        null,
                        1,
                        10,
                        List.of(),
                        null);
        SelectionFields.Result result =
                runtime.previewSelection(
                        new SelectionFields.PreviewQuery(query, refs, form, false), 10001);
        assertThat(result.options())
                .extracting(SelectionFields.Option::label)
                .containsExactly("候选A", "候选B");
        assertThat(result.defaultValue()).isEqualTo("93002");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.\"" + d.tableName() + "\"",
                                Long.class))
                .isZero();
        assertThatThrownBy(
                        () ->
                                runtime.previewSelection(
                                        new SelectionFields.PreviewQuery(
                                                query,
                                                List.of(
                                                        new ApplicationCenter.ObjectReference(
                                                                version.objectId(),
                                                                version.versionNo(),
                                                                "tampered")),
                                                form,
                                                false),
                                        10001))
                .hasMessageContaining("校验和");
        assertThatThrownBy(
                () ->
                        runtime.previewSelection(
                                new SelectionFields.PreviewQuery(query, refs, form, false), 10002));
    }

    @Test
    void selectionDictionaryUsesLiveCodesAndRejectsUnboundSource() {
        com.richuang.os.module.system.api.dict.DictDataApi dictionary =
                servicesContext.getBean(com.richuang.os.module.system.api.dict.DictDataApi.class);
        com.richuang.os.framework.common.biz.system.dict.dto.DictDataRespDTO usd =
                new com.richuang.os.framework.common.biz.system.dict.dto.DictDataRespDTO();
        usd.setDictType("test_currency");
        usd.setValue("USD");
        usd.setLabel("美元");
        usd.setStatus(0);
        org.mockito.Mockito.when(dictionary.getDictDataList("test_currency"))
                .thenReturn(List.of(usd));
        SaveObjectDraft request = fixture.createRequest("dictionary");
        ArrayList<FieldDefinition> fields = new ArrayList<>(request.fields());
        fields.add(fixture.field("currency", "currency", "SELECT", 1));
        SelectionFields.Source source =
                new SelectionFields.Source(
                        "SYSTEM_DICTIONARY",
                        null,
                        "test_currency",
                        List.of(),
                        false,
                        List.of(),
                        "NONE");
        DataCenter.Definition d =
                publish(
                        designs.save(
                                new DataCenter.SaveDesign(
                                        new SaveObjectDraft(
                                                null,
                                                null,
                                                request.objectCode(),
                                                request.objectName(),
                                                null,
                                                request.tableName(),
                                                request.titleFieldKey(),
                                                fields,
                                                List.of()),
                                        DataCenter.Settings.defaults(),
                                        Map.of(
                                                "currency",
                                                DataCenter.FieldOptions.defaults()
                                                        .withSelection(source)),
                                        List.of(),
                                        List.of(),
                                        List.of()),
                                10001));
        String app = app(d);
        String currency = field(d, "currency");
        ApplicationRecords.Aggregate saved =
                runtime.save(
                        new Save(
                                app,
                                d.objectId(),
                                null,
                                null,
                                Map.of(field(d, "name"), "字典测试", currency, "USD"),
                                null),
                        10001);
        assertThat(saved.record().displayValues()).containsEntry(currency, "美元");
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                d.objectId(),
                                                null,
                                                null,
                                                Map.of(field(d, "name"), "非法值", currency, "美元"),
                                                null),
                                        10001))
                .hasMessageContaining("选择值");
        usd.setLabel("美元新标签");
        assertThat(
                        runtime.get(app, d.objectId(), saved.record().id(), 10001)
                                .record()
                                .displayValues())
                .containsEntry(currency, "美元新标签");
        assertThatThrownBy(
                        () ->
                                SelectionFields.validate(
                                        fields.getLast(),
                                        DataCenter.FieldOptions.defaults()
                                                .withSelection(
                                                        new SelectionFields.Source(
                                                                "OBJECT_RELATION",
                                                                null,
                                                                null,
                                                                List.of(),
                                                                false,
                                                                List.of(),
                                                                "NONE"))))
                .hasMessageContaining("对象关系");
    }

    @Test
    void selectionMigrationRequiresCompleteMappingAndPublishesAtomically() {
        com.richuang.os.module.system.api.dict.DictDataApi dict =
                servicesContext.getBean(com.richuang.os.module.system.api.dict.DictDataApi.class);
        com.richuang.os.framework.common.biz.system.dict.dto.DictDataRespDTO oldA =
                new com.richuang.os.framework.common.biz.system.dict.dto.DictDataRespDTO();
        oldA.setValue("NORTH");
        oldA.setLabel("北区");
        oldA.setStatus(0);
        com.richuang.os.framework.common.biz.system.dict.dto.DictDataRespDTO oldB =
                new com.richuang.os.framework.common.biz.system.dict.dto.DictDataRespDTO();
        oldB.setValue("SOUTH");
        oldB.setLabel("南区");
        oldB.setStatus(0);
        org.mockito.Mockito.when(dict.getDictDataList("old_regions"))
                .thenReturn(List.of(oldA, oldB));
        com.richuang.os.module.system.controller.admin.organization.vo.OrganizationTreeRespVO org1 =
                new com.richuang.os.module.system.controller.admin.organization.vo
                        .OrganizationTreeRespVO();
        org1.setId("92001");
        org1.setOrgCode("NORTH_ORG");
        org1.setOrgName("北方公司");
        org1.setStatus(1);
        org1.setOrgType(1);
        com.richuang.os.module.system.controller.admin.organization.vo.OrganizationTreeRespVO org2 =
                new com.richuang.os.module.system.controller.admin.organization.vo
                        .OrganizationTreeRespVO();
        org2.setId("92002");
        org2.setOrgCode("SOUTH_ORG");
        org2.setOrgName("南方公司");
        org2.setStatus(1);
        org2.setOrgType(1);
        org.mockito.Mockito.when(
                        servicesContext
                                .getBean(
                                        com.richuang.os.module.system.service.organization
                                                .OrganizationService.class)
                                .getCurrentTenantTree(null, null))
                .thenReturn(List.of(org1, org2));
        SaveObjectDraft request = fixture.createRequest("migration");
        ArrayList<FieldDefinition> fields = new ArrayList<>(request.fields());
        fields.add(
                new FieldDefinition(
                        "org", null, "org", "所属组织", "SELECT", null, null, null, false, true, 1));
        SelectionFields.Source oldSource =
                new SelectionFields.Source(
                        "SYSTEM_DICTIONARY",
                        null,
                        "old_regions",
                        List.of(),
                        false,
                        List.of(),
                        "NONE");
        DataCenter.Definition d =
                publish(
                        designs.save(
                                new DataCenter.SaveDesign(
                                        new SaveObjectDraft(
                                                null,
                                                null,
                                                request.objectCode(),
                                                request.objectName(),
                                                null,
                                                request.tableName(),
                                                request.titleFieldKey(),
                                                fields,
                                                List.of()),
                                        DataCenter.Settings.defaults(),
                                        Map.of(
                                                "org",
                                                DataCenter.FieldOptions.defaults()
                                                        .withSelection(oldSource)),
                                        List.of(),
                                        List.of(),
                                        List.of()),
                                10001));
        jdbc.update(
                "INSERT INTO public.\""
                        + d.tableName()
                        + "\" (name, org) VALUES ('迁移甲','NORTH'), ('迁移乙','SOUTH')");
        DataCenter.Design design =
                designs.editPublished(
                        new DataCenter.Revision(
                                d.objectId(),
                                designs.get(d.objectId()).draft().lockVersion(),
                                "选择迁移"),
                        10001);
        List<FieldDefinition> changedFields =
                design.draft().fields().stream()
                        .map(
                                f ->
                                        f.code().equals("org")
                                                ? new FieldDefinition(
                                                        f.key(),
                                                        f.id(),
                                                        f.code(),
                                                        f.name(),
                                                        "ORGANIZATION",
                                                        null,
                                                        null,
                                                        null,
                                                        f.required(),
                                                        f.unique(),
                                                        f.sort())
                                                : f)
                        .toList();
        LinkedHashMap<String, DataCenter.FieldOptions> opts =
                new LinkedHashMap<>(design.fieldOptions());
        String fieldId = field(d, "org");
        opts.put(
                fieldId,
                DataCenter.FieldOptions.defaults()
                        .withSelection(
                                new SelectionFields.Source(
                                        "DIRECTORY",
                                        "ORGANIZATION",
                                        null,
                                        List.of(),
                                        false,
                                        List.of(),
                                        "NONE")));
        DataCenter.Design changed =
                designs.save(
                        new DataCenter.SaveDesign(
                                fixture.edit(
                                        design.draft(),
                                        changedFields,
                                        List.of(),
                                        design.draft().titleFieldId()),
                                design.settings(),
                                opts,
                                design.relations(),
                                design.indexes(),
                                design.details(),
                                design.mainBinding()),
                        10001);
        DataCenter.PublishPlan blocked =
                publisher.plan(
                        new DataCenter.Revision(d.objectId(), changed.draft().lockVersion(), null),
                        10001);
        assertThat(blocked.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(blocked.conversions())
                .singleElement()
                .satisfies(c -> assertThat(c.affectedRows()).isEqualTo(2));
        assertThatThrownBy(
                        () ->
                                publisher.execute(
                                        new DataCenter.ExecutePlan(blocked.id(), "未确认不能清空旧选择值"),
                                        10001))
                .hasMessageContaining("确认发布计划中全部待清空字段");
        DataCenter.Design current = changed;
        opts.put(
                fieldId,
                opts.get(fieldId)
                        .withSelection(
                                new SelectionFields.Source(
                                        "DIRECTORY",
                                        "ORGANIZATION",
                                        null,
                                        List.of(),
                                        false,
                                        List.of(),
                                        "NONE",
                                        Map.of(
                                                "NORTH",
                                                List.of("92001"),
                                                "SOUTH",
                                                List.of("92001")))));
        changed =
                designs.save(
                        new DataCenter.SaveDesign(
                                fixture.edit(
                                        current.draft(),
                                        current.draft().fields(),
                                        List.of(),
                                        current.draft().titleFieldId()),
                                current.settings(),
                                opts,
                                current.relations(),
                                current.indexes(),
                                current.details(),
                                current.mainBinding()),
                        10001);
        DataCenter.PublishPlan collision =
                publisher.plan(
                        new DataCenter.Revision(d.objectId(), changed.draft().lockVersion(), null),
                        10001);
        if (collision.checks().stream().noneMatch(DataCenter.Check::blocking))
            assertThatThrownBy(
                            () ->
                                    publisher.execute(
                                            new DataCenter.ExecutePlan(collision.id(), "回滚验证"),
                                            10001))
                    .isInstanceOf(RuntimeException.class);
        assertThat(
                        jdbc.queryForList(
                                "SELECT org::text FROM public.\""
                                        + d.tableName()
                                        + "\" ORDER BY org",
                                String.class))
                .containsExactly("NORTH", "SOUTH");
        assertThat(
                        servicesContext
                                .getBean(DataObjectApi.class)
                                .getPublished(d.objectId())
                                .fieldOptions()
                                .get(fieldId)
                                .selection()
                                .kind())
                .isEqualTo("SYSTEM_DICTIONARY");
        current = designs.get(d.objectId());
        opts.put(
                fieldId,
                opts.get(fieldId)
                        .withSelection(
                                new SelectionFields.Source(
                                        "DIRECTORY",
                                        "ORGANIZATION",
                                        null,
                                        List.of(),
                                        false,
                                        List.of(),
                                        "NONE",
                                        Map.of(
                                                "NORTH",
                                                List.of("92001"),
                                                "SOUTH",
                                                List.of("92002")))));
        DataCenter.Definition done =
                publish(
                        designs.save(
                                new DataCenter.SaveDesign(
                                        fixture.edit(
                                                current.draft(),
                                                current.draft().fields(),
                                                List.of(),
                                                current.draft().titleFieldId()),
                                        current.settings(),
                                        opts,
                                        current.relations(),
                                        current.indexes(),
                                        current.details(),
                                        current.mainBinding()),
                                10001));
        assertThat(
                        jdbc.queryForList(
                                "SELECT org::text FROM public.\""
                                        + d.tableName()
                                        + "\" ORDER BY org",
                                String.class))
                .containsExactly("92001", "92002");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT data_type FROM information_schema.columns WHERE"
                                        + " table_schema='public' AND table_name=? AND"
                                        + " column_name='org'",
                                String.class,
                                d.tableName()))
                .isEqualTo("bigint");
        assertThat(
                        runtime.page(
                                        new Query(
                                                app(done),
                                                done.objectId(),
                                                1,
                                                10,
                                                null,
                                                null,
                                                null,
                                                false),
                                        10001)
                                .getList())
                .allMatch(r -> r.displayValues().get(fieldId).endsWith("公司"));
    }

    @Test
    void selectionScenesDefaultsDetailsAndDescendantQueriesUsePublishedRules() {
        com.richuang.os.module.system.controller.admin.organization.vo.OrganizationTreeRespVO
                parent =
                        new com.richuang.os.module.system.controller.admin.organization.vo
                                .OrganizationTreeRespVO();
        parent.setId("93001");
        parent.setOrgCode("ROOT");
        parent.setOrgName("总部");
        parent.setStatus(1);
        parent.setOrgType(1);
        com.richuang.os.module.system.controller.admin.organization.vo.OrganizationTreeRespVO
                child =
                        new com.richuang.os.module.system.controller.admin.organization.vo
                                .OrganizationTreeRespVO();
        child.setId("93002");
        child.setOrgCode("BRANCH");
        child.setOrgName("子公司");
        child.setParentId("93001");
        child.setStatus(1);
        child.setOrgType(2);
        parent.setChildren(List.of(child));
        org.mockito.Mockito.when(
                        servicesContext
                                .getBean(
                                        com.richuang.os.module.system.service.organization
                                                .OrganizationService.class)
                                .getCurrentTenantTree(null, null))
                .thenReturn(List.of(parent));
        com.richuang.os.module.system.dal.dataobject.user.AdminUserDO user =
                new com.richuang.os.module.system.dal.dataobject.user.AdminUserDO();
        user.setOrgId(93002L);
        org.mockito.Mockito.when(
                        servicesContext
                                .getBean(
                                        com.richuang.os.module.system.service.user.AdminUserService
                                                .class)
                                .getUser(10001L))
                .thenReturn(user);
        SelectionFields.Source source =
                new SelectionFields.Source(
                        "DIRECTORY",
                        "ORGANIZATION",
                        null,
                        List.of("93001"),
                        true,
                        List.of(),
                        "CURRENT_USER_ORGANIZATION");
        SaveObjectDraft request = fixture.createRequest("selection_scene");
        ArrayList<FieldDefinition> fields = new ArrayList<>(request.fields());
        fields.add(fixture.field("org", "org", "ORGANIZATION", 1));
        fields.add(fixture.field("scope", "scope", "ORGANIZATION", 2));
        DataCenter.Detail detail =
                new DataCenter.Detail(
                        null,
                        "items",
                        "内部明细",
                        "biz_" + fixture.prefix + "items",
                        "ACTIVE",
                        List.of(fixture.field("orgs", "orgs", "MULTI_SELECT", 0)),
                        Map.of("orgs", DataCenter.FieldOptions.defaults().withSelection(source)),
                        List.of());
        DataCenter.Definition d =
                publish(
                        designs.save(
                                new DataCenter.SaveDesign(
                                        new SaveObjectDraft(
                                                null,
                                                null,
                                                request.objectCode(),
                                                request.objectName(),
                                                null,
                                                request.tableName(),
                                                request.titleFieldKey(),
                                                fields,
                                                List.of()),
                                        DataCenter.Settings.defaults(),
                                        Map.of(
                                                "org",
                                                DataCenter.FieldOptions.defaults()
                                                        .withSelection(source),
                                                "scope",
                                                DataCenter.FieldOptions.defaults()
                                                        .withSelection(
                                                                new SelectionFields.Source(
                                                                        "DIRECTORY",
                                                                        "ORGANIZATION",
                                                                        null,
                                                                        List.of(),
                                                                        false,
                                                                        List.of(),
                                                                        "NONE"))),
                                        List.of(),
                                        List.of(),
                                        List.of(detail)),
                                10001));
        String org = field(d, "org"), scope = field(d, "scope"), name = field(d, "name");
        ApplicationUi.Form form =
                new ApplicationUi.Form(
                        d.objectId(),
                        List.of(
                                new ApplicationUi.Node(
                                        "n", "FIELD", name, null, null, null, List.of()),
                                new ApplicationUi.Node(
                                        "s", "FIELD", scope, null, null, null, List.of()),
                                new ApplicationUi.Node(
                                        "o",
                                        "FIELD",
                                        org,
                                        null,
                                        null,
                                        null,
                                        List.of(),
                                        null,
                                        new ApplicationUi.FieldPresentation(
                                                null,
                                                null,
                                                null,
                                                false,
                                                new SelectionFields.Presentation(
                                                        "TREE",
                                                        List.of("93002"),
                                                        true,
                                                        scope,
                                                        null,
                                                        null)))),
                        List.of(d.details().getFirst().id()));
        String app =
                app(
                        d,
                        List.of(
                                new ApplicationCenter.Resource(
                                        "form",
                                        "FORM",
                                        "form",
                                        "组织选择表单",
                                        new com.fasterxml.jackson.databind.ObjectMapper()
                                                .convertValue(form, Map.class))));
        String actualForm =
                applications.published(app).definition().resources().stream()
                        .filter(r -> r.code().equals("form"))
                        .findFirst()
                        .orElseThrow()
                        .id();
        SelectionFields.Result options =
                runtime.selection(
                        new SelectionFields.Query(
                                app,
                                d.objectId(),
                                null,
                                org,
                                null,
                                1,
                                30,
                                List.of(),
                                null,
                                actualForm,
                                Map.of(scope, "93001")),
                        10001);
        assertThat(options.options())
                .extracting(SelectionFields.Option::value)
                .containsExactly("93002");
        assertThat(options.defaultValue()).isEqualTo("93002");
        DataCenter.Detail dt = d.details().getFirst();
        String df =
                dt.fields().stream()
                        .filter(f -> f.code().equals("orgs"))
                        .findFirst()
                        .orElseThrow()
                        .id();
        ApplicationRecords.Aggregate saved =
                runtime.save(
                        new Save(
                                app,
                                d.objectId(),
                                null,
                                null,
                                Map.of(name, "层级测试", scope, "93001"),
                                Map.of(dt.id(), List.of(new Row(null, null, Map.of()))),
                                null,
                                null,
                                actualForm),
                        10001);
        assertThat(saved.record().values()).containsEntry(org, "93002");
        assertThat(saved.details().get(dt.id()).getFirst().values())
                .containsEntry(df, List.of("93002"));
        SelectionFields.Result newDetail =
                runtime.selection(
                        new SelectionFields.Query(
                                app,
                                d.objectId(),
                                dt.id(),
                                df,
                                null,
                                1,
                                10,
                                List.of(),
                                saved.record().id(),
                                null,
                                null,
                                true,
                                null),
                        10001);
        assertThat(newDetail.defaultValue()).isEqualTo(List.of("93002"));
        SelectionFields.Result existingDetail =
                runtime.selection(
                        new SelectionFields.Query(
                                app,
                                d.objectId(),
                                dt.id(),
                                df,
                                null,
                                1,
                                10,
                                List.of(),
                                saved.record().id(),
                                null,
                                null,
                                false,
                                saved.details().get(dt.id()).getFirst().id()),
                        10001);
        assertThat(existingDetail.defaultValue()).isNull();
        assertThatThrownBy(
                () ->
                        runtime.selection(
                                new SelectionFields.Query(
                                        app,
                                        d.objectId(),
                                        dt.id(),
                                        df,
                                        null,
                                        1,
                                        10,
                                        List.of(),
                                        saved.record().id(),
                                        null,
                                        null,
                                        false,
                                        "missing"),
                                10001));
        assertThat(
                        runtime.page(
                                        new Query(
                                                app,
                                                d.objectId(),
                                                1,
                                                10,
                                                null,
                                                Map.of(
                                                        org,
                                                        Map.of(
                                                                "value",
                                                                "93001",
                                                                "includeDescendants",
                                                                true)),
                                                null,
                                                false),
                                        10001)
                                .getTotal())
                .isEqualTo(1);
        assertThat(
                        runtime.selectionImportValue(
                                app,
                                d.objectId(),
                                d.fields().stream()
                                        .filter(f -> f.id().equals(org))
                                        .findFirst()
                                        .orElseThrow(),
                                "code:BRANCH",
                                10001))
                .isEqualTo("93002");
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                d.objectId(),
                                                null,
                                                null,
                                                Map.of(name, "越界", scope, "93001", org, "93001"),
                                                null,
                                                null,
                                                null,
                                                actualForm),
                                        10001))
                .hasMessageContaining("表单范围");
        ApplicationUi.Form readOnly =
                new ApplicationUi.Form(
                        d.objectId(),
                        form.nodes(),
                        form.detailIds(),
                        new ApplicationUi.FormOptions("vertical", "保存", true));
        String readOnlyApp =
                app(
                        d,
                        List.of(
                                new ApplicationCenter.Resource(
                                        "readonly",
                                        "FORM",
                                        "readonly",
                                        "只读表单",
                                        new com.fasterxml.jackson.databind.ObjectMapper()
                                                .convertValue(readOnly, Map.class))));
        String readOnlyId =
                applications.published(readOnlyApp).definition().resources().getFirst().id();
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                readOnlyApp,
                                                d.objectId(),
                                                null,
                                                null,
                                                Map.of(name, "不能提交"),
                                                null,
                                                null,
                                                null,
                                                readOnlyId),
                                        10001))
                .hasMessageContaining("只读");
        com.richuang.os.nocode.web.RecordExcelService excel =
                servicesContext.getBean(com.richuang.os.nocode.web.RecordExcelService.class);
        byte[] exported =
                excel.export(new Query(app, d.objectId(), 1, 10, null, null, null, false), 10001);
        List<Map<Integer, String>> exportedRows =
                cn.idev.excel.FastExcelFactory.read(new java.io.ByteArrayInputStream(exported))
                        .headRowNumber(0)
                        .sheet()
                        .doReadSync();
        assertThat(exportedRows.getFirst().values())
                .contains(
                        d.fields().stream()
                                        .filter(f -> f.id().equals(org))
                                        .findFirst()
                                        .orElseThrow()
                                        .name()
                                + "（显示名称）");
        assertThat(exportedRows.getLast().values()).contains("93002", "子公司");
        List<FieldDefinition> importedFields =
                d.fields().stream().filter(f -> Set.of(name, org, scope).contains(f.id())).toList();
        List<String> cells =
                importedFields.stream()
                        .map(
                                f ->
                                        f.id().equals(name)
                                                ? "Excel导入"
                                                : f.id().equals(org) ? "code:BRANCH" : "code:ROOT")
                        .toList();
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        com.richuang.os.framework.excel.core.util.ExcelUtils.write(
                output,
                "业务数据",
                importedFields.stream().map(f -> f.name() + "【" + f.code() + "】").toList(),
                List.of(cells));
        org.springframework.mock.web.MockMultipartFile file =
                new org.springframework.mock.web.MockMultipartFile(
                        "file",
                        "selection.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        output.toByteArray());
        assertThat(excel.importFile(app, d.objectId(), file, 10001)).isEqualTo(1);
        assertThat(
                        runtime.page(
                                        new Query(
                                                app,
                                                d.objectId(),
                                                1,
                                                10,
                                                "Excel导入",
                                                null,
                                                null,
                                                false),
                                        10001)
                                .getList()
                                .getFirst()
                                .values())
                .containsEntry(org, "93002");
        // 缺少联动上游时，对象默认值也必须按表单范围留空，而不是到提交时才报越界。
        ApplicationRecords.Aggregate emptyScope =
                runtime.save(
                        new Save(
                                app,
                                d.objectId(),
                                null,
                                null,
                                Map.of(name, "缺少上游"),
                                null,
                                null,
                                null,
                                actualForm),
                        10001);
        assertThat(emptyScope.record().values().get(org)).isNull();
        ApplicationUi.Node defaultScopeNode =
                new ApplicationUi.Node(
                        "s",
                        "FIELD",
                        scope,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        new ApplicationUi.FieldPresentation(
                                null,
                                null,
                                null,
                                false,
                                new SelectionFields.Presentation(
                                        "TREE", List.of(), false, null, null, "93001")));
        ApplicationUi.Form defaultScopeForm =
                new ApplicationUi.Form(
                        d.objectId(),
                        List.of(form.nodes().getFirst(), form.nodes().getLast(), defaultScopeNode),
                        form.detailIds());
        String defaultScopeApp = app(d, List.of(selectionResource(defaultScopeForm)));
        String defaultScopeFormId =
                applications.published(defaultScopeApp).definition().resources().getFirst().id();
        ApplicationRecords.Aggregate dependencyDefaults =
                runtime.save(
                        new Save(
                                defaultScopeApp,
                                d.objectId(),
                                null,
                                null,
                                Map.of(name, "上游默认值后置"),
                                null,
                                null,
                                null,
                                defaultScopeFormId),
                        10001);
        assertThat(dependencyDefaults.record().values())
                .containsEntry(scope, "93001")
                .containsEntry(org, "93002");
    }

    @Test
    void restoringReleasePreservesDraftRecordsAndWithdrawnAuthorization() {
        DataCenter.Definition d = object(false);
        String app = app(d);
        ApplicationCenter.Published first = applications.published(app);
        ApplicationCenter.Detail current = applications.get(app);
        ApplicationCenter.Detail edited =
                applications.save(
                        new ApplicationCenter.Save(
                                app,
                                current.application().revision(),
                                current.application().code(),
                                "第二版名称",
                                null,
                                null,
                                current.draft()),
                        10001);
        applications.publish(
                new ApplicationCenter.Revision(app, edited.application().revision(), "第二版"), 10001);
        ApplicationRecords.Aggregate record = runtime.save(create(app, d, "第二版之后创建的数据"), 10001);
        authorize(app, member(grant(d, "ALL", Set.of("READ"), Set.of(field(d, "name")))));
        authorize(app);
        ApplicationAuthorization.Policy policyBefore = authorization().get(app);
        current = applications.get(app);
        ApplicationCenter.Detail draft =
                applications.save(
                        new ApplicationCenter.Save(
                                app,
                                current.application().revision(),
                                current.application().code(),
                                "未发布的草稿名称",
                                null,
                                null,
                                current.draft()),
                        10001);
        ApplicationCenter.Restore command =
                new ApplicationCenter.Restore(app, draft.application().revision(), 1, "回退页面配置");
        ApplicationCenter.Detail restored = applications.restore(command, 10001);
        assertThat(restored.application().publishedVersion()).isEqualTo(3);
        assertThat(restored.application().name()).isEqualTo("未发布的草稿名称");
        assertThat(applications.published(app).application().name())
                .isEqualTo(first.application().name());
        assertThat(applications.published(app).checksum()).isEqualTo(first.checksum());
        assertThat(applications.releases(app, 1, 100).getList().getFirst().reason())
                .startsWith("恢复 V1：");
        assertThat(runtime.get(app, d.objectId(), record.record().id(), 10001).record())
                .isEqualTo(record.record());
        assertThat(authorization().get(app)).isEqualTo(policyBefore);
        assertThatThrownBy(() -> runtime.model(app, d.objectId(), 20002))
                .hasMessageContaining("权限");
        assertThatThrownBy(() -> applications.restore(command, 10001)).hasMessageContaining("刷新");
    }

    /** 真实 PostgreSQL 验证组合检索不能放宽固定视图、记录权限及导出范围。 */
    @Test
    void unifiedListConditionsPreserveScopePrecisionAndPublishedConfiguration() {
        SaveObjectDraft request = fixture.createRequest("list_query");
        ArrayList<FieldDefinition> input = new ArrayList<>(request.fields());
        input.add(fixture.field("amount", "amount", "DECIMAL", 1));
        input.add(fixture.field("active", "active", "BOOLEAN", 2));
        input.add(fixture.field("date", "date", "DATE", 3));
        DataCenter.Definition d =
                publish(
                        designs.save(
                                new DataCenter.SaveDesign(
                                        new SaveObjectDraft(
                                                null,
                                                null,
                                                request.objectCode(),
                                                request.objectName(),
                                                null,
                                                request.tableName(),
                                                request.titleFieldKey(),
                                                input,
                                                List.of()),
                                        DataCenter.Settings.defaults(),
                                        null,
                                        List.of(),
                                        List.of(),
                                        List.of()),
                                10001));
        String name = field(d, "name"),
                amount = field(d, "amount"),
                active = field(d, "active"),
                date = field(d, "date");
        Map<String, Object> config =
                Map.<String, Object>of(
                        "objectId",
                        d.objectId(),
                        "fieldIds",
                        List.of(name, amount, active, date),
                        "equal",
                        Map.of(active, true),
                        "pageSize",
                        15,
                        "descending",
                        false,
                        "list",
                        Map.of(
                                "queryFieldIds",
                                List.of(name),
                                "advancedFieldIds",
                                List.of(amount, date),
                                "columnWidths",
                                Map.of(name, 240),
                                "batchDelete",
                                true));
        ApplicationCenter.Resource view =
                new ApplicationCenter.Resource("list_query", "VIEW", "list_query", "统一列表", config);
        String app = app(d, List.of(view));
        ApplicationRecords.Aggregate first =
                runtime.save(
                        new Save(
                                app,
                                d.objectId(),
                                null,
                                null,
                                Map.of(
                                        name,
                                        "甲公司 100%_",
                                        amount,
                                        "9007199254740993.01",
                                        active,
                                        true,
                                        date,
                                        "2026-09-07"),
                                null),
                        10001);
        runtime.save(
                new Save(
                        app,
                        d.objectId(),
                        null,
                        null,
                        Map.of(name, "乙公司", amount, "1.01", active, true, date, "2026-09-08"),
                        null),
                10001);
        runtime.save(
                new Save(
                        app,
                        d.objectId(),
                        null,
                        null,
                        Map.of(
                                name,
                                "甲公司 100%_",
                                amount,
                                "9007199254740993.01",
                                active,
                                false,
                                date,
                                "2026-09-07"),
                        null),
                10001);
        com.richuang.os.common.dto.DynamicConditionDTO condition =
                conditions(
                        "AND",
                        List.of(
                                Map.of(
                                        "type",
                                        "group",
                                        "groupLogic",
                                        "OR",
                                        "groupItems",
                                        List.of(leaf(name, "like", "甲"), leaf(name, "eq", "乙公司"))),
                                leaf(amount, "gte", "9007199254740993.01"),
                                leaf(date, "between", List.of("2026-09-07", "2026-09-07"))));
        ApplicationRecords.Query query =
                new Query(
                        app,
                        d.objectId(),
                        1,
                        15,
                        null,
                        Map.of(),
                        null,
                        false,
                        view.id(),
                        null,
                        condition);
        assertThat(runtime.page(query, 10001).getList())
                .extracting(Row::id)
                .containsExactly(first.record().id());
        assertThat(runtime.page(query, 10001).getTotal()).isEqualTo(1);
        assertThat(runtime.export(query, 10001))
                .extracting(Row::id)
                .containsExactly(first.record().id());
        ApplicationRecords.Query wildcard =
                new Query(
                        app,
                        d.objectId(),
                        1,
                        20,
                        null,
                        Map.of(),
                        null,
                        false,
                        view.id(),
                        null,
                        conditions("AND", List.of(leaf(name, "like", "%_"))));
        assertThat(runtime.page(wildcard, 10001).getTotal()).isEqualTo(1);
        ApplicationCenter.Published portal =
                servicesContext
                        .getBean(
                                com.richuang.os.nocode.runtime.service.application
                                        .ApplicationRuntimeService.class)
                        .application(app, 10001);
        ApplicationUi.View publishedView =
                mapper.convertValue(
                        portal.definition().resources().getFirst().config(),
                        ApplicationUi.View.class);
        assertThat(publishedView.list().queryFieldIds()).containsExactly(name);
        assertThat(publishedView.list().columnWidths()).containsEntry(name, 240);
        assertThat(publishedView.pageSize()).isEqualTo(15);
        // 查询权限收紧后，保存过的高级条件不能继续探测隐藏字段。
        authorize(app, member(grant(d, "ALL", Set.of("READ"), Set.of(name, active))));
        assertThatThrownBy(() -> runtime.page(query, 20002)).hasMessageContaining("字段");
        ApplicationCenter.Published memberPortal =
                servicesContext
                        .getBean(
                                com.richuang.os.nocode.runtime.service.application
                                        .ApplicationRuntimeService.class)
                        .application(app, 20002);
        ApplicationUi.View memberView =
                mapper.convertValue(
                        memberPortal.definition().resources().getFirst().config(),
                        ApplicationUi.View.class);
        assertThat(memberView.list().advancedFieldIds()).isEmpty();
        authorize(
                app,
                member(
                        grant(
                                d,
                                "OWN",
                                Set.of("READ", "EXPORT"),
                                Set.of(name, amount, active, date))));
        ApplicationRecords.Query broadOr =
                new Query(
                        app,
                        d.objectId(),
                        1,
                        20,
                        null,
                        Map.of(),
                        null,
                        false,
                        view.id(),
                        null,
                        conditions(
                                "OR", List.of(leaf(name, "like", "公司"), leaf(amount, "gte", "0"))));
        assertThat(runtime.page(broadOr, 20002).getTotal()).isZero();
        assertThat(runtime.export(broadOr, 20002)).isEmpty();
        LinkedHashMap<String, Object> badConfig = new LinkedHashMap<>(config);
        badConfig.put(
                "list",
                Map.of(
                        "queryFieldIds",
                        List.of(name, name),
                        "columnWidths",
                        Map.of(),
                        "batchDelete",
                        false));
        assertThatThrownBy(
                        () ->
                                app(
                                        d,
                                        List.of(
                                                new ApplicationCenter.Resource(
                                                        "bad_list",
                                                        "VIEW",
                                                        "bad_list",
                                                        "无效配置",
                                                        badConfig))))
                .hasMessageContaining("查询字段");
    }

    /** 「内容超出列宽时」是可选键：不配置的视图保存、发布、运行时读取都不出现它；配置成自动截断后原样到达运行端；其它取值保存时被拒。 */
    @Test
    void viewListOverflowIsOptionalPublishedAndValidated() {
        var d = object(false);
        String name = field(d, "name");
        java.util.function.Function<Map<String, Object>, ApplicationCenter.Resource> view =
                list ->
                        new ApplicationCenter.Resource(
                                "list_overflow",
                                "VIEW",
                                "list_overflow",
                                "列表",
                                Map.<String, Object>of(
                                        "objectId",
                                        d.objectId(),
                                        "fieldIds",
                                        List.of(name),
                                        "equal",
                                        Map.of(),
                                        "pageSize",
                                        10,
                                        "descending",
                                        true,
                                        "list",
                                        list));
        var plain =
                Map.<String, Object>of(
                        "queryFieldIds", List.of(), "columnWidths", Map.of(), "batchDelete", false);
        var runtimeApplications =
                servicesContext.getBean(
                        com.richuang.os.nocode.runtime.service.application.ApplicationRuntimeService
                                .class);
        // 不配置：已发布定义与运行端读到的配置里都没有这个键。
        String legacy = app(d, List.of(view.apply(plain)));
        var published =
                (Map<?, ?>)
                        applications
                                .published(legacy)
                                .definition()
                                .resources()
                                .getFirst()
                                .config()
                                .get("list");
        assertThat(published.containsKey("overflow")).isFalse();
        assertThat(published.containsKey("batchDelete")).isTrue();
        var legacyView =
                mapper.convertValue(
                        runtimeApplications
                                .application(legacy, 10001)
                                .definition()
                                .resources()
                                .getFirst()
                                .config(),
                        ApplicationUi.View.class);
        assertThat(legacyView.list().overflow()).isNull();
        // 配置成自动截断：能保存发布，并原样到达运行端。
        var configured = new LinkedHashMap<>(plain);
        configured.put("overflow", "ELLIPSIS");
        String app = app(d, List.of(view.apply(configured)));
        assertThat(
                        ((Map<?, ?>)
                                        applications
                                                .published(app)
                                                .definition()
                                                .resources()
                                                .getFirst()
                                                .config()
                                                .get("list"))
                                .get("overflow"))
                .isEqualTo("ELLIPSIS");
        var runtimeView =
                mapper.convertValue(
                        runtimeApplications
                                .application(app, 10001)
                                .definition()
                                .resources()
                                .getFirst()
                                .config(),
                        ApplicationUi.View.class);
        assertThat(runtimeView.list().overflow()).isEqualTo("ELLIPSIS");
        // 其它取值（含裁定不做的 WRAP）：保存时被拒，文案说的是界面上的选项名。
        for (String code : List.of("WRAP", "AUTO")) {
            var bad = new LinkedHashMap<>(plain);
            bad.put("overflow", code);
            assertThatThrownBy(() -> app(d, List.of(view.apply(bad))))
                    .as(code)
                    .hasMessageContaining("“内容超出列宽时”只支持“自动截断”");
        }
    }

    @Test
    void unifiedListRejectsUnknownFieldsOperatorsAndMalformedGroups() {
        DataCenter.Definition d = object(false);
        String app = app(d), name = field(d, "name");
        runtime.save(create(app, d, "保留记录"), 10001);
        for (com.richuang.os.common.dto.DynamicConditionDTO condition :
                List.of(
                        conditions("AND", List.of(leaf(name + " OR 1=1", "eq", "x"))),
                        conditions("AND", List.of(leaf(name, "gt", "x"))),
                        conditions("AND", List.of(leaf(name, "unknown", "x"))),
                        conditions(
                                "AND",
                                List.of(
                                        Map.of(
                                                "type",
                                                "group",
                                                "groupLogic",
                                                "OR",
                                                "groupItems",
                                                List.of()))))) {
            assertThatThrownBy(
                            () ->
                                    runtime.page(
                                            new Query(
                                                    app,
                                                    d.objectId(),
                                                    1,
                                                    20,
                                                    null,
                                                    Map.of(),
                                                    null,
                                                    false,
                                                    null,
                                                    null,
                                                    condition),
                                            10001))
                    .isInstanceOf(
                            com.richuang.os.framework.common.exception.ServiceException.class);
        }
        assertThat(
                        runtime.page(
                                        new Query(
                                                app,
                                                d.objectId(),
                                                1,
                                                20,
                                                null,
                                                Map.of(),
                                                null,
                                                false,
                                                null,
                                                null,
                                                conditions(
                                                        "AND",
                                                        List.of(leaf(name, "eq", "' OR TRUE --")))),
                                        10001)
                                .getTotal())
                .isZero();
        assertThat(
                        runtime.page(
                                        new Query(
                                                app,
                                                d.objectId(),
                                                1,
                                                20,
                                                null,
                                                Map.of(),
                                                null,
                                                false),
                                        10001)
                                .getTotal())
                .isEqualTo(1);
    }

    private Map<String, Object> leaf(String field, String operator, Object value) {
        return Map.of("type", "condition", "field", field, "operator", operator, "value", value);
    }

    private com.richuang.os.common.dto.DynamicConditionDTO conditions(
            String logic, List<Map<String, Object>> items) {
        return mapper.convertValue(
                Map.of("logic", logic, "items", items),
                com.richuang.os.common.dto.DynamicConditionDTO.class);
    }

    @Test
    void restoreRejectsPhysicalDriftWithoutCreatingAnotherRelease() {
        DataCenter.Definition d = object(false);
        String app = app(d);
        ApplicationCenter.Detail current = applications.get(app);
        jdbc.execute(
                "ALTER TABLE public.\""
                        + d.tableName()
                        + "\" ALTER COLUMN name TYPE bigint USING 1");
        assertThatThrownBy(
                        () ->
                                applications.restore(
                                        new ApplicationCenter.Restore(
                                                app, current.application().revision(), 1, "漂移验证"),
                                        10001))
                .hasMessageContaining("物理类型");
        assertThat(applications.releases(app, 1, 100).getList()).hasSize(1);
        assertThat(applications.get(app).application().revision())
                .isEqualTo(current.application().revision());
        assertThatThrownBy(() -> runtime.model(app, d.objectId(), 10001))
                .hasMessageContaining("物理类型");
    }

    @Test
    void objectPublishWarnsOnApplicationReferencesAndAppRepairsBeforeRelease() {
        DataCenter.Definition d = object(false);
        String app = app(d);
        DataCenter.Design current = designs.get(d.objectId());
        DataCenter.Design design =
                designs.editPublished(
                        new DataCenter.Revision(
                                d.objectId(), current.draft().lockVersion(), "兼容性验证"),
                        10001);
        ArrayList<FieldDefinition> fields = new ArrayList<>(design.draft().fields());
        fields.add(
                new FieldDefinition(
                        "extra", null, "extra", "新必填字段", "TEXT", 100, null, null, true, false, 1));
        DataCenter.Design changed =
                designs.save(
                        new DataCenter.SaveDesign(
                                fixture.edit(
                                        design.draft(),
                                        fields,
                                        List.of(),
                                        design.draft().titleFieldId()),
                                design.settings(),
                                design.fieldOptions(),
                                design.relations(),
                                design.indexes(),
                                design.details(),
                                design.mainBinding()),
                        10001);
        DataCenter.PublishPlan warned =
                publisher.plan(
                        new DataCenter.Revision(
                                d.objectId(), changed.draft().lockVersion(), "破坏性变更"),
                        10001);
        assertThat(warned.state()).isEqualTo("PENDING");
        assertThat(warned.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(warned.applicationUpgrades())
                .singleElement()
                .satisfies(
                        impact -> {
                            assertThat(impact.applicationId()).isEqualTo(app);
                            assertThat(impact.reasons())
                                    .anyMatch(reason -> reason.contains("新必填字段"));
                            assertThat(impact.blockers()).isEmpty();
                        });
        // 对象维护先明确暂停受影响应用，不能继续沿用只提示警告的旧发布路径。
        assertThatThrownBy(
                        () ->
                                publisher.execute(
                                        new DataCenter.ExecutePlan(warned.id(), "未确认应用暂停"), 10001))
                .hasMessageContaining("确认暂停");
        assertThat(applications.get(app).application().status()).isEqualTo("ACTIVE");
        DataCenter.PublishPlan confirmed =
                publisher.plan(
                        new DataCenter.Revision(
                                d.objectId(), changed.draft().lockVersion(), "确认维护"),
                        10001);
        assertThat(
                        publisher
                                .execute(
                                        new DataCenter.ExecutePlan(
                                                confirmed.id(),
                                                "确认暂停并升级对象",
                                                List.of(),
                                                List.of(app)),
                                        10001)
                                .state())
                .isEqualTo("SUCCEEDED");
        com.richuang.os.nocode.runtime.service.application.ApplicationRuntimeService portal =
                servicesContext.getBean(
                        com.richuang.os.nocode.runtime.service.application.ApplicationRuntimeService
                                .class);
        ApplicationCenter.Detail before = applications.get(app);
        assertThat(before.application().status()).isEqualTo("DISABLED");
        assertThat(before.issues())
                .anyMatch(
                        i ->
                                i.objectId().equals(d.objectId())
                                        && i.versionNo() == 1
                                        && i.latestVersionNo() == 2
                                        && !i.messages().isEmpty());
        ApplicationCenter.Detail kept =
                applications.save(
                        new ApplicationCenter.Save(
                                app,
                                before.application().revision(),
                                before.application().code(),
                                before.application().name(),
                                before.application().description(),
                                before.application().icon(),
                                before.draft()),
                        10001);
        assertThatThrownBy(
                        () ->
                                applications.publishAndEnable(
                                        new ApplicationCenter.Revision(
                                                app, kept.application().revision(), "未修复发布"),
                                        10001))
                .hasMessageContaining("不兼容当前结构");
        assertThat(applications.get(app).application().status()).isEqualTo("DISABLED");
        assertThatThrownBy(() -> portal.application(app, 10001)).hasMessageContaining("停用");
        DataObjectApi.PublishedObject latest =
                servicesContext.getBean(DataObjectApi.class).getVersion(d.objectId(), null);
        ApplicationCenter.Detail synced =
                applications.save(
                        new ApplicationCenter.Save(
                                app,
                                kept.application().revision(),
                                before.application().code(),
                                before.application().name(),
                                before.application().description(),
                                before.application().icon(),
                                new ApplicationCenter.Definition(
                                        List.of(
                                                new ApplicationCenter.ObjectReference(
                                                        latest.objectId(),
                                                        latest.versionNo(),
                                                        latest.checksum())),
                                        before.draft().resources())),
                        10001);
        applications.publishAndEnable(
                new ApplicationCenter.Revision(app, synced.application().revision(), "同步对象版本"),
                10001);
        assertThat(applications.get(app).application().status()).isEqualTo("ACTIVE");
        assertThat(applications.get(app).issues()).isEmpty();
        assertThat(portal.application(app, 10001).warnings()).isEmpty();
    }

    @Test
    void publishedViewAppliesItsOwnFilterAndProjection() {
        DataCenter.Definition d = object(false);
        String title = field(d, "name");
        ApplicationCenter.Resource view =
                new ApplicationCenter.Resource(
                        "view1",
                        "VIEW",
                        "filtered_view",
                        "固定筛选",
                        Map.of(
                                "objectId",
                                d.objectId(),
                                "fieldIds",
                                List.of(title),
                                "equal",
                                Map.of(title, "可见订单"),
                                "descending",
                                false,
                                "pageSize",
                                20));
        String app = app(d, List.of(view));
        runtime.save(create(app, d, "可见订单"), 10001);
        runtime.save(create(app, d, "其他订单"), 10001);
        com.richuang.os.framework.common.pojo.PageResult<ApplicationRecords.Row> result =
                runtime.page(
                        new Query(app, d.objectId(), 1, 20, null, null, null, false, "view1"),
                        10001);
        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getList().getFirst().values()).containsOnlyKeys(title);
        assertThat(
                        runtime.page(
                                        new Query(
                                                app,
                                                d.objectId(),
                                                1,
                                                20,
                                                null,
                                                Map.of(title, "其他订单"),
                                                null,
                                                false,
                                                "view1"),
                                        10001)
                                .getTotal())
                .isZero();
    }

    private com.richuang.os.nocode.application.service.authorization.ApplicationAuthorizationService
            authorization() {
        return servicesContext.getBean(
                com.richuang.os.nocode.application.service.authorization
                        .ApplicationAuthorizationService.class);
    }

    private com.richuang.os.nocode.application.service.sharing.ObjectSharingService sharing() {
        return servicesContext.getBean(
                com.richuang.os.nocode.application.service.sharing.ObjectSharingService.class);
    }

    private void share(
            String app, DataCenter.Definition d, ApplicationAuthorization.ObjectGrant grant) {
        int revision =
                sharing().forApplication(app).stream()
                        .filter(g -> g.objectId().equals(d.objectId()))
                        .mapToInt(ObjectSharing.Grant::revision)
                        .findFirst()
                        .orElse(0);
        sharing().save(new ObjectSharing.Save(d.objectId(), app, revision, grant, "共享授权回归"), 10001);
    }

    @Test
    void referencingObjectAutoGrantsDefaultCeilingButBuilderCannotSelfShare() {
        DataCenter.Definition d = object(false);
        DataObjectApi.PublishedObject v =
                servicesContext.getBean(DataObjectApi.class).getVersion(d.objectId(), null);
        String app =
                applications
                        .save(
                                new ApplicationCenter.Save(
                                        null,
                                        null,
                                        fixture.prefix + "untrusted",
                                        "引用即授权应用",
                                        null,
                                        null,
                                        new ApplicationCenter.Definition(
                                                List.of(
                                                        new ApplicationCenter.ObjectReference(
                                                                v.objectId(),
                                                                v.versionNo(),
                                                                v.checksum())),
                                                List.of())),
                                20002)
                        .application()
                        .id();
        // 引用即授权：保存即写入默认上限（字段、明细、关系都是「全部」），不含发起流程；计算取数不再由人勾选，默认上限里是空的。
        var ceiling = NocodeIntegrationSupport.resolvedPermission(d.objectId(), app);
        assertThat(ceiling).isNotNull();
        assertThat(ceiling.actions())
                .containsExactlyInAnyOrder(
                        "READ", "CREATE", "UPDATE", "DELETE", "IMPORT", "EXPORT");
        assertThat(ceiling.scope()).isEqualTo("ALL");
        assertThat(ceiling.readFields()).contains(field(d, "name"));
        assertThat(ceiling.writeFields()).contains(field(d, "name"));
        assertThat(ceiling.computeFields()).isEmpty();
        assertThat(sharing().storedPermission(d.objectId(), app).readFields())
                .as("库里存的是「全部」，不是当前版本的字段清单")
                .containsExactly("*");
        // 搭建者仍不能经共享管理接口自行改上限；默认授权只走引用内部通道。
        assertThatThrownBy(
                        () ->
                                sharing()
                                        .save(
                                                new ObjectSharing.Save(
                                                        d.objectId(),
                                                        app,
                                                        0,
                                                        grant(
                                                                d,
                                                                "ALL",
                                                                Set.of("READ", "UPDATE"),
                                                                Set.of(field(d, "name"))),
                                                        "自行授权"),
                                                20002))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        // 默认上限已满足发布前置，搭建者无需数据管理员单独同意即可发布。
        applications.publish(new ApplicationCenter.Revision(app, 0, "引用即授权后发布"), 20002);
        assertThat(runtime.model(app, d.objectId(), 20002).permissions().actions())
                .contains("READ", "CREATE", "UPDATE", "DELETE");
        // 数据管理员仍可收紧上限，运行时按收紧后的上限重新计算创建者能力。
        share(app, d, grant(d, "ALL", Set.of("READ"), Set.of(field(d, "name"))));
        assertThat(runtime.model(app, d.objectId(), 20002).permissions().actions())
                .containsExactly("READ");
        assertThatThrownBy(() -> runtime.save(create(app, d, "创建者越权"), 20002))
                .hasMessageContaining("权限");
    }

    @Test
    void readonlySharedApplicationBlocksOwnerAllWritesAndDoesNotBorrowAnotherApplicationsGrant() {
        DataCenter.Definition d = object(false);
        String writer = app(d), reader = app(d), title = field(d, "name");
        ApplicationRecords.Row record = runtime.save(create(writer, d, "同一条共享数据"), 10001).record();
        share(reader, d, grant(d, "ALL", Set.of("READ"), Set.of(title)));
        assertThat(
                        runtime.get(reader, d.objectId(), record.id(), 10001)
                                .record()
                                .values()
                                .get(title))
                .isEqualTo("同一条共享数据");
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                reader,
                                                d.objectId(),
                                                record.id(),
                                                record.revision(),
                                                Map.of(title, "越权"),
                                                null),
                                        10001))
                .hasMessageContaining("权限");
        assertThatThrownBy(() -> runtime.save(create(reader, d, "越权新增"), 10001))
                .hasMessageContaining("权限");
        assertThatThrownBy(
                        () ->
                                runtime.delete(
                                        new Delete(
                                                reader,
                                                d.objectId(),
                                                record.id(),
                                                record.revision()),
                                        10001))
                .hasMessageContaining("权限");
        assertThatThrownBy(
                        () ->
                                runtime.importRecords(
                                        reader,
                                        d.objectId(),
                                        List.of(Map.of(title, "越权导入")),
                                        10001))
                .hasMessageContaining("权限");
        assertThatThrownBy(
                        () ->
                                runtime.export(
                                        new Query(
                                                reader,
                                                d.objectId(),
                                                1,
                                                20,
                                                null,
                                                null,
                                                null,
                                                false),
                                        10001))
                .hasMessageContaining("权限");
        authorize(reader, member(grant(d, "ALL", Set.of("READ"), Set.of(title))));
        assertThatThrownBy(
                        () ->
                                authorize(
                                        reader,
                                        member(
                                                grant(
                                                        d,
                                                        "ALL",
                                                        Set.of("READ", "UPDATE"),
                                                        Set.of(title)))))
                .hasMessageContaining("超出");
        assertThatThrownBy(() -> runtime.get(writer, d.objectId(), record.id(), 20002))
                .hasMessageContaining("权限");
        // 显式获权的写应用仍可修改同一条数据，这是合法授权，并非绕过只读应用。
        runtime.save(
                new Save(
                        writer,
                        d.objectId(),
                        record.id(),
                        record.revision(),
                        Map.of(title, "合法修改"),
                        null),
                10001);
        assertThat(
                        runtime.get(reader, d.objectId(), record.id(), 20002)
                                .record()
                                .values()
                                .get(title))
                .isEqualTo("合法修改");
    }

    @Test
    void sharedCeilingRestrictsOwnerFieldsDetailsAndCurrentActorsOwnRows() {
        DataCenter.Definition d = object(true);
        String app = app(d), title = field(d, "name"), detail = d.details().getFirst().id();
        ApplicationRecords.Row ownerRow = runtime.save(create(app, d, "创建人记录"), 10001).record();
        authorize(app, member(grant(d, "ALL", Set.of("READ", "CREATE", "UPDATE"), Set.of(title))));
        ApplicationRecords.Row otherRow = runtime.save(create(app, d, "成员记录"), 20002).record();
        share(
                app,
                d,
                new ApplicationAuthorization.ObjectGrant(
                        d.objectId(),
                        Set.of("READ", "UPDATE"),
                        "OWN",
                        Set.of(title),
                        Set.of(),
                        Set.of(detail),
                        Set.of()));
        assertThat(
                        runtime.page(
                                        new Query(
                                                app, d.objectId(), 1, 20, null, null, null, false),
                                        10001)
                                .getTotal())
                .isEqualTo(1);
        assertThatThrownBy(() -> runtime.get(app, d.objectId(), otherRow.id(), 10001))
                .hasMessageContaining("权限");
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                d.objectId(),
                                                ownerRow.id(),
                                                ownerRow.revision(),
                                                Map.of(title, "字段越权"),
                                                null),
                                        10001))
                .hasMessageContaining("字段");
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                d.objectId(),
                                                ownerRow.id(),
                                                ownerRow.revision(),
                                                Map.of(),
                                                Map.of(
                                                        detail,
                                                        List.of(
                                                                new Row(
                                                                        null,
                                                                        null,
                                                                        Map.of(
                                                                                d.details()
                                                                                        .getFirst()
                                                                                        .fields()
                                                                                        .getFirst()
                                                                                        .id(),
                                                                                "1"))))),
                                        10001))
                .hasMessageContaining("明细");
        assertThat(
                        runtime.get(app, d.objectId(), otherRow.id(), 20002)
                                .record()
                                .permissions()
                                .writeFields())
                .isEmpty();
    }

    @Test
    void revokedSharingStopsOldReleaseAndRestorationAndKeepsAuditHistory() {
        DataCenter.Definition d = object(false);
        String app = app(d), title = field(d, "name");
        ApplicationRecords.Row row = runtime.save(create(app, d, "撤权前记录"), 10001).record();
        authorize(app, member(grant(d, "ALL", Set.of("READ", "UPDATE"), Set.of(title))));
        com.richuang.os.nocode.runtime.service.application.ApplicationRuntimeService service =
                servicesContext.getBean(
                        com.richuang.os.nocode.runtime.service.application.ApplicationRuntimeService
                                .class);
        share(app, d, null);
        assertThatThrownBy(() -> runtime.get(app, d.objectId(), row.id(), 10001))
                .hasMessageContaining("权限");
        assertThatThrownBy(() -> runtime.get(app, d.objectId(), row.id(), 20002))
                .hasMessageContaining("权限");
        assertThat(service.mine(10001)).noneMatch(a -> a.id().equals(app));
        assertThatThrownBy(() -> service.application(app, 10001)).hasMessageContaining("没有可访问对象");
        ApplicationCenter.Detail head = applications.get(app);
        assertThatThrownBy(
                        () ->
                                applications.restore(
                                        new ApplicationCenter.Restore(
                                                app, head.application().revision(), 1, "旧版本不能恢复授权"),
                                        10001))
                .hasMessageContaining("已被撤销");
        share(app, d, grant(d, "ALL", Set.of("READ"), Set.of(title)));
        assertThat(runtime.get(app, d.objectId(), row.id(), 20002).record().permissions().actions())
                .containsExactly("READ");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_object_application_grant_log"
                                        + " WHERE object_id=? AND application_id=?",
                                Integer.class,
                                Long.parseLong(d.objectId()),
                                Long.parseLong(app)))
                // 引用即授权默认上限、夹具显式授权、撤权、重新授权各留一条审计。
                .isEqualTo(4);
    }

    @Test
    void applicationManagementDoesNotPermitTakingOverAnotherBuildersApplication() {
        DataCenter.Definition d = object(false);
        String app = app(d);
        ApplicationCenter.Detail head = applications.get(app);
        assertThatThrownBy(() -> applications.requireDesigner(app, 20002))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(
                        () ->
                                applications.save(
                                        new ApplicationCenter.Save(
                                                app,
                                                head.application().revision(),
                                                head.application().code(),
                                                "越权管理",
                                                null,
                                                null,
                                                head.draft()),
                                        20002))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(
                        () ->
                                authorization()
                                        .save(
                                                new ApplicationAuthorization.Save(
                                                        app, 0, List.of()),
                                                20002))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(applications.page(1, 100, fixture.prefix, 20002L).getList()).isEmpty();
    }

    @Test
    void cascadeCannotDeleteSourceWhoseApplicationSharingIsReadonly() {
        DataCenter.Definition target = object(false);
        DataCenter.Definition source = relatedObject(target, "MASTER_DETAIL", "CASCADE");
        String app = groupedApp(target, source),
                reference = source.relations().getFirst().fieldId();
        ApplicationRecords.Row targetRow =
                runtime.save(create(app, target, "级联目标"), 10001).record();
        ApplicationRecords.Row sourceRow =
                runtime.save(
                                new Save(
                                        app,
                                        source.objectId(),
                                        null,
                                        null,
                                        Map.of(
                                                field(source, "name"),
                                                "受保护源记录",
                                                reference,
                                                targetRow.id()),
                                        null),
                                10001)
                        .record();
        share(
                app,
                source,
                grant(source, "ALL", Set.of("READ"), Set.of(field(source, "name"), reference)));
        assertThatThrownBy(
                        () ->
                                runtime.delete(
                                        new Delete(
                                                app,
                                                target.objectId(),
                                                targetRow.id(),
                                                targetRow.revision()),
                                        10001))
                .hasMessageContaining("权限");
        assertThat(runtime.get(app, target.objectId(), targetRow.id(), 10001).record().id())
                .isEqualTo(targetRow.id());
        assertThat(runtime.get(app, source.objectId(), sourceRow.id(), 10001).record().id())
                .isEqualTo(sourceRow.id());
    }

    @Test
    void sharedRelationCeilingAlsoRestrictsCreatorAndDoesNotRemoveExistingLinks() {
        DataCenter.Definition target = object(false);
        DataCenter.Definition source = relatedObject(target, "MANY_TO_MANY", "RESTRICT");
        String app = groupedApp(target, source), relation = source.relations().getFirst().id();
        ApplicationRecords.Row targetRow =
                runtime.save(create(app, target, "关系目标"), 10001).record();
        ApplicationRecords.Row sourceRow =
                runtime.save(
                                new Save(
                                        app,
                                        source.objectId(),
                                        null,
                                        null,
                                        Map.of(field(source, "name"), "关系源"),
                                        null,
                                        Map.of(relation, List.of(targetRow.id()))),
                                10001)
                        .record();
        share(
                app,
                source,
                new ApplicationAuthorization.ObjectGrant(
                        source.objectId(),
                        Set.of("READ", "UPDATE"),
                        "ALL",
                        Set.of(field(source, "name")),
                        Set.of(field(source, "name")),
                        Set.of(),
                        Set.of(),
                        Set.of(relation),
                        Set.of()));
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                source.objectId(),
                                                sourceRow.id(),
                                                sourceRow.revision(),
                                                Map.of(),
                                                null,
                                                Map.of(relation, List.of())),
                                        10001))
                .hasMessageContaining("关系");
        assertThat(
                        runtime.get(app, source.objectId(), sourceRow.id(), 10001)
                                .relations()
                                .get(relation))
                .containsExactly(targetRow.id());
    }

    private ApplicationAuthorization.ObjectGrant grant(
            DataCenter.Definition d, String scope, Set<String> actions, Set<String> fields) {
        return new ApplicationAuthorization.ObjectGrant(
                d.objectId(), actions, scope, fields, fields, Set.of(), Set.of());
    }

    private void authorize(String app, ApplicationAuthorization.Member... members) {
        authorization()
                .save(
                        new ApplicationAuthorization.Save(
                                app, authorization().get(app).revision(), List.of(members)),
                        10001);
    }

    private ApplicationAuthorization.Member member(ApplicationAuthorization.ObjectGrant grant) {
        return new ApplicationAuthorization.Member("USER", "20002", List.of(grant));
    }

    @Test
    void ownedScopeAppliesToCountsGetsWritesAndImmediateRevocation() {
        DataCenter.Definition d = object(false);
        String app = app(d);
        String title = field(d, "name");
        ApplicationRecords.Aggregate foreign = runtime.save(create(app, d, "创建人的订单"), 10001);
        authorize(
                app,
                member(
                        grant(
                                d,
                                "OWN",
                                Set.of("READ", "CREATE", "UPDATE", "DELETE"),
                                Set.of(title))));
        ApplicationRecords.Aggregate own = runtime.save(create(app, d, "成员的订单"), 20002);
        com.richuang.os.framework.common.pojo.PageResult<ApplicationRecords.Row> page =
                runtime.page(new Query(app, d.objectId(), 1, 20, null, null, null, false), 20002);
        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getList().getFirst().id()).isEqualTo(own.record().id());
        assertThatThrownBy(() -> runtime.get(app, d.objectId(), foreign.record().id(), 20002))
                .hasMessageContaining("权限");
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                d.objectId(),
                                                foreign.record().id(),
                                                foreign.record().revision(),
                                                Map.of(title, "越权"),
                                                null),
                                        20002))
                .hasMessageContaining("权限");
        assertThatThrownBy(
                        () ->
                                runtime.delete(
                                        new Delete(
                                                app,
                                                d.objectId(),
                                                foreign.record().id(),
                                                foreign.record().revision()),
                                        20002))
                .hasMessageContaining("权限");
        ApplicationRecords.Aggregate updated =
                runtime.save(
                        new Save(
                                app,
                                d.objectId(),
                                own.record().id(),
                                own.record().revision(),
                                Map.of(title, "正常修改"),
                                null),
                        20002);
        runtime.delete(
                new Delete(app, d.objectId(), updated.record().id(), updated.record().revision()),
                20002);
        assertThat(
                        runtime.page(
                                        new Query(
                                                app, d.objectId(), 1, 20, null, null, null, false),
                                        20002)
                                .getTotal())
                .isZero();
        authorize(app);
        assertThatThrownBy(() -> runtime.model(app, d.objectId(), 20002))
                .hasMessageContaining("权限");
    }

    @Test
    void hiddenFieldsAndDetailsCannotLeakThroughQueryModelOrWrite() {
        DataCenter.Definition d = object(true);
        String app = app(d);
        String title = field(d, "name");
        ApplicationRecords.Aggregate record = runtime.save(create(app, d, "隐藏的订单名称"), 10001);
        authorize(app, member(grant(d, "ALL", Set.of("READ", "UPDATE"), Set.of())));
        assertThat(runtime.model(app, d.objectId(), 20002).object().fields()).isEmpty();
        assertThat(runtime.model(app, d.objectId(), 20002).object().details()).isEmpty();
        ApplicationRecords.Aggregate result =
                runtime.get(app, d.objectId(), record.record().id(), 20002);
        assertThat(result.record().values()).isEmpty();
        assertThat(result.details()).isEmpty();
        assertThat(
                        runtime.page(
                                        new Query(
                                                app, d.objectId(), 1, 20, null, null, null, false),
                                        20002)
                                .getList()
                                .getFirst()
                                .values())
                .isEmpty();
        assertThatThrownBy(
                        () ->
                                runtime.page(
                                        new Query(
                                                app,
                                                d.objectId(),
                                                1,
                                                20,
                                                null,
                                                Map.of(title, "隐藏"),
                                                null,
                                                false),
                                        20002))
                .hasMessageContaining("无权");
        assertThatThrownBy(
                        () ->
                                runtime.page(
                                        new Query(
                                                app, d.objectId(), 1, 20, null, null, title, false),
                                        20002))
                .hasMessageContaining("无权");
        assertThatThrownBy(
                        () ->
                                runtime.page(
                                        new Query(
                                                app, d.objectId(), 1, 20, "隐藏", null, null, false),
                                        20002))
                .hasMessageContaining("搜索");
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                d.objectId(),
                                                result.record().id(),
                                                result.record().revision(),
                                                Map.of(title, "覆盖"),
                                                null),
                                        20002))
                .hasMessageContaining("无权");
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                d.objectId(),
                                                result.record().id(),
                                                result.record().revision(),
                                                Map.of(),
                                                Map.of(d.details().getFirst().id(), List.of())),
                                        20002))
                .hasMessageContaining("明细");
    }

    @Test
    void roleReadAllCannotBroadenUserWriteOwnScope() {
        DataCenter.Definition d = object(false);
        String app = app(d);
        String title = field(d, "name");
        ApplicationRecords.Aggregate record = runtime.save(create(app, d, "他人的订单"), 10001);
        com.richuang.os.module.system.api.permission.dto.RoleRespDTO role =
                new com.richuang.os.module.system.api.permission.dto.RoleRespDTO();
        role.setId(30003L);
        role.setCode("runtime_reader");
        role.setStatus(0);
        com.richuang.os.module.system.api.permission.RoleApi roleApi =
                servicesContext.getBean(com.richuang.os.module.system.api.permission.RoleApi.class);
        com.richuang.os.framework.common.biz.system.permission.PermissionCommonApi permissionApi =
                servicesContext.getBean(
                        com.richuang.os.framework.common.biz.system.permission.PermissionCommonApi
                                .class);
        org.mockito.Mockito.when(roleApi.getRole(30003L)).thenReturn(role);
        org.mockito.Mockito.when(permissionApi.hasAnyRoles(20002L, "runtime_reader"))
                .thenReturn(true);
        authorize(
                app,
                member(grant(d, "OWN", Set.of("READ", "UPDATE"), Set.of(title))),
                new ApplicationAuthorization.Member(
                        "ROLE", "30003", List.of(grant(d, "ALL", Set.of("READ"), Set.of(title)))));
        assertThat(runtime.get(app, d.objectId(), record.record().id(), 20002).record().values())
                .containsEntry(title, "他人的订单");
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                d.objectId(),
                                                record.record().id(),
                                                record.record().revision(),
                                                Map.of(title, "越权"),
                                                null),
                                        20002))
                .hasMessageContaining("权限");
        role.setStatus(1);
        assertThat(
                        runtime.page(
                                        new Query(
                                                app, d.objectId(), 1, 20, null, null, null, false),
                                        20002)
                                .getTotal())
                .isZero();
        org.mockito.Mockito.reset(roleApi, permissionApi);
    }

    @Test
    void authorizationRejectsStaleRevisionAndForeignFieldWithoutChangingPolicy() {
        DataCenter.Definition d = object(false);
        String app = app(d);
        authorize(app, member(grant(d, "ALL", Set.of("READ"), Set.of(field(d, "name")))));
        ApplicationAuthorization.Policy before = authorization().get(app);
        assertThatThrownBy(
                        () ->
                                authorization()
                                        .save(
                                                new ApplicationAuthorization.Save(
                                                        app, 0, List.of()),
                                                10001))
                .hasMessageContaining("已被修改");
        assertThat(authorization().get(app)).isEqualTo(before);
        // 清单里不存在（或已停用）的字段 ID 不再报错：保存时直接去掉，不落库。
        authorize(app, member(grant(d, "ALL", Set.of("READ"), Set.of("987654321"))));
        var after = authorization().get(app);
        assertThat(after.revision()).isEqualTo(before.revision() + 1);
        assertThat(after.members().getFirst().objects().getFirst().readFields()).isEmpty();
    }

    @Test
    void memberPortalTracksPublishedReferencesAndRevocation() {
        DataCenter.Definition d = object(false);
        String app = app(d);
        com.richuang.os.nocode.runtime.service.application.ApplicationRuntimeService portal =
                servicesContext.getBean(
                        com.richuang.os.nocode.runtime.service.application.ApplicationRuntimeService
                                .class);
        assertThat(portal.mine(20002)).noneMatch(a -> a.id().equals(app));
        authorize(app, member(grant(d, "OWN", Set.of("READ"), Set.of(field(d, "name")))));
        assertThat(portal.mine(20002)).anyMatch(a -> a.id().equals(app));
        assertThat(portal.application(app, 20002).definition().objects())
                .extracting(ApplicationCenter.ObjectReference::objectId)
                .containsExactly(d.objectId());
        authorize(app);
        assertThat(portal.mine(20002)).noneMatch(a -> a.id().equals(app));
        assertThatThrownBy(() -> portal.application(app, 20002)).hasMessageContaining("权限");
    }

    @Test
    void aggregateCreateUpdateDeletePreserveIdsAndAudit() {
        DataCenter.Definition d = object(true);
        String app = app(d);
        DataCenter.Detail detail = d.details().getFirst();
        String qty = detail.fields().getFirst().id();
        ApplicationRecords.Aggregate first =
                runtime.save(
                        new Save(
                                app,
                                d.objectId(),
                                null,
                                null,
                                Map.of(field(d, "name"), "订单 A"),
                                Map.of(
                                        detail.id(),
                                        List.of(new Row(null, null, Map.of(qty, "2"))))),
                        10001);
        ApplicationRecords.Row line = first.details().get(detail.id()).getFirst();
        assertThat(line.values().get(qty)).isEqualTo("2");
        ApplicationRecords.Aggregate second =
                runtime.save(
                        new Save(
                                app,
                                d.objectId(),
                                first.record().id(),
                                first.record().revision(),
                                Map.of(field(d, "name"), "订单 B"),
                                Map.of(
                                        detail.id(),
                                        List.of(
                                                new Row(
                                                        line.id(),
                                                        line.revision(),
                                                        Map.of(qty, "4"))))),
                        10001);
        assertThat(second.details().get(detail.id()).getFirst().id()).isEqualTo(line.id());
        assertThat(second.record().revision()).isNotEqualTo(first.record().revision());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT creator FROM public.\"" + d.tableName() + "\" WHERE id=?",
                                String.class,
                                Long.valueOf(first.record().id())))
                .isEqualTo("10001");
        runtime.delete(
                new Delete(app, d.objectId(), second.record().id(), second.record().revision()),
                10001);
        assertThat(
                        runtime.page(
                                        new Query(
                                                app, d.objectId(), 1, 20, null, null, null, false),
                                        10001)
                                .getTotal())
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT deleted FROM public.\""
                                        + detail.tableName()
                                        + "\" WHERE id=?",
                                Integer.class,
                                Long.valueOf(line.id())))
                .isEqualTo(1);
    }

    @Test
    void invalidDetailRollsBackMainInsertAndUpdate() {
        DataCenter.Definition d = object(true);
        String app = app(d);
        DataCenter.Detail detail = d.details().getFirst();
        String qty = detail.fields().getFirst().id();
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                d.objectId(),
                                                null,
                                                null,
                                                Map.of(field(d, "name"), "应回滚"),
                                                Map.of(
                                                        detail.id(),
                                                        List.of(
                                                                new Row(
                                                                        null,
                                                                        null,
                                                                        Map.of(
                                                                                qty,
                                                                                "not-number"))))),
                                        10001))
                .hasMessageContaining("格式");
        assertThat(
                        runtime.page(
                                        new Query(
                                                app, d.objectId(), 1, 20, null, null, null, false),
                                        10001)
                                .getTotal())
                .isZero();
        ApplicationRecords.Aggregate first = runtime.save(create(app, d, "原名称"), 10001);
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                d.objectId(),
                                                first.record().id(),
                                                first.record().revision(),
                                                Map.of(field(d, "name"), "未保存名称"),
                                                Map.of(
                                                        detail.id(),
                                                        List.of(new Row(null, null, Map.of())))),
                                        10001))
                .hasMessageContaining("必填");
        assertThat(runtime.get(app, d.objectId(), first.record().id(), 10001).record())
                .isEqualTo(first.record());
    }

    @Test
    void staleRecordAndForeignDetailAreRejected() {
        DataCenter.Definition d = object(true);
        String app = app(d);
        DataCenter.Detail detail = d.details().getFirst();
        String qty = detail.fields().getFirst().id();
        ApplicationRecords.Aggregate first =
                runtime.save(
                        new Save(
                                app,
                                d.objectId(),
                                null,
                                null,
                                Map.of(field(d, "name"), "订单1"),
                                Map.of(
                                        detail.id(),
                                        List.of(new Row(null, null, Map.of(qty, "1"))))),
                        10001);
        ApplicationRecords.Aggregate other = runtime.save(create(app, d, "订单2"), 10001);
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                d.objectId(),
                                                other.record().id(),
                                                other.record().revision(),
                                                Map.of(),
                                                Map.of(
                                                        detail.id(),
                                                        first.details().get(detail.id()))),
                                        10001))
                .hasMessageContaining("不属于");
        ApplicationRecords.Aggregate updated =
                runtime.save(
                        new Save(
                                app,
                                d.objectId(),
                                first.record().id(),
                                first.record().revision(),
                                Map.of(field(d, "name"), "新名称"),
                                null),
                        10001);
        assertThatThrownBy(
                        () ->
                                runtime.delete(
                                        new Delete(
                                                app,
                                                d.objectId(),
                                                first.record().id(),
                                                first.record().revision()),
                                        10001))
                .hasMessageContaining("刷新");
        assertThat(runtime.get(app, d.objectId(), updated.record().id(), 10001).record())
                .isEqualTo(updated.record());
    }

    @Test
    void queryValuesAreBoundAndUnknownFieldsOrActorsAreRejected() {
        DataCenter.Definition d = object(false);
        String app = app(d);
        String literal = "%' OR true --";
        runtime.save(create(app, d, literal), 10001);
        runtime.save(create(app, d, "正常记录"), 10001);
        assertThat(
                        runtime.page(
                                        new Query(
                                                app,
                                                d.objectId(),
                                                1,
                                                20,
                                                literal,
                                                null,
                                                null,
                                                false),
                                        10001)
                                .getTotal())
                .isEqualTo(1);
        assertThat(
                        runtime.page(
                                        new Query(
                                                app,
                                                d.objectId(),
                                                1,
                                                20,
                                                null,
                                                Map.of(field(d, "name"), literal),
                                                null,
                                                false),
                                        10001)
                                .getTotal())
                .isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                d.objectId(),
                                                null,
                                                null,
                                                Map.of("creator", "2"),
                                                null),
                                        10001))
                .hasMessageContaining("未定义");
        assertThatThrownBy(() -> runtime.model(app, d.objectId(), 20002))
                .hasMessageContaining("权限");
        assertThatThrownBy(
                        () ->
                                runtime.page(
                                        new Query(
                                                app,
                                                d.objectId(),
                                                1,
                                                20,
                                                null,
                                                null,
                                                "name;DROP TABLE",
                                                false),
                                        10001))
                .hasMessageContaining("排序");
    }

    private DataCenter.Definition adopted(String key, boolean base) {
        String name = "biz_" + fixture.prefix + "legacy" + serial++;
        ownedTables.add(name);
        jdbc.execute(
                "CREATE TABLE public.\""
                        + name
                        + "\" ("
                        + key
                        + ",name varchar(100)"
                        + (base
                                ? ",creator varchar(64) DEFAULT '',create_time timestamp NOT NULL"
                                        + " DEFAULT CURRENT_TIMESTAMP,updater varchar(64) DEFAULT"
                                        + " '',update_time timestamp NOT NULL DEFAULT"
                                        + " CURRENT_TIMESTAMP,deleted smallint NOT NULL DEFAULT 0"
                                : "")
                        + ")");
        com.richuang.os.nocode.metadata.api.DataTables.Preflight check =
                tables.preflight("public", name);
        DataCenter.Design draft =
                tables.adopt(
                        new DataCenter.Adoption(
                                "public",
                                name,
                                fixture.prefix + "legacy" + serial++,
                                "旧表",
                                "name",
                                check.fingerprint()),
                        10001);
        return publish(draft);
    }

    @Test
    void adoptedManualTextKeyIsRequiredAndPreserved() {
        DataCenter.Definition d = adopted("business_key varchar(40) PRIMARY KEY", true);
        String app = app(d);
        assertThat(runtime.model(app, d.objectId(), 10001).generatedKey()).isFalse();
        assertThatThrownBy(() -> runtime.save(create(app, d, "旧表"), 10001))
                .hasMessageContaining("主键");
        ApplicationRecords.Aggregate record =
                runtime.save(
                        new Save(
                                app,
                                d.objectId(),
                                null,
                                null,
                                Map.of(
                                        field(d, "business_key"),
                                        "LEGACY-1001",
                                        field(d, "name"),
                                        "纳管写入"),
                                null),
                        10001);
        assertThat(record.record().id()).isEqualTo("LEGACY-1001");
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                d.objectId(),
                                                record.record().id(),
                                                record.record().revision(),
                                                Map.of(field(d, "business_key"), "changed"),
                                                null),
                                        10001))
                .hasMessageContaining("系统维护");
    }

    @Test
    void adoptedSystemColumnsAreReadonlyWhileTypedBusinessFieldsRemainWritable() {
        DataCenter.Definition d =
                adopted(
                        "id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,enabled"
                                + " boolean,zoned_at timestamp(6) with time zone",
                        true);
        ApplicationCenter.Resource form =
                new ApplicationCenter.Resource(
                        "typed_form",
                        "FORM",
                        "typed_form",
                        "旧表业务表单",
                        Map.of(
                                "objectId",
                                d.objectId(),
                                "nodes",
                                List.of(
                                        Map.of(
                                                "id",
                                                "name_node",
                                                "type",
                                                "FIELD",
                                                "fieldId",
                                                field(d, "name"))),
                                "detailIds",
                                List.of()));
        String app = app(d, List.of(form));
        ApplicationRecords.Model model = runtime.model(app, d.objectId(), 10001);
        for (String column : com.richuang.os.framework.mybatis.core.metadata.BaseDOColumns.NAMES) {
            assertThat(d.fieldOptions().get(field(d, column)).generated()).isTrue();
            assertThat(model.object().fieldOptions().get(field(d, column)).generated()).isTrue();
        }
        // 模拟旧发布快照中缺失的标记；运行装饰按物理列修复，不能改写历史快照。
        com.fasterxml.jackson.databind.JsonNode tree = mapper.valueToTree(d);
        for (String column : com.richuang.os.framework.mybatis.core.metadata.BaseDOColumns.NAMES)
            ((com.fasterxml.jackson.databind.node.ObjectNode)
                            tree.path("fieldOptions").path(field(d, column)))
                    .put("generated", false);
        DataCenter.Definition legacy = mapper.convertValue(tree, DataCenter.Definition.class);
        DataCenter.Definition normalized =
                servicesContext
                        .getBean(
                                com.richuang.os.nocode.runtime.service.application
                                        .ApplicationBusinessRules.class)
                        .decorate(
                                app,
                                legacy,
                                servicesContext
                                        .getBean(
                                                com.richuang.os.nocode.runtime.service.record
                                                        .RuntimeSchema.class)
                                        .main(d));
        for (String column : com.richuang.os.framework.mybatis.core.metadata.BaseDOColumns.NAMES)
            assertThat(normalized.fieldOptions().get(field(d, column)).generated()).isTrue();

        String title = field(d, "name"),
                enabled = field(d, "enabled"),
                zoned = field(d, "zoned_at");
        ApplicationRecords.Aggregate first =
                runtime.save(
                        new Save(
                                app,
                                d.objectId(),
                                null,
                                null,
                                Map.of(
                                        title,
                                        "旧表可录入",
                                        enabled,
                                        false,
                                        zoned,
                                        "2026-09-06T12:30:00+08:00"),
                                null),
                        10001);
        assertThat(
                        java.time.OffsetDateTime.parse(
                                        first.record().values().get(zoned).toString())
                                .toInstant())
                .isEqualTo(java.time.Instant.parse("2026-09-06T04:30:00Z"));
        assertThat(first.record().values()).containsEntry(enabled, false);
        assertThat(
                        runtime.page(
                                        new Query(
                                                app,
                                                d.objectId(),
                                                1,
                                                20,
                                                null,
                                                Map.of(enabled, false),
                                                null,
                                                false),
                                        10001)
                                .getTotal())
                .isEqualTo(1);
        assertThat(
                        runtime.page(
                                        new Query(
                                                app,
                                                d.objectId(),
                                                1,
                                                20,
                                                null,
                                                Map.of(enabled, true),
                                                null,
                                                false),
                                        10001)
                                .getTotal())
                .isZero();
        assertThatThrownBy(
                        () ->
                                runtime.page(
                                        new Query(
                                                app,
                                                d.objectId(),
                                                1,
                                                20,
                                                null,
                                                Map.of(enabled, "false"),
                                                null,
                                                false),
                                        10001))
                .hasMessageContaining("格式");
        for (String column : com.richuang.os.framework.mybatis.core.metadata.BaseDOColumns.NAMES)
            assertThatThrownBy(
                            () ->
                                    runtime.save(
                                            new Save(
                                                    app,
                                                    d.objectId(),
                                                    first.record().id(),
                                                    first.record().revision(),
                                                    Map.of(field(d, column), "伪造"),
                                                    null),
                                            10001))
                    .hasMessageContaining("系统维护");
        ApplicationRecords.Aggregate changed =
                runtime.save(
                        new Save(
                                app,
                                d.objectId(),
                                first.record().id(),
                                first.record().revision(),
                                Map.of(title, "旧表可修改", enabled, true),
                                null),
                        10001);
        assertThat(changed.record().values())
                .containsEntry(title, "旧表可修改")
                .containsEntry(enabled, true);
    }

    @Test
    void arrayChoiceFieldsRoundTripAndRejectScalar() {
        SaveObjectDraft request = fixture.createRequest("choices");
        ArrayList<FieldDefinition> fields = new ArrayList<>(request.fields());
        fields.add(
                new FieldDefinition(
                        "region", null, "region", "地区", "REGION", null, null, null, false, false,
                        1));
        fields.add(
                new FieldDefinition(
                        "cascade", null, "cascade", "级联", "CASCADE", null, null, null, false, false,
                        2));
        DataCenter.FieldOptions options =
                new DataCenter.FieldOptions(
                        null,
                        "NORMAL",
                        null,
                        null,
                        null,
                        null,
                        null,
                        "ACTIVE",
                        List.of(
                                new DataCenter.Option("one", "选项一", false),
                                new DataCenter.Option("two", "选项二", false)),
                        null,
                        null,
                        "NONE",
                        null,
                        false,
                        false);
        DataCenter.Definition d =
                publish(
                        designs.save(
                                new DataCenter.SaveDesign(
                                        new SaveObjectDraft(
                                                null,
                                                null,
                                                request.objectCode(),
                                                request.objectName(),
                                                null,
                                                request.tableName(),
                                                request.titleFieldKey(),
                                                fields,
                                                List.of()),
                                        DataCenter.Settings.defaults(),
                                        Map.of("region", options, "cascade", options),
                                        List.of(),
                                        List.of(),
                                        List.of()),
                                10001));
        String app = app(d), region = field(d, "region"), cascade = field(d, "cascade");
        ApplicationRecords.Aggregate row =
                runtime.save(
                        new Save(
                                app,
                                d.objectId(),
                                null,
                                null,
                                Map.of(
                                        field(d, "name"),
                                        "数组录入",
                                        region,
                                        List.of("one", "two"),
                                        cascade,
                                        List.of("one")),
                                null),
                        10001);
        assertThat(runtime.get(app, d.objectId(), row.record().id(), 10001).record().values())
                .containsEntry(region, List.of("one", "two"))
                .containsEntry(cascade, List.of("one"));
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                d.objectId(),
                                                row.record().id(),
                                                row.record().revision(),
                                                Map.of(region, "one"),
                                                null),
                                        10001))
                .hasMessageContaining("多值字段格式");
    }

    @Test
    void objectAutoNumberRulesGenerateMainDetailsAndShareAcrossVersions() throws Exception {
        SaveObjectDraft request = fixture.createRequest("auto_rules");
        ArrayList<FieldDefinition> fields = new ArrayList<>(request.fields());
        fields.add(
                new FieldDefinition(
                        "number",
                        null,
                        "auto_code",
                        "自动编号",
                        "AUTO_NUMBER",
                        null,
                        null,
                        null,
                        true,
                        true,
                        1));
        fields.add(
                new FieldDefinition(
                        "legacy",
                        null,
                        "legacy_code",
                        "原生编号",
                        "AUTO_NUMBER",
                        null,
                        null,
                        null,
                        false,
                        false,
                        2));
        AutoNumberOptions rule = new AutoNumberOptions("NO-", "", 4, 20L, "NONE");
        AutoNumberOptions lineRule = new AutoNumberOptions("L-", "yyyyMMdd", 2, 1L, "DAY");
        DataCenter.Detail line =
                new DataCenter.Detail(
                        null,
                        "items",
                        "明细",
                        "biz_" + fixture.prefix + "auto_items",
                        "ACTIVE",
                        List.of(
                                new FieldDefinition(
                                        "line",
                                        null,
                                        "line_code",
                                        "行号",
                                        "AUTO_NUMBER",
                                        null,
                                        null,
                                        null,
                                        true,
                                        true,
                                        0)),
                        Map.of("line", DataCenter.FieldOptions.defaults().withAutoNumber(lineRule)),
                        List.of());
        DataCenter.Design saved =
                designs.save(
                        new DataCenter.SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        request.objectCode(),
                                        request.objectName(),
                                        null,
                                        request.tableName(),
                                        request.titleFieldKey(),
                                        fields,
                                        List.of()),
                                DataCenter.Settings.defaults(),
                                Map.of(
                                        "number",
                                        DataCenter.FieldOptions.defaults().withAutoNumber(rule)),
                                List.of(),
                                List.of(),
                                List.of(line)),
                        10001);
        assertThat(saved.fieldOptions().values()).anyMatch(o -> rule.equals(o.autoNumber()));
        DataCenter.Definition d = publish(saved);
        String number = field(d, "auto_code"),
                legacy = field(d, "legacy_code"),
                title = field(d, "name");
        DataCenter.Detail detail = d.details().getFirst();
        String lineNumber = detail.fields().getFirst().id();
        String app1 = app(d), app2 = app(d);
        ApplicationRecords.Aggregate first =
                runtime.save(
                        new Save(
                                app1,
                                d.objectId(),
                                null,
                                null,
                                Map.of(title, "第一条"),
                                Map.of(detail.id(), List.of(new Row(null, null, Map.of())))),
                        10001);
        assertThat(first.record().values()).containsEntry(number, "NO-0020");
        assertThat(first.record().values().get(legacy).toString()).isEqualTo("1");
        assertThat(first.details().get(detail.id()).getFirst().values().get(lineNumber))
                .isEqualTo(
                        lineRule.format(
                                java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")), 1));
        ApplicationRecords.Aggregate updated =
                runtime.save(
                        new Save(
                                app1,
                                d.objectId(),
                                first.record().id(),
                                first.record().revision(),
                                Map.of(title, "修改"),
                                null),
                        10001);
        assertThat(updated.record().values()).containsEntry(number, "NO-0020");
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app1,
                                                d.objectId(),
                                                null,
                                                null,
                                                Map.of(title, "手填", number, "FORGED"),
                                                null),
                                        10001))
                .hasMessageContaining("字段");
        try (java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newFixedThreadPool(4)) {
            List<java.util.concurrent.Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String selected = i % 2 == 0 ? app1 : app2;
                results.add(
                        executor.submit(
                                () ->
                                        runtime.save(create(selected, d, "并发"), 10001)
                                                .record()
                                                .values()
                                                .get(number)
                                                .toString()));
            }
            Set<String> numbers = new HashSet<>();
            for (java.util.concurrent.Future<String> future : results)
                numbers.add(future.get(30, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(numbers).hasSize(8).contains("NO-0021", "NO-0028");
        }
        // 新增记录失败后重新保存仍使用同一流水；计数器与真实记录事务一起回滚。
        org.springframework.transaction.support.TransactionTemplate tx =
                new org.springframework.transaction.support.TransactionTemplate(manager);
        assertThatThrownBy(
                        () ->
                                tx.execute(
                                        status -> {
                                            runtime.save(create(app1, d, "回滚"), 10001);
                                            throw new IllegalStateException("强制回滚");
                                        }))
                .hasMessageContaining("强制回滚");
        assertThat(runtime.save(create(app2, d, "回滚后"), 10001).record().values())
                .containsEntry(number, "NO-0029");
        DataCenter.Design edited =
                designs.editPublished(
                        new DataCenter.Revision(
                                d.objectId(),
                                designs.get(d.objectId()).draft().lockVersion(),
                                "更新编号"),
                        10001);
        HashMap<String, DataCenter.FieldOptions> changed = new HashMap<>(edited.fieldOptions());
        changed.put(
                number,
                changed.get(number)
                        .withAutoNumber(new AutoNumberOptions("NEW-", "", 2, 1L, "NONE")));
        ArrayList<FieldDefinition> newerFields = new ArrayList<>(edited.draft().fields());
        newerFields.add(
                new FieldDefinition(
                        "added",
                        null,
                        "added_code",
                        "新编号",
                        "AUTO_NUMBER",
                        null,
                        null,
                        null,
                        false,
                        false,
                        3));
        changed.put(
                "added",
                DataCenter.FieldOptions.defaults()
                        .withAutoNumber(new AutoNumberOptions("A-", "", 2, 1L, "NONE")));
        DataCenter.Design unpublished =
                designs.save(
                        new DataCenter.SaveDesign(
                                fixture.edit(
                                        edited.draft(),
                                        newerFields,
                                        List.of(),
                                        edited.draft().titleFieldId()),
                                edited.settings(),
                                changed,
                                edited.relations(),
                                edited.indexes(),
                                edited.details()),
                        10001);
        assertThat(runtime.save(create(app1, d, "草稿不生效"), 10001).record().values())
                .containsEntry(number, "NO-0030");
        DataCenter.Definition newer = publish(unpublished);
        ApplicationRecords.Aggregate oldAppRow = runtime.save(create(app1, d, "旧应用新规则"), 10001);
        assertThat(oldAppRow.record().values()).containsEntry(number, "NEW-31");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT added_code FROM public.\""
                                        + newer.tableName()
                                        + "\" WHERE id = ?",
                                String.class,
                                Long.parseLong(oldAppRow.record().id())))
                .isEqualTo("A-01");
        assertThat(runtime.get(app2, d.objectId(), first.record().id(), 10001).record().values())
                .containsEntry(number, "NO-0020");
        DataCenter.Design nextDraft =
                designs.editPublished(
                        new DataCenter.Revision(
                                d.objectId(),
                                designs.get(d.objectId()).draft().lockVersion(),
                                "验证旧编号边界"),
                        10001);
        HashMap<String, DataCenter.FieldOptions> forbidden =
                new HashMap<>(nextDraft.fieldOptions());
        forbidden.put(legacy, forbidden.get(legacy).withAutoNumber(rule));
        assertThatThrownBy(
                        () ->
                                designs.save(
                                        new DataCenter.SaveDesign(
                                                fixture.edit(
                                                        nextDraft.draft(),
                                                        nextDraft.draft().fields(),
                                                        List.of(),
                                                        nextDraft.draft().titleFieldId()),
                                                nextDraft.settings(),
                                                forbidden,
                                                nextDraft.relations(),
                                                nextDraft.indexes(),
                                                nextDraft.details()),
                                        10001))
                .hasMessageContaining("请新增字段");
        HashMap<String, DataCenter.FieldOptions> colliding =
                new HashMap<>(nextDraft.fieldOptions());
        AutoNumberOptions beforeCycle = new AutoNumberOptions("D-", "yyyyMMdd", 2, 32L, "NONE");
        colliding.put(number, colliding.get(number).withAutoNumber(beforeCycle));
        publish(
                designs.save(
                        new DataCenter.SaveDesign(
                                fixture.edit(
                                        nextDraft.draft(),
                                        nextDraft.draft().fields(),
                                        List.of(),
                                        nextDraft.draft().titleFieldId()),
                                nextDraft.settings(),
                                colliding,
                                nextDraft.relations(),
                                nextDraft.indexes(),
                                nextDraft.details()),
                        10001));
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        assertThat(runtime.save(create(app1, d, "周期变更前"), 10001).record().values())
                .containsEntry(number, beforeCycle.format(today, 32));
        DataCenter.Design cycleDraft =
                designs.editPublished(
                        new DataCenter.Revision(
                                d.objectId(),
                                designs.get(d.objectId()).draft().lockVersion(),
                                "周期变更"),
                        10001);
        HashMap<String, DataCenter.FieldOptions> cycleOptions =
                new HashMap<>(cycleDraft.fieldOptions());
        AutoNumberOptions daily = new AutoNumberOptions("D-", "yyyyMMdd", 2, 32L, "DAY");
        cycleOptions.put(number, cycleOptions.get(number).withAutoNumber(daily));
        publish(
                designs.save(
                        new DataCenter.SaveDesign(
                                fixture.edit(
                                        cycleDraft.draft(),
                                        cycleDraft.draft().fields(),
                                        List.of(),
                                        cycleDraft.draft().titleFieldId()),
                                cycleDraft.settings(),
                                cycleOptions,
                                cycleDraft.relations(),
                                cycleDraft.indexes(),
                                cycleDraft.details()),
                        10001));
        assertThat(runtime.save(create(app2, d, "同日重置跳过历史"), 10001).record().values())
                .containsEntry(number, daily.format(today, 33));
    }

    @Test
    void businessNumbersShareCounterAcrossAppsAndRollBackFailedSaves() throws Exception {
        SaveObjectDraft request = fixture.createRequest("numbered");
        ArrayList<FieldDefinition> fields = new ArrayList<>(request.fields());
        fields.add(
                new FieldDefinition(
                        "number",
                        null,
                        "business_number",
                        "业务编号",
                        "TEXT",
                        80,
                        null,
                        null,
                        true,
                        true,
                        1));
        DataCenter.Definition d =
                publish(
                        designs.save(
                                new DataCenter.SaveDesign(
                                        new SaveObjectDraft(
                                                null,
                                                null,
                                                request.objectCode(),
                                                request.objectName(),
                                                null,
                                                request.tableName(),
                                                request.titleFieldKey(),
                                                fields,
                                                List.of()),
                                        DataCenter.Settings.defaults(),
                                        null,
                                        List.of(),
                                        List.of(),
                                        List.of()),
                                10001));
        String number = field(d, "business_number");
        Map<String, Object> config =
                Map.<String, Object>of(
                        "objectId",
                        d.objectId(),
                        "fieldId",
                        number,
                        "prefix",
                        "N-",
                        "period",
                        "NONE",
                        "width",
                        4);
        String app1 =
                app(
                        d,
                        List.of(
                                new ApplicationCenter.Resource(
                                        "number1", "NUMBER_RULE", "order_number", "编号", config)));
        String app2 =
                app(
                        d,
                        List.of(
                                new ApplicationCenter.Resource(
                                        "number2", "NUMBER_RULE", "other_number", "共享编号", config)));
        ApplicationRecords.Aggregate first = runtime.save(create(app1, d, "第一条"), 10001);
        assertThat(first.record().values()).containsEntry(number, "N-0001");
        assertThat(
                        runtime.model(app1, d.objectId(), 10001)
                                .object()
                                .fieldOptions()
                                .get(number)
                                .generated())
                .isTrue();
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app1,
                                                d.objectId(),
                                                null,
                                                null,
                                                Map.of(field(d, "name"), "回滚"),
                                                Map.of("not-a-detail", List.of())),
                                        10001))
                .hasMessageContaining("明细");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT next_value FROM public.nocode_business_counter WHERE"
                                        + " object_id=? AND field_id=?",
                                Long.class,
                                Long.parseLong(d.objectId()),
                                Long.parseLong(number)))
                .isEqualTo(1L);
        try (java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newFixedThreadPool(4)) {
            List<java.util.concurrent.Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                final String selected = i % 2 == 0 ? app1 : app2;
                futures.add(
                        executor.submit(
                                () ->
                                        runtime.save(create(selected, d, "并发"), 10001)
                                                .record()
                                                .values()
                                                .get(number)
                                                .toString()));
            }
            Set<String> unique = new HashSet<>();
            for (java.util.concurrent.Future<String> future : futures)
                unique.add(future.get(30, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(unique).hasSize(12).contains("N-0002", "N-0013");
        }
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app1,
                                                d.objectId(),
                                                first.record().id(),
                                                first.record().revision(),
                                                Map.of(number, "FORGED"),
                                                null),
                                        10001))
                .hasMessageContaining("编号");

        // 无编号写权的成员、以及授权被收紧后的应用创建者，均不能借已发布配置间接写入。
        ApplicationAuthorization.ObjectGrant restricted =
                grant(d, "ALL", Set.of("READ", "CREATE"), Set.of(field(d, "name")));
        authorize(app1, member(restricted));
        assertThatThrownBy(() -> runtime.save(create(app1, d, "成员越权编号"), 20002))
                .hasMessageContaining("编号目标字段");
        share(app1, d, restricted);
        assertThatThrownBy(() -> runtime.save(create(app1, d, "创建者越权编号"), 10001))
                .hasMessageContaining("编号目标字段");
        ApplicationCenter.Detail beforeRepublish = applications.get(app1);
        assertThatThrownBy(
                        () ->
                                applications.publish(
                                        new ApplicationCenter.Revision(
                                                app1,
                                                beforeRepublish.application().revision(),
                                                "不得发布越权编号"),
                                        10001))
                .hasMessageContaining("业务编号")
                .hasMessageContaining("权限");
        assertThat(runtime.save(create(app2, d, "正常授权仍可编号"), 10001).record().values())
                .containsEntry(number, "N-0014");
    }

    @Test
    void publishedActionUsesServerValuesAndRespectsRecordPermissions() {
        DataCenter.Definition d = object(false);
        String title = field(d, "name");
        ApplicationCenter.Resource action =
                new ApplicationCenter.Resource(
                        "finish",
                        "ACTION",
                        "finish_order",
                        "完成订单",
                        Map.of(
                                "objectId",
                                d.objectId(),
                                "kind",
                                "UPDATE_FIELDS",
                                "values",
                                Map.of(title, "已完成")));
        String app = app(d, List.of(action));
        ApplicationRecords.Aggregate before = runtime.save(create(app, d, "待处理"), 10001);
        authorize(app, member(grant(d, "ALL", Set.of("READ"), Set.of(title))));
        ApplicationBusiness.Execute command =
                new ApplicationBusiness.Execute(
                        app,
                        d.objectId(),
                        action.id(),
                        before.record().id(),
                        before.record().revision());
        assertThatThrownBy(() -> runtime.execute(command, 20002)).hasMessageContaining("权限");
        var previousSharing = NocodeIntegrationSupport.resolvedPermission(d.objectId(), app);
        share(
                app,
                d,
                new ApplicationAuthorization.ObjectGrant(
                        d.objectId(),
                        Set.of("READ", "UPDATE"),
                        "ALL",
                        Set.of(title),
                        Set.of(),
                        Set.of(),
                        Set.of()));
        assertThatThrownBy(() -> runtime.execute(command, 10001)).hasMessageContaining("字段");
        share(app, d, grant(d, "ALL", Set.of("READ"), Set.of(title)));
        assertThatThrownBy(() -> runtime.execute(command, 10001)).hasMessageContaining("权限");
        share(app, d, previousSharing);
        ApplicationRecords.Aggregate after = runtime.execute(command, 10001);
        assertThat(after.record().values()).containsEntry(title, "已完成");
        assertThatThrownBy(() -> runtime.execute(command, 10001)).hasMessageContaining("已被修改");
        assertThatThrownBy(
                        () ->
                                runtime.execute(
                                        new ApplicationBusiness.Execute(
                                                app,
                                                d.objectId(),
                                                "injected",
                                                before.record().id(),
                                                after.record().revision()),
                                        10001))
                .hasMessageContaining("不存在");
    }

    @Test
    void applicationDictionaryIsPublishedWithItsViewWithoutChangingObjectOptions() {
        DataCenter.Definition d = object(false);
        String title = field(d, "name");
        ApplicationCenter.Resource dict =
                new ApplicationCenter.Resource(
                        "states",
                        "DICTIONARY",
                        "states",
                        "处理状态",
                        Map.of(
                                "items",
                                List.of(
                                        Map.of("code", "open", "label", "待处理", "disabled", false),
                                        Map.of(
                                                "code",
                                                "done",
                                                "label",
                                                "已完成",
                                                "disabled",
                                                false))));
        ApplicationCenter.Resource view =
                new ApplicationCenter.Resource(
                        "state_view",
                        "VIEW",
                        "state_view",
                        "状态列表",
                        Map.of(
                                "objectId",
                                d.objectId(),
                                "fieldIds",
                                List.of(title),
                                "equal",
                                Map.of(),
                                "pageSize",
                                20,
                                "descending",
                                false,
                                "filterDictionaries",
                                Map.of(title, dict.id())));
        String app = app(d, List.of(dict, view));
        ApplicationCenter.Published portal =
                servicesContext
                        .getBean(
                                com.richuang.os.nocode.runtime.service.application
                                        .ApplicationRuntimeService.class)
                        .application(app, 10001);
        assertThat(portal.definition().resources())
                .extracting(ApplicationCenter.Resource::id)
                .contains(dict.id(), view.id());
        assertThat(
                        servicesContext
                                .getBean(DataObjectApi.class)
                                .getPublished(d.objectId())
                                .fieldOptions()
                                .get(title)
                                .options())
                .isEmpty();
        ApplicationCenter.Resource bad =
                new ApplicationCenter.Resource(
                        "bad",
                        "DICTIONARY",
                        "duplicate_options",
                        "重复编码",
                        Map.of(
                                "items",
                                List.of(
                                        Map.of("code", "same", "label", "一"),
                                        Map.of("code", "same", "label", "二"))));
        assertThatThrownBy(() -> app(d, List.of(bad))).hasMessageContaining("重复");
    }

    @Test
    void platformCreatedReferenceColumnStillAcceptsBusinessSelection() {
        DataCenter.Definition customer = object(false);
        DataCenter.Definition order =
                publish(
                        designs.save(
                                new DataCenter.SaveDesign(
                                        fixture.createRequest("referencing"),
                                        DataCenter.Settings.defaults(),
                                        Map.of(),
                                        List.of(
                                                new DataCenter.Relation(
                                                        null,
                                                        "customer",
                                                        "客户",
                                                        "REFERENCE",
                                                        customer.objectId(),
                                                        null,
                                                        null,
                                                        true,
                                                        "RESTRICT")),
                                        List.of(),
                                        List.of()),
                                10001));
        DataObjectApi objectApi = servicesContext.getBean(DataObjectApi.class);
        List<ApplicationCenter.ObjectReference> refs =
                List.of(customer, order).stream()
                        .map(
                                d -> {
                                    DataObjectApi.PublishedObject v =
                                            objectApi.getVersion(d.objectId(), null);
                                    return new ApplicationCenter.ObjectReference(
                                            v.objectId(), v.versionNo(), v.checksum());
                                })
                        .toList();
        ApplicationCenter.Detail a =
                applications.save(
                        new ApplicationCenter.Save(
                                null,
                                null,
                                fixture.prefix + "app_reference",
                                "引用验证",
                                null,
                                null,
                                new ApplicationCenter.Definition(refs, List.of())),
                        10001);
        String app = a.application().id();
        ApplicationCenter.Resource incompleteForm =
                new ApplicationCenter.Resource(
                        "customer_form",
                        "FORM",
                        "customer_form",
                        "遗漏必填客户的表单",
                        Map.of(
                                "objectId",
                                order.objectId(),
                                "nodes",
                                List.of(
                                        Map.of(
                                                "id",
                                                "name_node",
                                                "type",
                                                "FIELD",
                                                "fieldId",
                                                field(order, "name"),
                                                "children",
                                                List.of())),
                                "detailIds",
                                List.of()));
        assertThatThrownBy(
                        () ->
                                applications.save(
                                        new ApplicationCenter.Save(
                                                app,
                                                a.application().revision(),
                                                a.application().code(),
                                                "引用验证",
                                                null,
                                                null,
                                                new ApplicationCenter.Definition(
                                                        refs, List.of(incompleteForm))),
                                        10001))
                .hasMessageContaining("必填");
        grantApplicationObjects(app);
        applications.publish(new ApplicationCenter.Revision(app, 0, "引用验证"), 10001);
        String reference = order.relations().getFirst().fieldId();
        assertThat(order.fieldOptions().get(reference).generated()).isTrue();
        assertThat(
                        runtime.model(app, order.objectId(), 10001)
                                .object()
                                .fieldOptions()
                                .get(reference)
                                .generated())
                .isFalse();
        ApplicationRecords.Aggregate customerRow =
                runtime.save(create(app, customer, "真实客户"), 10001);
        ApplicationRecords.Aggregate saved =
                runtime.save(
                        new Save(
                                app,
                                order.objectId(),
                                null,
                                null,
                                Map.of(
                                        field(order, "name"),
                                        "关联订单",
                                        reference,
                                        customerRow.record().id()),
                                null),
                        10001);
        assertThat(saved.record().values()).containsEntry(reference, customerRow.record().id());
        assertThatThrownBy(
                        () ->
                                runtime.delete(
                                        new Delete(
                                                app,
                                                customer.objectId(),
                                                customerRow.record().id(),
                                                customerRow.record().revision()),
                                        10001))
                .hasMessageContaining("引用");
    }

    @Test
    void directoryAndFileReferencesUseFoundationValidationAndPreserveHistoricalValues() {
        SaveObjectDraft request = fixture.createRequest("directories");
        ArrayList<FieldDefinition> fields = new ArrayList<>(request.fields());
        for (String kind : List.of("USER", "DEPARTMENT", "POST", "USER_GROUP", "ATTACHMENT"))
            fields.add(
                    fixture.field(
                            kind.toLowerCase(),
                            kind.equals("USER") ? "handler" : kind.toLowerCase(),
                            kind,
                            fields.size()));
        DataCenter.Definition d =
                publish(
                        designs.save(
                                new DataCenter.SaveDesign(
                                        new SaveObjectDraft(
                                                null,
                                                null,
                                                request.objectCode(),
                                                request.objectName(),
                                                null,
                                                request.tableName(),
                                                request.titleFieldKey(),
                                                fields,
                                                List.of()),
                                        DataCenter.Settings.defaults(),
                                        Map.of(),
                                        List.of(),
                                        List.of(),
                                        List.of()),
                                10001));
        String app = app(d);
        com.richuang.os.module.infra.service.file.FileService files =
                servicesContext.getBean(
                        com.richuang.os.module.infra.service.file.FileService.class);
        com.richuang.os.module.infra.dal.dataobject.file.FileDO file =
                new com.richuang.os.module.infra.dal.dataobject.file.FileDO();
        file.setId(123L);
        org.mockito.Mockito.when(files.getFiles(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of(file));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put(field(d, "name"), "底座校验");
        for (String code : List.of("handler", "department", "post", "user_group"))
            input.put(field(d, code), "123");
        input.put(field(d, "attachment"), List.of("123"));
        ApplicationRecords.Aggregate saved =
                runtime.save(new Save(app, d.objectId(), null, null, input, null), 10001);
        org.mockito.Mockito.verify(
                        servicesContext.getBean(
                                com.richuang.os.module.system.api.user.AdminUserApi.class))
                .validateUserList(Set.of(123L));
        org.mockito.Mockito.verify(
                        servicesContext.getBean(
                                com.richuang.os.module.system.api.dept.DeptApi.class))
                .validateDeptList(Set.of(123L));
        org.mockito.Mockito.verify(
                        servicesContext.getBean(
                                com.richuang.os.module.system.api.dept.PostApi.class))
                .validPostList(Set.of(123L));
        org.mockito.Mockito.verify(
                        servicesContext.getBean(
                                com.richuang.os.module.bpm.api.definition.BpmUserGroupApi.class))
                .validateUserGroups(Set.of(123L));
        org.mockito.Mockito.when(files.getFiles(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of());
        ApplicationRecords.Aggregate same =
                runtime.save(
                        new Save(
                                app,
                                d.objectId(),
                                saved.record().id(),
                                saved.record().revision(),
                                input,
                                null),
                        10001);
        assertThat(same.record().values()).containsEntry(field(d, "attachment"), List.of("123"));
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                d.objectId(),
                                                saved.record().id(),
                                                same.record().revision(),
                                                Map.of(field(d, "attachment"), List.of("456")),
                                                null),
                                        10001))
                .hasMessageContaining("附件不存在");
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                d.objectId(),
                                                saved.record().id(),
                                                same.record().revision(),
                                                Map.of(
                                                        field(d, "attachment"),
                                                        List.of("https://example.invalid/file")),
                                                null),
                                        10001))
                .hasMessageContaining("有效 ID");
        org.mockito.Mockito.doThrow(com.richuang.os.nocode.api.NocodeErrorCodes.invalid("部门已停用"))
                .when(servicesContext.getBean(com.richuang.os.module.system.api.dept.DeptApi.class))
                .validateDeptList(Set.of(456L));
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                d.objectId(),
                                                saved.record().id(),
                                                same.record().revision(),
                                                Map.of(field(d, "department"), "456"),
                                                null),
                                        10001))
                .hasMessageContaining("部门已停用");
        assertThat(runtime.get(app, d.objectId(), saved.record().id(), 10001).record().revision())
                .isEqualTo(same.record().revision());
    }

    private DataCenter.Definition relatedObject(
            DataCenter.Definition target, String kind, String deletion) {
        return publish(
                designs.save(
                        new DataCenter.SaveDesign(
                                fixture.createRequest("related" + serial++),
                                DataCenter.Settings.defaults(),
                                Map.of(),
                                List.of(
                                        new DataCenter.Relation(
                                                null,
                                                "related",
                                                "业务关联",
                                                kind,
                                                target.objectId(),
                                                null,
                                                null,
                                                false,
                                                deletion)),
                                List.of(),
                                List.of()),
                        10001));
    }

    private String groupedApp(DataCenter.Definition... definitions) {
        DataObjectApi objectApi = servicesContext.getBean(DataObjectApi.class);
        List<ApplicationCenter.ObjectReference> refs =
                Arrays.stream(definitions)
                        .map(
                                d -> {
                                    DataObjectApi.PublishedObject v =
                                            objectApi.getVersion(d.objectId(), null);
                                    return new ApplicationCenter.ObjectReference(
                                            v.objectId(), v.versionNo(), v.checksum());
                                })
                        .toList();
        ApplicationCenter.Detail app =
                applications.save(
                        new ApplicationCenter.Save(
                                null,
                                null,
                                fixture.prefix + "group" + serial++,
                                "关系验收",
                                null,
                                null,
                                new ApplicationCenter.Definition(refs, List.of())),
                        10001);
        grantApplicationObjects(app.application().id());
        applications.publish(
                new ApplicationCenter.Revision(app.application().id(), 0, "关系验收"), 10001);
        return app.application().id();
    }

    private void publishRelatedPage(
            String app,
            DataCenter.Definition current,
            DataCenter.Definition target,
            DataCenter.Relation relation,
            String direction) {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                servicesContext.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
        ApplicationUi.View view =
                new ApplicationUi.View(
                        target.objectId(),
                        target.fields().stream().map(FieldDefinition::id).toList(),
                        Map.of(),
                        null,
                        false,
                        10,
                        null);
        ApplicationUi.Node node =
                new ApplicationUi.Node(
                        "related",
                        "RELATED",
                        null,
                        "related_view",
                        "关联业务",
                        null,
                        List.of(),
                        new ApplicationUi.RelationBinding(relation.id(), direction),
                        null);
        ApplicationUi.Page page = new ApplicationUi.Page(List.of(node), current.objectId(), 2);
        ApplicationCenter.Detail before = applications.get(app);
        List<ApplicationCenter.Resource> resources =
                List.of(
                        new ApplicationCenter.Resource(
                                "related_view",
                                "VIEW",
                                "related_view",
                                "相关记录",
                                mapper.convertValue(
                                        view,
                                        new com.fasterxml.jackson.core.type.TypeReference<
                                                Map<String, Object>>() {})),
                        new ApplicationCenter.Resource(
                                "record_page",
                                "PAGE",
                                "record_page",
                                "业务详情",
                                mapper.convertValue(
                                        page,
                                        new com.fasterxml.jackson.core.type.TypeReference<
                                                Map<String, Object>>() {})));
        ApplicationCenter.Detail saved =
                applications.save(
                        new ApplicationCenter.Save(
                                app,
                                before.application().revision(),
                                before.application().code(),
                                before.application().name(),
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        before.draft().objects(), resources)),
                        10001);
        applications.publish(
                new ApplicationCenter.Revision(app, saved.application().revision(), "关联详情验证"),
                10001);
    }

    @Test
    void relatedPageScopesQueryWriteExportAndImportWithRealtimePermissions() {
        DataCenter.Definition company = object(false);
        DataCenter.Definition account = relatedObject(company, "REFERENCE", "RESTRICT");
        DataCenter.Relation relation = account.relations().getFirst();
        String app = groupedApp(company, account);
        String finance = groupedApp(company, account);
        publishRelatedPage(app, company, account, relation, "INCOMING");
        ApplicationRecords.Aggregate a = runtime.save(create(app, company, "公司甲"), 10001);
        ApplicationRecords.Aggregate b = runtime.save(create(app, company, "公司乙"), 10001);
        ApplicationRecords.Context contextA =
                new Context("record_page", "related", a.record().id());
        ApplicationRecords.Context contextB =
                new Context("record_page", "related", b.record().id());
        ApplicationRecords.Aggregate accountA =
                runtime.save(
                        new Save(
                                app,
                                account.objectId(),
                                null,
                                null,
                                Map.of(field(account, "name"), "甲账户"),
                                null,
                                null,
                                contextA),
                        10001);
        ApplicationRecords.Aggregate accountB =
                runtime.save(
                        new Save(
                                app,
                                account.objectId(),
                                null,
                                null,
                                Map.of(field(account, "name"), "乙账户"),
                                null,
                                null,
                                contextB),
                        10001);
        assertThat(accountA.record().values()).containsEntry(relation.fieldId(), a.record().id());
        ApplicationRecords.Query query =
                new Query(
                        app,
                        account.objectId(),
                        1,
                        10,
                        null,
                        Map.of(),
                        null,
                        false,
                        "related_view",
                        contextA);
        assertThat(runtime.page(query, 10001).getList())
                .extracting(Row::id)
                .containsExactly(accountA.record().id());
        assertThat(runtime.export(query, 10001))
                .extracting(Row::id)
                .containsExactly(accountA.record().id());
        assertThat(
                        runtime.importRecords(
                                app,
                                account.objectId(),
                                List.of(Map.of(field(account, "name"), "甲导入账户")),
                                10001,
                                contextA))
                .isEqualTo(1);
        assertThat(runtime.page(query, 10001).getTotal()).isEqualTo(2);
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                account.objectId(),
                                                null,
                                                null,
                                                Map.of(
                                                        field(account, "name"),
                                                        "伪造归属",
                                                        relation.fieldId(),
                                                        b.record().id()),
                                                null,
                                                null,
                                                contextA),
                                        10001))
                .hasMessageContaining("归属");
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                account.objectId(),
                                                accountB.record().id(),
                                                accountB.record().revision(),
                                                Map.of(field(account, "name"), "跨公司修改"),
                                                null,
                                                null,
                                                contextA),
                                        10001))
                .hasMessageContaining("不属于");
        assertThatThrownBy(
                        () ->
                                runtime.page(
                                        new Query(
                                                app,
                                                account.objectId(),
                                                1,
                                                10,
                                                null,
                                                Map.of(),
                                                null,
                                                false,
                                                null,
                                                contextA),
                                        10001))
                .hasMessageContaining("已发布视图");
        assertThatThrownBy(
                        () ->
                                runtime.page(
                                        new Query(
                                                finance,
                                                account.objectId(),
                                                1,
                                                10,
                                                null,
                                                Map.of(),
                                                null,
                                                false,
                                                "related_view",
                                                contextA),
                                        10001))
                .hasMessageContaining("视图");
        assertThat(
                        runtime.get(finance, account.objectId(), accountA.record().id(), 10001)
                                .record()
                                .id())
                .isEqualTo(accountA.record().id());
        share(
                app,
                account,
                grant(
                        account,
                        "ALL",
                        Set.of("READ"),
                        account.fields().stream()
                                .map(FieldDefinition::id)
                                .collect(java.util.stream.Collectors.toSet())));
        assertThat(runtime.page(query, 10001).getTotal()).isEqualTo(2);
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                account.objectId(),
                                                null,
                                                null,
                                                Map.of(field(account, "name"), "只读应用禁止新增"),
                                                null,
                                                null,
                                                contextA),
                                        10001))
                .hasMessageContaining("权限");
        // 系统主键通过 Row.id 返回，不进入业务字段授权；根记录无可读业务字段仍可提供关联上下文。
        assertThat(company.fields()).noneMatch(f -> "id".equals(f.code()));
        assertThat(account.fields()).noneMatch(f -> "id".equals(f.code()));
        assertThat(
                        NocodeIntegrationSupport.resolvedPermission(account.objectId(), app)
                                .readFields())
                .doesNotContain("id");
        share(app, company, grant(company, "ALL", Set.of("READ"), Set.of()));
        ApplicationRecords.Row identityOnly =
                runtime.get(app, company.objectId(), a.record().id(), 10001).record();
        assertThat(identityOnly.id()).isEqualTo(a.record().id());
        assertThat(identityOnly.values()).isEmpty();
        assertThat(runtime.page(query, 10001).getList())
                .extracting(Row::id)
                .contains(accountA.record().id())
                .doesNotContain(accountB.record().id());
        // 关联外键是业务字段；缺少它的查看权限必须拒绝，而不是借主键或页面关系绕过授权。
        share(app, account, grant(account, "ALL", Set.of("READ"), Set.of(field(account, "name"))));
        assertThatThrownBy(() -> runtime.page(query, 10001)).hasMessageContaining("没有关联字段的查看权限");
        share(
                app,
                account,
                grant(
                        account,
                        "ALL",
                        Set.of("READ"),
                        Set.of(field(account, "name"), relation.fieldId())));
        assertThat(runtime.page(query, 10001).getTotal()).isEqualTo(2);
        share(app, company, null);
        assertThatThrownBy(() -> runtime.page(query, 10001)).hasMessageContaining("权限");
        assertThat(
                        runtime.get(finance, account.objectId(), accountA.record().id(), 10001)
                                .record()
                                .id())
                .isEqualTo(accountA.record().id());
        assertThatThrownBy(
                        () ->
                                runtime.delete(
                                        new Delete(
                                                finance,
                                                company.objectId(),
                                                a.record().id(),
                                                a.record().revision()),
                                        10001))
                .hasMessageContaining("引用");
    }

    @Test
    void relatedPageInjectedReferenceStillRequiresMemberWritePermission() {
        DataCenter.Definition company = object(false);
        DataCenter.Definition account = relatedObject(company, "REFERENCE", "RESTRICT");
        String app = groupedApp(company, account);
        publishRelatedPage(app, company, account, account.relations().getFirst(), "INCOMING");
        ApplicationRecords.Aggregate parent = runtime.save(create(app, company, "间接赋值权限验收"), 10001);
        ApplicationRecords.Context context =
                new Context("record_page", "related", parent.record().id());
        authorize(
                app,
                new ApplicationAuthorization.Member(
                        "USER",
                        "20002",
                        List.of(
                                grant(
                                        company,
                                        "ALL",
                                        Set.of("READ"),
                                        Set.of(field(company, "name"))),
                                grant(
                                        account,
                                        "ALL",
                                        Set.of("READ", "CREATE", "IMPORT"),
                                        Set.of(field(account, "name"))))));
        Map<String, Object> input = Map.<String, Object>of(field(account, "name"), "禁止自动取得引用字段权限");
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                account.objectId(),
                                                null,
                                                null,
                                                input,
                                                null,
                                                null,
                                                context),
                                        20002))
                .hasMessageContaining("无权");
        assertThatThrownBy(
                        () ->
                                runtime.importRecords(
                                        app, account.objectId(), List.of(input), 20002, context))
                .hasMessageContaining("未写入");
        assertThat(
                        runtime.page(
                                        new Query(
                                                app,
                                                account.objectId(),
                                                1,
                                                10,
                                                null,
                                                Map.of(),
                                                null,
                                                false,
                                                "related_view",
                                                context),
                                        10001)
                                .getTotal())
                .isZero();
    }

    @Test
    void relatedPageManyToManyUsesBothDirectionsAndKeepsIndependentRecordIdentity() {
        DataCenter.Definition target = object(false);
        DataCenter.Definition source = relatedObject(target, "MANY_TO_MANY", "RESTRICT");
        String app = groupedApp(target, source);
        DataCenter.Relation relation = source.relations().getFirst();
        ApplicationRecords.Aggregate one = runtime.save(create(app, target, "目标一"), 10001);
        ApplicationRecords.Aggregate other = runtime.save(create(app, target, "目标二"), 10001);
        ApplicationRecords.Aggregate src =
                runtime.save(
                        new Save(
                                app,
                                source.objectId(),
                                null,
                                null,
                                Map.of(field(source, "name"), "来源"),
                                null,
                                Map.of(relation.id(), List.of(one.record().id()))),
                        10001);
        publishRelatedPage(app, source, target, relation, "OUTGOING");
        ApplicationRecords.Context context =
                new Context("record_page", "related", src.record().id());
        assertThat(
                        runtime.page(
                                        new Query(
                                                app,
                                                target.objectId(),
                                                1,
                                                10,
                                                null,
                                                Map.of(),
                                                null,
                                                false,
                                                "related_view",
                                                context),
                                        10001)
                                .getList())
                .extracting(Row::id)
                .containsExactly(one.record().id());
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                target.objectId(),
                                                other.record().id(),
                                                other.record().revision(),
                                                Map.of(field(target, "name"), "未关联记录"),
                                                null,
                                                null,
                                                context),
                                        10001))
                .hasMessageContaining("不属于");
        publishRelatedPage(app, target, source, relation, "INCOMING");
        assertThat(
                        runtime.page(
                                        new Query(
                                                app,
                                                source.objectId(),
                                                1,
                                                10,
                                                null,
                                                Map.of(),
                                                null,
                                                false,
                                                "related_view",
                                                new Context(
                                                        "record_page",
                                                        "related",
                                                        one.record().id())),
                                        10001)
                                .getList())
                .extracting(Row::id)
                .containsExactly(src.record().id());
    }

    @Test
    void selectionLinksRejectFileArraysAndUnboundUpstreamFields() {
        DataCenter.Definition target = object(false);
        DataCenter.Definition source = relatedObject(target, "REFERENCE", "RESTRICT");
        DataCenter.Relation relation = source.relations().getFirst();
        for (String type : List.of("IMAGE", "ATTACHMENT", "MULTI_SELECT", "REGION", "CASCADE")) {
            FieldDefinition array =
                    new FieldDefinition(
                            "upstream",
                            "upstream",
                            "upstream",
                            "数组上游",
                            type,
                            null,
                            null,
                            null,
                            false,
                            false,
                            0);
            assertThat(BusinessFields.linkable(array)).isFalse();
            Map data = mapper.convertValue(source, Map.class);
            ArrayList<FieldDefinition> fields = new ArrayList<>(source.fields());
            fields.add(array);
            data.put("fields", fields);
            DataCenter.Definition definition =
                    mapper.convertValue(data, DataCenter.Definition.class);
            ApplicationUi.Node reference =
                    new ApplicationUi.Node(
                            "ref",
                            "FIELD",
                            relation.fieldId(),
                            null,
                            null,
                            null,
                            List.of(),
                            null,
                            new ApplicationUi.FieldPresentation(
                                    null,
                                    null,
                                    null,
                                    false,
                                    new SelectionFields.Presentation(
                                            "SELECT",
                                            List.of(),
                                            false,
                                            "upstream",
                                            field(target, "name"),
                                            null)));
            ApplicationUi.Form form =
                    new ApplicationUi.Form(
                            source.objectId(),
                            List.of(
                                    new ApplicationUi.Node(
                                            "upstream",
                                            "FIELD",
                                            "upstream",
                                            null,
                                            null,
                                            null,
                                            List.of()),
                                    reference),
                            List.of());
            assertThatThrownBy(
                            () ->
                                    SelectionFields.validatePresentations(
                                            form,
                                            definition,
                                            Map.of(
                                                    target.objectId(),
                                                    target,
                                                    source.objectId(),
                                                    definition)))
                    .hasMessageContaining("单值字段");
            ApplicationUi.Form unbound =
                    new ApplicationUi.Form(source.objectId(), List.of(reference), List.of());
            assertThatThrownBy(
                            () ->
                                    SelectionFields.validatePresentations(
                                            unbound,
                                            definition,
                                            Map.of(
                                                    target.objectId(),
                                                    target,
                                                    source.objectId(),
                                                    definition)))
                    .hasMessageContaining("表单中的其他字段");
        }
    }

    private void publishChainResources(String app, List<ApplicationCenter.Resource> resources) {
        ApplicationCenter.Detail before = applications.get(app);
        ApplicationCenter.Detail saved =
                applications.save(
                        new ApplicationCenter.Save(
                                app,
                                before.application().revision(),
                                before.application().code(),
                                before.application().name(),
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        before.draft().objects(), resources)),
                        10001);
        applications.publish(
                new ApplicationCenter.Revision(app, saved.application().revision(), "逻辑字段验证"),
                10001);
    }

    @Test
    void logicalRelationsUseUnifiedFormsQueriesSelectionAndExport() throws Exception {
        SaveObjectDraft request = fixture.createRequest("title_chain");
        DataCenter.Definition target =
                publish(
                        designs.save(
                                new DataCenter.SaveDesign(
                                        request,
                                        new DataCenter.Settings(null, null, null, "分类 {{name}}"),
                                        Map.of(),
                                        List.of(),
                                        List.of(),
                                        List.of()),
                                10001));
        DataCenter.Definition source = relatedObject(target, "MANY_TO_MANY", "RESTRICT");
        String app = groupedApp(target, source),
                relation = source.relations().getFirst().id(),
                key = BusinessFields.key(source.relations().getFirst());
        ApplicationRecords.Aggregate one = runtime.save(create(app, target, "甲"), 10001);
        ApplicationRecords.Aggregate two = runtime.save(create(app, target, "乙"), 10001);
        ArrayList<ApplicationUi.Node> nodes = new ArrayList<ApplicationUi.Node>();
        source.fields()
                .forEach(
                        f ->
                                nodes.add(
                                        new ApplicationUi.Node(
                                                "node_" + f.id(),
                                                "FIELD",
                                                f.id(),
                                                null,
                                                null,
                                                null,
                                                List.of())));
        nodes.add(new ApplicationUi.Node("many_node", "FIELD", key, null, null, null, List.of()));
        ApplicationUi.Form form =
                new ApplicationUi.Form(
                        source.objectId(),
                        nodes,
                        List.of(),
                        new ApplicationUi.FormOptions("vertical", "保存", false, true));
        ApplicationUi.View view =
                new ApplicationUi.View(
                        source.objectId(),
                        List.of(field(source, "name"), key),
                        Map.of(),
                        null,
                        false,
                        10,
                        "selection_form");
        ApplicationCenter.Resource viewResource =
                new ApplicationCenter.Resource(
                        "chain_view",
                        "VIEW",
                        "chain_view",
                        "逻辑视图",
                        mapper.convertValue(view, Map.class));
        publishChainResources(app, List.of(selectionResource(form), viewResource));
        SelectionFields.Result selected =
                runtime.selection(
                        new SelectionFields.Query(
                                app,
                                source.objectId(),
                                null,
                                key,
                                null,
                                1,
                                10,
                                List.of(one.record().id()),
                                null,
                                "selection_form",
                                Map.of()),
                        10001);
        assertThat(selected.options())
                .extracting(SelectionFields.Option::label)
                .contains("分类 甲", "分类 乙");
        assertThat(selected.selected().getFirst().label()).isEqualTo("分类 甲");
        SelectionFields.Result preview =
                runtime.previewSelection(
                        new SelectionFields.PreviewQuery(
                                new SelectionFields.Query(
                                        app,
                                        source.objectId(),
                                        null,
                                        key,
                                        null,
                                        1,
                                        10,
                                        List.of(one.record().id(), two.record().id()),
                                        null),
                                applications.get(app).draft().objects(),
                                form,
                                true),
                        10001);
        assertThat(preview.selected()).hasSize(2);
        ApplicationRecords.Aggregate saved =
                runtime.save(
                        new Save(
                                app,
                                source.objectId(),
                                null,
                                null,
                                Map.of(field(source, "name"), "资产"),
                                null,
                                Map.of(relation, List.of(one.record().id(), two.record().id())),
                                null,
                                "selection_form"),
                        10001);
        assertThat(saved.record().values().get(key))
                .isEqualTo(List.of(one.record().id(), two.record().id()));
        assertThat(saved.record().displayValues().get(key)).isEqualTo("分类 甲、分类 乙");
        assertThat(
                        runtime.page(
                                        new Query(
                                                app,
                                                source.objectId(),
                                                1,
                                                10,
                                                null,
                                                Map.of(key, List.of(two.record().id())),
                                                null,
                                                false,
                                                "chain_view"),
                                        10001)
                                .getList())
                .extracting(Row::id)
                .containsExactly(saved.record().id());
        assertThat(
                        runtime.page(
                                        new Query(
                                                app,
                                                source.objectId(),
                                                1,
                                                10,
                                                null,
                                                Map.of(key, List.of("999999999")),
                                                null,
                                                false),
                                        10001)
                                .getList())
                .isEmpty();
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                source.objectId(),
                                                saved.record().id(),
                                                saved.record().revision(),
                                                Map.of(key, List.of()),
                                                null),
                                        10001))
                .hasMessageContaining("字段");
        com.richuang.os.nocode.web.RecordExcelService excel =
                servicesContext.getBean(com.richuang.os.nocode.web.RecordExcelService.class);
        try (org.apache.poi.ss.usermodel.Workbook book =
                org.apache.poi.ss.usermodel.WorkbookFactory.create(
                        new java.io.ByteArrayInputStream(
                                excel.export(
                                        new Query(
                                                app,
                                                source.objectId(),
                                                1,
                                                10,
                                                null,
                                                Map.of(),
                                                null,
                                                false,
                                                "chain_view"),
                                        10001)))) {
            org.apache.poi.ss.usermodel.Sheet sheet = book.getSheetAt(0);
            assertThat(sheet.getRow(0).getLastCellNum()).isEqualTo((short) 4);
            assertThat(sheet.getRow(1).getCell(3).getStringCellValue()).isEqualTo("分类 甲、分类 乙");
        }
        // 多选关系也在服务端复核表单联动，不能只依赖候选界面。
        ArrayList<ApplicationUi.Node> linkedNodes = new ArrayList<>(nodes);
        linkedNodes.set(
                linkedNodes.size() - 1,
                new ApplicationUi.Node(
                        "many_node",
                        "FIELD",
                        key,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        new ApplicationUi.FieldPresentation(
                                null,
                                null,
                                null,
                                false,
                                new SelectionFields.Presentation(
                                        "SELECT",
                                        List.of(),
                                        false,
                                        field(source, "name"),
                                        field(target, "name"),
                                        null))));
        ApplicationUi.Form linkedForm =
                new ApplicationUi.Form(
                        source.objectId(),
                        linkedNodes,
                        List.of(),
                        new ApplicationUi.FormOptions("vertical", "保存", false, true));
        publishChainResources(app, List.of(selectionResource(linkedForm), viewResource));
        ApplicationRecords.Aggregate linkedRecord =
                runtime.save(
                        new Save(
                                app,
                                source.objectId(),
                                null,
                                null,
                                Map.of(field(source, "name"), "甲"),
                                null,
                                Map.of(relation, List.of(one.record().id())),
                                null,
                                "selection_form"),
                        10001);
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                source.objectId(),
                                                linkedRecord.record().id(),
                                                linkedRecord.record().revision(),
                                                Map.of(field(source, "name"), "乙"),
                                                null,
                                                Map.of(relation, List.of(one.record().id())),
                                                null,
                                                "selection_form"),
                                        10001))
                .hasMessageContaining("联动条件");
        // 新表单隐藏关系后，服务端也不接受越界关系写入。
        ApplicationUi.Form hidden =
                new ApplicationUi.Form(
                        source.objectId(),
                        nodes.subList(0, nodes.size() - 1),
                        List.of(),
                        new ApplicationUi.FormOptions("vertical", "保存", false, true));
        publishChainResources(app, List.of(selectionResource(hidden), viewResource));
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                source.objectId(),
                                                saved.record().id(),
                                                saved.record().revision(),
                                                Map.of(),
                                                null,
                                                Map.of(relation, List.of()),
                                                null,
                                                "selection_form"),
                                        10001))
                .hasMessageContaining("当前表单");
        // 旧表单仍兼容底部关系入口。
        ApplicationUi.Form legacy =
                new ApplicationUi.Form(source.objectId(), hidden.nodes(), List.of());
        publishChainResources(app, List.of(selectionResource(legacy), viewResource));
        assertThat(
                        runtime.selection(
                                        new SelectionFields.Query(
                                                app,
                                                source.objectId(),
                                                null,
                                                key,
                                                null,
                                                1,
                                                10,
                                                List.of(),
                                                saved.record().id(),
                                                "selection_form",
                                                Map.of()),
                                        10001)
                                .total())
                .isEqualTo(2);
        ApplicationRecords.Aggregate cleared =
                runtime.save(
                        new Save(
                                app,
                                source.objectId(),
                                saved.record().id(),
                                saved.record().revision(),
                                Map.of(),
                                null,
                                Map.of(relation, List.of()),
                                null,
                                "selection_form"),
                        10001);
        assertThat(cleared.record().values().get(key)).isEqualTo(List.of());
        assertThat(cleared.record().displayValues().get(key)).isEmpty();
        assertThat(RecordTitles.render(target, Map.of())).isEqualTo("未提供可见标题");
        assertThatThrownBy(() -> RecordTitles.validate("{{missing}}", target.fields()))
                .hasMessageContaining("模板字段");
    }

    /** 表单限定视图：候选、已选回显、写入与发布校验都只取该视图的固定范围。 */
    @Test
    void formSelectionViewLimitsReferenceCandidatesWritesAndPublishValidation() {
        DataCenter.Definition target =
                publish(
                        designs.save(
                                new DataCenter.SaveDesign(
                                        fixture.createRequest("view_target"),
                                        new DataCenter.Settings(null, null, null, "分类 {{name}}"),
                                        Map.of(),
                                        List.of(),
                                        List.of(),
                                        List.of()),
                                10001));
        DataCenter.Definition source = relatedObject(target, "REFERENCE", "RESTRICT");
        String app = groupedApp(target, source),
                reference = source.relations().getFirst().fieldId();
        ApplicationRecords.Aggregate one = runtime.save(create(app, target, "甲"), 10001);
        ApplicationRecords.Aggregate two = runtime.save(create(app, target, "乙"), 10001);
        ApplicationUi.Form plainForm =
                new ApplicationUi.Form(
                        source.objectId(),
                        List.of(
                                new ApplicationUi.Node(
                                        "name_node",
                                        "FIELD",
                                        field(source, "name"),
                                        null,
                                        null,
                                        null,
                                        List.of()),
                                new ApplicationUi.Node(
                                        "ref_node",
                                        "FIELD",
                                        reference,
                                        null,
                                        null,
                                        null,
                                        List.of())),
                        List.of());
        publishChainResources(app, List.of(selectionResource(plainForm)));
        ApplicationRecords.Aggregate outside =
                runtime.save(
                        new Save(
                                app,
                                source.objectId(),
                                null,
                                null,
                                Map.of(field(source, "name"), "历史单", reference, two.record().id()),
                                null),
                        10001);
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                servicesContext.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
        ApplicationUi.View limited =
                new ApplicationUi.View(
                        target.objectId(),
                        List.of(field(target, "name")),
                        Map.of(),
                        null,
                        false,
                        10,
                        null,
                        Map.of(),
                        null,
                        null,
                        null,
                        new ViewQueryOptions(
                                List.of(new DataScope.Condition(field(target, "name"), "eq", "甲")),
                                null,
                                null));
        ApplicationUi.Form form =
                new ApplicationUi.Form(
                        source.objectId(),
                        List.of(
                                new ApplicationUi.Node(
                                        "name_node",
                                        "FIELD",
                                        field(source, "name"),
                                        null,
                                        null,
                                        null,
                                        List.of()),
                                new ApplicationUi.Node(
                                        "ref_node",
                                        "FIELD",
                                        reference,
                                        null,
                                        null,
                                        null,
                                        List.of(),
                                        null,
                                        new ApplicationUi.FieldPresentation(
                                                null,
                                                null,
                                                null,
                                                false,
                                                new SelectionFields.Presentation(
                                                        "SELECT",
                                                        List.of(),
                                                        false,
                                                        null,
                                                        null,
                                                        null,
                                                        "limit_view")))),
                        List.of());
        publishChainResources(
                app,
                List.of(
                        selectionResource(form),
                        new ApplicationCenter.Resource(
                                "limit_view",
                                "VIEW",
                                "limit_view",
                                "限定候选视图",
                                mapper.convertValue(limited, Map.class))));
        SelectionFields.Result candidates =
                runtime.selection(
                        new SelectionFields.Query(
                                app,
                                source.objectId(),
                                null,
                                reference,
                                null,
                                1,
                                10,
                                List.of(two.record().id()),
                                null,
                                "selection_form",
                                Map.of()),
                        10001);
        assertThat(candidates.options())
                .extracting(SelectionFields.Option::label)
                .containsExactly("分类 甲");
        assertThat(candidates.selected())
                .extracting(SelectionFields.Option::label)
                .containsExactly("分类 乙");
        assertThat(candidates.selected().getFirst().disabled()).isTrue();
        // 历史超范围值仍回显，编辑其它字段不阻断；新选超范围值被拒绝。
        ApplicationRecords.Aggregate edited =
                runtime.save(
                        new Save(
                                app,
                                source.objectId(),
                                outside.record().id(),
                                outside.record().revision(),
                                Map.of(field(source, "name"), "历史单改"),
                                null,
                                null,
                                null,
                                "selection_form"),
                        10001);
        assertThat(edited.record().values()).containsEntry(reference, two.record().id());
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                source.objectId(),
                                                null,
                                                null,
                                                Map.of(
                                                        field(source, "name"),
                                                        "越界新建",
                                                        reference,
                                                        two.record().id()),
                                                null,
                                                null,
                                                null,
                                                "selection_form"),
                                        10001))
                .hasMessageContaining("视图范围");
        ApplicationRecords.Aggregate inside =
                runtime.save(
                        new Save(
                                app,
                                source.objectId(),
                                null,
                                null,
                                Map.of(
                                        field(source, "name"),
                                        "范围内新建",
                                        reference,
                                        one.record().id()),
                                null,
                                null,
                                null,
                                "selection_form"),
                        10001);
        assertThat(inside.record().values()).containsEntry(reference, one.record().id());
        // 草稿预览同样按当前草稿的视图过滤候选，校验预览不接受超范围值。
        SelectionFields.Query previewQuery =
                new SelectionFields.Query(
                        app,
                        source.objectId(),
                        null,
                        reference,
                        null,
                        1,
                        10,
                        List.of(two.record().id()),
                        null);
        SelectionFields.Result preview =
                runtime.previewSelection(
                        new SelectionFields.PreviewQuery(
                                previewQuery, applications.get(app).draft().objects(), form, false),
                        10001);
        assertThat(preview.options())
                .extracting(SelectionFields.Option::label)
                .containsExactly("分类 甲");
        assertThat(preview.selected().getFirst().disabled()).isTrue();
        assertThatThrownBy(
                        () ->
                                runtime.previewSelection(
                                        new SelectionFields.PreviewQuery(
                                                previewQuery,
                                                applications.get(app).draft().objects(),
                                                form,
                                                true),
                                        10001))
                .hasMessageContaining("超出预览范围");
        // 视图删除或改绑其它对象后，表单引用在下一次保存／发布被拦截。
        ApplicationUi.Form missingView =
                new ApplicationUi.Form(source.objectId(), form.nodes(), List.of());
        assertThatThrownBy(
                        () -> publishChainResources(app, List.of(selectionResource(missingView))))
                .hasMessageContaining("限定视图不存在或已删除");
        ApplicationUi.View wrongObject =
                new ApplicationUi.View(
                        source.objectId(),
                        List.of(field(source, "name")),
                        Map.of(),
                        null,
                        false,
                        10,
                        null);
        assertThatThrownBy(
                        () ->
                                publishChainResources(
                                        app,
                                        List.of(
                                                selectionResource(form),
                                                new ApplicationCenter.Resource(
                                                        "limit_view",
                                                        "VIEW",
                                                        "limit_view",
                                                        "错绑视图",
                                                        mapper.convertValue(
                                                                wrongObject, Map.class)))))
                .hasMessageContaining("必须与引用目标对象一致");
    }

    /** 多选关系逐项套用同一视图范围，历史越界项保留而新增越界项被拒绝。 */
    @Test
    void formSelectionViewLimitsEachManyToManyValue() {
        DataCenter.Definition target =
                publish(
                        designs.save(
                                new DataCenter.SaveDesign(
                                        fixture.createRequest("view_many_target"),
                                        new DataCenter.Settings(null, null, null, "分类 {{name}}"),
                                        Map.of(),
                                        List.of(),
                                        List.of(),
                                        List.of()),
                                10001));
        DataCenter.Definition source = relatedObject(target, "MANY_TO_MANY", "RESTRICT");
        String app = groupedApp(target, source);
        DataCenter.Relation relation = source.relations().getFirst();
        String key = BusinessFields.key(relation);
        ApplicationRecords.Aggregate one = runtime.save(create(app, target, "甲"), 10001);
        ApplicationRecords.Aggregate two = runtime.save(create(app, target, "乙"), 10001);
        ArrayList<ApplicationUi.Node> nodes = new ArrayList<ApplicationUi.Node>();
        source.fields()
                .forEach(
                        f ->
                                nodes.add(
                                        new ApplicationUi.Node(
                                                "node_" + f.id(),
                                                "FIELD",
                                                f.id(),
                                                null,
                                                null,
                                                null,
                                                List.of())));
        nodes.add(new ApplicationUi.Node("many_node", "FIELD", key, null, null, null, List.of()));
        ApplicationUi.Form plainForm =
                new ApplicationUi.Form(
                        source.objectId(),
                        nodes,
                        List.of(),
                        new ApplicationUi.FormOptions("vertical", "保存", false, true));
        publishChainResources(app, List.of(selectionResource(plainForm)));
        ApplicationRecords.Aggregate legacy =
                runtime.save(
                        new Save(
                                app,
                                source.objectId(),
                                null,
                                null,
                                Map.of(field(source, "name"), "历史多选"),
                                null,
                                Map.of(
                                        relation.id(),
                                        List.of(one.record().id(), two.record().id())),
                                null,
                                "selection_form"),
                        10001);
        assertThat(legacy.record().values().get(key))
                .isEqualTo(List.of(one.record().id(), two.record().id()));
        ArrayList<ApplicationUi.Node> limitedNodes = new ArrayList<>(nodes);
        limitedNodes.set(
                limitedNodes.size() - 1,
                new ApplicationUi.Node(
                        "many_node",
                        "FIELD",
                        key,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        new ApplicationUi.FieldPresentation(
                                null,
                                null,
                                null,
                                false,
                                new SelectionFields.Presentation(
                                        "SELECT",
                                        List.of(),
                                        false,
                                        null,
                                        null,
                                        null,
                                        "limit_view"))));
        ApplicationUi.Form form =
                new ApplicationUi.Form(
                        source.objectId(),
                        limitedNodes,
                        List.of(),
                        new ApplicationUi.FormOptions("vertical", "保存", false, true));
        ApplicationUi.View limited =
                new ApplicationUi.View(
                        target.objectId(),
                        List.of(field(target, "name")),
                        Map.of(),
                        null,
                        false,
                        10,
                        null,
                        Map.of(),
                        null,
                        null,
                        null,
                        new ViewQueryOptions(
                                List.of(new DataScope.Condition(field(target, "name"), "eq", "甲")),
                                null,
                                null));
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                servicesContext.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
        publishChainResources(
                app,
                List.of(
                        selectionResource(form),
                        new ApplicationCenter.Resource(
                                "limit_view",
                                "VIEW",
                                "limit_view",
                                "限定候选视图",
                                mapper.convertValue(limited, Map.class))));
        SelectionFields.Result candidates =
                runtime.selection(
                        new SelectionFields.Query(
                                app,
                                source.objectId(),
                                null,
                                key,
                                null,
                                1,
                                10,
                                List.of(one.record().id(), two.record().id()),
                                legacy.record().id(),
                                "selection_form",
                                Map.of()),
                        10001);
        assertThat(candidates.options())
                .extracting(SelectionFields.Option::label)
                .containsExactly("分类 甲");
        assertThat(candidates.selected())
                .extracting(SelectionFields.Option::label, SelectionFields.Option::disabled)
                .containsExactly(tuple("分类 甲", false), tuple("分类 乙", true));
        // 历史越界项在编辑其它字段时保留，新选越界项被拒绝。
        ApplicationRecords.Aggregate edited =
                runtime.save(
                        new Save(
                                app,
                                source.objectId(),
                                legacy.record().id(),
                                legacy.record().revision(),
                                Map.of(field(source, "name"), "历史多选改"),
                                null,
                                Map.of(
                                        relation.id(),
                                        List.of(one.record().id(), two.record().id())),
                                null,
                                "selection_form"),
                        10001);
        assertThat(edited.record().values().get(key))
                .isEqualTo(List.of(one.record().id(), two.record().id()));
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                source.objectId(),
                                                null,
                                                null,
                                                Map.of(field(source, "name"), "越界多选"),
                                                null,
                                                Map.of(relation.id(), List.of(two.record().id())),
                                                null,
                                                "selection_form"),
                                        10001))
                .hasMessageContaining("视图范围");
        ApplicationRecords.Aggregate inside =
                runtime.save(
                        new Save(
                                app,
                                source.objectId(),
                                null,
                                null,
                                Map.of(field(source, "name"), "范围内多选"),
                                null,
                                Map.of(relation.id(), List.of(one.record().id())),
                                null,
                                "selection_form"),
                        10001);
        assertThat(inside.record().values().get(key)).isEqualTo(List.of(one.record().id()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void manyToManyRoundTripRetainsUniqueLinksAndChecksActualIncomingRows(boolean legacy) {
        DataCenter.Definition target = object(false);
        DataCenter.Definition source = relatedObject(target, "MANY_TO_MANY", "RESTRICT");
        String app = groupedApp(target, source), relation = source.relations().getFirst().id();
        if (legacy) {
            // 只改名本测试拥有的关联表，模拟升级前的物理布局，后续走真实运行读写。
            String current =
                    com.richuang.os.nocode.metadata.service.table.DataTableService.relationTable(
                            source.objectId(), relation);
            jdbc.execute(
                    "ALTER TABLE public."
                            + current
                            + " RENAME TO nocode_data_r_"
                            + source.objectId()
                            + "_"
                            + relation);
        }
        ApplicationRecords.Aggregate unused = runtime.save(create(app, target, "尚未关联"), 10001);
        runtime.delete(
                new Delete(
                        app, target.objectId(), unused.record().id(), unused.record().revision()),
                10001);
        ApplicationRecords.Aggregate targetRow = runtime.save(create(app, target, "课程"), 10001);
        ApplicationRecords.Aggregate saved =
                runtime.save(
                        new Save(
                                app,
                                source.objectId(),
                                null,
                                null,
                                Map.of(field(source, "name"), "学生"),
                                null,
                                Map.of(relation, List.of(targetRow.record().id()))),
                        10001);
        assertThat(saved.relations().get(relation)).containsExactly(targetRow.record().id());
        assertThat(saved.record().values().get("relation_" + relation))
                .isEqualTo(List.of(targetRow.record().id()));
        assertThat(saved.record().displayValues().get("relation_" + relation)).isEqualTo("课程");
        assertThat(runtime.model(app, source.objectId(), 10001).permissions().writeRelations())
                .contains(relation);
        assertThatThrownBy(
                        () ->
                                runtime.delete(
                                        new Delete(
                                                app,
                                                target.objectId(),
                                                targetRow.record().id(),
                                                targetRow.record().revision()),
                                        10001))
                .hasMessageContaining("多对多关联");
        ApplicationRecords.Aggregate removed =
                runtime.save(
                        new Save(
                                app,
                                source.objectId(),
                                saved.record().id(),
                                saved.record().revision(),
                                Map.of(),
                                null,
                                Map.of(relation, List.of())),
                        10001);
        ApplicationRecords.Aggregate attached =
                runtime.save(
                        new Save(
                                app,
                                source.objectId(),
                                saved.record().id(),
                                removed.record().revision(),
                                Map.of(),
                                null,
                                Map.of(relation, List.of(targetRow.record().id()))),
                        10001);
        assertThat(attached.relations().get(relation)).containsExactly(targetRow.record().id());
        String table =
                com.richuang.os.nocode.metadata.service.table.DataTableService.relationTable(
                        databaseMetadata, "public", source.objectId(), relation);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public." + table, Long.class))
                .isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                source.objectId(),
                                                saved.record().id(),
                                                attached.record().revision(),
                                                Map.of(field(source, "name"), "不能部分写入"),
                                                null,
                                                Map.of(relation, List.of("999999999"))),
                                        10001))
                .hasMessageContaining("不存在");
        assertThat(
                        runtime.get(app, source.objectId(), saved.record().id(), 10001)
                                .record()
                                .revision())
                .isEqualTo(attached.record().revision());
        runtime.delete(
                new Delete(
                        app, source.objectId(), saved.record().id(), attached.record().revision()),
                10001);
        runtime.delete(
                new Delete(
                        app,
                        target.objectId(),
                        targetRow.record().id(),
                        targetRow.record().revision()),
                10001);
    }

    @Test
    void relationAuthorizationIsExplicitAndTargetReadScopeCannotBeBypassed() {
        DataCenter.Definition target = object(false);
        DataCenter.Definition source = relatedObject(target, "MANY_TO_MANY", "RESTRICT");
        String app = groupedApp(target, source), relation = source.relations().getFirst().id();
        ApplicationRecords.Aggregate hidden = runtime.save(create(app, target, "他人目标"), 10001);
        ApplicationRecords.Aggregate record = runtime.save(create(app, source, "成员编辑"), 10001);
        ApplicationAuthorization.ObjectGrant sourceGrant =
                new ApplicationAuthorization.ObjectGrant(
                        source.objectId(),
                        Set.of("READ", "UPDATE"),
                        "ALL",
                        Set.of(field(source, "name")),
                        Set.of(field(source, "name")),
                        Set.of(),
                        Set.of(),
                        Set.of(relation),
                        Set.of(relation));
        authorize(
                app,
                new ApplicationAuthorization.Member(
                        "USER",
                        "20002",
                        List.of(
                                sourceGrant,
                                grant(
                                        target,
                                        "OWN",
                                        Set.of("READ"),
                                        Set.of(field(target, "name"))))));
        // 2026-10 起（关联对象隐式只读）：成员在目标对象上的授权——这里是「只看本人创建的」——不再限制他在引用里按名称选到谁，
        // 只要他能读、能改本对象上的这个关系，就能选目标对象的任何记录。引用里他仍只看得到名称，目标对象自己的列表照旧只有本人创建的。
        var linked =
                runtime.save(
                        new Save(
                                app,
                                source.objectId(),
                                record.record().id(),
                                record.record().revision(),
                                Map.of(),
                                null,
                                Map.of(relation, List.of(hidden.record().id()))),
                        20002);
        assertThat(
                        runtime.get(app, source.objectId(), record.record().id(), 10001)
                                .relations()
                                .get(relation))
                .containsExactly(hidden.record().id());
        assertThat(
                        runtime.page(
                                        new Query(
                                                app,
                                                target.objectId(),
                                                1,
                                                20,
                                                null,
                                                null,
                                                null,
                                                false),
                                        20002)
                                .getTotal())
                .as("目标对象自己的列表仍按成员的记录范围：别人建的看不到")
                .isZero();
        authorize(
                app,
                member(
                        grant(
                                source,
                                "ALL",
                                Set.of("READ", "UPDATE"),
                                Set.of(field(source, "name")))));
        assertThat(runtime.model(app, source.objectId(), 20002).object().relations()).isEmpty();
        assertThat(
                        runtime.get(app, source.objectId(), record.record().id(), 20002)
                                .record()
                                .values())
                .doesNotContainKey("relation_" + relation);
        assertThatThrownBy(
                        () ->
                                runtime.selection(
                                        new SelectionFields.Query(
                                                app,
                                                source.objectId(),
                                                null,
                                                "relation_" + relation,
                                                null,
                                                1,
                                                10,
                                                List.of(),
                                                null),
                                        20002))
                .hasMessageContaining("权限");
        assertThat(runtime.get(app, source.objectId(), record.record().id(), 20002).relations())
                .isEmpty();
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                source.objectId(),
                                                record.record().id(),
                                                linked.record().revision(),
                                                Map.of(),
                                                null,
                                                Map.of(relation, List.of())),
                                        20002))
                .hasMessageContaining("无权修改");
    }

    @Test
    void logicalDeleteExecutesSetNullAndCascadeOnlyWithAffectedRecordPermissions() {
        for (String deletion : List.of("SET_NULL", "CASCADE")) {
            DataCenter.Definition target = object(false);
            DataCenter.Definition source =
                    relatedObject(
                            target,
                            deletion.equals("CASCADE") ? "MASTER_DETAIL" : "REFERENCE",
                            deletion);
            String app = groupedApp(target, source), fk = source.relations().getFirst().fieldId();
            ApplicationRecords.Aggregate parent = runtime.save(create(app, target, "主记录"), 10001);
            ApplicationRecords.Aggregate child =
                    runtime.save(
                            new Save(
                                    app,
                                    source.objectId(),
                                    null,
                                    null,
                                    Map.of(field(source, "name"), "从记录", fk, parent.record().id()),
                                    null),
                            10001);
            authorize(
                    app,
                    new ApplicationAuthorization.Member(
                            "USER",
                            "20002",
                            List.of(
                                    grant(
                                            target,
                                            "ALL",
                                            Set.of("READ", "DELETE"),
                                            Set.of(field(target, "name"))),
                                    grant(
                                            source,
                                            "ALL",
                                            Set.of("READ"),
                                            Set.of(field(source, "name"), fk)))));
            ApplicationRecords.Delete command =
                    new Delete(
                            app,
                            target.objectId(),
                            parent.record().id(),
                            parent.record().revision());
            assertThatThrownBy(() -> runtime.delete(command, 20002)).hasMessageContaining("权限");
            assertThat(
                            runtime.get(app, target.objectId(), parent.record().id(), 10001)
                                    .record()
                                    .revision())
                    .isEqualTo(parent.record().revision());
            runtime.delete(command, 10001);
            if (deletion.equals("CASCADE"))
                assertThatThrownBy(
                                () ->
                                        runtime.get(
                                                app, source.objectId(), child.record().id(), 10001))
                        .hasMessageContaining("不存在");
            else
                assertThat(
                                runtime.get(app, source.objectId(), child.record().id(), 10001)
                                        .record()
                                        .values())
                        .containsEntry(fk, null);
        }
    }

    @Test
    void detailSummariesAreLivePreciseAndRespectSourceDetailAuthorization() {
        DataCenter.Definition original = object(true);
        DataCenter.Design current = designs.get(original.objectId());
        DataCenter.Design draft =
                designs.editPublished(
                        new DataCenter.Revision(
                                original.objectId(), current.draft().lockVersion(), "增加汇总"),
                        10001);
        ArrayList<FieldDefinition> fields = new ArrayList<>(draft.draft().fields());
        HashMap<String, DataCenter.FieldOptions> options = new HashMap<>(draft.fieldOptions());
        for (String operation : List.of("count", "sum", "avg", "min", "max")) {
            fields.add(fixture.field(operation, operation + "_items", "SUMMARY", fields.size()));
            options.put(
                    operation,
                    new DataCenter.FieldOptions(
                            null,
                            "NORMAL",
                            null,
                            null,
                            null,
                            null,
                            null,
                            "ACTIVE",
                            List.of(),
                            operation.equals("count")
                                    ? "count(items)"
                                    : operation + "(items.quantity)",
                            operation.equals("count") ? "INTEGER" : "DECIMAL",
                            "NONE",
                            null,
                            false,
                            false));
        }
        DataCenter.Definition d =
                publish(
                        designs.save(
                                new DataCenter.SaveDesign(
                                        fixture.edit(
                                                draft.draft(),
                                                fields,
                                                List.of(),
                                                draft.draft().titleFieldId()),
                                        draft.settings(),
                                        options,
                                        draft.relations(),
                                        draft.indexes(),
                                        draft.details(),
                                        draft.mainBinding()),
                                10001));
        ApplicationCenter.Resource summaryView =
                new ApplicationCenter.Resource(
                        "summary_view",
                        "VIEW",
                        "summary_view",
                        "汇总视图",
                        Map.of(
                                "objectId",
                                d.objectId(),
                                "fieldIds",
                                List.of(field(d, "name"), field(d, "sum_items")),
                                "equal",
                                Map.of(),
                                "pageSize",
                                20,
                                "descending",
                                false));
        LinkedHashMap<String, Object> invalidSort = new LinkedHashMap<>(summaryView.config());
        invalidSort.put("sortFieldId", field(d, "sum_items"));
        assertThatThrownBy(
                        () ->
                                app(
                                        d,
                                        List.of(
                                                new ApplicationCenter.Resource(
                                                        "bad_sort",
                                                        "VIEW",
                                                        "bad_sort",
                                                        "非法排序",
                                                        invalidSort))))
                .hasMessageContaining("不能用作排序");
        String app = app(d, List.of(summaryView)),
                detail = d.details().getFirst().id(),
                quantity = d.details().getFirst().fields().getFirst().id();
        ApplicationRecords.Aggregate row =
                runtime.save(
                        new Save(
                                app,
                                d.objectId(),
                                null,
                                null,
                                Map.of(field(d, "name"), "实时汇总"),
                                Map.of(
                                        detail,
                                        List.of(
                                                new Row(
                                                        null,
                                                        null,
                                                        Map.of(quantity, "9007199254740993")),
                                                new Row(null, null, Map.of(quantity, "7"))))),
                        10001);
        assertThat(row.record().values())
                .containsEntry(field(d, "count_items"), "2")
                .containsEntry(field(d, "sum_items"), "9007199254741000");
        assertThat(
                        runtime.page(
                                        new Query(
                                                app,
                                                d.objectId(),
                                                1,
                                                20,
                                                null,
                                                null,
                                                null,
                                                false,
                                                "summary_view"),
                                        10001)
                                .getList()
                                .getFirst()
                                .values())
                .containsEntry(field(d, "sum_items"), "9007199254741000");
        assertThat(
                        runtime.page(
                                        new Query(
                                                app, d.objectId(), 1, 20, null, null, null, false),
                                        10001)
                                .getList()
                                .getFirst()
                                .values())
                .containsEntry(field(d, "min_items"), "7");
        authorize(
                app,
                member(
                        grant(
                                d,
                                "ALL",
                                Set.of("READ"),
                                Set.of(field(d, "name"), field(d, "sum_items")))));
        assertThat(runtime.get(app, d.objectId(), row.record().id(), 20002).record().values())
                .doesNotContainKey(field(d, "sum_items"));
        assertThat(runtime.model(app, d.objectId(), 20002).object().fields())
                .noneMatch(f -> f.code().equals("sum_items"));
        ApplicationRecords.Aggregate empty =
                runtime.save(
                        new Save(
                                app,
                                d.objectId(),
                                row.record().id(),
                                row.record().revision(),
                                Map.of(),
                                Map.of(detail, List.of())),
                        10001);
        assertThat(empty.record().values())
                .containsEntry(field(d, "sum_items"), "0")
                .containsEntry(field(d, "count_items"), "0")
                .containsEntry(field(d, "avg_items"), null);
    }

    @Test
    void foundationAndLegacyExcelAnnotationsShareTheSameWorkbookEngine() throws Exception {
        com.richuang.os.module.system.legacy.dto.UserImportDTO user =
                new com.richuang.os.module.system.legacy.dto.UserImportDTO();
        user.setUsername("fixture_excel");
        user.setNickname("底座兼容验证");
        user.setPhone("13800000000");
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        cn.idev.excel.FastExcelFactory.write(output, user.getClass())
                .autoCloseStream(false)
                .sheet("用户模板")
                .doWrite(List.of(user));
        List<com.richuang.os.module.system.legacy.dto.UserImportDTO> imported =
                cn.idev.excel.FastExcelFactory.read(
                                new java.io.ByteArrayInputStream(output.toByteArray()),
                                user.getClass(),
                                null)
                        .sheet()
                        .doReadSync();
        assertThat(imported).hasSize(1);
        assertThat(imported.getFirst().getUsername()).isEqualTo(user.getUsername());
        assertThat(imported.getFirst().getPhone()).isEqualTo(user.getPhone());
        com.richuang.os.module.system.legacy.dto.UserExcelVO export =
                new com.richuang.os.module.system.legacy.dto.UserExcelVO();
        export.setUsername(user.getUsername());
        export.setCreateTime(java.time.LocalDateTime.of(2026, 9, 6, 0, 0));
        org.springframework.mock.web.MockHttpServletResponse response =
                new org.springframework.mock.web.MockHttpServletResponse();
        com.richuang.os.framework.excel.core.util.ExcelUtils.write(
                response,
                "users.xlsx",
                "用户",
                com.richuang.os.module.system.legacy.dto.UserExcelVO.class,
                List.of(export));
        try (org.apache.poi.ss.usermodel.Workbook book =
                org.apache.poi.ss.usermodel.WorkbookFactory.create(
                        new java.io.ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(book.getSheetAt(0).getRow(0).getCell(0).getStringCellValue())
                    .isEqualTo("用户名");
            assertThat(book.getSheetAt(0).getRow(1).getCell(0).getStringCellValue())
                    .isEqualTo(user.getUsername());
        }
    }

    private org.springframework.mock.web.MockMultipartFile workbook(
            List<String> headers, List<List<String>> rows) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        com.richuang.os.framework.excel.core.util.ExcelUtils.write(output, "业务数据", headers, rows);
        return new org.springframework.mock.web.MockMultipartFile(
                "file",
                "records.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                output.toByteArray());
    }

    @Test
    void excelImportUsesTemplateAndAtomicWritesWhileExportNarrowsIndependentScope()
            throws Exception {
        DataCenter.Definition d = object(false);
        String app = app(d), title = field(d, "name");
        runtime.save(create(app, d, "别人记录"), 10001);
        authorize(
                app,
                member(
                        grant(
                                d,
                                "OWN",
                                Set.of("READ", "CREATE", "IMPORT", "EXPORT"),
                                Set.of(title))));
        com.richuang.os.nocode.web.RecordExcelService excel =
                servicesContext.getBean(com.richuang.os.nocode.web.RecordExcelService.class);
        byte[] template = excel.template(app, d.objectId(), 20002);
        String header;
        try (org.apache.poi.ss.usermodel.Workbook book =
                org.apache.poi.ss.usermodel.WorkbookFactory.create(
                        new java.io.ByteArrayInputStream(template))) {
            header = book.getSheetAt(0).getRow(0).getCell(0).getStringCellValue();
        }
        assertThat(
                        excel.importFile(
                                app,
                                d.objectId(),
                                workbook(
                                        List.of(header), List.of(List.of("=1+1"), List.of("我的导入"))),
                                20002))
                .isEqualTo(2);
        authorize(app, member(grant(d, "ALL", Set.of("READ"), Set.of(title))));
        assertThatThrownBy(
                        () ->
                                excel.export(
                                        new Query(
                                                app, d.objectId(), 1, 20, null, null, null, false),
                                        20002))
                .hasMessageContaining("权限");
        authorize(app, member(grant(d, "OWN", Set.of("READ", "EXPORT"), Set.of(title))));
        byte[] exported =
                excel.export(new Query(app, d.objectId(), 1, 20, null, null, null, false), 20002);
        try (org.apache.poi.ss.usermodel.Workbook book =
                org.apache.poi.ss.usermodel.WorkbookFactory.create(
                        new java.io.ByteArrayInputStream(exported))) {
            org.apache.poi.ss.usermodel.Sheet sheet = book.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
            assertThat(sheet.getRow(1).getCell(1).getCellType())
                    .isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("=1+1");
        }
        assertThatThrownBy(() -> excel.template(app, d.objectId(), 20002))
                .hasMessageContaining("权限");
    }

    @Test
    void invalidExcelRowRollsBackEarlierRecordsAndReportsWorksheetRow() throws Exception {
        DataCenter.Definition d = object(false);
        String app = app(d), title = field(d, "name");
        com.richuang.os.nocode.web.RecordExcelService excel =
                servicesContext.getBean(com.richuang.os.nocode.web.RecordExcelService.class);
        String header =
                d.fields().stream()
                                .filter(f -> f.id().equals(title))
                                .findFirst()
                                .orElseThrow()
                                .name()
                        + "【name】";
        // Excel 单元格自身限制更严格，使用错误表头和显式空值分别验证解析及业务事务边界。
        assertThatThrownBy(
                        () ->
                                excel.importFile(
                                        app,
                                        d.objectId(),
                                        workbook(List.of("错误列"), List.of(List.of("值"))),
                                        10001))
                .hasMessageContaining("表头");
        assertThatThrownBy(
                        () ->
                                runtime.importRecords(
                                        app,
                                        d.objectId(),
                                        List.of(
                                                Map.of(title, "应回滚"),
                                                Map.of(title, "x".repeat(100001))),
                                        10001))
                .hasMessageContaining("第 3 行");
        assertThat(
                        runtime.page(
                                        new Query(
                                                app, d.objectId(), 1, 20, null, null, null, false),
                                        10001)
                                .getTotal())
                .isZero();
    }

    private com.richuang.os.module.bpm.api.task.BpmProcessInstanceApi bpm() {
        return servicesContext.getBean(
                com.richuang.os.module.bpm.api.task.BpmProcessInstanceApi.class);
    }

    private ApplicationCenter.Resource processAction(DataCenter.Definition d) {
        com.richuang.os.module.bpm.api.definition.dto.BpmProcessDefinitionDTO definition =
                new com.richuang.os.module.bpm.api.definition.dto.BpmProcessDefinitionDTO();
        definition.setId(fixture.prefix + "process:1");
        definition.setKey(fixture.prefix + "process");
        definition.setName("订单审批");
        definition.setFormType(20);
        definition.setFormCustomViewPath("/nocode-app/process-record");
        org.mockito.Mockito.when(
                        servicesContext
                                .getBean(
                                        com.richuang.os.module.bpm.api.definition
                                                .BpmProcessDefinitionApi.class)
                                .getProcessDefinition(definition.getId()))
                .thenReturn(definition);
        return new ApplicationCenter.Resource(
                "submit_process",
                "ACTION",
                "submit_process",
                "提交审批",
                Map.of(
                        "objectId",
                        d.objectId(),
                        "kind",
                        "START_PROCESS",
                        "processDefinitionId",
                        definition.getId(),
                        "variables",
                        Map.of("nc_title", field(d, "name"))));
    }

    private void finish(String business, String instance, String key, int status) {
        com.richuang.os.module.bpm.api.event.BpmProcessInstanceStatusEvent event =
                new com.richuang.os.module.bpm.api.event.BpmProcessInstanceStatusEvent(this);
        event.setBusinessKey(business);
        event.setId(instance);
        event.setProcessDefinitionKey(key);
        event.setStatus(status);
        servicesContext.publishEvent(event);
    }

    @Test
    void processLocksRecordAndProtectsRunningApplicationThenUnlocksOnTrustedTerminalEvent() {
        DataCenter.Definition d = object(true);
        ApplicationCenter.Resource action = processAction(d);
        String app = app(d, List.of(action));
        ApplicationRecords.Aggregate before = runtime.save(create(app, d, "待审批"), 10001);
        String instance = fixture.prefix + "instance";
        org.mockito.Mockito.when(
                        bpm().createProcessInstance(
                                        org.mockito.ArgumentMatchers.eq(10001L),
                                        org.mockito.ArgumentMatchers.any()))
                .thenAnswer(
                        call -> {
                            com.richuang.os.module.bpm.api.task.dto.BpmProcessInstanceCreateReqDTO
                                    request =
                                            call.getArgument(
                                                    1,
                                                    com.richuang.os.module.bpm.api.task.dto
                                                            .BpmProcessInstanceCreateReqDTO.class);
                            assertThat(request.getVariables())
                                    .containsOnlyKeys("nc_title")
                                    .containsEntry("nc_title", "待审批");
                            assertThat(request.getProcessDefinitionId())
                                    .isEqualTo(action.config().get("processDefinitionId"));
                            return instance;
                        });
        ApplicationBusiness.Execute command =
                new ApplicationBusiness.Execute(
                        app,
                        d.objectId(),
                        action.id(),
                        before.record().id(),
                        before.record().revision());
        authorize(app, member(grant(d, "ALL", Set.of("READ"), Set.of(field(d, "name")))));
        assertThatThrownBy(() -> runtime.execute(command, 20002)).hasMessageContaining("权限");
        var previousSharing = NocodeIntegrationSupport.resolvedPermission(d.objectId(), app);
        share(app, d, grant(d, "ALL", Set.of("READ"), Set.of(field(d, "name"))));
        assertThatThrownBy(() -> runtime.execute(command, 10001)).hasMessageContaining("权限");
        org.mockito.Mockito.verifyNoInteractions(bpm());
        share(app, d, previousSharing);
        ApplicationRecords.Aggregate submitted = runtime.execute(command, 10001);
        assertThat(submitted.processes()).hasSize(1);
        ApplicationRecords.Process process = submitted.processes().getFirst();
        assertThat(process.status()).isEqualTo("RUNNING");
        assertThat(submitted.record().revision()).isNotEqualTo(before.record().revision());
        assertThat(submitted.record().permissions().actions())
                .doesNotContain("UPDATE", "DELETE", "START_PROCESS");
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                d.objectId(),
                                                before.record().id(),
                                                submitted.record().revision(),
                                                Map.of(field(d, "name"), "篡改"),
                                                null),
                                        10001))
                .hasMessageContaining("流程");
        assertThatThrownBy(
                        () ->
                                runtime.delete(
                                        new Delete(
                                                app,
                                                d.objectId(),
                                                before.record().id(),
                                                submitted.record().revision()),
                                        10001))
                .hasMessageContaining("流程");
        ApplicationCenter.Detail head = applications.get(app);
        assertThatThrownBy(
                        () ->
                                applications.status(
                                        new ApplicationCenter.Revision(
                                                app, head.application().revision(), "停用"),
                                        "DISABLED",
                                        10001))
                .hasMessageContaining("运行中的流程");
        assertThat(runtime.processRecord(process.businessKey(), 20002).recordId())
                .isEqualTo(before.record().id());
        authorize(app);
        assertThatThrownBy(() -> runtime.processRecord(process.businessKey(), 20002))
                .hasMessageContaining("权限");
        finish(process.businessKey(), "forged-instance", fixture.prefix + "process", 2);
        finish(process.businessKey(), instance, "wrong-definition", 2);
        assertThat(
                        runtime.get(app, d.objectId(), before.record().id(), 10001)
                                .processes()
                                .getFirst()
                                .status())
                .isEqualTo("RUNNING");
        finish(process.businessKey(), instance, fixture.prefix + "process", 2);
        finish(process.businessKey(), instance, fixture.prefix + "process", 3);
        ApplicationRecords.Aggregate finished =
                runtime.get(app, d.objectId(), before.record().id(), 10001);
        assertThat(finished.processes().getFirst().status()).isEqualTo("APPROVED");
        assertThat(finished.record().permissions().actions())
                .contains("UPDATE", "DELETE", "START_PROCESS");
        runtime.delete(
                new Delete(app, d.objectId(), finished.record().id(), finished.record().revision()),
                10001);
    }

    @Test
    void synchronousProcessCompletionAndStartFailurePreserveTransactionAndRevision() {
        DataCenter.Definition d = object(false);
        ApplicationCenter.Resource action = processAction(d);
        String app = app(d, List.of(action));
        ApplicationRecords.Aggregate before = runtime.save(create(app, d, "同步结束"), 10001);
        ApplicationBusiness.Execute command =
                new ApplicationBusiness.Execute(
                        app,
                        d.objectId(),
                        action.id(),
                        before.record().id(),
                        before.record().revision());
        org.mockito.Mockito.when(
                        bpm().createProcessInstance(
                                        org.mockito.ArgumentMatchers.eq(10001L),
                                        org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("模拟底座启动失败"));
        assertThatThrownBy(() -> runtime.execute(command, 10001)).hasMessageContaining("启动失败");
        ApplicationRecords.Aggregate unchanged =
                runtime.get(app, d.objectId(), before.record().id(), 10001);
        assertThat(unchanged.processes()).isEmpty();
        assertThat(unchanged.record().revision()).isEqualTo(before.record().revision());
        org.mockito.Mockito.doAnswer(
                        call -> {
                            com.richuang.os.module.bpm.api.task.dto.BpmProcessInstanceCreateReqDTO
                                    request =
                                            call.getArgument(
                                                    1,
                                                    com.richuang.os.module.bpm.api.task.dto
                                                            .BpmProcessInstanceCreateReqDTO.class);
                            String id = fixture.prefix + "instant";
                            finish(request.getBusinessKey(), id, fixture.prefix + "process", 2);
                            return id;
                        })
                .when(bpm())
                .createProcessInstance(
                        org.mockito.ArgumentMatchers.eq(10001L),
                        org.mockito.ArgumentMatchers.any());
        ApplicationRecords.Aggregate complete = runtime.execute(command, 10001);
        assertThat(complete.processes().getFirst().status()).isEqualTo("APPROVED");
        assertThatThrownBy(() -> runtime.execute(command, 10001)).hasMessageContaining("已被修改");
        assertThat(runtime.get(app, d.objectId(), before.record().id(), 10001).processes())
                .hasSize(1);
    }

    @Test
    void wideObjectRoundTripDoesNotExceedDatabaseJsonArgumentLimit() {
        SaveObjectDraft request = fixture.createRequest("wide");
        ArrayList<FieldDefinition> fields = new ArrayList<>(request.fields());
        for (int i = 1; i <= 60; i++) {
            fields.add(fixture.field("wide-" + i, "extra_" + i, "TEXT", i));
        }
        DataCenter.Design design =
                designs.save(
                        new DataCenter.SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        request.objectCode(),
                                        request.objectName(),
                                        null,
                                        request.tableName(),
                                        request.titleFieldKey(),
                                        fields,
                                        List.of()),
                                DataCenter.Settings.defaults(),
                                null,
                                List.of(),
                                List.of(),
                                List.of()),
                        10001);
        DataCenter.Definition d = publish(design);
        String app = app(d);
        LinkedHashMap<String, Object> input = new LinkedHashMap<String, Object>();
        d.fields().forEach(f -> input.put(f.id(), "值" + f.code()));
        ApplicationRecords.Aggregate saved =
                runtime.save(new Save(app, d.objectId(), null, null, input, null), 10001);
        assertThat(saved.record().values()).containsAllEntriesOf(input);
        assertThat(
                        runtime.page(
                                        new Query(
                                                app, d.objectId(), 1, 20, null, null, null, false),
                                        10001)
                                .getList()
                                .getFirst()
                                .values())
                .containsAllEntriesOf(input);
    }

    @Test
    void adoptedIdentityAndReadOnlyContractAreRespected() {
        DataCenter.Definition d =
                adopted("legacy_id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY", true);
        String app = app(d);
        assertThat(runtime.model(app, d.objectId(), 10001).generatedKey()).isTrue();
        assertThat(runtime.save(create(app, d, "自增旧表"), 10001).record().id()).isNotBlank();
        DataCenter.Definition ro = adopted("legacy_key varchar(40) PRIMARY KEY", false);
        String roApp = app(ro);
        assertThat(runtime.model(roApp, ro.objectId(), 10001).writable()).isFalse();
        assertThatThrownBy(() -> runtime.save(create(roApp, ro, "不允许写入"), 10001))
                .hasMessageContaining("只读");
    }

    /** 页面按钮只引用现有对象入口，样式与动作均使用受控契约。 */
    @Test
    void pageMaterialsValidateRestoreAndDoNotEnlargeWritePermissions() {
        DataCenter.Definition d = object(false);
        ApplicationCenter.Resource form =
                new ApplicationCenter.Resource(
                        "form",
                        "FORM",
                        "record_form",
                        "表单",
                        Map.of(
                                "objectId",
                                d.objectId(),
                                "detailIds",
                                List.of(),
                                "nodes",
                                List.of(
                                        Map.of(
                                                "id",
                                                "name_field",
                                                "type",
                                                "FIELD",
                                                "fieldId",
                                                field(d, "name"),
                                                "children",
                                                List.of()))));
        ApplicationCenter.Resource view =
                new ApplicationCenter.Resource(
                        "view",
                        "VIEW",
                        "record_view",
                        "列表",
                        Map.of(
                                "objectId",
                                d.objectId(),
                                "fieldIds",
                                List.of(field(d, "name")),
                                "equal",
                                Map.of(),
                                "pageSize",
                                20,
                                "formId",
                                "form"));
        Map<String, Object> list =
                Map.<String, Object>of(
                        "id", "list", "type", "VIEW", "resourceId", "view", "children", List.of());
        Map<String, Object> detail =
                Map.<String, Object>of(
                        "id",
                        "detail",
                        "type",
                        "DETAIL",
                        "resourceId",
                        "form",
                        "children",
                        List.of());
        Map<String, Object> button =
                Map.<String, Object>of(
                        "id",
                        "create",
                        "type",
                        "BUTTON",
                        "text",
                        "新增记录",
                        "children",
                        List.of(),
                        "display",
                        Map.of("buttonType", "PRIMARY"),
                        "action",
                        Map.of("kind", "CREATE", "targetNodeId", "list"));
        Map<String, Object> heading =
                Map.<String, Object>of(
                        "id",
                        "heading",
                        "type",
                        "HEADING",
                        "text",
                        "业务工作台",
                        "children",
                        List.of(),
                        "style",
                        Map.of("color", "#172554", "padding", 12),
                        "display",
                        Map.of("headingLevel", 2));
        ApplicationCenter.Resource page =
                new ApplicationCenter.Resource(
                        "page",
                        "PAGE",
                        "record_page",
                        "业务页面",
                        Map.of(
                                "contextObjectId",
                                d.objectId(),
                                "protocolVersion",
                                2,
                                "nodes",
                                List.of(heading, button, list, detail)));
        String app = app(d, List.of(form, view, page));
        com.richuang.os.nocode.runtime.service.application.ApplicationRuntimeService portal =
                servicesContext.getBean(
                        com.richuang.os.nocode.runtime.service.application.ApplicationRuntimeService
                                .class);
        ApplicationCenter.Resource actual =
                portal.application(app, 10001).definition().resources().stream()
                        .filter(r -> r.id().equals("page"))
                        .findFirst()
                        .orElseThrow();
        ApplicationUi.Page decoded =
                servicesContext
                        .getBean(com.fasterxml.jackson.databind.ObjectMapper.class)
                        .convertValue(actual.config(), ApplicationUi.Page.class);
        assertThat(decoded.nodes().getFirst().style().color()).isEqualTo("#172554");
        assertThat(decoded.nodes().get(1).action().targetNodeId()).isEqualTo("list");
        assertThat(runtime.save(create(app, d, "按钮合法数据"), 10001).record().id()).isNotBlank();
        share(app, d, grant(d, "ALL", Set.of("READ"), Set.of(field(d, "name"))));
        assertThat(runtime.model(app, d.objectId(), 10001).permissions().actions())
                .containsExactly("READ");
        assertThatThrownBy(() -> runtime.save(create(app, d, "旧页面越权新增"), 10001))
                .hasMessageContaining("权限");
        List<ApplicationCenter.ObjectReference> refs = applications.get(app).draft().objects();
        for (Map<String, String> action :
                List.of(
                        Map.of("kind", "CREATE", "targetNodeId", "missing"),
                        Map.of("kind", "CREATE", "targetNodeId", "detail"),
                        Map.of("kind", "CREATE", "targetNodeId", "list", "resourceId", "view"),
                        Map.of("kind", "EXECUTE_ACTION", "resourceId", "view"),
                        Map.of("kind", "OPEN_PAGE", "resourceId", "missing"),
                        Map.of("kind", "FETCH", "resourceId", "https://example.com"))) {
            LinkedHashMap<String, Object> badButton = new LinkedHashMap<>(button);
            badButton.put("action", action);
            ApplicationCenter.Resource badPage =
                    new ApplicationCenter.Resource(
                            "page",
                            "PAGE",
                            "record_page",
                            "错误按钮",
                            Map.of(
                                    "contextObjectId",
                                    d.objectId(),
                                    "nodes",
                                    List.of(badButton, list, detail)));
            assertThatThrownBy(
                            () ->
                                    applications.normalize(
                                            new ApplicationCenter.Definition(
                                                    refs, List.of(form, view, badPage))))
                    .isInstanceOf(RuntimeException.class);
        }
        for (Map<String, ?> style :
                List.of(
                        Map.of("padding", 49),
                        Map.of("background", "url(https://example.com)"),
                        Map.of("color", "red;display:none"),
                        Map.of("direction", "ROW"),
                        Map.of("minHeight", -1))) {
            LinkedHashMap<String, Object> badHeading = new LinkedHashMap<>(heading);
            badHeading.put("style", style);
            ApplicationCenter.Resource badPage =
                    new ApplicationCenter.Resource(
                            "page",
                            "PAGE",
                            "record_page",
                            "错误样式",
                            Map.of("nodes", List.of(badHeading)));
            assertThatThrownBy(
                            () ->
                                    applications.normalize(
                                            new ApplicationCenter.Definition(
                                                    refs, List.of(badPage))))
                    .isInstanceOf(RuntimeException.class);
        }
        for (String kind : List.of("HEADING", "IMAGE", "ALERT", "FLEX", "SPACER", "BUTTON")) {
            ApplicationCenter.Resource badForm =
                    new ApplicationCenter.Resource(
                            "form",
                            "FORM",
                            "record_form",
                            "混入页面物料",
                            Map.of(
                                    "objectId",
                                    d.objectId(),
                                    "detailIds",
                                    List.of(),
                                    "nodes",
                                    List.of(
                                            Map.of(
                                                    "id",
                                                    "bad",
                                                    "type",
                                                    kind,
                                                    "children",
                                                    List.of()))));
            assertThatThrownBy(
                            () ->
                                    applications.normalize(
                                            new ApplicationCenter.Definition(
                                                    refs, List.of(badForm))))
                    .hasMessageContaining("业务页面");
        }
    }

    @Test
    void withdrawingOneObjectRemovesItsPageBlocksAndDependentButtons() {
        DataCenter.Definition kept = object(false);
        DataCenter.Definition withdrawn = object(false);
        String app = groupedApp(kept, withdrawn);
        ApplicationCenter.Resource view =
                new ApplicationCenter.Resource(
                        "view",
                        "VIEW",
                        "record_view",
                        "受控列表",
                        Map.of(
                                "objectId",
                                withdrawn.objectId(),
                                "fieldIds",
                                List.of(field(withdrawn, "name")),
                                "equal",
                                Map.of(),
                                "pageSize",
                                20));
        ApplicationCenter.Resource page =
                new ApplicationCenter.Resource(
                        "page",
                        "PAGE",
                        "workspace",
                        "工作台",
                        Map.of(
                                "nodes",
                                List.of(
                                        Map.of(
                                                "id",
                                                "title",
                                                "type",
                                                "HEADING",
                                                "text",
                                                "共享工作台",
                                                "children",
                                                List.of()),
                                        Map.of(
                                                "id",
                                                "refresh",
                                                "type",
                                                "BUTTON",
                                                "text",
                                                "刷新列表",
                                                "children",
                                                List.of(),
                                                "action",
                                                Map.of("kind", "REFRESH", "targetNodeId", "list")),
                                        Map.of(
                                                "id",
                                                "list",
                                                "type",
                                                "VIEW",
                                                "resourceId",
                                                "view",
                                                "children",
                                                List.of()))));
        ApplicationCenter.Detail head = applications.get(app);
        ApplicationCenter.Detail saved =
                applications.save(
                        new ApplicationCenter.Save(
                                app,
                                head.application().revision(),
                                head.application().code(),
                                head.application().name(),
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        head.draft().objects(), List.of(view, page))),
                        10001);
        applications.publish(
                new ApplicationCenter.Revision(app, saved.application().revision(), "按钮撤权回归"),
                10001);
        share(app, withdrawn, null);
        com.richuang.os.nocode.runtime.service.application.ApplicationRuntimeService portal =
                servicesContext.getBean(
                        com.richuang.os.nocode.runtime.service.application.ApplicationRuntimeService
                                .class);
        ApplicationCenter.Resource actual =
                portal.application(app, 10001).definition().resources().stream()
                        .filter(r -> r.id().equals("page"))
                        .findFirst()
                        .orElseThrow();
        ApplicationUi.Page decoded =
                servicesContext
                        .getBean(com.fasterxml.jackson.databind.ObjectMapper.class)
                        .convertValue(actual.config(), ApplicationUi.Page.class);
        assertThat(decoded.nodes()).extracting(ApplicationUi.Node::id).containsExactly("title");
        assertThatThrownBy(
                        () ->
                                runtime.page(
                                        new Query(
                                                app,
                                                withdrawn.objectId(),
                                                1,
                                                20,
                                                null,
                                                null,
                                                null,
                                                false),
                                        10001))
                .hasMessageContaining("权限");
    }

    @Test
    void viewInteractionAndRecordExtrasPublishWithoutChangingSharedAuthorization() {
        DataCenter.Definition d = object(false);
        Map<String, Object> interaction =
                Map.<String, Object>of(
                        "buttons",
                        List.of("VIEW", "EXPORT"),
                        "actionIds",
                        List.of(),
                        "editMode",
                        "DRAWER",
                        "detailMode",
                        "MODAL");
        ApplicationCenter.Resource form =
                new ApplicationCenter.Resource(
                        "form",
                        "FORM",
                        "record_form",
                        "记录表单",
                        Map.of(
                                "objectId",
                                d.objectId(),
                                "detailIds",
                                List.of(),
                                "nodes",
                                d.fields().stream()
                                        .map(
                                                f ->
                                                        Map.of(
                                                                "id",
                                                                "field_" + f.id(),
                                                                "type",
                                                                "FIELD",
                                                                "fieldId",
                                                                f.id(),
                                                                "children",
                                                                List.of()))
                                        .toList()));
        LinkedHashMap<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("objectId", d.objectId());
        config.put("fieldIds", List.of(field(d, "name")));
        config.put("equal", Map.of());
        config.put("pageSize", 20);
        config.put("interaction", interaction);
        ApplicationCenter.Resource view =
                new ApplicationCenter.Resource("view", "VIEW", "record_view", "记录列表", config);
        ApplicationCenter.Resource page =
                new ApplicationCenter.Resource(
                        "page",
                        "PAGE",
                        "record_page",
                        "记录资料",
                        Map.of(
                                "contextObjectId",
                                d.objectId(),
                                "nodes",
                                List.of(
                                        Map.of(
                                                "id",
                                                "files",
                                                "type",
                                                "ATTACHMENTS",
                                                "resourceId",
                                                "form",
                                                "children",
                                                List.of()),
                                        Map.of(
                                                "id",
                                                "processes",
                                                "type",
                                                "PROCESSES",
                                                "resourceId",
                                                "form",
                                                "children",
                                                List.of()))));
        String app = app(d, List.of(form, view, page));
        com.richuang.os.nocode.runtime.service.application.ApplicationRuntimeService portal =
                servicesContext.getBean(
                        com.richuang.os.nocode.runtime.service.application.ApplicationRuntimeService
                                .class);
        assertThat(
                        portal.application(app, 10001).definition().resources().stream()
                                .filter(r -> r.id().equals("view"))
                                .findFirst()
                                .orElseThrow()
                                .config()
                                .get("interaction"))
                .isEqualTo(interaction);
        // 隐藏按钮不是新的权限系统：合法 API 写入仍可进行，收回共享 CREATE 后才必须拒绝。
        assertThat(runtime.save(create(app, d, "合法直接调用"), 10001).record().id()).isNotBlank();
        share(app, d, grant(d, "ALL", Set.of("READ"), Set.of(field(d, "name"))));
        assertThatThrownBy(() -> runtime.save(create(app, d, "撤权后禁止"), 10001))
                .hasMessageContaining("权限");
        List<ApplicationCenter.ObjectReference> refs = applications.get(app).draft().objects();
        for (Map<String, Object> invalidConfig :
                List.of(
                        Map.of(
                                "buttons",
                                List.of("SHELL"),
                                "actionIds",
                                List.of(),
                                "editMode",
                                "DRAWER",
                                "detailMode",
                                "MODAL"),
                        Map.of(
                                "buttons",
                                List.of("VIEW"),
                                "actionIds",
                                List.of(),
                                "editMode",
                                "https://example.com",
                                "detailMode",
                                "MODAL"),
                        Map.of(
                                "buttons",
                                List.of("VIEW"),
                                "actionIds",
                                List.of("missing"),
                                "editMode",
                                "DRAWER",
                                "detailMode",
                                "MODAL"))) {
            LinkedHashMap<String, Object> invalidView = new LinkedHashMap<>(config);
            invalidView.put("interaction", invalidConfig);
            assertThatThrownBy(
                            () ->
                                    applications.normalize(
                                            new ApplicationCenter.Definition(
                                                    refs,
                                                    List.of(
                                                            new ApplicationCenter.Resource(
                                                                    "view",
                                                                    "VIEW",
                                                                    "record_view",
                                                                    "错误配置",
                                                                    invalidView)))))
                    .isInstanceOf(RuntimeException.class);
        }
        ApplicationCenter.Resource missingContext =
                new ApplicationCenter.Resource(
                        "page",
                        "PAGE",
                        "record_page",
                        "缺少当前记录",
                        Map.of("nodes", page.config().get("nodes")));
        assertThatThrownBy(
                        () ->
                                applications.normalize(
                                        new ApplicationCenter.Definition(
                                                refs, List.of(form, missingContext))))
                .hasMessageContaining("当前记录");
    }

    private FieldDefinition enhancementField(String code, String type) {
        return new FieldDefinition(
                code,
                null,
                code,
                code,
                type,
                "TEXT".equals(type) ? 100 : null,
                "DECIMAL".equals(type) ? 20 : null,
                "DECIMAL".equals(type) ? 4 : null,
                false,
                false,
                10);
    }

    private DataCenter.Design enhancementDesign(
            List<FieldDefinition> extra, Map<String, DataCenter.FieldOptions> options) {
        SaveObjectDraft request = fixture.createRequest("enhance" + serial++);
        ArrayList<FieldDefinition> fields = new ArrayList<>(request.fields());
        fields.addAll(extra);
        return designs.save(
                new DataCenter.SaveDesign(
                        new SaveObjectDraft(
                                null,
                                null,
                                request.objectCode(),
                                request.objectName(),
                                null,
                                request.tableName(),
                                request.titleFieldKey(),
                                fields,
                                List.of()),
                        DataCenter.Settings.defaults(),
                        options,
                        List.of(),
                        List.of(),
                        List.of()),
                10001);
    }

    private DataCenter.FieldOptions calculation(
            String expression, String result, CalculationOptions calculation) {
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
                result,
                "NONE",
                null,
                false,
                false,
                null,
                calculation);
    }

    @Test
    void internalDetailReferencesValidateRollbackResolveLabelsAndProtectLogicalDeletion() {
        DataCenter.Definition target = object(false);
        DataCenter.Detail detail =
                new DataCenter.Detail(
                        null,
                        "entries",
                        "分录",
                        "biz_" + fixture.prefix + "entries",
                        "ACTIVE",
                        List.of(fixture.field("summary", "summary", "TEXT", 0)),
                        Map.of(),
                        List.of());
        DataCenter.Design design =
                designs.save(
                        new DataCenter.SaveDesign(
                                fixture.createRequest("voucher"),
                                DataCenter.Settings.defaults(),
                                null,
                                List.of(
                                        new DataCenter.Relation(
                                                null,
                                                "account",
                                                "科目",
                                                "REFERENCE",
                                                target.objectId(),
                                                null,
                                                null,
                                                true,
                                                "RESTRICT",
                                                "detail:entries")),
                                List.of(),
                                List.of(detail)),
                        10001);
        DataCenter.Definition source = publish(design);
        String app = enhancementApp(target, source);
        ApplicationRecords.Row account =
                runtime.save(create(app, target, "现 金 科 目"), 10001).record();
        DataCenter.Detail entries = source.details().getFirst();
        String reference = source.relations().getFirst().fieldId();
        String summary =
                entries.fields().stream()
                        .filter(f -> f.code().equals("summary"))
                        .findFirst()
                        .orElseThrow()
                        .id();
        ApplicationRecords.Row line =
                new Row(null, null, Map.of(reference, account.id(), summary, "借方"));
        ApplicationRecords.Aggregate saved =
                runtime.save(
                        new Save(
                                app,
                                source.objectId(),
                                null,
                                null,
                                Map.of(field(source, "name"), "凭证"),
                                Map.of(entries.id(), List.of(line))),
                        10001);
        assertThat(saved.details().get(entries.id()).getFirst().displayValues().get(reference))
                .isNotNull();
        assertThatThrownBy(
                        () ->
                                runtime.delete(
                                        new Delete(
                                                app,
                                                target.objectId(),
                                                account.id(),
                                                account.revision()),
                                        10001))
                .hasMessageContaining("内部明细引用");
        long before =
                jdbc.queryForObject(
                        "SELECT count(*) FROM public.\""
                                + source.tableName()
                                + "\" WHERE deleted=0",
                        Long.class);
        ApplicationRecords.Row invalid =
                new Row(null, null, Map.of(reference, "999999999999", summary, "非法"));
        assertThatThrownBy(
                () ->
                        runtime.save(
                                new Save(
                                        app,
                                        source.objectId(),
                                        null,
                                        null,
                                        Map.of(field(source, "name"), "不能留下主表"),
                                        Map.of(entries.id(), List.of(line, invalid))),
                                10001));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.\""
                                        + source.tableName()
                                        + "\" WHERE deleted=0",
                                Long.class))
                .isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.\""
                                        + entries.tableName()
                                        + "\" WHERE deleted=0",
                                Long.class))
                .isEqualTo(1L);
    }

    private static DocumentPolicy.Expression documentValue(Object value) {
        return new DocumentPolicy.Expression("VALUE", null, null, value, List.of());
    }

    private static DocumentPolicy.Expression documentField(String field) {
        return new DocumentPolicy.Expression("FIELD", field, null, null, List.of());
    }

    private static DocumentPolicy.Expression documentAggregate(
            String op, String detail, String field) {
        return new DocumentPolicy.Expression(op, field, detail, null, List.of());
    }

    private static DocumentPolicy.Expression documentCompare(
            String op, DocumentPolicy.Expression... args) {
        return new DocumentPolicy.Expression(op, null, null, null, List.of(args));
    }

    private DataCenter.Definition documentObject() {
        SaveObjectDraft base = fixture.createRequest("document" + serial++);
        ArrayList<FieldDefinition> fields = new ArrayList<>(base.fields());
        fields.add(fixture.field("state", "state", "SELECT", 1));
        DataCenter.FieldOptions stateOptions =
                new DataCenter.FieldOptions(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "ACTIVE",
                        List.of(
                                new DataCenter.Option("draft", "草稿", false),
                                new DataCenter.Option("registered", "已登记", false)),
                        null,
                        null,
                        "NONE",
                        null,
                        false,
                        false);
        DataCenter.Detail entries =
                new DataCenter.Detail(
                        null,
                        "entries",
                        "分录",
                        "biz_" + fixture.prefix + "entries" + serial++,
                        "ACTIVE",
                        List.of(
                                fixture.field("debit", "debit", "MONEY", 0),
                                fixture.field("credit", "credit", "MONEY", 1)),
                        Map.of(),
                        List.of());
        DocumentPolicy.Expression registered =
                documentCompare("EQ", documentField("state"), documentValue("registered"));
        List<DocumentPolicy.Rule> rules =
                List.of(
                        new DocumentPolicy.Rule(
                                "nonnegative",
                                "金额非负",
                                "ROW",
                                "detail:entries",
                                null,
                                documentCompare(
                                        "AND",
                                        documentCompare(
                                                "GE", documentField("debit"), documentValue(0)),
                                        documentCompare(
                                                "GE", documentField("credit"), documentValue(0))),
                                "debit",
                                "借贷金额必须非负"),
                        new DocumentPolicy.Rule(
                                "exclusive",
                                "借贷互斥",
                                "ROW",
                                "detail:entries",
                                registered,
                                documentCompare(
                                        "OR",
                                        documentCompare(
                                                "AND",
                                                documentCompare(
                                                        "GT",
                                                        documentField("debit"),
                                                        documentValue(0)),
                                                documentCompare(
                                                        "EQ",
                                                        documentField("credit"),
                                                        documentValue(0))),
                                        documentCompare(
                                                "AND",
                                                documentCompare(
                                                        "GT",
                                                        documentField("credit"),
                                                        documentValue(0)),
                                                documentCompare(
                                                        "EQ",
                                                        documentField("debit"),
                                                        documentValue(0)))),
                                "debit",
                                "每行只可填写借方或贷方"),
                        new DocumentPolicy.Rule(
                                "two_lines",
                                "至少两行",
                                "DETAIL",
                                "detail:entries",
                                registered,
                                documentCompare(
                                        "GE",
                                        documentAggregate("COUNT", "detail:entries", null),
                                        documentValue(2)),
                                null,
                                "登记至少需要两行"),
                        new DocumentPolicy.Rule(
                                "balance",
                                "借贷平衡",
                                "DOCUMENT",
                                null,
                                registered,
                                documentCompare(
                                        "EQ",
                                        documentAggregate("SUM", "detail:entries", "debit"),
                                        documentAggregate("SUM", "detail:entries", "credit")),
                                null,
                                "借贷合计不平衡"));
        DocumentPolicy.Lifecycle lifecycle =
                new DocumentPolicy.Lifecycle(
                        "state",
                        "draft",
                        List.of(
                                new DocumentPolicy.State("draft", "草稿", List.of(), List.of(), true),
                                new DocumentPolicy.State(
                                        "registered",
                                        "已登记",
                                        List.of("new-title"),
                                        List.of("detail:entries"),
                                        false)),
                        List.of(
                                new DocumentPolicy.Action(
                                        "register", "登记", List.of("draft"), "registered", "UPDATE"),
                                new DocumentPolicy.Action(
                                        "reopen",
                                        "退回草稿",
                                        List.of("registered"),
                                        "draft",
                                        "UPDATE")));
        return publish(
                designs.save(
                        new DataCenter.SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        base.objectCode(),
                                        base.objectName(),
                                        base.description(),
                                        base.tableName(),
                                        base.titleFieldKey(),
                                        fields,
                                        List.of()),
                                new DataCenter.Settings(
                                        null,
                                        null,
                                        null,
                                        null,
                                        new DocumentPolicy(rules, lifecycle)),
                                Map.of("state", stateOptions),
                                List.of(),
                                List.of(),
                                List.of(entries)),
                        10001));
    }

    private Save documentSave(
            String app,
            DataCenter.Definition d,
            Row row,
            Map<String, Object> values,
            List<Row> lines,
            String action) {
        return new Save(
                app,
                d.objectId(),
                row == null ? null : row.id(),
                row == null ? null : row.revision(),
                values,
                lines == null ? null : Map.of(d.details().getFirst().id(), lines),
                null,
                null,
                null,
                null,
                action);
    }

    @Test
    void documentRulesUseWholeCandidateRollbackStateAndKeepStableRowErrors() {
        DataCenter.Definition d = documentObject();
        String app = enhancementApp(d);
        DataCenter.Detail detail = d.details().getFirst();
        String debit =
                detail.fields().stream()
                        .filter(f -> f.code().equals("debit"))
                        .findFirst()
                        .orElseThrow()
                        .id();
        String credit =
                detail.fields().stream()
                        .filter(f -> f.code().equals("credit"))
                        .findFirst()
                        .orElseThrow()
                        .id();
        ApplicationRecords.Row first =
                new Row(
                        null,
                        null,
                        Map.of(debit, "0.30", credit, "0"),
                        null,
                        Map.of(),
                        "line-kept");
        ApplicationRecords.Aggregate draft =
                runtime.save(
                        documentSave(
                                app,
                                d,
                                null,
                                Map.of(field(d, "name"), "整单草稿"),
                                List.of(first),
                                null),
                        10001);
        assertThat(draft.record().values().get(field(d, "state"))).isEqualTo("draft");
        assertThat(draft.details().get(detail.id()).getFirst().clientRowKey())
                .isEqualTo("line-kept");
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        documentSave(
                                                app, d, draft.record(), Map.of(), null, "register"),
                                        10001))
                .hasMessageContaining("至少需要两行");
        ApplicationRecords.Aggregate unchanged =
                runtime.get(app, d.objectId(), draft.record().id(), 10001);
        assertThat(unchanged.record().revision()).isEqualTo(draft.record().revision());
        assertThat(unchanged.record().values().get(field(d, "state"))).isEqualTo("draft");
        ApplicationRecords.Row old = unchanged.details().get(detail.id()).getFirst();
        ApplicationRecords.Aggregate saved =
                runtime.save(
                        documentSave(
                                app,
                                d,
                                unchanged.record(),
                                Map.of(),
                                List.of(
                                        new Row(
                                                old.id(),
                                                old.revision(),
                                                Map.of(debit, "0.30", credit, "0"),
                                                null,
                                                Map.of(),
                                                "line-kept"),
                                        new Row(
                                                null,
                                                null,
                                                Map.of(debit, "0", credit, "0.30"),
                                                null,
                                                Map.of(),
                                                "line-new")),
                                "register"),
                        10001);
        assertThat(saved.record().values().get(field(d, "state"))).isEqualTo("registered");
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        documentSave(
                                                app,
                                                d,
                                                saved.record(),
                                                Map.of(field(d, "state"), "draft"),
                                                null,
                                                null),
                                        10001))
                .hasMessageContaining("状态由平台动作维护");
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        documentSave(
                                                app,
                                                d,
                                                saved.record(),
                                                Map.of(field(d, "name"), "偷改"),
                                                null,
                                                "reopen"),
                                        10001))
                .hasMessageContaining("当前状态不允许修改");
        assertThatThrownBy(
                        () ->
                                runtime.delete(
                                        new Delete(
                                                app,
                                                d.objectId(),
                                                saved.record().id(),
                                                saved.record().revision()),
                                        10001))
                .hasMessageContaining("当前状态不允许删除");
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        documentSave(
                                                app, d, saved.record(), Map.of(), List.of(), null),
                                        10001))
                .hasMessageContaining("当前状态不允许修改内部明细");
        long before =
                jdbc.queryForObject(
                        "SELECT count(*) FROM public.\"" + d.tableName() + "\" WHERE deleted=0",
                        Long.class);
        ApplicationRecords.Row bad =
                new Row(
                        null,
                        null,
                        Map.of(debit, "-1", credit, "0"),
                        null,
                        Map.of(),
                        "stable-bad-row");
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        documentSave(
                                                app,
                                                d,
                                                null,
                                                Map.of(field(d, "name"), "不能落库"),
                                                List.of(bad),
                                                null),
                                        10001))
                .isInstanceOfSatisfying(
                        com.richuang.os.framework.common.exception.ServiceException.class,
                        error -> {
                            Map<?, ?> details = (Map<?, ?>) error.getDetails();
                            DocumentPolicy.Problem problem =
                                    (DocumentPolicy.Problem)
                                            ((List<?>) details.get("problems")).getFirst();
                            assertThat(problem.clientRowKey()).isEqualTo("stable-bad-row");
                            assertThat(problem.fieldId()).isEqualTo(debit);
                        });
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.\""
                                        + d.tableName()
                                        + "\" WHERE deleted=0",
                                Long.class))
                .isEqualTo(before);
        assertThat(
                        runtime.get(app, d.objectId(), saved.record().id(), 10001)
                                .details()
                                .get(detail.id()))
                .hasSize(2);
    }

    private static Save withRequest(Save command, String key) {
        return new Save(
                command.applicationId(),
                command.objectId(),
                command.id(),
                command.expectedRevision(),
                command.values(),
                command.details(),
                command.relations(),
                command.context(),
                command.formId(),
                key,
                command.actionCode());
    }

    @Test
    void documentReceiptConcurrencyRestoresOriginalResultAndRejectsChangedIntent()
            throws Exception {
        DataCenter.Definition d = object(true);
        String app = enhancementApp(d);
        String key = UUID.randomUUID().toString();
        DataCenter.Detail detail = d.details().getFirst();
        ApplicationRecords.Save command =
                withRequest(
                        new Save(
                                app,
                                d.objectId(),
                                null,
                                null,
                                Map.of(field(d, "name"), "重复点击"),
                                Map.of(
                                        detail.id(),
                                        List.of(
                                                new Row(
                                                        null,
                                                        null,
                                                        Map.of(detail.fields().getFirst().id(), 2),
                                                        null,
                                                        Map.of(),
                                                        "stable-line")))),
                        key);
        assertThat(runtime.receipt(app, d.objectId(), key, 10001).status()).isEqualTo("NOT_FOUND");
        Aggregate first, second;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try (java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Future<ApplicationRecords.Aggregate> a =
                    executor.submit(
                            () -> {
                                start.await();
                                return runtime.save(command, 10001);
                            });
            java.util.concurrent.Future<ApplicationRecords.Aggregate> b =
                    executor.submit(
                            () -> {
                                start.await();
                                return runtime.save(command, 10001);
                            });
            start.countDown();
            first = a.get(20, java.util.concurrent.TimeUnit.SECONDS);
            second = b.get(20, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(second.record().id()).isEqualTo(first.record().id());
        assertThat(second.record().revision()).isEqualTo(first.record().revision());
        assertThat(second.details().get(detail.id()).getFirst().clientRowKey())
                .isEqualTo("stable-line");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.\""
                                        + d.tableName()
                                        + "\" WHERE deleted=0",
                                Long.class))
                .isEqualTo(1L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_document_receipt WHERE"
                                        + " object_id=?",
                                Long.class,
                                Long.valueOf(d.objectId())))
                .isEqualTo(1L);
        ApplicationRecords.Save changed =
                withRequest(
                        new Save(
                                app,
                                d.objectId(),
                                null,
                                null,
                                Map.of(field(d, "name"), "不同内容"),
                                command.details()),
                        key);
        assertThatThrownBy(() -> runtime.save(changed, 10001)).hasMessageContaining("不同的保存内容");
        runtime.save(
                new Save(
                        app,
                        d.objectId(),
                        first.record().id(),
                        first.record().revision(),
                        Map.of(field(d, "name"), "后续修改"),
                        null),
                10001);
        ApplicationRecords.SaveReceipt recovered = runtime.receipt(app, d.objectId(), key, 10001);
        assertThat(recovered.status()).isEqualTo("SUCCEEDED");
        assertThat(recovered.result().record().values().get(field(d, "name"))).isEqualTo("重复点击");
        assertThat(recovered.revision()).isEqualTo(first.record().revision());
        assertThatThrownBy(() -> runtime.receipt(app, d.objectId(), key, 10002));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_record_history WHERE"
                                        + " object_id=? AND operation_id=CAST(? AS uuid)",
                                Long.class,
                                Long.valueOf(d.objectId()),
                                recovered.operationId()))
                .isEqualTo(1L);
    }

    @Test
    void documentReceiptFailureRollsBackMainDetailsHistoryAndReceipt() {
        DataCenter.Definition d = object(true);
        String app = enhancementApp(d), key = UUID.randomUUID().toString();
        DataCenter.Detail detail = d.details().getFirst();
        ApplicationRecords.Save command =
                withRequest(
                        new Save(
                                app,
                                d.objectId(),
                                null,
                                null,
                                Map.of(field(d, "name"), "故障回滚"),
                                Map.of(
                                        detail.id(),
                                        List.of(
                                                new Row(
                                                        null,
                                                        null,
                                                        Map.of(
                                                                detail.fields().getFirst().id(),
                                                                1))))),
                        key);
        writeFailure.failAfter("INSERT INTO public.nocode_document_receipt");
        try {
            assertThatThrownBy(() -> runtime.save(command, 10001));
        } finally {
            writeFailure.clear();
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.\"" + d.tableName() + "\"",
                                Long.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.\"" + detail.tableName() + "\"",
                                Long.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_record_history WHERE"
                                        + " object_id=?",
                                Long.class,
                                Long.valueOf(d.objectId())))
                .isZero();
        assertThat(runtime.receipt(app, d.objectId(), key, 10001).status()).isEqualTo("NOT_FOUND");
        ApplicationRecords.Aggregate saved = runtime.save(command, 10001);
        assertThat(runtime.receipt(app, d.objectId(), key, 10001).recordId())
                .isEqualTo(saved.record().id());
        ApplicationRecords.Row line = saved.details().get(detail.id()).getFirst();
        ApplicationRecords.Aggregate changed =
                runtime.save(
                        new Save(
                                app,
                                d.objectId(),
                                saved.record().id(),
                                saved.record().revision(),
                                Map.of(),
                                Map.of(
                                        detail.id(),
                                        List.of(
                                                new Row(
                                                        line.id(),
                                                        line.revision(),
                                                        Map.of(
                                                                detail.fields().getFirst().id(),
                                                                7))))),
                        10001);
        assertThat(changed.record().revision()).isNotEqualTo(saved.record().revision());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_record_history WHERE"
                                        + " object_id=? AND operation='UPDATE'",
                                Long.class,
                                Long.valueOf(d.objectId())))
                .isEqualTo(1L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT after_json->'details'->?->?->>? FROM"
                                        + " public.nocode_record_history WHERE object_id=? AND"
                                        + " operation='UPDATE'",
                                String.class,
                                detail.id(),
                                line.id(),
                                detail.fields().getFirst().id(),
                                Long.valueOf(d.objectId())))
                .isEqualTo("7");
    }

    @Test
    void documentOrderPersistsAndConcurrentDetailEditsCannotOverwrite() throws Exception {
        DataCenter.Definition d = object(true);
        String app = enhancementApp(d);
        DataCenter.Detail detail = d.details().getFirst();
        String quantity = detail.fields().getFirst().id();
        ApplicationRecords.Aggregate saved =
                runtime.save(
                        new Save(
                                app,
                                d.objectId(),
                                null,
                                null,
                                Map.of(field(d, "name"), "排序与并发"),
                                Map.of(
                                        detail.id(),
                                        List.of(
                                                new Row(null, null, Map.of(quantity, 1)),
                                                new Row(null, null, Map.of(quantity, 2))))),
                        10001);
        ArrayList<ApplicationRecords.Row> reversed =
                new ArrayList<>(saved.details().get(detail.id()));
        Collections.reverse(reversed);
        ApplicationRecords.Aggregate reordered =
                runtime.save(
                        new Save(
                                app,
                                d.objectId(),
                                saved.record().id(),
                                saved.record().revision(),
                                Map.of(),
                                Map.of(
                                        detail.id(),
                                        reversed.stream()
                                                .map(r -> new Row(r.id(), r.revision(), r.values()))
                                                .toList())),
                        10001);
        ApplicationRecords.Aggregate read =
                runtime.get(app, d.objectId(), saved.record().id(), 10001);
        assertThat(read.details().get(detail.id()).stream().map(Row::id).toList())
                .containsExactly(reversed.get(0).id(), reversed.get(1).id());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_record_history WHERE"
                                        + " object_id=? AND operation='UPDATE'",
                                Long.class,
                                Long.valueOf(d.objectId())))
                .isEqualTo(1L);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try (java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newFixedThreadPool(2)) {
            List<java.util.concurrent.Future<Boolean>> writes = new ArrayList<>();
            for (int number : List.of(11, 22))
                writes.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    try {
                                        runtime.save(
                                                new Save(
                                                        app,
                                                        d.objectId(),
                                                        read.record().id(),
                                                        read.record().revision(),
                                                        Map.of(),
                                                        Map.of(
                                                                detail.id(),
                                                                List.of(
                                                                        new Row(
                                                                                read.details()
                                                                                        .get(
                                                                                                detail
                                                                                                        .id())
                                                                                        .getFirst()
                                                                                        .id(),
                                                                                read.details()
                                                                                        .get(
                                                                                                detail
                                                                                                        .id())
                                                                                        .getFirst()
                                                                                        .revision(),
                                                                                Map.of(
                                                                                        quantity,
                                                                                        number))))),
                                                10001);
                                        return true;
                                    } catch (
                                            com.richuang.os.framework.common.exception
                                                            .ServiceException
                                                    conflict) {
                                        assertThat(conflict.getCode())
                                                .isEqualTo(
                                                        com.richuang.os.nocode.api.NocodeErrorCodes
                                                                .CONFLICT);
                                        return false;
                                    }
                                }));
            start.countDown();
            assertThat(
                            List.of(
                                    writes.get(0).get(20, java.util.concurrent.TimeUnit.SECONDS),
                                    writes.get(1).get(20, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        ApplicationRecords.Aggregate after =
                runtime.get(app, d.objectId(), reordered.record().id(), 10001);
        assertThat(after.details().get(detail.id())).hasSize(1);
        assertThat(after.record().revision()).isNotEqualTo(read.record().revision());
    }

    private String enhancementApp(DataCenter.Definition... definitions) {
        List<ApplicationCenter.ObjectReference> refs =
                Arrays.stream(definitions)
                        .map(
                                d ->
                                        servicesContext
                                                .getBean(DataObjectApi.class)
                                                .getVersion(d.objectId(), null))
                        .map(
                                v ->
                                        new ApplicationCenter.ObjectReference(
                                                v.objectId(), v.versionNo(), v.checksum()))
                        .toList();
        ApplicationCenter.Detail a =
                applications.save(
                        new ApplicationCenter.Save(
                                null,
                                null,
                                fixture.prefix + "enhanceapp" + serial++,
                                "计算验收",
                                null,
                                null,
                                new ApplicationCenter.Definition(refs, List.of())),
                        10001);
        grantApplicationObjects(a.application().id());
        applications.publish(new ApplicationCenter.Revision(a.application().id(), 0, "验收"), 10001);
        return a.application().id();
    }

    private Query enhancementQuery(String app, DataCenter.Definition d) {
        return new Query(app, d.objectId(), 1, 20, null, Map.of(), null, false);
    }

    private DataScope scope(String field, String operator, Object value) {
        return new DataScope(
                "AND", List.of(new DataScope.Condition(field, operator, value)), List.of());
    }

    private CalculationOptions tableWideOptions(
            String mode,
            String aggregate,
            List<String> groups,
            CalculationOptions.RunningTotal running) {
        return new CalculationOptions(
                mode,
                "LIVE",
                null,
                null,
                "COUNT".equals(aggregate) ? null : "incoming",
                aggregate,
                "AND",
                List.of(),
                false,
                groups,
                running);
    }

    private DataCenter.Design tableWideDesign(Map<String, CalculationOptions> calculations) {
        ArrayList<FieldDefinition> fields =
                new ArrayList<>(
                        List.of(
                                enhancementField("account", "TEXT"),
                                enhancementField("currency", "TEXT"),
                                enhancementField("booked", "DATE"),
                                enhancementField("sequence", "INTEGER"),
                                enhancementField("incoming", "DECIMAL"),
                                enhancementField("outgoing", "DECIMAL"),
                                enhancementField("opening", "DECIMAL"),
                                enhancementField("included", "BOOLEAN"),
                                enhancementField("memo", "TEXTAREA")));
        HashMap<String, DataCenter.FieldOptions> options =
                new HashMap<String, DataCenter.FieldOptions>();
        calculations.forEach(
                (code, config) -> {
                    fields.add(enhancementField(code, "FORMULA"));
                    options.put(
                            code,
                            calculation(
                                    null,
                                    "COUNT".equals(config.aggregate()) ? "INTEGER" : "DECIMAL",
                                    config));
                });
        return enhancementDesign(fields, options);
    }

    private void tableWideGrant(String app, DataCenter.Definition d) {
        var grant = NocodeIntegrationSupport.resolvedPermission(d.objectId(), app);
        share(
                app,
                d,
                new ApplicationAuthorization.ObjectGrant(
                        grant.objectId(),
                        grant.actions(),
                        grant.scope(),
                        grant.readFields(),
                        grant.writeFields(),
                        grant.readDetails(),
                        grant.writeDetails(),
                        grant.readRelations(),
                        grant.writeRelations(),
                        Map.of(),
                        d.fields().stream()
                                .filter(f -> !"FORMULA".equals(f.type()))
                                .map(FieldDefinition::id)
                                .collect(java.util.stream.Collectors.toSet())));
    }

    private Map<String, Object> tableWideValues(
            DataCenter.Definition d, Map<String, Object> values) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        values.forEach((code, value) -> result.put(field(d, code), value));
        return result;
    }

    private Row tableWideRow(String app, DataCenter.Definition d, Map<String, Object> values) {
        return runtime.save(
                        new Save(app, d.objectId(), null, null, tableWideValues(d, values), null),
                        10001)
                .record();
    }

    @Test
    void tableWideStatisticsAggregateAllRowsBeyondLookupLimitAndRefresh() {
        LinkedHashMap<String, CalculationOptions> configs =
                new LinkedHashMap<String, CalculationOptions>();
        for (String aggregate : List.of("COUNT", "SUM", "AVG", "MIN", "MAX"))
            configs.put(
                    "stat_" + aggregate.toLowerCase(Locale.ROOT),
                    tableWideOptions("STATISTICS", aggregate, List.of(), null));
        configs.put(
                "group_sum",
                tableWideOptions("STATISTICS", "SUM", List.of("account", "currency"), null));
        configs.put(
                "filtered_sum",
                new CalculationOptions(
                        "STATISTICS",
                        "LIVE",
                        null,
                        null,
                        "incoming",
                        "SUM",
                        "AND",
                        List.of(new CalculationOptions.Match("included", "eq", null, true)),
                        false,
                        List.of(),
                        null));
        configs.put(
                "others",
                new CalculationOptions(
                        "STATISTICS",
                        "LIVE",
                        null,
                        null,
                        null,
                        "COUNT",
                        "AND",
                        List.of(),
                        true,
                        List.of(),
                        null));
        DataCenter.Definition d = publish(tableWideDesign(configs));
        String app = app(d);
        tableWideGrant(app, d);
        jdbc.execute(
                "INSERT INTO public.\""
                        + d.tableName()
                        + "\" (name, account, currency, booked, sequence, incoming, outgoing,"
                        + " opening, included, creator, updater, create_time, update_time, deleted)"
                        + " SELECT '统计行' || n, CASE WHEN n <= 300 THEN 'A' ELSE 'B' END, 'CNY',"
                        + " DATE '2026-09-01', n, 0.1, 0, 0, n <= 300, '10001', '10001',"
                        + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0 FROM generate_series(1, 600)"
                        + " n");
        com.richuang.os.framework.common.pojo.PageResult<ApplicationRecords.Row> page =
                runtime.page(
                        new Query(
                                app,
                                d.objectId(),
                                2,
                                1,
                                null,
                                Map.of(),
                                field(d, "sequence"),
                                true),
                        10001);
        assertThat(page.getTotal()).isEqualTo(600);
        ApplicationRecords.Row row = page.getList().getFirst();
        assertThat(row.values())
                .containsEntry(field(d, "stat_count"), "600")
                .containsEntry(field(d, "stat_sum"), "60.0000000000")
                .containsEntry(field(d, "stat_avg"), "0.1000000000")
                .containsEntry(field(d, "stat_min"), "0.1000000000")
                .containsEntry(field(d, "stat_max"), "0.1000000000")
                .containsEntry(field(d, "group_sum"), "30.0000000000")
                .containsEntry(field(d, "filtered_sum"), "30.0000000000")
                .containsEntry(field(d, "others"), "599");
        ApplicationRecords.Row changed =
                runtime.save(
                                new Save(
                                        app,
                                        d.objectId(),
                                        row.id(),
                                        row.revision(),
                                        Map.of(field(d, "incoming"), "0.2"),
                                        null),
                                10001)
                        .record();
        assertThat(changed.values()).containsEntry(field(d, "stat_sum"), "60.1000000000");
        runtime.delete(new Delete(app, d.objectId(), changed.id(), changed.revision()), 10001);
        assertThat(runtime.page(enhancementQuery(app, d), 10001).getList().getFirst().values())
                .containsEntry(field(d, "stat_sum"), "59.9000000000");
    }

    @Test
    void publishedLiveRunningBalanceCanChangeOrderWithoutReplacingItsColumn() {
        CalculationOptions original =
                tableWideOptions(
                        "RUNNING_TOTAL",
                        "SUM",
                        List.of("account"),
                        new CalculationOptions.RunningTotal("booked", null, "outgoing", "0", null));
        DataCenter.Definition published = publish(tableWideDesign(Map.of("balance", original)));
        DataCenter.Design design =
                designs.editPublished(
                        new DataCenter.Revision(
                                published.objectId(),
                                designs.get(published.objectId()).draft().lockVersion(),
                                "调整实时累计排序"),
                        10001);
        String balanceId = field(published, "balance");
        CalculationOptions changed =
                tableWideOptions(
                        "RUNNING_TOTAL",
                        "SUM",
                        List.of("account"),
                        new CalculationOptions.RunningTotal(
                                "booked", "sequence", "outgoing", "0", null));
        Map<String, DataCenter.FieldOptions> options = new HashMap<>(design.fieldOptions());
        options.put(balanceId, options.get(balanceId).withCalculation(changed));
        DataCenter.Design draft =
                designs.save(
                        new DataCenter.SaveDesign(
                                fixture.edit(
                                        design.draft(),
                                        design.draft().fields(),
                                        List.of(),
                                        design.draft().titleFieldId()),
                                design.settings(),
                                options,
                                design.relations(),
                                design.indexes(),
                                design.details(),
                                design.mainBinding()),
                        10001);
        DataCenter.PublishPlan plan =
                publisher.plan(
                        new DataCenter.Revision(
                                published.objectId(), draft.draft().lockVersion(), null),
                        10001);
        assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "调整实时累计排序"), 10001)
                                .state())
                .isEqualTo("SUCCEEDED");
        assertThat(
                        servicesContext
                                .getBean(DataObjectApi.class)
                                .getPublished(published.objectId())
                                .fieldOptions()
                                .get(balanceId)
                                .calculation())
                .isEqualTo(changed);
    }

    @Test
    void tableWideRunningBalancesUseFixedOrderGroupsAndHistoricalChanges() {
        CalculationOptions c =
                tableWideOptions(
                        "RUNNING_TOTAL",
                        "SUM",
                        List.of("account", "currency"),
                        new CalculationOptions.RunningTotal(
                                "booked", "sequence", "outgoing", "100", null));
        DataCenter.Definition d = publish(tableWideDesign(Map.of("balance", c)));
        String app = app(d);
        tableWideGrant(app, d);
        ApplicationRecords.Row late =
                tableWideRow(
                        app,
                        d,
                        Map.of(
                                "name",
                                "后发生",
                                "account",
                                "A",
                                "currency",
                                "CNY",
                                "booked",
                                "2026-09-02",
                                "sequence",
                                2,
                                "incoming",
                                "0.2",
                                "outgoing",
                                "20"));
        ApplicationRecords.Row early =
                tableWideRow(
                        app,
                        d,
                        Map.of(
                                "name",
                                "补录首笔",
                                "account",
                                "A",
                                "currency",
                                "CNY",
                                "booked",
                                "2026-09-01",
                                "sequence",
                                1,
                                "incoming",
                                "10.1"));
        ApplicationRecords.Row other =
                tableWideRow(
                        app,
                        d,
                        Map.of(
                                "name",
                                "其他账户",
                                "account",
                                "B",
                                "currency",
                                "CNY",
                                "booked",
                                "2026-09-01",
                                "sequence",
                                1,
                                "incoming",
                                "900"));
        ApplicationRecords.Row foreign =
                tableWideRow(
                        app,
                        d,
                        Map.of(
                                "name",
                                "其他币种",
                                "account",
                                "A",
                                "currency",
                                "USD",
                                "booked",
                                "2026-09-01",
                                "sequence",
                                1,
                                "incoming",
                                "50"));
        assertThat(early.values()).containsEntry(field(d, "balance"), "110.1000000000");
        assertThat(other.values()).containsEntry(field(d, "balance"), "1000.0000000000");
        assertThat(foreign.values()).containsEntry(field(d, "balance"), "150.0000000000");
        assertThat(runtime.get(app, d.objectId(), late.id(), 10001).record().values())
                .containsEntry(field(d, "balance"), "90.3000000000");
        com.richuang.os.framework.common.pojo.PageResult<ApplicationRecords.Row> filtered =
                runtime.page(
                        new Query(
                                app, d.objectId(), 1, 1, "后发生", Map.of(), field(d, "booked"), true),
                        10001);
        assertThat(filtered.getList().getFirst().values())
                .containsEntry(field(d, "balance"), "90.3000000000");
        early =
                runtime.save(
                                new Save(
                                        app,
                                        d.objectId(),
                                        early.id(),
                                        early.revision(),
                                        tableWideValues(d, Map.of("incoming", "30.1")),
                                        null),
                                10001)
                        .record();
        assertThat(runtime.get(app, d.objectId(), late.id(), 10001).record().values())
                .containsEntry(field(d, "balance"), "110.3000000000");
        early =
                runtime.save(
                                new Save(
                                        app,
                                        d.objectId(),
                                        early.id(),
                                        early.revision(),
                                        tableWideValues(d, Map.of("booked", "2026-09-03")),
                                        null),
                                10001)
                        .record();
        assertThat(runtime.get(app, d.objectId(), late.id(), 10001).record().values())
                .containsEntry(field(d, "balance"), "80.2000000000");
        runtime.delete(new Delete(app, d.objectId(), early.id(), early.revision()), 10001);
        assertThat(runtime.get(app, d.objectId(), late.id(), 10001).record().values())
                .containsEntry(field(d, "balance"), "80.2000000000");
        ApplicationRecords.Row sameOrder =
                tableWideRow(
                        app,
                        d,
                        Map.of(
                                "name",
                                "相同时序",
                                "account",
                                "A",
                                "currency",
                                "CNY",
                                "booked",
                                "2026-09-02",
                                "sequence",
                                2,
                                "incoming",
                                "0.3"));
        assertThat(sameOrder.values()).containsEntry(field(d, "balance"), "80.5000000000");
        assertThat(runtime.get(app, d.objectId(), late.id(), 10001).record().values())
                .containsEntry(field(d, "balance"), "80.2000000000");
    }

    @Test
    void tableWidePreviewReplacesCandidateAndInitialFieldIsOnlyAddedOnce() {
        CalculationOptions c =
                new CalculationOptions(
                        "RUNNING_TOTAL",
                        "LIVE",
                        null,
                        null,
                        "incoming",
                        "SUM",
                        "AND",
                        List.of(new CalculationOptions.Match("included", "eq", null, true)),
                        false,
                        List.of("account"),
                        new CalculationOptions.RunningTotal(
                                "sequence", null, "outgoing", null, "opening"));
        DataCenter.Definition d =
                publish(
                        tableWideDesign(
                                Map.of(
                                        "balance",
                                        c,
                                        "total",
                                        tableWideOptions(
                                                "STATISTICS", "SUM", List.of("account"), null))));
        String app = app(d);
        tableWideGrant(app, d);
        com.richuang.os.nocode.runtime.service.record.RecordCalculations calculator =
                servicesContext.getBean(
                        com.richuang.os.nocode.runtime.service.record.RecordCalculations.class);
        Map<String, Object> draft =
                tableWideValues(
                        d,
                        Map.of(
                                "name",
                                "候选首笔",
                                "sequence",
                                1,
                                "opening",
                                "1000",
                                "incoming",
                                "0.1",
                                "included",
                                true));
        assertThat(calculator.preview(app, d, null, draft, Map.of(), 10001))
                .containsEntry(field(d, "balance"), "1000.1000000000")
                .containsEntry(field(d, "total"), "0.1000000000");
        assertThat(runtime.page(enhancementQuery(app, d), 10001).getTotal()).isZero();
        ApplicationRecords.Row first =
                tableWideRow(
                        app,
                        d,
                        Map.of(
                                "name",
                                "首笔",
                                "sequence",
                                1,
                                "opening",
                                "1000",
                                "incoming",
                                "0.1",
                                "included",
                                true));
        ApplicationRecords.Row second =
                tableWideRow(
                        app,
                        d,
                        Map.of(
                                "name",
                                "次笔",
                                "sequence",
                                2,
                                "opening",
                                "9999",
                                "incoming",
                                "0.2",
                                "included",
                                true));
        assertThat(second.values()).containsEntry(field(d, "balance"), "1000.3000000000");
        ApplicationRecords.Row excluded =
                tableWideRow(
                        app,
                        d,
                        Map.of(
                                "name",
                                "未入账",
                                "sequence",
                                0,
                                "opening",
                                "9000",
                                "incoming",
                                "100",
                                "included",
                                false));
        assertThat(excluded.values()).containsEntry(field(d, "balance"), null);
        HashMap<String, Object> candidate = new HashMap<>(first.values());
        candidate.put(field(d, "incoming"), "0.5");
        assertThat(calculator.preview(app, d, first.id(), candidate, Map.of(), 10001))
                .containsEntry(field(d, "balance"), "1000.5000000000")
                .containsEntry(field(d, "total"), "100.7000000000");
        assertThat(runtime.get(app, d.objectId(), first.id(), 10001).record().values())
                .containsEntry(field(d, "balance"), "1000.1000000000");
    }

    @Test
    void tableWideRejectsUnsafeConfigurationsAndPreservesExistingJson() throws Exception {
        String legacy =
                "{\"mode\":\"LOCAL\",\"updateMode\":\"LIVE\",\"targetObjectId\":null,\"relationId\":null,"
                    + "\"targetField\":null,\"aggregate\":null,\"logic\":null,\"conditions\":[],\"excludeCurrent\":false}";
        com.fasterxml.jackson.databind.ObjectMapper json =
                new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(
                        json.readTree(
                                json.writeValueAsString(
                                        json.readValue(legacy, CalculationOptions.class))))
                .isEqualTo(json.readTree(legacy));
        CalculationOptions.RunningTotal running =
                new CalculationOptions.RunningTotal("booked", null, "outgoing", "0", null);
        // 顺序累计已支持同组联动落库，ON_SAVE 本身是合法配置，仍保留以下不安全组合的拒绝检查。
        CalculationOptions stored =
                new CalculationOptions(
                        "RUNNING_TOTAL",
                        "ON_SAVE",
                        null,
                        null,
                        "incoming",
                        "SUM",
                        "AND",
                        List.of(),
                        false,
                        List.of(),
                        running);
        DataCenter.Design accepted = tableWideDesign(Map.of("result", stored));
        String resultId =
                accepted.draft().fields().stream()
                        .filter(item -> "result".equals(item.code()))
                        .findFirst()
                        .orElseThrow()
                        .id();
        assertThat(accepted.fieldOptions().get(resultId).calculation()).isEqualTo(stored);
        List<CalculationOptions> invalids =
                List.of(
                        new CalculationOptions(
                                "RUNNING_TOTAL",
                                "LIVE",
                                null,
                                null,
                                "incoming",
                                "SUM",
                                "AND",
                                List.of(),
                                true,
                                List.of(),
                                running),
                        new CalculationOptions(
                                "RUNNING_TOTAL",
                                "LIVE",
                                null,
                                null,
                                "incoming",
                                "SUM",
                                "AND",
                                List.of(),
                                false,
                                List.of(),
                                new CalculationOptions.RunningTotal(
                                        "booked", null, "outgoing", "1", "opening")),
                        new CalculationOptions(
                                "RUNNING_TOTAL",
                                "LIVE",
                                null,
                                null,
                                "incoming",
                                "SUM",
                                "AND",
                                List.of(
                                        new CalculationOptions.Match(
                                                "account", "eq", "account", null)),
                                false,
                                List.of(),
                                running),
                        tableWideOptions("STATISTICS", "SUM", List.of("account", "account"), null),
                        tableWideOptions(
                                "RUNNING_TOTAL",
                                "SUM",
                                List.of(),
                                new CalculationOptions.RunningTotal(
                                        "account", null, null, "0", null)),
                        tableWideOptions("STATISTICS", "SUM", List.of("missing"), null),
                        tableWideOptions("STATISTICS", "SINGLE", List.of(), null),
                        new CalculationOptions(
                                "STATISTICS",
                                "LIVE",
                                null,
                                null,
                                "name",
                                "SUM",
                                "AND",
                                List.of(),
                                false,
                                List.of(),
                                null));
        for (CalculationOptions invalid : invalids)
            assertThatThrownBy(() -> tableWideDesign(Map.of("result", invalid)))
                    .as("不安全的全表计算配置：%s", invalid)
                    .isInstanceOf(
                            com.richuang.os.framework.common.exception.ServiceException.class);
    }

    @Test
    void tableWideRequiresComputePermissionForOrderingGroupingAndInitialFields() {
        CalculationOptions c =
                tableWideOptions(
                        "RUNNING_TOTAL",
                        "SUM",
                        List.of("account"),
                        new CalculationOptions.RunningTotal(
                                "booked", "sequence", "outgoing", null, "opening"));
        DataCenter.Definition d = publish(tableWideDesign(Map.of("balance", c)));
        String app = app(d);
        // 计算取数不再由人勾选：默认授权（计算取数为空）下照常出值。
        assertThat(NocodeIntegrationSupport.resolvedPermission(d.objectId(), app).computeFields())
                .isEmpty();
        var row =
                tableWideRow(
                        app,
                        d,
                        Map.of(
                                "name",
                                "已授权",
                                "incoming",
                                "1",
                                "opening",
                                "10",
                                "booked",
                                "2026-09-01"));
        var grant = NocodeIntegrationSupport.resolvedPermission(d.objectId(), app);
        // 取数门槛改为「应用对来源对象的授权读得到所需字段」：排序、分组、初始值、金额任一来源字段不在可查看字段里就拒绝，并点名。
        for (String code :
                List.of("account", "booked", "sequence", "opening", "outgoing", "incoming")) {
            var readable = new HashSet<>(grant.readFields());
            readable.remove(field(d, code));
            var writable = new HashSet<>(grant.writeFields());
            writable.remove(field(d, code));
            share(
                    app,
                    d,
                    new ApplicationAuthorization.ObjectGrant(
                            grant.objectId(),
                            grant.actions(),
                            grant.scope(),
                            readable,
                            writable,
                            grant.readDetails(),
                            grant.writeDetails(),
                            grant.readRelations(),
                            grant.writeRelations(),
                            Map.of(),
                            Set.of()));
            assertThatThrownBy(() -> runtime.get(app, d.objectId(), row.id(), 10001))
                    .hasMessageContaining("取数，但现在取不了")
                    .hasMessageContaining("没有把字段「");
        }
        share(app, d, grant);
        assertThat(runtime.get(app, d.objectId(), row.id(), 10001).record().values())
                .containsEntry(field(d, "balance"), "11.0000000000");
    }

    @Test
    void tableWideBalancesBeyondFiveHundredRowsAndNullOrderRemainComplete() {
        DataCenter.Definition d =
                publish(
                        tableWideDesign(
                                Map.of(
                                        "balance",
                                        tableWideOptions(
                                                "RUNNING_TOTAL",
                                                "SUM",
                                                List.of(),
                                                new CalculationOptions.RunningTotal(
                                                        "sequence",
                                                        null,
                                                        "outgoing",
                                                        "0",
                                                        null)))));
        String app = app(d);
        tableWideGrant(app, d);
        jdbc.execute(
                "INSERT INTO public.\""
                        + d.tableName()
                        + "\" (name, sequence, incoming, outgoing, creator, updater, create_time,"
                        + " update_time, deleted) SELECT '累计行' || n, n, 0.1, 0, '10001', '10001',"
                        + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0 FROM generate_series(1, 600)"
                        + " n");
        com.richuang.os.framework.common.pojo.PageResult<ApplicationRecords.Row> page =
                runtime.page(
                        new Query(
                                app,
                                d.objectId(),
                                1,
                                1,
                                null,
                                Map.of(),
                                field(d, "sequence"),
                                true),
                        10001);
        assertThat(page.getList().getFirst().values())
                .containsEntry(field(d, "balance"), "60.0000000000");
        ApplicationRecords.Row noOrder =
                tableWideRow(app, d, Map.of("name", "空排序最后", "incoming", "0.2"));
        assertThat(noOrder.values()).containsEntry(field(d, "balance"), "60.2000000000");
        ApplicationRecords.Row earlier =
                runtime.page(
                                new Query(
                                        app,
                                        d.objectId(),
                                        1,
                                        1,
                                        null,
                                        Map.of(),
                                        field(d, "sequence"),
                                        false),
                                10001)
                        .getList()
                        .getFirst();
        assertThat(earlier.values()).containsEntry(field(d, "balance"), "0.1000000000");
    }

    @Test
    void tableWideStatisticsSupportLocalFormulaSnapshotsAndGroupReassignment() {
        CalculationOptions statistics =
                tableWideOptions("STATISTICS", "SUM", List.of("account"), null);
        CalculationOptions snapshot =
                new CalculationOptions(
                        "STATISTICS",
                        "ON_SAVE",
                        null,
                        null,
                        "incoming",
                        "SUM",
                        "AND",
                        List.of(),
                        false,
                        List.of("account"),
                        null);
        CalculationOptions running =
                tableWideOptions(
                        "RUNNING_TOTAL",
                        "SUM",
                        List.of("account"),
                        new CalculationOptions.RunningTotal(
                                "sequence", null, "outgoing", "0", null));
        DataCenter.Design original =
                tableWideDesign(
                        Map.of("total", statistics, "snapshot", snapshot, "balance", running));
        ArrayList<FieldDefinition> fields = new ArrayList<>(original.draft().fields());
        fields.add(enhancementField("ratio", "FORMULA"));
        HashMap<String, DataCenter.FieldOptions> options = new HashMap<>(original.fieldOptions());
        options.put(
                "ratio",
                calculation(
                        "incoming / coalesce(total, 1)",
                        "DECIMAL",
                        new CalculationOptions(
                                "LOCAL", "LIVE", null, null, null, null, null, List.of(), false,
                                List.of(), null)));
        DataCenter.Definition d =
                publish(
                        designs.save(
                                new DataCenter.SaveDesign(
                                        fixture.edit(
                                                original.draft(),
                                                fields,
                                                List.of(),
                                                original.draft().titleFieldId()),
                                        original.settings(),
                                        options,
                                        original.relations(),
                                        original.indexes(),
                                        original.details()),
                                10001));
        String app = app(d);
        tableWideGrant(app, d);
        ApplicationRecords.Row first =
                tableWideRow(
                        app,
                        d,
                        Map.of("name", "A首笔", "account", "A", "sequence", 1, "incoming", "10"));
        ApplicationRecords.Row second =
                tableWideRow(
                        app,
                        d,
                        Map.of("name", "A次笔", "account", "A", "sequence", 2, "incoming", "30"));
        assertThat(runtime.get(app, d.objectId(), first.id(), 10001).record().values())
                .containsEntry(field(d, "total"), "40.0000000000")
                .containsEntry(field(d, "snapshot"), "10.0000000000")
                .containsEntry(field(d, "ratio"), "0.2500000000");
        assertThat(second.values()).containsEntry(field(d, "balance"), "40.0000000000");
        ApplicationRecords.Row moved =
                runtime.save(
                                new Save(
                                        app,
                                        d.objectId(),
                                        first.id(),
                                        first.revision(),
                                        Map.of(field(d, "account"), "B"),
                                        null),
                                10001)
                        .record();
        assertThat(moved.values()).containsEntry(field(d, "balance"), "10.0000000000");
        assertThat(runtime.get(app, d.objectId(), second.id(), 10001).record().values())
                .containsEntry(field(d, "balance"), "30.0000000000")
                .containsEntry(field(d, "total"), "30.0000000000")
                .containsEntry(field(d, "snapshot"), "40.0000000000");
    }

    @Test
    void tableWideStatisticsHandleEmptySetsDynamicMatchesAndOrConditions() {
        LinkedHashMap<String, CalculationOptions> configs =
                new LinkedHashMap<String, CalculationOptions>();
        for (String aggregate : List.of("COUNT", "SUM", "AVG", "MIN", "MAX"))
            configs.put(
                    "empty_" + aggregate.toLowerCase(Locale.ROOT),
                    new CalculationOptions(
                            "STATISTICS",
                            "LIVE",
                            null,
                            null,
                            "COUNT".equals(aggregate) ? null : "incoming",
                            aggregate,
                            "AND",
                            List.of(new CalculationOptions.Match("included", "eq", null, true)),
                            false,
                            List.of(),
                            null));
        configs.put(
                "dynamic",
                new CalculationOptions(
                        "STATISTICS",
                        "LIVE",
                        null,
                        null,
                        "incoming",
                        "SUM",
                        "AND",
                        List.of(new CalculationOptions.Match("account", "eq", "account", null)),
                        false,
                        List.of(),
                        null));
        configs.put(
                "union_total",
                new CalculationOptions(
                        "STATISTICS",
                        "LIVE",
                        null,
                        null,
                        "incoming",
                        "SUM",
                        "OR",
                        List.of(
                                new CalculationOptions.Match("account", "eq", null, "A"),
                                new CalculationOptions.Match("sequence", "gt", null, 1)),
                        false,
                        List.of(),
                        null));
        DataCenter.Definition d = publish(tableWideDesign(configs));
        String app = app(d);
        tableWideGrant(app, d);
        ApplicationRecords.Row first =
                tableWideRow(
                        app,
                        d,
                        Map.of(
                                "name",
                                "A",
                                "account",
                                "A",
                                "sequence",
                                1,
                                "incoming",
                                "0.1",
                                "included",
                                false));
        tableWideRow(
                app,
                d,
                Map.of(
                        "name",
                        "B",
                        "account",
                        "B",
                        "sequence",
                        2,
                        "incoming",
                        "0.2",
                        "included",
                        false));
        Map<String, Object> value =
                runtime.get(app, d.objectId(), first.id(), 10001).record().values();
        assertThat(value)
                .containsEntry(field(d, "empty_count"), "0")
                .containsEntry(field(d, "empty_sum"), "0.0000000000")
                .containsEntry(field(d, "empty_avg"), null)
                .containsEntry(field(d, "empty_min"), null)
                .containsEntry(field(d, "empty_max"), null)
                .containsEntry(field(d, "dynamic"), "0.1000000000")
                .containsEntry(field(d, "union_total"), "0.3000000000");
    }

    @Test
    void tableWideProtectsClassificationAndDeletedGroupingDependencies() {
        DataCenter.Design original =
                tableWideDesign(
                        Map.of(
                                "balance",
                                tableWideOptions(
                                        "RUNNING_TOTAL",
                                        "SUM",
                                        List.of("account"),
                                        new CalculationOptions.RunningTotal(
                                                "sequence", null, "outgoing", null, "opening"))));
        HashMap<String, DataCenter.FieldOptions> options = new HashMap<>(original.fieldOptions());
        String opening =
                original.draft().fields().stream()
                        .filter(f -> f.code().equals("opening"))
                        .findFirst()
                        .orElseThrow()
                        .id();
        Map raw = mapper.convertValue(options.get(opening), Map.class);
        raw.put("classification", "SECRET");
        options.put(opening, mapper.convertValue(raw, DataCenter.FieldOptions.class));
        assertThatThrownBy(
                        () ->
                                designs.save(
                                        new DataCenter.SaveDesign(
                                                fixture.edit(
                                                        original.draft(),
                                                        original.draft().fields(),
                                                        List.of(),
                                                        original.draft().titleFieldId()),
                                                original.settings(),
                                                options,
                                                original.relations(),
                                                original.indexes(),
                                                original.details()),
                                        10001))
                .hasMessageContaining("分类");
        String account =
                original.draft().fields().stream()
                        .filter(f -> f.code().equals("account"))
                        .findFirst()
                        .orElseThrow()
                        .id();
        HashMap<String, DataCenter.FieldOptions> remainingOptions =
                new HashMap<>(original.fieldOptions());
        remainingOptions.remove(account);
        assertThatThrownBy(
                        () ->
                                designs.save(
                                        new DataCenter.SaveDesign(
                                                fixture.edit(
                                                        original.draft(),
                                                        original.draft().fields().stream()
                                                                .filter(
                                                                        f ->
                                                                                !f.id().equals(
                                                                                                account))
                                                                .toList(),
                                                        List.of(account),
                                                        original.draft().titleFieldId()),
                                                original.settings(),
                                                remainingOptions,
                                                original.relations(),
                                                original.indexes(),
                                                original.details()),
                                        10001))
                .hasMessageContaining("不存在或已停用");
    }

    @Test
    void tableWideDynamicMatchRequiresLocalOperandComputePermission() {
        CalculationOptions config =
                new CalculationOptions(
                        "STATISTICS",
                        "LIVE",
                        null,
                        null,
                        "incoming",
                        "SUM",
                        "AND",
                        List.of(new CalculationOptions.Match("account", "eq", "currency", null)),
                        false,
                        List.of(),
                        null);
        DataCenter.Definition d = publish(tableWideDesign(Map.of("total", config)));
        String app = app(d);
        tableWideGrant(app, d);
        ApplicationRecords.Row row =
                tableWideRow(
                        app,
                        d,
                        Map.of("name", "动态条件", "account", "A", "currency", "A", "incoming", "3"));
        assertThat(row.values()).containsEntry(field(d, "total"), "3.0000000000");
        var grant = NocodeIntegrationSupport.resolvedPermission(d.objectId(), app);
        var allowed = new HashSet<>(grant.computeFields());
        var readable = new HashSet<>(grant.readFields());
        var writable = new HashSet<>(grant.writeFields());
        allowed.remove(field(d, "currency"));
        readable.remove(field(d, "currency"));
        writable.remove(field(d, "currency"));
        share(
                app,
                d,
                new ApplicationAuthorization.ObjectGrant(
                        grant.objectId(),
                        grant.actions(),
                        grant.scope(),
                        readable,
                        writable,
                        grant.readDetails(),
                        grant.writeDetails(),
                        grant.readRelations(),
                        grant.writeRelations(),
                        Map.of(),
                        allowed));
        assertThatThrownBy(() -> runtime.get(app, d.objectId(), row.id(), 10001))
                .hasMessageContaining("没有把字段「currency」授给应用");
        share(app, d, grant);
        assertThat(runtime.get(app, d.objectId(), row.id(), 10001).record().values())
                .containsEntry(field(d, "total"), "3.0000000000");
    }

    @Test
    void tableWideGroupsPreserveLongTextAndWhitespaceValues() {
        DataCenter.Definition d =
                publish(
                        tableWideDesign(
                                Map.of(
                                        "total",
                                                tableWideOptions(
                                                        "STATISTICS", "SUM", List.of("memo"), null),
                                        "balance",
                                                tableWideOptions(
                                                        "RUNNING_TOTAL",
                                                        "SUM",
                                                        List.of("memo"),
                                                        new CalculationOptions.RunningTotal(
                                                                "sequence",
                                                                null,
                                                                null,
                                                                "0",
                                                                null)))));
        String app = app(d);
        tableWideGrant(app, d);
        for (String memo : List.of("长文本".repeat(1000), "   ")) {
            ApplicationRecords.Row first =
                    tableWideRow(
                            app,
                            d,
                            Map.of("name", "同组首笔", "memo", memo, "sequence", 1, "incoming", "1"));
            ApplicationRecords.Row second =
                    tableWideRow(
                            app,
                            d,
                            Map.of("name", "同组次笔", "memo", memo, "sequence", 2, "incoming", "2"));
            assertThat(runtime.get(app, d.objectId(), first.id(), 10001).record().values())
                    .containsEntry(field(d, "memo"), memo)
                    .containsEntry(field(d, "total"), "3.0000000000")
                    .containsEntry(field(d, "balance"), "1.0000000000");
            assertThat(second.values())
                    .containsEntry(field(d, "total"), "3.0000000000")
                    .containsEntry(field(d, "balance"), "3.0000000000");
        }
    }

    @Test
    void enhancementFormulasUsePreciseCrossTableSourcesAndSaveSnapshots() {
        DataCenter.Definition source =
                publish(
                        enhancementDesign(
                                List.of(
                                        enhancementField("group_code", "TEXT"),
                                        enhancementField("amount", "DECIMAL")),
                                Map.of()));
        List<CalculationOptions.Match> matches =
                List.of(new CalculationOptions.Match("group_code", "eq", "group_code", null));
        CalculationOptions live =
                new CalculationOptions(
                        "LOOKUP",
                        "LIVE",
                        source.objectId(),
                        null,
                        "amount",
                        "SUM",
                        "AND",
                        matches,
                        false,
                        List.of(),
                        null);
        CalculationOptions snapshot =
                new CalculationOptions(
                        "LOOKUP",
                        "ON_SAVE",
                        source.objectId(),
                        null,
                        "amount",
                        "SUM",
                        "AND",
                        matches,
                        false,
                        List.of(),
                        null);
        CalculationOptions local =
                new CalculationOptions(
                        "LOCAL", "LIVE", null, null, null, null, null, List.of(), false, List.of(),
                        null);
        DataCenter.Definition target =
                publish(
                        enhancementDesign(
                                List.of(
                                        enhancementField("group_code", "TEXT"),
                                        enhancementField("qty", "INTEGER"),
                                        enhancementField("live_sum", "FORMULA"),
                                        enhancementField("saved_sum", "FORMULA"),
                                        enhancementField("total", "FORMULA"),
                                        enhancementField("double_qty", "FORMULA")),
                                Map.of(
                                        "live_sum",
                                        calculation(null, "DECIMAL", live),
                                        "saved_sum",
                                        calculation(null, "DECIMAL", snapshot),
                                        "total",
                                        calculation(
                                                "coalesce(live_sum, 0) * qty", "DECIMAL", local),
                                        "double_qty",
                                        calculation("qty * 2", "INTEGER", null))));
        String app = enhancementApp(source, target);
        assertThat(
                        runtime.model(app, target.objectId(), 10001)
                                .object()
                                .fieldOptions()
                                .get(field(target, "live_sum"))
                                .calculation())
                .isEqualTo(live);
        ApplicationReports.Config reportConfig =
                new ApplicationReports.Config(
                        target.objectId(),
                        List.of(),
                        List.of(new ApplicationReports.Metric("count", "记录数", "COUNT", null)),
                        Map.of(),
                        List.of(),
                        null,
                        "Asia/Shanghai",
                        "METRIC",
                        null,
                        false,
                        20,
                        null);
        publishChainResources(
                app,
                List.of(
                        new ApplicationCenter.Resource(
                                "calculation_report",
                                "REPORT",
                                "calculation_report",
                                "计算明细",
                                mapper.convertValue(reportConfig, Map.class))));
        var sourceGrant = NocodeIntegrationSupport.resolvedPermission(source.objectId(), app);
        share(
                app,
                source,
                new ApplicationAuthorization.ObjectGrant(
                        sourceGrant.objectId(),
                        sourceGrant.actions(),
                        sourceGrant.scope(),
                        sourceGrant.readFields(),
                        sourceGrant.writeFields(),
                        sourceGrant.readDetails(),
                        sourceGrant.writeDetails(),
                        sourceGrant.readRelations(),
                        sourceGrant.writeRelations(),
                        Map.of(),
                        Set.of(field(source, "group_code"), field(source, "amount"))));
        ApplicationRecords.Row first =
                runtime.save(
                                new Save(
                                        app,
                                        source.objectId(),
                                        null,
                                        null,
                                        Map.of(
                                                field(source, "name"),
                                                "一",
                                                field(source, "group_code"),
                                                "A",
                                                field(source, "amount"),
                                                "0.1"),
                                        null),
                                10001)
                        .record();
        runtime.save(
                new Save(
                        app,
                        source.objectId(),
                        null,
                        null,
                        Map.of(
                                field(source, "name"),
                                "二",
                                field(source, "group_code"),
                                "A",
                                field(source, "amount"),
                                "0.2"),
                        null),
                10001);
        runtime.save(
                new Save(
                        app,
                        source.objectId(),
                        null,
                        null,
                        Map.of(
                                field(source, "name"),
                                "其他组",
                                field(source, "group_code"),
                                "B",
                                field(source, "amount"),
                                "99"),
                        null),
                10001);
        ApplicationRecords.Row row =
                runtime.save(
                                new Save(
                                        app,
                                        target.objectId(),
                                        null,
                                        null,
                                        Map.of(
                                                field(target, "name"),
                                                "订单",
                                                field(target, "group_code"),
                                                "A",
                                                field(target, "qty"),
                                                3),
                                        null),
                                10001)
                        .record();
        assertThat(row.values())
                .containsEntry(field(target, "live_sum"), "0.3000000000")
                .containsEntry(field(target, "saved_sum"), "0.3000000000")
                .containsEntry(field(target, "total"), "0.9000000000")
                .containsEntry(field(target, "double_qty"), "6");
        com.richuang.os.framework.common.pojo.PageResult<ApplicationRecords.Row> drill =
                servicesContext
                        .getBean(
                                com.richuang.os.nocode.runtime.service.report
                                        .ApplicationReportService.class)
                        .details(
                                new ApplicationReports.Query(
                                        app,
                                        "calculation_report",
                                        Map.of(),
                                        null,
                                        null,
                                        null,
                                        null,
                                        1,
                                        20),
                                10001);
        assertThat(drill.getList().getFirst().values())
                .containsEntry(field(target, "total"), "0.9000000000");
        first =
                runtime.save(
                                new Save(
                                        app,
                                        source.objectId(),
                                        first.id(),
                                        first.revision(),
                                        Map.of(field(source, "amount"), "0.5"),
                                        null),
                                10001)
                        .record();
        ApplicationRecords.Row reread =
                runtime.get(app, target.objectId(), row.id(), 10001).record();
        assertThat(reread.values())
                .containsEntry(field(target, "live_sum"), "0.7000000000")
                .containsEntry(field(target, "saved_sum"), "0.3000000000")
                .containsEntry(field(target, "total"), "2.1000000000");
        row =
                runtime.save(
                                new Save(
                                        app,
                                        target.objectId(),
                                        row.id(),
                                        reread.revision(),
                                        Map.of(field(target, "qty"), 4),
                                        null),
                                10001)
                        .record();
        assertThat(row.values())
                .containsEntry(field(target, "saved_sum"), "0.7000000000")
                .containsEntry(field(target, "total"), "2.8000000000");
        runtime.delete(new Delete(app, source.objectId(), first.id(), first.revision()), 10001);
        assertThat(runtime.page(enhancementQuery(app, target), 10001).getList().getFirst().values())
                .containsEntry(field(target, "live_sum"), "0.2000000000")
                .containsEntry(field(target, "saved_sum"), "0.7000000000");
        String id = row.id();
        String revision = row.revision();
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                target.objectId(),
                                                id,
                                                revision,
                                                Map.of(field(target, "total"), 999),
                                                null),
                                        10001))
                .hasMessageContaining("字段");
        // 计算取数不再由人勾选：门槛是应用对来源对象的授权读得到所需字段。把来源字段从可查看字段里拿掉 ⇒ 按新文案拒绝并点名。
        var readableSource = new HashSet<>(sourceGrant.readFields());
        readableSource.remove(field(source, "amount"));
        var writableSource = new HashSet<>(sourceGrant.writeFields());
        writableSource.remove(field(source, "amount"));
        share(
                app,
                source,
                new ApplicationAuthorization.ObjectGrant(
                        sourceGrant.objectId(),
                        sourceGrant.actions(),
                        sourceGrant.scope(),
                        readableSource,
                        writableSource,
                        sourceGrant.readDetails(),
                        sourceGrant.writeDetails(),
                        sourceGrant.readRelations(),
                        sourceGrant.writeRelations(),
                        Map.of(),
                        Set.of()));
        assertThatThrownBy(() -> runtime.get(app, target.objectId(), id, 10001))
                .hasMessageContaining("取数，但现在取不了")
                .hasMessageContaining("没有把字段「");
        var edited =
                designs.editPublished(
                        new DataCenter.Revision(
                                target.objectId(),
                                designs.get(target.objectId()).draft().lockVersion(),
                                "计算规则变更"),
                        10001);
        HashMap<String, DataCenter.FieldOptions> changedOptions =
                new HashMap<>(edited.fieldOptions());
        String savedField = field(target, "saved_sum");
        changedOptions.put(savedField, changedOptions.get(savedField).withCalculation(live));
        DataCenter.Design changedDesign =
                designs.save(
                        new DataCenter.SaveDesign(
                                fixture.edit(
                                        edited.draft(),
                                        edited.draft().fields(),
                                        List.of(),
                                        edited.draft().titleFieldId()),
                                edited.settings(),
                                changedOptions,
                                edited.relations(),
                                edited.indexes(),
                                edited.details()),
                        10001);
        assertThat(
                        publisher
                                .plan(
                                        new DataCenter.Revision(
                                                target.objectId(),
                                                changedDesign.draft().lockVersion(),
                                                "计算规则变更"),
                                        10001)
                                .checks())
                .anyMatch(check -> check.blocking() && check.message().contains("新增字段"));
    }

    @Test
    void enhancementRejectsFormulaCyclesAndRollsBackCalculationErrors() {
        CalculationOptions local =
                new CalculationOptions(
                        "LOCAL", "ON_SAVE", null, null, null, null, null, List.of(), false,
                        List.of(), null);
        assertThatThrownBy(
                        () ->
                                enhancementDesign(
                                        List.of(
                                                enhancementField("a", "FORMULA"),
                                                enhancementField("b", "FORMULA")),
                                        Map.of(
                                                "a",
                                                calculation("b + 1", "DECIMAL", local),
                                                "b",
                                                calculation("a + 1", "DECIMAL", local))))
                .hasMessageContaining("循环");
        DataCenter.Definition d =
                publish(
                        enhancementDesign(
                                List.of(
                                        enhancementField("qty", "INTEGER"),
                                        enhancementField("result", "FORMULA")),
                                Map.of("result", calculation("10 / qty", "DECIMAL", local))));
        String app = app(d);
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                d.objectId(),
                                                null,
                                                null,
                                                Map.of(field(d, "name"), "除零", field(d, "qty"), 0),
                                                null),
                                        10001))
                .hasMessageContaining("除数");
        assertThat(runtime.page(enhancementQuery(app, d), 10001).getTotal()).isZero();
        ApplicationRecords.Row row =
                runtime.save(
                                new Save(
                                        app,
                                        d.objectId(),
                                        null,
                                        null,
                                        Map.of(field(d, "name"), "有效", field(d, "qty"), 4),
                                        null),
                                10001)
                        .record();
        assertThat(row.values()).containsEntry(field(d, "result"), "2.5000000000");
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                d.objectId(),
                                                row.id(),
                                                row.revision(),
                                                Map.of(field(d, "qty"), 0),
                                                null),
                                        10001))
                .hasMessageContaining("除数");
        assertThat(runtime.get(app, d.objectId(), row.id(), 10001).record().values())
                .containsEntry(field(d, "qty"), "4")
                .containsEntry(field(d, "result"), "2.5000000000");
    }

    @Test
    void enhancementScopesConstrainPagingExportAndBothSidesOfWrites() {
        DataCenter.Definition d =
                publish(
                        enhancementDesign(
                                List.of(
                                        enhancementField("status", "TEXT"),
                                        enhancementField("amount", "INTEGER")),
                                Map.of()));
        ApplicationReports.Config reportConfig =
                new ApplicationReports.Config(
                        d.objectId(),
                        List.of(),
                        List.of(new ApplicationReports.Metric("count", "记录数", "COUNT", null)),
                        Map.of(),
                        List.of(),
                        null,
                        "Asia/Shanghai",
                        "METRIC",
                        null,
                        false,
                        20,
                        null);
        String
                app =
                        app(
                                d,
                                List.of(
                                        new ApplicationCenter.Resource(
                                                "scope_report",
                                                "REPORT",
                                                "scope_report",
                                                "权限统计",
                                                mapper.convertValue(reportConfig, Map.class)))),
                status = field(d, "status"),
                amount = field(d, "amount"),
                name = field(d, "name");
        ApplicationRecords.Row a =
                runtime.save(
                                new Save(
                                        app,
                                        d.objectId(),
                                        null,
                                        null,
                                        Map.of(name, "可改", status, "A", amount, 50),
                                        null),
                                10001)
                        .record();
        ApplicationRecords.Row b =
                runtime.save(
                                new Save(
                                        app,
                                        d.objectId(),
                                        null,
                                        null,
                                        Map.of(name, "只读", status, "B", amount, 200),
                                        null),
                                10001)
                        .record();
        ApplicationRecords.Row hidden =
                runtime.save(
                                new Save(
                                        app,
                                        d.objectId(),
                                        null,
                                        null,
                                        Map.of(name, "隐藏", status, "C", amount, 10),
                                        null),
                                10001)
                        .record();
        DataScope read =
                new DataScope(
                        "OR",
                        List.of(
                                new DataScope.Condition(status, "eq", "A"),
                                new DataScope.Condition(status, "in", List.of("B"))),
                        List.of());
        ApplicationAuthorization.ObjectGrant grant =
                new ApplicationAuthorization.ObjectGrant(
                        d.objectId(),
                        Set.of("READ", "CREATE", "UPDATE", "DELETE", "EXPORT"),
                        "ALL",
                        Set.of(name, status, amount),
                        Set.of(name, status, amount),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Map.of(
                                "READ",
                                read,
                                "CREATE",
                                scope(status, "eq", "A"),
                                "UPDATE",
                                scope(amount, "lte", 100),
                                "DELETE",
                                scope(status, "eq", "A"),
                                "EXPORT",
                                scope(status, "eq", "B")),
                        Set.of());
        authorize(app, member(grant));
        com.richuang.os.nocode.runtime.service.report.ApplicationReportService reports =
                servicesContext.getBean(
                        com.richuang.os.nocode.runtime.service.report.ApplicationReportService
                                .class);
        ApplicationReports.Query reportQuery =
                new ApplicationReports.Query(
                        app, "scope_report", Map.of(), null, null, null, null, 1, 20);
        assertThat(reports.query(reportQuery, 20002).recordCount()).isEqualTo(2);
        assertThat(reports.export(reportQuery, 20002).recordCount()).isEqualTo(1);
        assertThat(runtime.page(enhancementQuery(app, d), 20002).getTotal()).isEqualTo(2);
        assertThat(runtime.export(enhancementQuery(app, d), 20002))
                .extracting(Row::id)
                .containsExactly(b.id());
        assertThatThrownBy(() -> runtime.get(app, d.objectId(), hidden.id(), 20002))
                .hasMessageContaining("权限");
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                d.objectId(),
                                                b.id(),
                                                b.revision(),
                                                Map.of(name, "越权"),
                                                null),
                                        20002))
                .hasMessageContaining("权限");
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                d.objectId(),
                                                a.id(),
                                                a.revision(),
                                                Map.of(amount, 101),
                                                null),
                                        20002))
                .hasMessageContaining("权限");
        assertThat(runtime.get(app, d.objectId(), a.id(), 10001).record().values())
                .containsEntry(amount, "50");
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                d.objectId(),
                                                null,
                                                null,
                                                Map.of(name, "越界新增", status, "B", amount, 2),
                                                null),
                                        20002))
                .hasMessageContaining("权限");
        assertThat(runtime.page(enhancementQuery(app, d), 10001).getTotal()).isEqualTo(3);
        ApplicationRecords.Row changed =
                runtime.save(
                                new Save(
                                        app,
                                        d.objectId(),
                                        a.id(),
                                        a.revision(),
                                        Map.of(amount, 99),
                                        null),
                                20002)
                        .record();
        assertThat(changed.values()).containsEntry(amount, "99");
        assertThatThrownBy(
                        () ->
                                runtime.delete(
                                        new Delete(app, d.objectId(), b.id(), b.revision()), 20002))
                .hasMessageContaining("权限");
    }

    @Test
    void enhancementFixedMultiDictionaryScopeAndHyperlinksRoundTrip() {
        DataCenter.FieldOptions options =
                new DataCenter.FieldOptions(
                        null,
                        "NORMAL",
                        null,
                        null,
                        null,
                        null,
                        null,
                        "ACTIVE",
                        List.of(
                                new DataCenter.Option("A", "甲", false),
                                new DataCenter.Option("B", "乙", false),
                                new DataCenter.Option("C", "丙", false)),
                        null,
                        null,
                        "NONE",
                        null,
                        false,
                        false);
        DataCenter.Definition d =
                publish(
                        enhancementDesign(
                                List.of(
                                        enhancementField("tags", "MULTI_SELECT"),
                                        enhancementField("link", "URL")),
                                Map.of("tags", options)));
        String tags = field(d, "tags"), link = field(d, "link"), name = field(d, "name");
        ViewQueryOptions query =
                new ViewQueryOptions(
                        List.of(new DataScope.Condition(tags, "containsAny", List.of("A", "B"))),
                        Map.of(tags, List.of("A")),
                        Map.of());
        ApplicationUi.View view =
                new ApplicationUi.View(
                        d.objectId(),
                        List.of(name, tags, link),
                        Map.of(),
                        null,
                        false,
                        10,
                        null,
                        Map.of(),
                        null,
                        null,
                        new ApplicationUi.ViewList(List.of(tags), List.of(), Map.of(), false, null),
                        query);
        String app =
                app(
                        d,
                        List.of(
                                new ApplicationCenter.Resource(
                                        "scope_view",
                                        "VIEW",
                                        "scope_view",
                                        "限定视图",
                                        mapper.convertValue(view, Map.class))));
        runtime.save(
                new Save(
                        app,
                        d.objectId(),
                        null,
                        null,
                        Map.of(
                                name,
                                "甲",
                                tags,
                                List.of("A"),
                                link,
                                Map.of("link", "https://example.com/a?q=1", "text", "官网")),
                        null),
                10001);
        runtime.save(
                new Save(
                        app,
                        d.objectId(),
                        null,
                        null,
                        Map.of(name, "乙丙", tags, List.of("B", "C")),
                        null),
                10001);
        runtime.save(
                new Save(
                        app, d.objectId(), null, null, Map.of(name, "丙", tags, List.of("C")), null),
                10001);
        ApplicationRecords.Query request =
                new Query(app, d.objectId(), 1, 20, null, Map.of(), null, false, "scope_view");
        com.richuang.os.framework.common.pojo.PageResult<ApplicationRecords.Row> page =
                runtime.page(request, 10001);
        assertThat(page.getTotal()).isEqualTo(2);
        assertThat(page.getList().getFirst().values().get(link))
                .isEqualTo(Map.of("link", "https://example.com/a?q=1", "text", "官网"));
        ApplicationRecords.Query none =
                new Query(
                        app,
                        d.objectId(),
                        1,
                        20,
                        null,
                        Map.of(tags, List.of("C")),
                        null,
                        false,
                        "scope_view");
        assertThat(runtime.page(none, 10001).getTotal()).isZero();
        ApplicationRecords.Query any =
                new Query(
                        app,
                        d.objectId(),
                        1,
                        20,
                        null,
                        Map.of(),
                        null,
                        false,
                        "scope_view",
                        null,
                        conditions("AND", List.of(leaf(tags, "containsAny", List.of("B")))));
        assertThat(runtime.page(any, 10001).getTotal()).isEqualTo(1);
        assertThat(query.visible(Set.of(tags), Map.of()).candidates())
                .containsEntry(tags, List.of("A", "B"));
        assertThat(query.visible(Set.of(tags), Map.of()).defaults())
                .containsEntry(tags, List.of("A"));
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                app,
                                                d.objectId(),
                                                null,
                                                null,
                                                Map.of(name, "非法链接", link, "javascript:alert(1)"),
                                                null),
                                        10001))
                .hasMessageContaining("链接");
        assertThat(runtime.page(enhancementQuery(app, d), 10001).getTotal()).isEqualTo(3);
    }

    @Test
    void enhancementRelationCalculationFollowsRebindingAndTargetDeletion() {
        DataCenter.Definition target =
                publish(
                        enhancementDesign(
                                List.of(enhancementField("amount", "DECIMAL")), Map.of()));
        DataCenter.Definition source = relatedObject(target, "REFERENCE", "SET_NULL");
        DataCenter.Design edit =
                designs.editPublished(
                        new DataCenter.Revision(
                                source.objectId(),
                                designs.get(source.objectId()).draft().lockVersion(),
                                "计算"),
                        10001);
        ArrayList<FieldDefinition> fields = new ArrayList<>(edit.draft().fields());
        fields.add(enhancementField("related_amount", "FORMULA"));
        HashMap<String, DataCenter.FieldOptions> options = new HashMap<>(edit.fieldOptions());
        DataCenter.Relation relation = source.relations().getFirst();
        options.put(
                "related_amount",
                calculation(
                        null,
                        "DECIMAL",
                        new CalculationOptions(
                                "RELATION",
                                "LIVE",
                                null,
                                relation.id(),
                                "amount",
                                "SINGLE",
                                "AND",
                                List.of(),
                                false,
                                List.of(),
                                null)));
        source =
                publish(
                        designs.save(
                                new DataCenter.SaveDesign(
                                        fixture.edit(
                                                edit.draft(),
                                                fields,
                                                List.of(),
                                                edit.draft().titleFieldId()),
                                        edit.settings(),
                                        options,
                                        edit.relations(),
                                        edit.indexes(),
                                        edit.details()),
                                10001));
        String app = enhancementApp(target, source);
        var permission = NocodeIntegrationSupport.resolvedPermission(target.objectId(), app);
        share(
                app,
                target,
                new ApplicationAuthorization.ObjectGrant(
                        permission.objectId(),
                        permission.actions(),
                        permission.scope(),
                        permission.readFields(),
                        permission.writeFields(),
                        permission.readDetails(),
                        permission.writeDetails(),
                        permission.readRelations(),
                        permission.writeRelations(),
                        Map.of(),
                        Set.of(field(target, "amount"))));
        ApplicationRecords.Row first =
                runtime.save(
                                new Save(
                                        app,
                                        target.objectId(),
                                        null,
                                        null,
                                        Map.of(
                                                field(target, "name"),
                                                "一",
                                                field(target, "amount"),
                                                "12.34"),
                                        null),
                                10001)
                        .record();
        ApplicationRecords.Row second =
                runtime.save(
                                new Save(
                                        app,
                                        target.objectId(),
                                        null,
                                        null,
                                        Map.of(
                                                field(target, "name"),
                                                "二",
                                                field(target, "amount"),
                                                "25.01"),
                                        null),
                                10001)
                        .record();
        ApplicationRecords.Row row =
                runtime.save(
                                new Save(
                                        app,
                                        source.objectId(),
                                        null,
                                        null,
                                        Map.of(
                                                field(source, "name"),
                                                "关联记录",
                                                relation.fieldId(),
                                                first.id()),
                                        null),
                                10001)
                        .record();
        assertThat(row.values()).containsEntry(field(source, "related_amount"), "12.3400000000");
        row =
                runtime.save(
                                new Save(
                                        app,
                                        source.objectId(),
                                        row.id(),
                                        row.revision(),
                                        Map.of(relation.fieldId(), second.id()),
                                        null),
                                10001)
                        .record();
        assertThat(row.values()).containsEntry(field(source, "related_amount"), "25.0100000000");
        runtime.delete(new Delete(app, target.objectId(), second.id(), second.revision()), 10001);
        assertThat(runtime.get(app, source.objectId(), row.id(), 10001).record().values())
                .containsEntry(field(source, "related_amount"), null);
    }

    @Test
    void enhancementDepartmentScopeUsesFoundationIdentityAndCeilingIntersection() {
        DataCenter.Definition d =
                publish(
                        enhancementDesign(
                                List.of(
                                        enhancementField("department", "INTEGER"),
                                        enhancementField("status", "TEXT")),
                                Map.of()));
        String app = app(d),
                dept = field(d, "department"),
                status = field(d, "status"),
                name = field(d, "name");
        for (int department : List.of(101, 102, 103))
            for (String state : List.of("A", "B"))
                runtime.save(
                        new Save(
                                app,
                                d.objectId(),
                                null,
                                null,
                                Map.of(
                                        name,
                                        "记录" + department + state,
                                        dept,
                                        department,
                                        status,
                                        state),
                                null),
                        10001);
        com.richuang.os.module.system.api.user.dto.AdminUserRespDTO user =
                new com.richuang.os.module.system.api.user.dto.AdminUserRespDTO();
        user.setId(20002L);
        user.setDeptId(101L);
        com.richuang.os.module.system.api.dept.dto.DeptRespDTO department =
                new com.richuang.os.module.system.api.dept.dto.DeptRespDTO();
        department.setId(101L);
        department.setStatus(0);
        com.richuang.os.module.system.api.dept.dto.DeptRespDTO child =
                new com.richuang.os.module.system.api.dept.dto.DeptRespDTO();
        child.setId(102L);
        child.setStatus(0);
        org.mockito.Mockito.when(
                        servicesContext
                                .getBean(com.richuang.os.module.system.api.user.AdminUserApi.class)
                                .getUser(20002L))
                .thenReturn(user);
        com.richuang.os.module.system.api.dept.DeptApi depts =
                servicesContext.getBean(com.richuang.os.module.system.api.dept.DeptApi.class);
        org.mockito.Mockito.when(depts.getDept(101L)).thenReturn(department);
        org.mockito.Mockito.when(depts.getChildDeptList(101L)).thenReturn(List.of(child));
        var ceiling = NocodeIntegrationSupport.resolvedPermission(d.objectId(), app);
        share(
                app,
                d,
                new ApplicationAuthorization.ObjectGrant(
                        ceiling.objectId(),
                        ceiling.actions(),
                        ceiling.scope(),
                        ceiling.readFields(),
                        ceiling.writeFields(),
                        ceiling.readDetails(),
                        ceiling.writeDetails(),
                        ceiling.readRelations(),
                        ceiling.writeRelations(),
                        Map.of("READ", scope(status, "eq", "A")),
                        Set.of()));
        ApplicationAuthorization.ObjectGrant member =
                new ApplicationAuthorization.ObjectGrant(
                        d.objectId(),
                        Set.of("READ"),
                        "ALL",
                        Set.of(name, dept, status),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Map.of(
                                "READ",
                                new DataScope(
                                        "AND",
                                        List.of(
                                                new DataScope.Condition(
                                                        dept,
                                                        "in",
                                                        null,
                                                        "CURRENT_DEPARTMENT_TREE")),
                                        List.of())),
                        Set.of());
        authorize(app, member(member));
        assertThat(runtime.page(enhancementQuery(app, d), 20002).getTotal()).isEqualTo(2);
        org.mockito.Mockito.when(depts.getDept(101L)).thenReturn(null);
        assertThat(runtime.page(enhancementQuery(app, d), 20002).getTotal()).isZero();
    }

    private CalculationOptions sequenceOptions(String operation, String direction) {
        return new CalculationOptions(
                "SEQUENCE",
                "LIVE",
                null,
                null,
                null,
                null,
                "AND",
                List.of(),
                false,
                List.of("account"),
                null,
                new CalculationOptions.Sequence(
                        "sequence",
                        null,
                        direction,
                        operation,
                        "CUMULATIVE".equals(operation) ? "1000" : null));
    }

    private DataCenter.Definition sequenceDefinition() {
        List<FieldDefinition> fields =
                List.of(
                        enhancementField("account", "TEXT"),
                        enhancementField("sequence", "INTEGER"),
                        enhancementField("kind", "TEXT"),
                        enhancementField("amount", "DECIMAL"),
                        enhancementField("net", "FORMULA"),
                        enhancementField("half", "FORMULA"),
                        enhancementField("balance", "FORMULA"),
                        enhancementField("next_delta", "FORMULA"));
        CalculationOptions local =
                new CalculationOptions(
                        "LOCAL", "LIVE", null, null, null, null, null, List.of(), false, List.of(),
                        null);
        return publish(
                enhancementDesign(
                        fields,
                        Map.of(
                                "net",
                                        calculation(
                                                "IF(kind = 'IN', coalesce(amount, 0),"
                                                        + " -coalesce(amount, 0))",
                                                "DECIMAL",
                                                local),
                                "half",
                                        calculation(
                                                "IF(OR(ISBLANK(amount), amount = 0), 0,"
                                                        + " ROUND(amount / 2, 2))",
                                                "DECIMAL",
                                                null),
                                "balance",
                                        calculation(
                                                "net + half * 0",
                                                "DECIMAL",
                                                sequenceOptions("CUMULATIVE", "PREVIOUS")),
                                "next_delta",
                                        calculation(
                                                "coalesce(__previous_net, 0) - net",
                                                "DECIMAL",
                                                sequenceOptions("ADJACENT", "NEXT")))));
    }

    private void sequenceGrant(String app, DataCenter.Definition d) {
        ApplicationAuthorization.ObjectGrant grant =
                NocodeIntegrationSupport.resolvedPermission(d.objectId(), app);
        share(
                app,
                d,
                new ApplicationAuthorization.ObjectGrant(
                        grant.objectId(),
                        grant.actions(),
                        grant.scope(),
                        grant.readFields(),
                        grant.writeFields(),
                        grant.readDetails(),
                        grant.writeDetails(),
                        grant.readRelations(),
                        grant.writeRelations(),
                        Map.of(),
                        d.fields().stream()
                                .map(FieldDefinition::id)
                                .collect(java.util.stream.Collectors.toSet())));
    }

    @Test
    void sequenceFormulaCombinesLocalContributionsPartitionsAndHistory() {
        DataCenter.Definition d = sequenceDefinition();
        String app = app(d);
        sequenceGrant(app, d);
        Row late =
                tableWideRow(
                        app,
                        d,
                        Map.of(
                                "name",
                                "出金",
                                "account",
                                "A",
                                "sequence",
                                2,
                                "kind",
                                "OUT",
                                "amount",
                                "80"));
        Row early =
                tableWideRow(
                        app,
                        d,
                        Map.of(
                                "name",
                                "补录入金",
                                "account",
                                "A",
                                "sequence",
                                1,
                                "kind",
                                "IN",
                                "amount",
                                "200"));
        Row other =
                tableWideRow(
                        app,
                        d,
                        Map.of(
                                "name",
                                "其他账户",
                                "account",
                                "B",
                                "sequence",
                                1,
                                "kind",
                                "IN",
                                "amount",
                                "5"));
        assertThat(runtime.get(app, d.objectId(), late.id(), 10001).record().values())
                .containsEntry(field(d, "balance"), "1120.0000000000")
                .containsEntry(field(d, "next_delta"), "80.0000000000");
        assertThat(runtime.get(app, d.objectId(), early.id(), 10001).record().values())
                .containsEntry(field(d, "balance"), "1200.0000000000")
                .containsEntry(field(d, "next_delta"), "-280.0000000000");
        assertThat(other.values()).containsEntry(field(d, "balance"), "1005.0000000000");
        assertThat(
                        runtime.page(
                                        new Query(
                                                app,
                                                d.objectId(),
                                                1,
                                                1,
                                                null,
                                                Map.of(field(d, "kind"), "OUT"),
                                                field(d, "sequence"),
                                                true),
                                        10001)
                                .getList()
                                .getFirst()
                                .values())
                .containsEntry(field(d, "balance"), "1120.0000000000");
        Row changed =
                runtime.save(
                                new Save(
                                        app,
                                        d.objectId(),
                                        early.id(),
                                        early.revision(),
                                        Map.of(field(d, "amount"), "180"),
                                        null),
                                10001)
                        .record();
        assertThat(runtime.get(app, d.objectId(), late.id(), 10001).record().values())
                .containsEntry(field(d, "balance"), "1100.0000000000");
        runtime.delete(new Delete(app, d.objectId(), changed.id(), changed.revision()), 10001);
        assertThat(runtime.get(app, d.objectId(), late.id(), 10001).record().values())
                .containsEntry(field(d, "balance"), "920.0000000000");
    }

    @Test
    void sequenceFormulaPreviewReplacesCandidateAndHandlesMissingNeighbor() {
        DataCenter.Definition d = sequenceDefinition();
        String app = app(d);
        sequenceGrant(app, d);
        Row first =
                tableWideRow(
                        app,
                        d,
                        Map.of(
                                "name",
                                "第一笔",
                                "account",
                                "A",
                                "sequence",
                                1,
                                "kind",
                                "IN",
                                "amount",
                                "20"));
        Row second =
                tableWideRow(
                        app,
                        d,
                        Map.of(
                                "name",
                                "第二笔",
                                "account",
                                "A",
                                "sequence",
                                2,
                                "kind",
                                "OUT",
                                "amount",
                                "10"));
        com.richuang.os.nocode.runtime.service.record.RecordCalculations service =
                servicesContext.getBean(
                        com.richuang.os.nocode.runtime.service.record.RecordCalculations.class);
        Map<String, Object> candidate = new LinkedHashMap<>(second.values());
        candidate.put(field(d, "amount"), "5");
        assertThat(service.preview(app, d, second.id(), candidate, Map.of(), 10001))
                .containsEntry(field(d, "balance"), "1015.0000000000")
                .containsEntry(field(d, "next_delta"), "5.0000000000");
        Map<String, Object> inserted =
                tableWideValues(
                        d,
                        Map.of(
                                "name",
                                "新首笔",
                                "account",
                                "A",
                                "sequence",
                                0,
                                "kind",
                                "IN",
                                "amount",
                                "3"));
        assertThat(service.preview(app, d, null, inserted, Map.of(), 10001))
                .containsEntry(field(d, "balance"), "1003.0000000000")
                .containsEntry(field(d, "next_delta"), "17.0000000000");
        assertThat(service.freshValues(app, d, second.id(), Set.of(field(d, "balance")), 10001))
                .containsEntry(field(d, "balance"), "1010.0000000000");
        assertThat(runtime.get(app, d.objectId(), first.id(), 10001).record().values())
                .containsEntry(field(d, "balance"), "1020.0000000000");
    }

    @Test
    void sequenceFormulaRejectsRecursiveAndCrossRecordInputs() {
        List<FieldDefinition> fields =
                List.of(
                        enhancementField("account", "TEXT"),
                        enhancementField("sequence", "INTEGER"),
                        enhancementField("amount", "DECIMAL"),
                        enhancementField("balance", "FORMULA"));
        assertThatThrownBy(
                        () ->
                                enhancementDesign(
                                        fields,
                                        Map.of(
                                                "balance",
                                                calculation(
                                                        "coalesce(__previous_balance, 0) + amount",
                                                        "DECIMAL",
                                                        sequenceOptions("ADJACENT", "PREVIOUS")))))
                .hasMessageContaining("字段不存在");
        assertThatThrownBy(
                        () ->
                                enhancementDesign(
                                        fields,
                                        Map.of(
                                                "balance",
                                                calculation(
                                                        "amount",
                                                        "DECIMAL",
                                                        sequenceOptions("CUMULATIVE", "NEXT")))))
                .hasMessageContaining("从前向后");
    }

    @Test
    void sequenceFormulaReadsWholeGroupBeyondLookupLimit() {
        DataCenter.Definition d = sequenceDefinition();
        String app = app(d);
        sequenceGrant(app, d);
        jdbc.execute(
                "INSERT INTO public.\""
                        + d.tableName()
                        + "\" (name, account, sequence, kind, amount, creator, updater,"
                        + " create_time, update_time, deleted) SELECT '顺序' || n, 'A', n, 'IN', 0.1,"
                        + " '10001', '10001', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0 FROM"
                        + " generate_series(1, 600) n");
        ApplicationRecords.Row row =
                runtime.page(
                                new Query(
                                        app,
                                        d.objectId(),
                                        1,
                                        1,
                                        null,
                                        Map.of(),
                                        field(d, "sequence"),
                                        true),
                                10001)
                        .getList()
                        .getFirst();
        assertThat(row.values()).containsEntry(field(d, "balance"), "1060.0000000000");
        ApplicationAuthorization.ObjectGrant grant =
                NocodeIntegrationSupport.resolvedPermission(d.objectId(), app);
        // 计算取数不再由人勾选：把排序字段从应用的可查看字段里拿掉，整组累计按新文案拒绝。
        Set<String> readable = new HashSet<>(grant.readFields());
        readable.remove(field(d, "sequence"));
        Set<String> writable = new HashSet<>(grant.writeFields());
        writable.remove(field(d, "sequence"));
        share(
                app,
                d,
                new ApplicationAuthorization.ObjectGrant(
                        grant.objectId(),
                        grant.actions(),
                        grant.scope(),
                        readable,
                        writable,
                        grant.readDetails(),
                        grant.writeDetails(),
                        grant.readRelations(),
                        grant.writeRelations(),
                        Map.of(),
                        Set.of()));
        assertThatThrownBy(() -> runtime.get(app, d.objectId(), row.id(), 10001))
                .hasMessageContaining("取数，但现在取不了")
                .hasMessageContaining("没有把字段「");
    }

    @Test
    void sequenceFormulaGeneratedConditionsKeepDecimalDivision() {
        DataCenter.Definition d =
                publish(
                        enhancementDesign(
                                List.of(
                                        enhancementField("sequence", "INTEGER"),
                                        enhancementField("ratio", "FORMULA")),
                                Map.of(
                                        "ratio",
                                        calculation(
                                                "IF(AND(NOT(ISBLANK(sequence)), sequence > 0), 1 /"
                                                        + " sequence, 0)",
                                                "DECIMAL",
                                                null))));
        String app = app(d);
        Row half = tableWideRow(app, d, Map.of("name", "整数除法", "sequence", 2));
        assertThat(half.values().get(field(d, "ratio"))).isEqualTo("0.5000000000");
        Row zero = tableWideRow(app, d, Map.of("name", "零分支", "sequence", 0));
        assertThat(zero.values().get(field(d, "ratio"))).isEqualTo("0.0000000000");
        Row missing = tableWideRow(app, d, Map.of("name", "空分支"));
        assertThat(missing.values().get(field(d, "ratio"))).isEqualTo("0.0000000000");
    }
}
