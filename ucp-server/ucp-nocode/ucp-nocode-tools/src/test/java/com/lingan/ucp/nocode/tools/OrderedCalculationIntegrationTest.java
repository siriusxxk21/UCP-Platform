package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationRecords.*;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.application.service.sharing.ObjectSharingService;
import com.lingan.ucp.nocode.metadata.service.formula.OrderedCalculationStateService;
import com.lingan.ucp.nocode.runtime.service.maintenance.OrderedCalibrationService;
import com.lingan.ucp.nocode.runtime.service.record.RecordService;
import com.lingan.ucp.nocode.runtime.service.report.ApplicationReportService;

import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/** 有序 ON_SAVE 在真实开发库的完整写链路回归；仅创建并清理本测试随机前缀夹具。 */
class OrderedCalculationIntegrationTest {
    private NocodeIntegrationSupport fixture;
    private RecordService runtime;
    private ApplicationService applications;
    private DataCenter.Definition definition;
    private String application;
    private int appSerial;

    /** 浏览器验收显式创建/清理入口，不参与普通测试；只接受本夹具严格格式前缀。 */
    public static void main(String[] args) throws Exception {
        if (args.length < 1
                || !Set.of("create-browser-fixture", "cleanup-browser-fixture").contains(args[0]))
            throw new IllegalArgumentException("请指定浏览器夹具创建或清理操作");
        OrderedCalculationIntegrationTest owner = new OrderedCalculationIntegrationTest();
        open();
        try {
            owner.setup();
            if ("cleanup-browser-fixture".equals(args[0])) {
                if (args.length != 2 || !args[1].matches("test_b1_[0-9a-f]{16}"))
                    throw new IllegalArgumentException("只能清理明确指定的本测试前缀");
                owner.fixture.prefix = args[1];
                owner.cleanup();
                System.out.println("ORDERED_BROWSER_FIXTURE_CLEANED prefix=" + args[1]);
                return;
            }
            owner.configure("LIVE");
            owner.create("A", 10, "10");
            owner.create("A", 20, "20");
            owner.create("B", 10, "30");
            for (String account : List.of("C", "D", "E", "F", "G", "H"))
                owner.create(account, 10, "10");
            owner.switchMode("ON_SAVE");
            System.out.println(
                    "ORDERED_BROWSER_FIXTURE objectId="
                            + owner.definition.objectId()
                            + " objectCode="
                            + owner.definition.objectCode()
                            + " prefix="
                            + owner.fixture.prefix
                            + " table="
                            + owner.definition.tableName());
            System.out.println(
                    "ORDERED_BROWSER_MANIFEST "
                            + mapper.writeValueAsString(
                                    Map.of(
                                            "objectId",
                                            owner.definition.objectId(),
                                            "objectCode",
                                            owner.definition.objectCode(),
                                            "prefix",
                                            owner.fixture.prefix,
                                            "groups",
                                            8,
                                            "rows",
                                            9)));
        } finally {
            shutdown();
        }
    }

    @BeforeAll
    static void open() throws Exception {
        connect();
        session.getSqlSessionFactory()
                .getConfiguration()
                .addInterceptor(new OrderedCalculationPerformanceTest.StatementCounter());
    }

    @AfterAll
    static void shutdown() {
        close();
    }

    @BeforeEach
    void announce(TestInfo test) {
        System.out.println("ORDERED_TEST starting=" + test.getDisplayName());
    }

    @BeforeEach
    void setup() {
        fixture = new NocodeIntegrationSupport();
        fixture.name();
        runtime = servicesContext.getBean(RecordService.class);
        applications = servicesContext.getBean(ApplicationService.class);
        com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi permissions =
                servicesContext.getBean(
                        com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi
                                .class);
        org.mockito.Mockito.when(permissions.hasAnyPermissions(10001L, "nocode:object:query"))
                .thenReturn(true);
        org.mockito.Mockito.when(permissions.hasAnyPermissions(10001L, "nocode:object:manage"))
                .thenReturn(true);
    }

    @AfterEach
    void cleanup() {
        List<Long> apps =
                jdbc.queryForList(
                        "SELECT id FROM public.nocode_application WHERE app_code LIKE ?",
                        Long.class,
                        fixture.prefix + "%");
        for (Long id : apps) {
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
                "DELETE FROM public.nocode_ordered_calculation_state WHERE object_id IN"
                        + " (SELECT id FROM public.nocode_object WHERE object_code LIKE ?)",
                fixture.prefix + "%");
        fixture.clean();
    }

    @Test
    void threeAlgorithmsPersistCompleteGroupsAndFinalRevision() {
        configure("ON_SAVE");
        Row late = create("A", 20, "20");
        Row early = create("A", 10, "10");
        Row other = create("B", 10, "7");
        expect(late, "balance", "30");
        expect(early, "balance", "10");
        expect(late, "cumulative", "1030");
        expect(early, "cumulative", "1010");
        expect(early, "next_value", "20");
        expect(late, "previous_value", "10");
        expect(late, "next_value", null);
        expect(other, "balance", "7");
        // 返回修订必须属于计算完成后的物理行，可不额外刷新直接用于下一笔保存。
        Row changed =
                runtime.save(
                                new Save(
                                        application,
                                        definition.objectId(),
                                        early.id(),
                                        early.revision(),
                                        values(Map.of("incoming", "5")),
                                        null),
                                10001)
                        .record();
        expect(changed, "balance", "5");
        expect(late, "balance", "25");
        Row twice =
                runtime.save(
                                new Save(
                                        application,
                                        definition.objectId(),
                                        changed.id(),
                                        changed.revision(),
                                        values(Map.of("memo", "可继续编辑")),
                                        null),
                                10001)
                        .record();
        assertThat(twice.values()).containsEntry(field("memo"), "可继续编辑");
        expect(late, "snapshot", "20");
        expect(early, "snapshot", "25");
    }

    @Test
    void movingBetweenGroupsAndReorderingUpdatesBothGroupsAndNeighbors() {
        configure("ON_SAVE");
        Row first = create("A", 10, "10");
        Row moving = create("A", 20, "20");
        Row last = create("A", 30, "30");
        Row other = create("B", 10, "7");
        change(moving, Map.of("account", "B", "sequence", 5));
        expect(first, "balance", "10");
        expect(last, "balance", "40");
        expect(first, "next_value", "30");
        expect(last, "previous_value", "10");
        expect(moving, "balance", "20");
        expect(other, "balance", "27");
        expect(other, "cumulative", "1027");
        change(last, Map.of("sequence", 1));
        expect(last, "balance", "30");
        expect(first, "balance", "40");
        expect(last, "next_value", "10");
        expect(first, "next_value", null);
        expect(other, "snapshot", "7");
    }

    @Test
    void deletionRecomputesRemainingRowsAndNextAppendChangesFormerTail() {
        configure("ON_SAVE");
        Row first = create("A", 10, "10");
        Row middle = create("A", 20, "20");
        expect(first, "next_value", "20");
        Row last = create("A", 30, "30");
        expect(middle, "next_value", "30");
        Row current = get(middle);
        runtime.delete(
                new Delete(application, definition.objectId(), current.id(), current.revision()),
                10001);
        expect(first, "next_value", "30");
        expect(last, "balance", "40");
        expect(last, "cumulative", "1040");
        expect(last, "previous_value", "10");
        expect(last, "snapshot", "60");
    }

    @Test
    void filterExitAndInitialFieldChangesClearExcludedValueAndRecomputeOpening() {
        configure("ON_SAVE");
        Row first = create("A", 10, "10");
        Row second = create("A", 20, "20");
        change(first, Map.of("opening", "100"));
        expect(first, "balance", "110");
        expect(second, "balance", "130");
        change(first, Map.of("included", false));
        expect(first, "balance", null);
        expect(second, "balance", "20");
        change(second, Map.of("opening", "500"));
        expect(second, "balance", "520");
        change(first, Map.of("included", true));
        expect(first, "balance", "110");
        expect(second, "balance", "130");
    }

    @Test
    void expressionAndTransitiveLocalInputsPropagateWithoutChangingOrdinarySnapshots() {
        configure("ON_SAVE");
        Row first = create("A", 10, "10");
        Row second = create("A", 20, "20");
        change(first, Map.of("kind", "OUT"));
        expect(first, "cumulative", "990");
        expect(second, "cumulative", "1010");
        expect(second, "previous_value", "-10");
        expect(second, "balance", "30");
        expect(second, "snapshot", "30");
        change(first, Map.of("divisor", "2"));
        expect(second, "cumulative", "1015");
        expect(second, "snapshot", "30");
    }

    @Test
    void fullGroupAccumulationDoesNotReuseRoundedStoredPrefix() {
        configure("ON_SAVE");
        Row first = create("A", 10, "0.0000000006", Map.of("divisor", "10"));
        Row second = create("A", 20, "0.0000000006", Map.of("divisor", "10"));
        expect(first, "cumulative", "1000.0000000001");
        expect(second, "cumulative", "1000.0000000001");
    }

    @Test
    void stableTieBreakersAndNullGroupsKeepIndependentCompleteResults() {
        configure("ON_SAVE");
        Map<String, Object> missingGroup = new LinkedHashMap<>();
        missingGroup.put("account", null);
        missingGroup.put("tie", 2);
        Row second = create("A", 10, "20", missingGroup);
        missingGroup.put("tie", 1);
        Row first = create("A", 10, "10", missingGroup);
        Row blank = create(" ", 10, "7");
        expect(first, "balance", "10");
        expect(second, "balance", "30");
        expect(blank, "balance", "7");
        expect(first, "next_value", "20");
    }

    @Test
    void calculationFailureRollsBackSourceDerivedRowsAndHistory() {
        configure("ON_SAVE");
        Row first = create("A", 10, "10");
        create("A", 20, "20");
        List<String> before = databaseRows();
        long history = historyCount();
        assertThatThrownBy(() -> change(first, Map.of("divisor", "0")))
                .isInstanceOf(RuntimeException.class);
        assertThat(databaseRows()).isEqualTo(before);
        assertThat(historyCount()).isEqualTo(history);
        change(first, Map.of("incoming", "5"));
        expect(first, "balance", "5");
    }

    @Test
    void prepareHandlingSavepointRestoresDerivedRowsAndDoesNotPoisonFollowingSave() {
        configure("ON_SAVE");
        Row first = create("A", 10, "10");
        Row second = create("A", 20, "20");
        List<String> before = databaseRows();
        long history = historyCount();
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        status -> {
                            Row current = get(first);
                            runtime.prepareHandling(
                                    new Save(
                                            application,
                                            definition.objectId(),
                                            current.id(),
                                            current.revision(),
                                            values(Map.of("incoming", "100")),
                                            null),
                                    10001);
                            assertThat(databaseRows()).isEqualTo(before);
                            assertThat(historyCount()).isEqualTo(history);
                            change(first, Map.of("incoming", "5"));
                        });
        expect(first, "balance", "5");
        expect(second, "balance", "25");
        expect(second, "cumulative", "1025");
    }

    @Test
    void linkedRowsHaveFinalHistoryButUnrelatedSaveDoesNotWriteThem() throws Exception {
        configure("ON_SAVE");
        Row first = create("A", 10, "10");
        Row second = create("A", 20, "20");
        change(first, Map.of("incoming", "5"));
        String after =
                jdbc.queryForObject(
                        "SELECT after_json::text FROM public.nocode_record_history WHERE"
                            + " object_id=? AND record_id=? AND operation='UPDATE' ORDER BY id DESC"
                            + " LIMIT 1",
                        String.class,
                        Long.parseLong(definition.objectId()),
                        second.id());
        assertThat(
                        new BigDecimal(
                                mapper.readTree(after)
                                        .path("values")
                                        .path(field("balance"))
                                        .asText()))
                .isEqualByComparingTo("25");
        Row settled = get(second);
        long events = historyCount(second.id());
        change(first, Map.of("memo", "仅修改备注"));
        assertThat(get(second).revision()).isEqualTo(settled.revision());
        assertThat(historyCount(second.id())).isEqualTo(events);
    }

    @Test
    void concurrentAppendsSerializeWholeGroupAndLeaveNoStaleValues() throws Exception {
        configure("ON_SAVE");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Row>> futures = new ArrayList<>();
            for (int index : List.of(1, 2)) {
                futures.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    if (!start.await(10, TimeUnit.SECONDS))
                                        throw new IllegalStateException("并发测试启动超时");
                                    return create("A", index, Integer.toString(index * 10));
                                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            Row first = futures.getFirst().get(30, TimeUnit.SECONDS);
            Row second = futures.get(1).get(30, TimeUnit.SECONDS);
            expect(first, "balance", "10");
            expect(second, "balance", "30");
            expect(first, "next_value", "20");
            expect(second, "cumulative", "1030");
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void importFiveHundredRowsUsesCompleteOrderedValuesAndPreservesPerSaveSnapshot() {
        definition = publish(design("ON_SAVE", false, true));
        createApplication();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 1; index <= 500; index++) rows.add(values(base("A", index, "1")));
        Map<String, Long> statements = new LinkedHashMap<>();
        OrderedCalculationPerformanceTest.StatementCounter.ACTIVE.set(statements);
        try {
            assertThat(runtime.importRecords(application, definition.objectId(), rows, 10001))
                    .isEqualTo(500);
        } finally {
            OrderedCalculationPerformanceTest.StatementCounter.ACTIVE.remove();
            System.out.println("ORDERED_IMPORT_SNAPSHOT statements=" + statements);
        }
        assertThat(statements.getOrDefault("runningTotals", 0L)).isEqualTo(1);
        assertThat(statements.getOrDefault("sequenceRows", 0L)).isEqualTo(3);
        Query query =
                new Query(
                        application,
                        definition.objectId(),
                        1,
                        1,
                        null,
                        Map.of(),
                        field("sequence"),
                        true);
        Row last = runtime.page(query, 10001).getList().getFirst();
        expect(last, "balance", "500");
        expect(last, "cumulative", "1500");
        expect(last, "next_value", null);
        expect(last, "snapshot", "500");
        Row first =
                runtime.page(
                                new Query(
                                        application,
                                        definition.objectId(),
                                        1,
                                        1,
                                        null,
                                        Map.of(),
                                        field("sequence"),
                                        false),
                                10001)
                        .getList()
                        .getFirst();
        expect(first, "snapshot", "1");
        expect(first, "balance", "1");
    }

    @Test
    void savedOrderedColumnsSupportSortingFilteringAndExportFromPhysicalResults() {
        configure("ON_SAVE");
        Row first = create("A", 10, "10");
        Row second = create("A", 20, "20");
        Map<String, Long> statements = new LinkedHashMap<>();
        OrderedCalculationPerformanceTest.StatementCounter.ACTIVE.set(statements);
        try {
            Query descending =
                    new Query(
                            application,
                            definition.objectId(),
                            1,
                            20,
                            null,
                            Map.of(),
                            field("balance"),
                            true);
            assertThat(runtime.page(descending, 10001).getList())
                    .extracting(Row::id)
                    .containsExactly(second.id(), first.id());
            Query filtered =
                    new Query(
                            application,
                            definition.objectId(),
                            1,
                            20,
                            null,
                            Map.of(field("balance"), "30"),
                            field("balance"),
                            false);
            assertThat(runtime.page(filtered, 10001).getList())
                    .extracting(Row::id)
                    .containsExactly(second.id());
            assertThat(runtime.export(filtered, 10001))
                    .extracting(Row::id)
                    .containsExactly(second.id());
        } finally {
            OrderedCalculationPerformanceTest.StatementCounter.ACTIVE.remove();
            System.out.println("ORDERED_STORED_READ statements=" + statements);
        }
        for (String query :
                List.of("runningTotal", "runningTotals", "sequenceRows", "sequenceValues"))
            assertThat(statements.getOrDefault(query, 0L)).as(query).isZero();
    }

    @Test
    void successfulReceiptContainsFinalCalculatedRevisionAndRetryDoesNotReapply() {
        configure("ON_SAVE");
        create("A", 20, "20");
        String key = UUID.randomUUID().toString();
        Save command =
                new Save(
                        application,
                        definition.objectId(),
                        null,
                        null,
                        values(base("A", 10, "10")),
                        null,
                        null,
                        null,
                        null,
                        key,
                        null);
        Row saved = runtime.save(command, 10001).record();
        SaveReceipt receipt = runtime.receipt(application, definition.objectId(), key, 10001);
        assertThat(receipt.revision()).isEqualTo(saved.revision()).isEqualTo(get(saved).revision());
        assertThat(receipt.result().record().values())
                .containsEntry(field("balance"), "10.0000000000");
        long history = historyCount();
        assertThat(runtime.save(command, 10001).record().revision()).isEqualTo(saved.revision());
        assertThat(historyCount()).isEqualTo(history);
        runtime.save(
                new Save(
                        application,
                        definition.objectId(),
                        saved.id(),
                        saved.revision(),
                        values(Map.of("memo", "按收据修订继续保存")),
                        null),
                10001);
    }

    @Test
    void fixedViewScopesCompareOrderedFormulaResultsAsNumbers() {
        configure("ON_SAVE");
        Row first = create("A", 10, "10");
        Row second = create("A", 20, "20");
        List<ApplicationCenter.Resource> resources = new ArrayList<>();
        for (DataScope.Condition condition :
                List.of(
                        new DataScope.Condition(field("balance"), "gt", "15"),
                        new DataScope.Condition(field("balance"), "eq", "10"))) {
            ViewQueryOptions scope = new ViewQueryOptions(List.of(condition), Map.of(), Map.of());
            scope.validate(definition);
            if ("eq".equals(condition.operator()))
                assertThat(
                                scope.scope()
                                        .matches(
                                                definition,
                                                Map.of(field("balance"), "10.0000000000"),
                                                Map.of()))
                        .isTrue();
            ApplicationUi.View view =
                    new ApplicationUi.View(
                            definition.objectId(),
                            List.of(field("name"), field("balance")),
                            Map.of(),
                            null,
                            false,
                            20,
                            null,
                            Map.of(),
                            null,
                            null,
                            new ApplicationUi.ViewList(
                                    List.of(field("balance")), List.of(), Map.of(), false, null),
                            scope);
            String viewId = "balance_" + condition.operator();
            resources.add(
                    new ApplicationCenter.Resource(
                            viewId,
                            "VIEW",
                            viewId,
                            "余额固定范围",
                            mapper.convertValue(view, Map.class)));
        }
        createApplication(resources);
        for (String operation : List.of("gt", "eq"))
            assertThat(
                            runtime.page(
                                            new Query(
                                                    application,
                                                    definition.objectId(),
                                                    1,
                                                    20,
                                                    null,
                                                    Map.of(),
                                                    null,
                                                    false,
                                                    "balance_" + operation),
                                            10001)
                                    .getList())
                    .extracting(Row::id)
                    .containsExactly("gt".equals(operation) ? second.id() : first.id());
    }

    @Test
    void reportAggregatesUseStoredFormulaResultType() {
        configure("ON_SAVE");
        create("A", 10, "10");
        create("A", 20, "20");
        List<ApplicationReports.Metric> metrics =
                List.of(
                        new ApplicationReports.Metric("sum", "求和", "SUM", field("balance")),
                        new ApplicationReports.Metric("avg", "平均", "AVG", field("balance")),
                        new ApplicationReports.Metric("min", "最小", "MIN", field("balance")),
                        new ApplicationReports.Metric("max", "最大", "MAX", field("balance")));
        ApplicationReports.Config config =
                new ApplicationReports.Config(
                        definition.objectId(),
                        List.of(new ApplicationReports.Dimension(field("account"), null, "VALUE")),
                        metrics,
                        Map.of(),
                        List.of(),
                        null,
                        "Asia/Shanghai",
                        "TABLE",
                        "sum",
                        true,
                        20,
                        null);
        ApplicationReports.Result result =
                servicesContext
                        .getBean(ApplicationReportService.class)
                        .preview(
                                new ApplicationReports.Preview(
                                        application,
                                        applications.get(application).draft().objects(),
                                        config,
                                        List.of()),
                                10001);
        assertThat(new BigDecimal(result.totals().get("sum"))).isEqualByComparingTo("40");
        assertThat(new BigDecimal(result.totals().get("avg"))).isEqualByComparingTo("20");
        assertThat(new BigDecimal(result.totals().get("min"))).isEqualByComparingTo("10");
        assertThat(new BigDecimal(result.totals().get("max"))).isEqualByComparingTo("30");
    }

    @Test
    void existingComputeFieldGrantStillProtectsWholeGroupCalculation() {
        configure("ON_SAVE");
        Row row = create("A", 10, "10");
        List<String> before = databaseRows();
        ObjectSharingService sharing = servicesContext.getBean(ObjectSharingService.class);
        ApplicationAuthorization.ObjectGrant grant =
                NocodeIntegrationSupport.resolvedPermission(definition.objectId(), application);
        ObjectSharing.Grant existing =
                sharing.forApplication(application).stream()
                        .filter(item -> item.objectId().equals(definition.objectId()))
                        .findFirst()
                        .orElseThrow();
        // 计算取数不再由人勾选：门槛是应用对来源对象的授权读得到所需字段。把一个来源字段从可查看字段里拿掉。
        Set<String> readable = new HashSet<>(grant.readFields());
        readable.remove(field("outgoing"));
        Set<String> writable = new HashSet<>(grant.writeFields());
        writable.remove(field("outgoing"));
        sharing.save(
                new ObjectSharing.Save(
                        definition.objectId(),
                        application,
                        existing.revision(),
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
                                Set.of()),
                        "回归撤销部分计算来源授权"),
                10001);
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                application,
                                                definition.objectId(),
                                                row.id(),
                                                row.revision(),
                                                values(Map.of("incoming", "20")),
                                                null),
                                        10001))
                .isInstanceOf(RuntimeException.class);
        assertThat(databaseRows()).isEqualTo(before);
    }

    @Test
    void failedImportLeavesNeitherRowsNorDerivedHistory() {
        configure("ON_SAVE");
        Row existing = create("A", 50, "50");
        List<String> before = databaseRows();
        long history = historyCount();
        Map<String, Object> invalid = base("A", 20, "20");
        invalid.put("divisor", "0");
        assertThatThrownBy(
                        () ->
                                runtime.importRecords(
                                        application,
                                        definition.objectId(),
                                        List.of(values(base("A", 10, "10")), values(invalid)),
                                        10001))
                .isInstanceOf(RuntimeException.class);
        assertThat(databaseRows()).isEqualTo(before);
        assertThat(historyCount()).isEqualTo(history);
        expect(existing, "balance", "50");
    }

    @Test
    void fixedLiveVersionCannotWriteWhenObjectSwitchesToStoredOrderedMode() {
        configure("LIVE");
        Row row = create("A", 10, "10");
        DataCenter.Definition pinned = definition;
        switchMode("ON_SAVE");
        assertThatThrownBy(
                        () -> OrderedCalculationStateService.requireCompatible(pinned, definition))
                .hasMessageContaining("固定版本");
        assertThatThrownBy(
                        () -> OrderedCalculationStateService.requireCompatible(definition, pinned))
                .hasMessageContaining("固定版本");
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                application,
                                                definition.objectId(),
                                                row.id(),
                                                row.revision(),
                                                values(Map.of("incoming", "20")),
                                                null),
                                        10001))
                .hasMessageMatching(".*(校准|版本|维护).*");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT incoming FROM public.\""
                                        + definition.tableName()
                                        + "\" WHERE id=?",
                                BigDecimal.class,
                                Long.parseLong(row.id())))
                .isEqualByComparingTo("10");
    }

    private void switchMode(String mode) {
        DataCenter.Design edited =
                designs.editPublished(
                        new DataCenter.Revision(
                                definition.objectId(),
                                designs.get(definition.objectId()).draft().lockVersion(),
                                "测试有序模式切换"),
                        10001);
        Map<String, DataCenter.FieldOptions> options = new LinkedHashMap<>(edited.fieldOptions());
        for (Map.Entry<String, DataCenter.FieldOptions> entry :
                new ArrayList<>(options.entrySet())) {
            CalculationOptions old = entry.getValue().calculation();
            if (old == null || !Set.of("RUNNING_TOTAL", "SEQUENCE").contains(old.mode())) continue;
            options.put(
                    entry.getKey(),
                    entry.getValue()
                            .withCalculation(
                                    new CalculationOptions(
                                            old.mode(),
                                            mode,
                                            old.targetObjectId(),
                                            old.relationId(),
                                            old.targetField(),
                                            old.aggregate(),
                                            old.logic(),
                                            old.conditions(),
                                            old.excludeCurrent(),
                                            old.groupFields(),
                                            old.runningTotal(),
                                            old.sequence())));
        }
        definition =
                publish(
                        designs.save(
                                new DataCenter.SaveDesign(
                                        fixture.edit(
                                                edited.draft(),
                                                edited.draft().fields(),
                                                List.of(),
                                                edited.draft().titleFieldId()),
                                        edited.settings(),
                                        options,
                                        edited.relations(),
                                        edited.indexes(),
                                        edited.details(),
                                        edited.mainBinding()),
                                10001));
    }

    @Test
    void pendingCalibrationHasAccurateNullCountsAndPersistsResumableProgress() {
        configure("LIVE");
        Row first = create("A", 10, "10");
        Row excluded = create("A", 20, "20", Map.of("included", false));
        Row other = create("B", 10, "30");
        DataCenter.Definition live = definition;
        String oldApplication = application;
        switchMode("ON_SAVE");
        assertThat(applications.get(oldApplication).application().status()).isEqualTo("DISABLED");
        createApplication();
        OrderedCalibrationService calibration =
                servicesContext.getBean(OrderedCalibrationService.class);
        OrderedCalculationCalibration.Preview preview = preview(orderedFields());
        OrderedCalculationCalibration.FieldPreview balance =
                preview.fields().stream()
                        .filter(item -> item.fieldId().equals(field("balance")))
                        .findFirst()
                        .orElseThrow();
        assertThat(balance.state()).isEqualTo("PENDING");
        assertThat(balance.groups()).isEqualTo(2);
        assertThat(balance.rows()).isEqualTo(3);
        assertThat(balance.nullRows()).isEqualTo(3);
        assertThat(balance.changedRows()).isEqualTo(2);
        assertThat(balance.fillRows()).isEqualTo(2);
        assertThat(balance.validNullRows()).isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                runtime.page(
                                        new Query(
                                                application,
                                                definition.objectId(),
                                                1,
                                                20,
                                                null,
                                                Map.of(field("balance"), "10"),
                                                field("balance"),
                                                false),
                                        10001))
                .hasMessageContaining("校准");
        // 旧 LIVE 定义不依赖物理列完整度；被暂停应用本身仍遵守既有停用规则。
        servicesContext
                .getBean(OrderedCalculationStateService.class)
                .requireReady(live, live.fields().stream().map(FieldDefinition::id).toList());
        OrderedCalculationCalibration.Command command = command(preview, 1);
        OrderedCalculationCalibration.Result firstBatch = calibration.calibrate(command, 10001);
        assertThat(firstBatch.complete()).isFalse();
        assertThat(
                        firstBatch.states().stream()
                                .mapToLong(OrderedCalculations.State::completedGroups)
                                .sum())
                .isEqualTo(1);
        List<OrderedCalculations.State> persisted =
                calibration.status(definition.objectId(), 10001);
        assertThat(persisted)
                .anySatisfy(
                        state -> {
                            assertThat(state.cursor().get("requestId"))
                                    .isEqualTo(command.requestId());
                            assertThat(state.completedGroups()).isEqualTo(1);
                        });
        assertThatThrownBy(() -> create("A", 30, "1")).hasMessageContaining("校准");
        OrderedCalculations.State restored =
                persisted.stream()
                        .filter(state -> state.fieldId().equals(field("balance")))
                        .findFirst()
                        .orElseThrow();
        OrderedCalculationCalibration.Command resume =
                new OrderedCalculationCalibration.Command(
                        definition.objectId(),
                        ((Number) restored.cursor().get("versionNo")).intValue(),
                        restored.cursor().get("checksum").toString(),
                        command.fieldIds(),
                        command.signatures(),
                        restored.cursor().get("requestId").toString(),
                        1);
        assertThat(calibration.pause(resume, 10001).states())
                .anyMatch(state -> "FAILED".equals(state.state()));
        OrderedCalculationCalibration.Result restarted = calibration.resume(resume, 10001);
        assertThat(restarted.states()).noneMatch(state -> "FAILED".equals(state.state()));
        assertThat(restarted.states()).anyMatch(state -> "BACKFILLING".equals(state.state()));
        complete(calibration, resume);
        expect(first, "balance", "10");
        expect(excluded, "balance", null);
        expect(other, "balance", "30");
        long history = historyCount();
        assertThat(calibration.calibrate(command, 10001).complete()).isTrue();
        assertThat(historyCount()).isEqualTo(history);
        expect(other, "snapshot", "30");
        change(first, Map.of("memo", "校准结束后可保存"));
    }

    @Test
    void calibrationFindsIncorrectNonNullValuesAndDoesNotTouchOrdinarySnapshots() {
        configure("ON_SAVE");
        Row first = create("A", 10, "10");
        Row second = create("A", 20, "20");
        jdbc.update(
                "UPDATE public.\"" + definition.tableName() + "\" SET balance=999 WHERE id=?",
                Long.parseLong(first.id()));
        OrderedCalculationCalibration.Preview preview = preview(List.of(field("balance")));
        OrderedCalculationCalibration.FieldPreview output = preview.fields().getFirst();
        assertThat(output.nullRows()).isZero();
        assertThat(output.changedRows()).isEqualTo(1);
        assertThat(output.incorrectRows()).isEqualTo(1);
        assertThat(output.fillRows()).isZero();
        OrderedCalibrationService calibration =
                servicesContext.getBean(OrderedCalibrationService.class);
        OrderedCalculationCalibration.Command command = command(preview, 1);
        assertThat(calibration.calibrate(command, 10001).complete()).isTrue();
        expect(first, "balance", "10");
        expect(first, "snapshot", "10");
        expect(second, "snapshot", "30");
        assertThatThrownBy(() -> preview(List.of(field("snapshot")))).hasMessageContaining("有序落库");
    }

    @Test
    void failedGroupKeepsEarlierGroupCommittedAndRetryUsesSavedPosition() {
        configure("ON_SAVE");
        Row first = create("A", 10, "10");
        Row second = create("B", 10, "20");
        jdbc.update("UPDATE public.\"" + definition.tableName() + "\" SET cumulative=900");
        OrderedCalculationCalibration.Command command =
                command(preview(List.of(field("cumulative"))), 2);
        // 仅向自有夹具注入一次故障，验证每组事务与失败后的维护窗口。
        jdbc.update(
                "UPDATE public.\"" + definition.tableName() + "\" SET divisor=0 WHERE id=?",
                Long.parseLong(second.id()));
        OrderedCalibrationService calibration =
                servicesContext.getBean(OrderedCalibrationService.class);
        OrderedCalculationCalibration.Result failed = calibration.calibrate(command, 10001);
        assertThat(failed.complete()).isFalse();
        assertThat(failed.states().getFirst().state()).isEqualTo("FAILED");
        assertThat(failed.states().getFirst().completedGroups()).isEqualTo(1);
        assertThat(physical(first, "cumulative")).isEqualByComparingTo("1010");
        assertThat(physical(second, "cumulative")).isEqualByComparingTo("900");
        assertThatThrownBy(() -> create("A", 20, "1")).hasMessageContaining("校准");
        long firstHistory = historyCount(first.id());
        jdbc.update(
                "UPDATE public.\"" + definition.tableName() + "\" SET divisor=1 WHERE id=?",
                Long.parseLong(second.id()));
        assertThat(calibration.resume(command, 10001).complete()).isTrue();
        assertThat(historyCount(first.id())).isEqualTo(firstHistory);
        expect(second, "cumulative", "1020");
    }

    @Test
    void pausedCalibrationCanReturnToLiveAndReactivationRequiresFreshBackfill() {
        configure("LIVE");
        create("A", 10, "10");
        create("B", 10, "20");
        switchMode("ON_SAVE");
        OrderedCalibrationService calibration =
                servicesContext.getBean(OrderedCalibrationService.class);
        OrderedCalculationCalibration.Command command = command(preview(orderedFields()), 1);
        assertThat(calibration.calibrate(command, 10001).complete()).isFalse();
        assertThat(calibration.pause(command, 10001).states())
                .allMatch(state -> "FAILED".equals(state.state()));
        switchMode("LIVE");
        createApplication();
        Row latest = create("B", 20, "5");
        assertThat(new BigDecimal(get(latest).values().get(field("balance")).toString()))
                .isEqualByComparingTo("25");
        switchMode("ON_SAVE");
        assertThat(calibration.status(definition.objectId(), 10001))
                .allMatch(state -> "PENDING".equals(state.state()) && state.completedGroups() == 0);
        assertThatThrownBy(() -> calibration.resume(command, 10001)).hasMessageContaining("版本");
    }

    @Test
    void ruleSignatureIgnoresDisplayChangesAndDetectsTransitiveExpressionChanges() {
        configure("ON_SAVE");
        String balance = OrderedCalculationStateService.signature(definition, field("balance"));
        String cumulative =
                OrderedCalculationStateService.signature(definition, field("cumulative"));
        ObjectNode renamed = mapper.valueToTree(definition);
        ((ObjectNode) renamed.withArray("fields").get(0)).put("name", "仅改显示名称");
        DataCenter.Definition displayChange =
                mapper.convertValue(renamed, DataCenter.Definition.class);
        assertThat(OrderedCalculationStateService.signature(displayChange, field("balance")))
                .isEqualTo(balance);
        assertThat(OrderedCalculationStateService.signature(displayChange, field("cumulative")))
                .isEqualTo(cumulative);
        OrderedCalculationStateService.requireCompatible(definition, displayChange);

        ObjectNode changed = mapper.valueToTree(definition);
        ((ObjectNode) changed.path("fieldOptions").path(field("net")))
                .put("expression", "IF(kind = 'IN', incoming * 2, -incoming)");
        DataCenter.Definition expressionChange =
                mapper.convertValue(changed, DataCenter.Definition.class);
        assertThat(OrderedCalculationStateService.signature(expressionChange, field("balance")))
                .isEqualTo(balance);
        assertThat(OrderedCalculationStateService.signature(expressionChange, field("cumulative")))
                .isNotEqualTo(cumulative);
        assertThatThrownBy(
                        () ->
                                OrderedCalculationStateService.requireCompatible(
                                        definition, expressionChange))
                .hasMessageContaining("固定版本");
    }

    @Test
    void calibrationRequestIdentityCannotBeReusedWithDifferentSelectedFields() {
        configure("LIVE");
        create("A", 10, "10");
        create("B", 10, "20");
        switchMode("ON_SAVE");
        OrderedCalibrationService calibration =
                servicesContext.getBean(OrderedCalibrationService.class);
        OrderedCalculationCalibration.Command original =
                command(preview(List.of(field("balance"))), 1);
        assertThat(calibration.calibrate(original, 10001).complete()).isFalse();
        List<OrderedCalculations.State> before = calibration.status(definition.objectId(), 10001);
        OrderedCalculationCalibration.Preview all = preview(orderedFields());
        Map<String, String> signatures =
                all.fields().stream()
                        .collect(
                                Collectors.toMap(
                                        OrderedCalculationCalibration.FieldPreview::fieldId,
                                        OrderedCalculationCalibration.FieldPreview::signature));
        for (List<String> selected :
                List.of(
                        List.of(field("balance"), field("cumulative")),
                        List.of(field("cumulative")))) {
            OrderedCalculationCalibration.Command changed =
                    new OrderedCalculationCalibration.Command(
                            original.objectId(),
                            original.versionNo(),
                            original.checksum(),
                            selected,
                            signatures,
                            original.requestId(),
                            1);
            assertThatThrownBy(() -> calibration.calibrate(changed, 10001))
                    .isInstanceOf(RuntimeException.class);
            assertThat(calibration.status(definition.objectId(), 10001)).isEqualTo(before);
        }
    }

    @Test
    void calibrationPreservesHighPrecisionDecimalGroupKeys() {
        definition = publish(design("LIVE", true));
        createApplication();
        Row first = create("9007199254740993.1", 10, "5");
        Row second = create("9007199254740993.2", 10, "7");
        switchMode("ON_SAVE");
        createApplication();
        OrderedCalculationCalibration.Preview preview = preview(orderedFields());
        OrderedCalculationCalibration.FieldPreview balance =
                preview.fields().stream()
                        .filter(value -> value.fieldId().equals(field("balance")))
                        .findFirst()
                        .orElseThrow();
        assertThat(balance.groups()).isEqualTo(2);
        assertThat(balance.changedRows()).isEqualTo(2);
        OrderedCalibrationService service =
                servicesContext.getBean(OrderedCalibrationService.class);
        OrderedCalculationCalibration.Command command = command(preview, 20);
        assertThat(service.calibrate(command, 10001).complete()).isTrue();
        expect(first, "balance", "5");
        expect(second, "balance", "7");
    }

    @Test
    void returningToLiveInvalidatesPreviouslyReadyMaterializationAndFixedStoredReads() {
        configure("ON_SAVE");
        create("A", 10, "10");
        DataCenter.Definition stored = definition;
        OrderedCalculationStateService states =
                servicesContext.getBean(OrderedCalculationStateService.class);
        assertThat(states.forObject(definition.objectId()))
                .allMatch(state -> "READY".equals(state.state()));
        switchMode("LIVE");
        assertThat(states.forObject(definition.objectId()))
                .allMatch(state -> "PENDING".equals(state.state()) && state.cursor().isEmpty());
        assertThatThrownBy(() -> states.requireReady(stored, List.of(field("balance"))))
                .hasMessageContaining("模式");
    }

    @Test
    void localFormulaDependencyBudgetFailsWithoutCommittingPartialOrderedResults() {
        configure("ON_SAVE");
        jdbc.execute(
                "INSERT INTO public.\""
                        + definition.tableName()
                        + "\" (name, account, sequence, tie, incoming, outgoing, opening, divisor,"
                        + " included, kind, balance, cumulative, creator, updater, create_time,"
                        + " update_time, deleted) SELECT '预算种子' || n, 'A', n, n, 1, 0, 0, 1,"
                        + " true, 'IN', n, 1000+n, '10001', '10001', CURRENT_TIMESTAMP,"
                        + " CURRENT_TIMESTAMP, 0 FROM generate_series(1,5000) n");
        List<String> before = databaseRows();
        long history = historyCount();
        assertThatThrownBy(() -> create("A", 5001, "1")).hasMessageContaining("10000 个单元格");
        assertThat(databaseRows()).isEqualTo(before);
        assertThat(historyCount()).isEqualTo(history);
    }

    @Test
    void derivedValidationReadsLiveLocalFormulaAndRejectsInvalidCalibrationAtomically() {
        DataCenter.Design draft = design("ON_SAVE");
        String net =
                draft.draft().fields().stream()
                        .filter(value -> "net".equals(value.code()))
                        .findFirst()
                        .orElseThrow()
                        .id();
        DocumentPolicy.Expression assertion =
                new DocumentPolicy.Expression(
                        "GE",
                        null,
                        null,
                        null,
                        List.of(
                                new DocumentPolicy.Expression("FIELD", net, null, null, List.of()),
                                new DocumentPolicy.Expression("VALUE", null, null, 0, List.of())));
        DocumentPolicy policy =
                new DocumentPolicy(
                        List.of(
                                new DocumentPolicy.Rule(
                                        "nonnegative_net",
                                        "净额非负",
                                        "DOCUMENT",
                                        null,
                                        null,
                                        assertion,
                                        net,
                                        "净额必须非负")),
                        null);
        definition =
                publish(
                        designs.save(
                                new DataCenter.SaveDesign(
                                        fixture.edit(
                                                draft.draft(),
                                                draft.draft().fields(),
                                                List.of(),
                                                draft.draft().titleFieldId()),
                                        new DataCenter.Settings(null, null, null, null, policy),
                                        draft.fieldOptions(),
                                        draft.relations(),
                                        draft.indexes(),
                                        draft.details(),
                                        draft.mainBinding()),
                                10001));
        createApplication();
        Row first = create("A", 10, "10");
        Row second = create("A", 20, "20");
        change(first, Map.of("incoming", "5"));
        expect(second, "balance", "25");
        Map<String, Long> statements = new LinkedHashMap<>();
        OrderedCalculationPerformanceTest.StatementCounter.ACTIVE.set(statements);
        try {
            assertThat(
                            runtime.importRecords(
                                    application,
                                    definition.objectId(),
                                    List.of(
                                            values(base("A", 30, "1")),
                                            values(base("A", 40, "1")),
                                            values(base("A", 50, "1"))),
                                    10001))
                    .isEqualTo(3);
        } finally {
            OrderedCalculationPerformanceTest.StatementCounter.ACTIVE.remove();
        }
        // 整单规则是中间消费者，本例明确保留逐行完成点，不能推迟到批末。
        assertThat(statements.getOrDefault("runningTotals", 0L)).isEqualTo(3);
        List<String> before = databaseRows();
        assertThatThrownBy(() -> change(first, Map.of("kind", "OUT"))).hasMessageContaining("净额");
        assertThat(databaseRows()).isEqualTo(before);

        jdbc.update(
                "UPDATE public.\""
                        + definition.tableName()
                        + "\" SET balance=0, kind=CASE WHEN id=? THEN 'OUT' ELSE kind END",
                Long.parseLong(second.id()));
        OrderedCalibrationService service =
                servicesContext.getBean(OrderedCalibrationService.class);
        OrderedCalculationCalibration.Command command =
                command(preview(List.of(field("balance"))), 1);
        before = databaseRows();
        OrderedCalculationCalibration.Result failed = service.calibrate(command, 10001);
        assertThat(failed.states()).allMatch(state -> "FAILED".equals(state.state()));
        assertThat(databaseRows()).isEqualTo(before);
        jdbc.update(
                "UPDATE public.\"" + definition.tableName() + "\" SET kind='IN' WHERE id=?",
                Long.parseLong(second.id()));
        assertThat(service.resume(command, 10001).complete()).isTrue();
        expect(first, "balance", "5");
        expect(second, "balance", "25");
    }

    private List<String> orderedFields() {
        return List.of(
                field("balance"),
                field("cumulative"),
                field("next_value"),
                field("previous_value"));
    }

    private OrderedCalculationCalibration.Preview preview(List<String> fields) {
        DataObjectApi.PublishedObject version =
                servicesContext
                        .getBean(DataObjectApi.class)
                        .getVersion(definition.objectId(), null);
        return servicesContext
                .getBean(OrderedCalibrationService.class)
                .preview(
                        new OrderedCalculationCalibration.PreviewRequest(
                                definition.objectId(),
                                version.versionNo(),
                                version.checksum(),
                                fields),
                        10001);
    }

    private OrderedCalculationCalibration.Command command(
            OrderedCalculationCalibration.Preview preview, int groups) {
        return new OrderedCalculationCalibration.Command(
                definition.objectId(),
                preview.versionNo(),
                preview.checksum(),
                preview.fields().stream()
                        .map(OrderedCalculationCalibration.FieldPreview::fieldId)
                        .toList(),
                preview.fields().stream()
                        .collect(
                                Collectors.toMap(
                                        OrderedCalculationCalibration.FieldPreview::fieldId,
                                        OrderedCalculationCalibration.FieldPreview::signature)),
                UUID.randomUUID().toString(),
                groups);
    }

    private void complete(
            OrderedCalibrationService service, OrderedCalculationCalibration.Command command) {
        for (int index = 0; index < 20; index++) {
            OrderedCalculationCalibration.Result result = service.resume(command, 10001);
            assertThat(result.states()).noneMatch(state -> "FAILED".equals(state.state()));
            if (result.complete()) return;
        }
        fail("校准未在预期批次数内完成");
    }

    private BigDecimal physical(Row row, String code) {
        return jdbc.queryForObject(
                "SELECT \"" + code + "\" FROM public.\"" + definition.tableName() + "\" WHERE id=?",
                BigDecimal.class,
                Long.parseLong(row.id()));
    }

    private void configure(String mode) {
        definition = publish(design(mode));
        createApplication();
    }

    private void createApplication() {
        createApplication(List.of());
    }

    private void createApplication(List<ApplicationCenter.Resource> resources) {
        DataObjectApi.PublishedObject version =
                servicesContext
                        .getBean(DataObjectApi.class)
                        .getVersion(definition.objectId(), null);
        ApplicationCenter.Detail app =
                applications.save(
                        new ApplicationCenter.Save(
                                null,
                                null,
                                fixture.prefix + "orderedapp" + appSerial++,
                                "有序落库回归",
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
        application = app.application().id();
        grantApplicationObjects(application);
        ObjectSharingService sharing = servicesContext.getBean(ObjectSharingService.class);
        ApplicationAuthorization.ObjectGrant grant =
                NocodeIntegrationSupport.resolvedPermission(definition.objectId(), application);
        ObjectSharing.Grant existing =
                sharing.forApplication(application).stream()
                        .filter(item -> item.objectId().equals(definition.objectId()))
                        .findFirst()
                        .orElseThrow();
        sharing.save(
                new ObjectSharing.Save(
                        definition.objectId(),
                        application,
                        existing.revision(),
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
                                definition.fields().stream()
                                        .map(FieldDefinition::id)
                                        .collect(Collectors.toSet())),
                        "计算回归显式授权"),
                10001);
        applications.publish(new ApplicationCenter.Revision(application, 0, "有序落库回归"), 10001);
    }

    private DataCenter.Design design(String mode) {
        return design(mode, false);
    }

    private DataCenter.Design design(String mode, boolean decimalGroup) {
        return design(mode, decimalGroup, false);
    }

    private DataCenter.Design design(String mode, boolean decimalGroup, boolean storedNet) {
        List<FieldDefinition> fields = new ArrayList<>();
        for (String code : List.of("name", "kind", "memo"))
            fields.add(fieldDefinition(code, "TEXT", fields.size()));
        fields.add(fieldDefinition("account", decimalGroup ? "DECIMAL" : "TEXT", fields.size()));
        for (String code : List.of("sequence", "tie"))
            fields.add(fieldDefinition(code, "INTEGER", fields.size()));
        for (String code : List.of("incoming", "outgoing", "opening", "divisor"))
            fields.add(fieldDefinition(code, "DECIMAL", fields.size()));
        fields.add(fieldDefinition("included", "BOOLEAN", fields.size()));
        Map<String, DataCenter.FieldOptions> options = new LinkedHashMap<>();
        options.put(
                "net",
                formula(
                        "IF(kind = 'IN', incoming, -incoming)",
                        new CalculationOptions(
                                "LOCAL",
                                storedNet ? "ON_SAVE" : "LIVE",
                                null,
                                null,
                                null,
                                null,
                                "AND",
                                List.of(),
                                false,
                                List.of(),
                                null)));
        options.put(
                "snapshot",
                formula(
                        null,
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
                                null)));
        options.put(
                "balance",
                formula(
                        null,
                        new CalculationOptions(
                                "RUNNING_TOTAL",
                                mode,
                                null,
                                null,
                                "incoming",
                                "SUM",
                                "AND",
                                List.of(new CalculationOptions.Match("included", "eq", null, true)),
                                false,
                                List.of("account"),
                                new CalculationOptions.RunningTotal(
                                        "sequence", "tie", "outgoing", null, "opening"))));
        options.put(
                "cumulative",
                formula("net / coalesce(divisor, 1)", sequence(mode, "CUMULATIVE", "PREVIOUS")));
        options.put("next_value", formula("__previous_net", sequence(mode, "ADJACENT", "NEXT")));
        options.put(
                "previous_value",
                formula("__previous_net", sequence(mode, "ADJACENT", "PREVIOUS")));
        for (String code : options.keySet())
            fields.add(fieldDefinition(code, "FORMULA", fields.size()));
        SaveObjectDraft base = fixture.createRequest("ordered");
        return designs.save(
                new DataCenter.SaveDesign(
                        new SaveObjectDraft(
                                null,
                                null,
                                base.objectCode(),
                                "有序落库验证",
                                null,
                                base.tableName(),
                                "name",
                                fields,
                                List.of()),
                        DataCenter.Settings.defaults(),
                        options,
                        List.of(),
                        List.of(),
                        List.of()),
                10001);
    }

    private FieldDefinition fieldDefinition(String code, String type, int sort) {
        return new FieldDefinition(
                code,
                null,
                code,
                code,
                type,
                "TEXT".equals(type) ? 100 : null,
                "DECIMAL".equals(type) ? 38 : null,
                "DECIMAL".equals(type) ? 10 : null,
                false,
                false,
                sort);
    }

    private CalculationOptions sequence(String mode, String operation, String direction) {
        return new CalculationOptions(
                "SEQUENCE",
                mode,
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
                        "tie",
                        direction,
                        operation,
                        "CUMULATIVE".equals(operation) ? "1000" : null));
    }

    private DataCenter.FieldOptions formula(String expression, CalculationOptions calculation) {
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
                calculation);
    }

    private DataCenter.Definition publish(DataCenter.Design design) {
        DataCenter.PublishPlan plan =
                publisher.plan(
                        new DataCenter.Revision(
                                design.draft().id(), design.draft().lockVersion(), null),
                        10001);
        assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(
                        publisher
                                .execute(
                                        new DataCenter.ExecutePlan(
                                                plan.id(),
                                                "有序落库回归",
                                                List.of(),
                                                plan.applicationUpgrades().stream()
                                                        .map(
                                                                ObjectApplicationUpgrade.Impact
                                                                        ::applicationId)
                                                        .toList()),
                                        10001)
                                .state())
                .isEqualTo("SUCCEEDED");
        return servicesContext.getBean(DataObjectApi.class).getPublished(design.draft().id());
    }

    private Map<String, Object> base(String account, int sequence, String amount) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", account + sequence);
        result.put("account", account);
        result.put("sequence", sequence);
        result.put("tie", sequence);
        result.put("incoming", amount);
        result.put("outgoing", "0");
        result.put("opening", "0");
        result.put("included", true);
        result.put("kind", "IN");
        result.put("divisor", "1");
        return result;
    }

    private Row create(String account, int sequence, String amount) {
        return create(account, sequence, amount, Map.of());
    }

    private Row create(String account, int sequence, String amount, Map<String, Object> overrides) {
        Map<String, Object> input = base(account, sequence, amount);
        input.putAll(overrides);
        return runtime.save(
                        new Save(
                                application,
                                definition.objectId(),
                                null,
                                null,
                                values(input),
                                null),
                        10001)
                .record();
    }

    private Row change(Row row, Map<String, Object> input) {
        Row current = get(row);
        return runtime.save(
                        new Save(
                                application,
                                definition.objectId(),
                                current.id(),
                                current.revision(),
                                values(input),
                                null),
                        10001)
                .record();
    }

    private Row get(Row row) {
        return runtime.get(application, definition.objectId(), row.id(), 10001).record();
    }

    private String field(String code) {
        return definition.fields().stream()
                .filter(item -> item.code().equals(code))
                .findFirst()
                .orElseThrow()
                .id();
    }

    private Map<String, Object> values(Map<String, Object> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        input.forEach((code, value) -> result.put(field(code), value));
        return result;
    }

    private void expect(Row row, String code, String expected) {
        Object actual = get(row).values().get(field(code));
        BigDecimal stored =
                jdbc.queryForObject(
                        "SELECT \""
                                + code
                                + "\" FROM public.\""
                                + definition.tableName()
                                + "\" WHERE id=?",
                        BigDecimal.class,
                        Long.parseLong(row.id()));
        if (expected == null) {
            assertThat(actual).as(code + "读取值").isNull();
            assertThat(stored).as(code + "物理值").isNull();
        } else {
            assertThat(new BigDecimal(actual.toString()))
                    .as(code + "读取值")
                    .isEqualByComparingTo(expected);
            assertThat(stored).as(code + "物理值").isEqualByComparingTo(expected);
        }
    }

    private List<String> databaseRows() {
        return jdbc.queryForList(
                "SELECT to_jsonb(t)::text FROM public.\""
                        + definition.tableName()
                        + "\" t ORDER BY id",
                String.class);
    }

    private long historyCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM public.nocode_record_history WHERE object_id=?",
                Long.class,
                Long.parseLong(definition.objectId()));
    }

    private long historyCount(String record) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM public.nocode_record_history WHERE object_id=? AND"
                        + " record_id=?",
                Long.class,
                Long.parseLong(definition.objectId()),
                record);
    }
}
