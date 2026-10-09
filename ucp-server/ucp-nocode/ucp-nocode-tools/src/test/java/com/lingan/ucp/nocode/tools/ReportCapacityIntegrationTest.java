package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lingan.ucp.module.system.api.permission.PermissionApi;
import com.lingan.ucp.module.system.api.permission.RoleApi;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationAuthorization.*;
import com.lingan.ucp.nocode.report.service.authorization.ReportDatasetAuthorizationService;
import com.lingan.ucp.nocode.report.service.dashboard.ReportDashboardService;
import com.lingan.ucp.nocode.report.service.dataset.ReportDatasetService;
import com.lingan.ucp.nocode.runtime.service.report.ReportDashboardQueryService;
import com.lingan.ucp.nocode.runtime.service.report.ReportDatasetQueryService;
import com.lingan.ucp.nocode.web.RecordExcelService;

import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.lang.management.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * A28 显式启用的开发库容量观察；只提交精确自有夹具，真实查询各自完成授权事务，退出后核验并清理。 身份 API 沿用既有测试替身，查询、授权、Mapper 和 XLSX
 * 均为真实实现；耗时不是模拟值或性能承诺。
 */
@EnabledIfSystemProperty(named = "report.capacity", matches = "true")
class ReportCapacityIntegrationTest {
    private static final long ACTOR = 10001L;
    private static String previousSqlLogging;
    private AnnotationConfigApplicationContext context;
    private NocodeIntegrationSupport fixture;
    private ReportDatasetService datasets;
    private ReportDatasetAuthorizationService authorization;
    private ReportDatasetQueryService dataQuery;
    private ReportDashboardService boards;
    private ReportDashboardQueryService boardQuery;
    private Path output;
    private final Map<String, String> ownedObjects = new LinkedHashMap<>();
    private final Map<String, String> ownedDatasets = new LinkedHashMap<>();
    private final Map<String, String> ownedBoards = new LinkedHashMap<>();
    private final List<Map<String, Object>> observations = new ArrayList<>();
    private final List<String> checks = new ArrayList<>();
    private boolean completed;

    private record Seed(String objectId, String table, ReportDatasets.Release dataset) {}

    private record Measured<T>(T value, Map<String, Object> observation) {}

    private record RequestResult(ApplicationReports.Result result, double millis) {}

    private record BatchResult(List<RequestResult> requests, int peakConcurrency) {}

    private record BlockedQuery(int pid, String sql, String blockers, String waitEvent) {}

    @BeforeAll
    static void open() throws Exception {
        // 容量观测避免开发配置逐条打印SQL/大JSON；只影响本次测试JVM，不改服务配置。
        previousSqlLogging = System.getProperty("logging.level.com.lingan.ucp");
        if (!Boolean.getBoolean("report.capacity.debugSql"))
            System.setProperty("logging.level.com.lingan.ucp", "INFO");
        connect();
    }

    @AfterAll
    static void shutdown() {
        try {
            close();
        } finally {
            if (previousSqlLogging == null) System.clearProperty("logging.level.com.lingan.ucp");
            else System.setProperty("logging.level.com.lingan.ucp", previousSqlLogging);
        }
    }

    @BeforeEach
    void setup(TestInfo test) throws Exception {
        fixture = new NocodeIntegrationSupport();
        fixture.name();
        context = ReportIntegrationSupport.context();
        datasets = context.getBean(ReportDatasetService.class);
        authorization = context.getBean(ReportDatasetAuthorizationService.class);
        dataQuery = context.getBean(ReportDatasetQueryService.class);
        boards = context.getBean(ReportDashboardService.class);
        boardQuery = context.getBean(ReportDashboardQueryService.class);
        PermissionApi permissions = servicesContext.getBean(PermissionApi.class);
        reset(
                permissions,
                servicesContext.getBean(RoleApi.class),
                servicesContext.getBean(AdminUserApi.class));
        ReportIntegrationSupport.activeUsers(ACTOR, 10003L);
        for (String action : List.of("query", "create", "update", "publish", "manage", "authorize"))
            when(permissions.hasAnyPermissions(ACTOR, "nocode:report:" + action)).thenReturn(true);
        when(permissions.hasAnyPermissions(ACTOR, "nocode:object:query")).thenReturn(true);
        when(permissions.hasAnyPermissions(10003L, "nocode:object:share")).thenReturn(true);
        output =
                Path.of(
                        System.getProperty("report.capacity.output", "../.work/report-capacity"),
                        fixture.prefix,
                        test.getTestMethod().orElseThrow().getName());
        Files.createDirectories(output);
    }

    @AfterEach
    void cleanup() throws Exception {
        List<String> cleanupErrors = new ArrayList<>();
        writeFailure.clear();
        try {
            cleanReports();
            Set<Long> expected =
                    ownedObjects.keySet().stream()
                            .map(Long::valueOf)
                            .collect(java.util.stream.Collectors.toSet());
            assertThat(
                            jdbc.queryForList(
                                    "SELECT id FROM public.nocode_object WHERE object_code LIKE ?",
                                    Long.class,
                                    fixture.prefix + "%"))
                    .containsExactlyInAnyOrderElementsOf(expected);
            for (Map.Entry<String, String> object : ownedObjects.entrySet())
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT count(*) FROM public.nocode_object WHERE id=? AND"
                                                + " object_code=? AND object_name=? AND creator=?",
                                        Long.class,
                                        Long.parseLong(object.getKey()),
                                        object.getValue(),
                                        object.getValue(),
                                        Long.toString(ACTOR)))
                        .isEqualTo(1L);
            // 清理器只接纳本次随机前缀；先验证其命中集合恰为本次精确对象 ID 集合。
            fixture.clean();
            for (String id : ownedObjects.keySet())
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT count(*) FROM public.nocode_object WHERE id=?",
                                        Long.class,
                                        Long.parseLong(id)))
                        .isZero();
        } catch (Throwable error) {
            cleanupErrors.add(error.getClass().getSimpleName() + ": " + error.getMessage());
        } finally {
            context.close();
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("finishedAt", Instant.now().toString());
            report.put("prefix", fixture.prefix);
            report.put("fixtureObjects", ownedObjects);
            report.put("fixtureDatasets", ownedDatasets);
            report.put("fixtureDashboards", ownedBoards);
            report.put("javaVersion", System.getProperty("java.version"));
            report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
            report.put(
                    "heapMaxBytes",
                    ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax());
            report.put("databaseVersion", jdbc.queryForObject("SELECT version()", String.class));
            report.put("dataSourceType", ds.getClass().getName());
            report.put(
                    "sqlLoggingLevel", System.getProperty("logging.level.com.lingan.ucp", "配置缺省"));
            report.put("measurementBoundary", "真实服务调用；身份API为既有替身；JVM堆每10ms采样，非数据库/浏览器内存或RSS；无强制GC");
            report.put("percentileMethod", "nearest-rank；只描述本次有限样本，不是SLA；预热轮不进入统计");
            report.put("checks", checks);
            report.put("observations", observations);
            report.put("cleanupErrors", cleanupErrors);
            report.put("status", completed && cleanupErrors.isEmpty() ? "PASSED" : "FAILED");
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(output.resolve("result.json").toFile(), report);
            System.out.println(
                    "Report capacity: " + report.get("status") + "; " + output.toAbsolutePath());
        }
        assertThat(cleanupErrors).isEmpty();
    }

    @Test
    void measuresRecordAndGroupGrowthWithBoundedExports() throws Exception {
        Seed seed = seed();
        int[] records = levels("report.capacity.records", "1000,10000,50000", 500, 500000);
        int[] groups = levels("report.capacity.groups", "10,100,300", 1, 1000);
        for (int count : records)
            for (int groupCount : groups) {
                assertThat(count).isGreaterThanOrEqualTo(groupCount);
                load(seed, count, groupCount, 1);
                Map<String, Object> scale = scale(seed, count, groupCount, 1);
                measure(
                        "dataset-query",
                        scale,
                        () -> dataQuery.query(datasetRequest(seed, 200), ACTOR),
                        result -> aggregate(result, count, groupCount, 200));
                ReportDashboards.Chart chart = chart(seed, "export", 0, false, 24);
                Measured<byte[]> export =
                        measure(
                                "aggregate-xlsx",
                                scale,
                                () ->
                                        new RecordExcelService()
                                                .reportResult(dataQuery.chart(chart, true, ACTOR)),
                                bytes -> assertThat(bytes.length).isPositive());
                export.observation().put("xlsxBytes", export.value().length);
                verifyWorkbook(
                        export.value(),
                        "展示 " + Math.min(groupCount, 100) + " / " + groupCount + " 组");
                Files.write(
                        output.resolve("aggregate-" + count + "-" + groupCount + ".xlsx"),
                        export.value());
            }
        int count = Math.max(records[records.length - 1], 36000);
        load(seed, count, 300, 120);
        ReportDashboards.Chart pivot = chart(seed, "pivot", 0, true, 100);
        Map<String, Object> scale = scale(seed, count, 300, 120);
        Consumer<ApplicationReports.Result> validatePivot =
                result -> {
                    assertThat(result.recordCount()).isEqualTo(count);
                    assertThat(new BigDecimal(result.totals().get("sum")))
                            .isEqualByComparingTo(Integer.toString(count));
                    assertThat(result.pivot().totalRowGroups()).isEqualTo(300);
                    assertThat(result.pivot().totalColumnGroups()).isEqualTo(120);
                    assertThat(result.pivot().rows()).hasSize(100);
                    assertThat(result.pivot().columns()).hasSize(100);
                    assertThat(result.pivot().rowsTruncated()).isTrue();
                    assertThat(result.pivot().columnsTruncated()).isTrue();
                };
        measure("pivot-query", scale, () -> dataQuery.chart(pivot, false, ACTOR), validatePivot);
        Measured<byte[]> export =
                measure(
                        "pivot-xlsx",
                        scale,
                        () -> {
                            ApplicationReports.Result result = dataQuery.chart(pivot, true, ACTOR);
                            validatePivot.accept(result);
                            return new RecordExcelService().reportResult(result);
                        },
                        bytes -> assertThat(bytes.length).isPositive());
        export.observation().put("xlsxBytes", export.value().length);
        verifyWorkbook(export.value(), "行仅显示前 100 个，共 300 个", "列组仅显示前 100 个，共 120 个");
        Files.write(output.resolve("pivot-truncated.xlsx"), export.value());
        assertThatThrownBy(() -> dataQuery.query(datasetRequest(seed, 201), ACTOR))
                .hasMessageContaining("超出范围");
        assertThatThrownBy(
                        () ->
                                boards.save(
                                        new ReportDashboards.Save(null, 0, boardContent(seed, 31)),
                                        ACTOR))
                .hasMessageContaining("30个组件");
        ReportDashboards.Content invalidPivot =
                new ReportDashboards.Content(
                        1,
                        fixture.prefix + "badpivot",
                        "",
                        List.of(chart(seed, "invalid", 0, true, 101)));
        assertThatThrownBy(
                        () -> boards.save(new ReportDashboards.Save(null, 0, invalidPivot), ACTOR))
                .hasMessageContaining("透视维度或列组数量");
        checks.add("递增记录/组数；展示限组不裁总体，聚合XLSX明确展示规模；透视100行/100列截断保留300/120总组数和完整总计");
        checks.add("数据集201组请求、看板31组件、透视101列组配置明确拒绝，不扩为无限导出");
        completed = true;
    }

    @Test
    void measuresComponentBatchesAtExplicitRequestConcurrency() throws Exception {
        Seed seed = seed();
        int count = integer("report.capacity.batchRecords", 10000, 1000, 500000);
        load(seed, count, 300, 1);
        for (int components : new int[] {1, 6, 30}) {
            ReportDashboards.Content content = boardContent(seed, components);
            ReportDashboards.Detail board =
                    boards.save(new ReportDashboards.Save(null, 0, content), ACTOR);
            ownedBoards.put(board.id(), content.name());
            ReportDashboards.Release release =
                    boards.publish(
                            new ReportDashboards.Publish(
                                    board.id(), board.revision(), UUID.randomUUID().toString()),
                            ACTOR);
            for (int concurrency : new int[] {1, 2, 4}) {
                ExecutorService pool = Executors.newFixedThreadPool(concurrency);
                List<Double> requestTimes = new ArrayList<>();
                try {
                    Map<String, Object> scale = scale(seed, count, 300, 1);
                    scale.put("components", components);
                    scale.put("inFlightLimit", concurrency);
                    scale.put("effectiveMaximum", Math.min(components, concurrency));
                    Measured<BatchResult> batch =
                            measure(
                                    "dashboard-batch",
                                    scale,
                                    () -> batch(pool, release, count),
                                    result -> {});
                    @SuppressWarnings("unchecked")
                    List<List<Double>> rounds =
                            (List<List<Double>>) batch.observation().remove("requestRounds");
                    rounds.forEach(requestTimes::addAll);
                    batch.observation().put("requestLatency", statistics(requestTimes));
                    assertThat((Integer) batch.observation().get("observedPeakConcurrency"))
                            .isBetween(1, Math.min(components, concurrency));
                } finally {
                    pool.shutdownNow();
                    assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
                }
            }
        }
        checks.add("1/6/30组件批次 × 1/2/4在途请求上限；每张图执行真实当前看板版本和数据授权查询，总计/来源记录一致");
        completed = true;
    }

    /** 自有表独占锁制造真实SQL等待；本项单独启用，不把Future取消视为SQL或浏览器取消。 */
    @Test
    @EnabledIfSystemProperty(named = "report.capacity.timeout", matches = "true")
    void observesSqlTimeoutOnOwnedTableAndRecoversAfterUnlock() throws Exception {
        Seed seed = seed();
        load(seed, 1000, 10, 1);
        aggregate(dataQuery.query(datasetRequest(seed, 200), ACTOR), 1000, 10, 200);
        // 关系大小查询也会取表锁，必须在独占锁之前完成，不能阻塞本测试的观察连接。
        Map<String, Object> observation = scale(seed, 1000, 10, 1);
        observation.put("kind", "sql-timeout");
        observation.put("startedAt", Instant.now().toString());
        observation.put("status", "RUNNING");
        observations.add(observation);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<ApplicationReports.Result> pending = null;
        long started = System.nanoTime();
        try (Connection blocker = ds.getConnection()) {
            blocker.setAutoCommit(false);
            int blockingPid;
            try (Statement identity = blocker.createStatement();
                    ResultSet pid = identity.executeQuery("SELECT pg_backend_pid()")) {
                assertThat(pid.next()).isTrue();
                blockingPid = pid.getInt(1);
            }
            observation.put("blockingPid", blockingPid);
            try (Statement lock = blocker.createStatement()) {
                lock.execute("LOCK TABLE " + table(seed) + " IN ACCESS EXCLUSIVE MODE");
            }
            try {
                started = System.nanoTime();
                pending = pool.submit(() -> dataQuery.query(datasetRequest(seed, 200), ACTOR));
                // 元数据完整性校验可能先于聚合SQL阻塞，不能只查WITH或参数化SQL中的表名字面量。
                BlockedQuery blocked = blockedQuery(blockingPid, pending);
                assertThat(blocked).isNotNull();
                observation.put("blockedPid", blocked.pid());
                observation.put("blockedSql", blocked.sql());
                observation.put("blockers", blocked.blockers());
                observation.put("waitEvent", blocked.waitEvent());
                observation.put(
                        "blockedStatementPhase",
                        blocked.sql().stripLeading().startsWith("WITH")
                                ? "report-aggregate"
                                : "source-metadata");
                Throwable failure;
                try {
                    pending.get(35, TimeUnit.SECONDS);
                    throw new AssertionError("独占锁未释放时不应成功读取");
                } catch (ExecutionException error) {
                    failure = error.getCause();
                }
                String sqlState = sqlState(failure);
                assertThat(sqlState).isEqualTo("57014");
                observation.put("elapsedMillis", millis(System.nanoTime() - started));
                observation.put("sqlState", sqlState);
                observation.put("failureClass", failure.getClass().getName());
                observation.put("configuredMapperTimeoutSeconds", 20);
                observation.put("configuredAuthorizationTransactionTimeoutSeconds", 25);
                observation.put(
                        "timeoutBoundary",
                        "当前实际阻塞语句受原事务剩余时间/Statement查询超时取消；元数据阶段不声称已进入20秒ReportMapper");
            } finally {
                blocker.rollback();
                if (pending != null && !pending.isDone()) pending.cancel(true);
            }
        } catch (Exception | AssertionError failure) {
            measurementFailure(observation, failure, started);
            throw failure;
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
        try {
            aggregate(dataQuery.query(datasetRequest(seed, 200), ACTOR), 1000, 10, 200);
        } catch (RuntimeException | AssertionError failure) {
            measurementFailure(observation, failure, System.nanoTime());
            throw failure;
        }
        observation.put("status", "PASSED");
        observation.put("finishedAt", Instant.now().toString());
        checks.add(
                "精确自有表锁PID确认真实SQL等待与SQLState57014取消，记录实际元数据/聚合阶段；finally释放锁后真实授权查询恢复；未验证HTTP/浏览器自动取消");
        completed = true;
    }

    private Seed seed() {
        String code = fixture.prefix + "capacity";
        ObjectDraft draft =
                service.create(
                        new SaveObjectDraft(
                                null,
                                null,
                                code,
                                code,
                                null,
                                "biz_" + code,
                                "rowkey",
                                List.of(
                                        new FieldDefinition(
                                                "rowkey", null, "rowkey", "行组", "TEXT", 100, null,
                                                null, false, false, 0),
                                        new FieldDefinition(
                                                "colkey", null, "colkey", "列组", "TEXT", 100, null,
                                                null, false, false, 1),
                                        new FieldDefinition(
                                                "amount", null, "amount", "金额", "DECIMAL", null, 20,
                                                2, false, false, 2)),
                                List.of()),
                        ACTOR,
                        UUID.randomUUID());
        ownedObjects.put(draft.id(), code);
        DataCenter.PublishPlan plan =
                publisher.plan(
                        new DataCenter.Revision(draft.id(), draft.lockVersion(), null), ACTOR);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "容量自有夹具"), ACTOR)
                                .state())
                .isEqualTo("SUCCEEDED");
        DataObjectApi.PublishedObject object =
                servicesContext.getBean(DataObjectApi.class).getVersion(draft.id(), null);
        assertThat(object.definition().tableName()).isEqualTo("biz_" + code);
        Map<String, String> fields = new LinkedHashMap<>();
        draft.fields().forEach(field -> fields.put(field.code(), field.id()));
        ReportDatasets.Source source =
                new ReportDatasets.Source(
                        1,
                        new ReportDatasets.ObjectReference(
                                draft.id(), object.versionNo(), object.checksum()),
                        List.of(),
                        List.of(
                                new ReportDatasets.Field(
                                        "rowkey",
                                        List.of(),
                                        fields.get("rowkey"),
                                        "行组",
                                        "DIMENSION"),
                                new ReportDatasets.Field(
                                        "colkey",
                                        List.of(),
                                        fields.get("colkey"),
                                        "列组",
                                        "DIMENSION"),
                                new ReportDatasets.Field(
                                        "amount",
                                        List.of(),
                                        fields.get("amount"),
                                        "金额",
                                        "MEASURE")));
        ReportDatasets.Detail saved =
                datasets.save(
                        new ReportDatasets.Save(
                                null,
                                0,
                                code,
                                "",
                                source,
                                new ReportDatasets.Analysis(
                                        1,
                                        List.of(
                                                new ReportDatasetQueries.Metric(
                                                        "sum", "金额", "SUM", "amount")),
                                        null,
                                        Map.of(),
                                        "Asia/Shanghai")),
                        ACTOR);
        ownedDatasets.put(saved.id(), code);
        ObjectGrant grant =
                new ObjectGrant(
                        draft.id(),
                        Set.of("READ", "EXPORT"),
                        "ALL",
                        Set.copyOf(fields.values()),
                        Set.of(),
                        Set.of(),
                        Set.of());
        authorization.saveCeiling(
                new ReportAuthorization.SaveCeiling(saved.id(), draft.id(), 0, grant, "容量上限"),
                10003L);
        authorization.saveDataPolicy(
                new ReportAuthorization.SaveDataPolicy(
                        saved.id(),
                        0,
                        List.of(new Member("USER", Long.toString(ACTOR), List.of(grant))),
                        "容量成员"),
                ACTOR);
        ReportDatasets.Release release =
                datasets.publish(
                        new ReportDatasets.Publish(
                                saved.id(),
                                saved.revision(),
                                UUID.randomUUID().toString(),
                                "容量数据集"),
                        ACTOR);
        return new Seed(draft.id(), object.definition().tableName(), release);
    }

    private void load(Seed seed, int records, int rowGroups, int columnGroups) {
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        tx -> {
                            jdbc.update("DELETE FROM " + table(seed));
                            jdbc.update(
                                    "INSERT INTO "
                                            + table(seed)
                                            + "(rowkey,colkey,amount,creator,deleted) SELECT"
                                            + " 'r'||lpad(((n-1)%?)::text,6,'0'),"
                                            + " 'c'||lpad((((n-1)/?)%?)::text,6,'0'),1.00,?,0 FROM"
                                            + " generate_series(1,?) s(n)",
                                    rowGroups,
                                    rowGroups,
                                    columnGroups,
                                    Long.toString(ACTOR),
                                    records);
                            jdbc.execute("ANALYZE " + table(seed));
                        });
    }

    private String table(Seed seed) {
        assertThat(seed.table()).matches("biz_" + fixture.prefix + "[a-z0-9_]+");
        assertThat(ownedObjects).containsKey(seed.objectId());
        return "public.\"" + seed.table() + "\"";
    }

    private ReportDatasetQueries.Query datasetRequest(Seed seed, int limit) {
        return new ReportDatasetQueries.Query(
                seed.dataset().datasetId(),
                seed.dataset().versionNo(),
                seed.dataset().checksum(),
                false,
                List.of(new ReportDatasetQueries.Dimension("rowkey", "VALUE")),
                null,
                Map.of(),
                limit,
                null,
                List.of("sum"));
    }

    private ReportDashboards.Chart chart(
            Seed seed, String id, int index, boolean pivot, int columns) {
        ReportDashboards.Dataset reference =
                new ReportDashboards.Dataset(
                        seed.dataset().datasetId(),
                        seed.dataset().versionNo(),
                        seed.dataset().checksum());
        return new ReportDashboards.Chart(
                id,
                "容量图" + index,
                pivot ? "PIVOT" : "TABLE",
                reference,
                List.of(new ReportDatasetQueries.Dimension("rowkey", "VALUE")),
                List.of("sum"),
                index % 3 * 4,
                index / 3 * 4,
                4,
                3,
                pivot ? List.of(new ReportDatasetQueries.Dimension("colkey", "VALUE")) : null,
                pivot ? new ApplicationReports.Pivot(false, true, true, "NONE", columns) : null);
    }

    private ReportDashboards.Content boardContent(Seed seed, int components) {
        List<ReportDashboards.Chart> charts = new ArrayList<>();
        for (int i = 0; i < components; i++) charts.add(chart(seed, "chart" + i, i, false, 24));
        return new ReportDashboards.Content(1, fixture.prefix + "board" + components, "", charts);
    }

    private void aggregate(ApplicationReports.Result result, int records, int groups, int limit) {
        assertThat(result.recordCount()).isEqualTo(records);
        assertThat(result.totalGroups()).isEqualTo(groups);
        assertThat(result.groups()).hasSize(Math.min(groups, limit));
        assertThat(new BigDecimal(result.totals().get("sum")))
                .isEqualByComparingTo(Integer.toString(records));
    }

    private Map<String, Object> scale(Seed seed, int records, int rowGroups, int columnGroups) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("rowGroups", rowGroups);
        result.put("columnGroups", columnGroups);
        result.put(
                "tableBytes",
                jdbc.queryForObject(
                        "SELECT pg_total_relation_size(?::regclass)", Long.class, table(seed)));
        return result;
    }

    private <T> Measured<T> measure(
            String kind, Map<String, Object> scale, Supplier<T> action, Consumer<T> validate) {
        List<Double> durations = new ArrayList<>();
        List<List<Double>> requestRounds = new ArrayList<>();
        int warmups = warmups(), rounds = integer("report.capacity.samples", 10, 5, 30);
        T value = null;
        Map<String, Object> observation = new LinkedHashMap<>(scale);
        observation.put("kind", kind);
        observation.put("startedAt", Instant.now().toString());
        observation.put("status", "RUNNING");
        observations.add(observation);
        for (int i = 0; i < warmups; i++) {
            observation.put("phase", "warmup");
            observation.put("round", i + 1);
            long started = System.nanoTime();
            try {
                validate.accept(action.get());
            } catch (RuntimeException | AssertionError failure) {
                measurementFailure(observation, failure, started);
                throw failure;
            }
        }
        long gcCount = gcCount(), gcMillis = gcMillis();
        int peakConcurrency = 0;
        try (HeapSampler memory = new HeapSampler()) {
            for (int i = 0; i < rounds; i++) {
                observation.put("phase", "measured");
                observation.put("round", i + 1);
                long started = System.nanoTime();
                double elapsed;
                try {
                    value = action.get();
                    elapsed = millis(System.nanoTime() - started);
                    validate.accept(value);
                } catch (RuntimeException | AssertionError failure) {
                    observation.put("completedRawMillis", List.copyOf(durations));
                    measurementFailure(observation, failure, started);
                    throw failure;
                }
                durations.add(elapsed);
                if (value instanceof BatchResult batch) {
                    requestRounds.add(
                            batch.requests().stream().map(RequestResult::millis).toList());
                    peakConcurrency = Math.max(peakConcurrency, batch.peakConcurrency());
                }
            }
            observation.put("warmups", warmups);
            observation.put("latency", statistics(durations));
            observation.put("heapBeforeBytes", memory.before);
            observation.put("heapPeakObservedBytes", memory.peak.get());
            observation.put(
                    "heapAfterBytes",
                    ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            observation.put("heapSampleIntervalMillis", 10);
            observation.put("gcCollections", gcCount() - gcCount);
            observation.put("gcMillis", gcMillis() - gcMillis);
            if (!requestRounds.isEmpty()) {
                observation.put("requestRounds", requestRounds);
                observation.put("observedPeakConcurrency", peakConcurrency);
            }
        }
        observation.put("status", "PASSED");
        observation.put("finishedAt", Instant.now().toString());
        System.out.println(
                "Capacity sample: "
                        + kind
                        + " records="
                        + scale.get("records")
                        + " groups="
                        + scale.get("rowGroups")
                        + " "
                        + observation.get("latency"));
        return new Measured<>(value, observation);
    }

    private BatchResult batch(ExecutorService pool, ReportDashboards.Release release, int records) {
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
        List<Future<RequestResult>> futures = new ArrayList<>();
        for (ReportDashboards.Chart chart : release.content().charts())
            futures.add(
                    pool.submit(
                            () -> {
                                int concurrency = active.incrementAndGet();
                                peak.accumulateAndGet(concurrency, Math::max);
                                try {
                                    long started = System.nanoTime();
                                    ApplicationReports.Result result =
                                            boardQuery.query(
                                                    new ReportDashboards.Query(
                                                            release.id(),
                                                            chart.id(),
                                                            false,
                                                            release.versionNo(),
                                                            release.checksum()),
                                                    ACTOR);
                                    return new RequestResult(
                                            result, millis(System.nanoTime() - started));
                                } finally {
                                    active.decrementAndGet();
                                }
                            }));
        List<RequestResult> results = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        try {
            for (Future<RequestResult> future : futures) {
                RequestResult result =
                        future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                aggregate(result.result(), records, 300, 100);
                results.add(result);
            }
            return new BatchResult(results, peak.get());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        } catch (ExecutionException | TimeoutException error) {
            throw new IllegalStateException(error);
        } finally {
            futures.forEach(
                    future -> {
                        if (!future.isDone()) future.cancel(true);
                    });
        }
    }

    private void measurementFailure(
            Map<String, Object> observation, Throwable failure, long started) {
        observation.put("status", "FAILED");
        observation.put("finishedAt", Instant.now().toString());
        observation.put("failedRoundElapsedMillis", millis(System.nanoTime() - started));
        observation.put("failureClass", failure.getClass().getName());
        observation.put("message", String.valueOf(failure.getMessage()));
        String state = sqlState(failure);
        if (state != null) observation.put("sqlState", state);
    }

    private BlockedQuery blockedQuery(int blockingPid, Future<?> pending)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && !pending.isDone()) {
            List<Map<String, Object>> blocked =
                    jdbc.queryForList(
                            "SELECT pid,query,wait_event,pg_blocking_pids(pid)::text AS blockers"
                                    + " FROM pg_stat_activity WHERE datname=current_database() AND"
                                    + " usename=current_user AND wait_event_type='Lock' AND"
                                    + " state='active' AND ?=ANY(pg_blocking_pids(pid))",
                            blockingPid);
            if (!blocked.isEmpty()) {
                assertThat(blocked).hasSize(1);
                Map<String, Object> row = blocked.getFirst();
                return new BlockedQuery(
                        ((Number) row.get("pid")).intValue(),
                        (String) row.get("query"),
                        (String) row.get("blockers"),
                        (String) row.get("wait_event"));
            }
            Thread.sleep(50);
        }
        return null;
    }

    private String sqlState(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof SQLException sql && sql.getSQLState() != null)
                return sql.getSQLState();
        return null;
    }

    private void verifyWorkbook(byte[] bytes, String... notes) throws Exception {
        List<String> values = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            for (Sheet sheet : workbook)
                for (Row row : sheet)
                    for (Cell cell : row)
                        if (cell.getCellType() == CellType.STRING)
                            values.add(cell.getStringCellValue());
        }
        for (String note : notes) assertThat(values).anyMatch(value -> value.contains(note));
    }

    private void cleanReports() {
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        tx -> {
                            for (Map.Entry<String, String> board : ownedBoards.entrySet()) {
                                long id = Long.parseLong(board.getKey());
                                assertThat(
                                                jdbc.queryForObject(
                                                        "SELECT count(*) FROM"
                                                                + " public.nocode_report_dashboard"
                                                                + " WHERE id=? AND owner_id=? AND"
                                                                + " name=?",
                                                        Long.class,
                                                        id,
                                                        ACTOR,
                                                        board.getValue()))
                                        .isEqualTo(1L);
                                assertThat(
                                                jdbc.queryForObject(
                                                        "SELECT count(*) FROM"
                                                            + " public.nocode_report_dependency"
                                                            + " WHERE target_kind='DASHBOARD' AND"
                                                            + " target_id=?",
                                                        Long.class,
                                                        id))
                                        .isZero();
                                jdbc.update(
                                        "DELETE FROM public.nocode_report_dependency WHERE"
                                                + " source_kind='DASHBOARD' AND source_id=?",
                                        id);
                                jdbc.update(
                                        "DELETE FROM public.nocode_report_preference WHERE"
                                                + " dashboard_id=?",
                                        id);
                                jdbc.update(
                                        "DELETE FROM public.nocode_report_resource_acl WHERE"
                                                + " resource_kind='DASHBOARD' AND resource_id=?",
                                        id);
                                jdbc.update(
                                        "DELETE FROM public.nocode_report_operation_log WHERE"
                                                + " resource_kind='DASHBOARD' AND resource_id=?",
                                        id);
                                jdbc.update(
                                        "DELETE FROM public.nocode_report_dashboard_version WHERE"
                                                + " dashboard_id=?",
                                        id);
                                assertThat(
                                                jdbc.update(
                                                        "DELETE FROM public.nocode_report_dashboard"
                                                                + " WHERE id=? AND owner_id=? AND"
                                                                + " name=?",
                                                        id,
                                                        ACTOR,
                                                        board.getValue()))
                                        .isEqualTo(1);
                            }
                            for (Map.Entry<String, String> dataset : ownedDatasets.entrySet()) {
                                long id = Long.parseLong(dataset.getKey());
                                assertThat(
                                                jdbc.queryForObject(
                                                        "SELECT count(*) FROM"
                                                            + " public.nocode_report_dataset WHERE"
                                                            + " id=? AND owner_id=? AND name=?",
                                                        Long.class,
                                                        id,
                                                        ACTOR,
                                                        dataset.getValue()))
                                        .isEqualTo(1L);
                                assertThat(
                                                jdbc.queryForObject(
                                                        "SELECT count(*) FROM"
                                                                + " public.nocode_report_dependency"
                                                                + " WHERE target_kind='DATASET' AND"
                                                                + " target_id=?",
                                                        Long.class,
                                                        id))
                                        .isZero();
                                ReportIntegrationSupport.clearAuthorization(dataset.getKey());
                                jdbc.update(
                                        "DELETE FROM public.nocode_resource_dependency WHERE"
                                                + " source_kind='DATASET' AND source_key LIKE ?",
                                        dataset.getKey() + ":%");
                                jdbc.update(
                                        "DELETE FROM public.nocode_report_operation_log WHERE"
                                                + " resource_kind='DATASET' AND resource_id=?",
                                        id);
                                jdbc.update(
                                        "DELETE FROM public.nocode_report_dataset_version WHERE"
                                                + " dataset_id=?",
                                        id);
                                assertThat(
                                                jdbc.update(
                                                        "DELETE FROM public.nocode_report_dataset"
                                                                + " WHERE id=? AND owner_id=? AND"
                                                                + " name=?",
                                                        id,
                                                        ACTOR,
                                                        dataset.getValue()))
                                        .isEqualTo(1);
                            }
                        });
    }

    private static int warmups() {
        return integer("report.capacity.warmups", 2, 1, 5);
    }

    private static int integer(String name, int fallback, int minimum, int maximum) {
        int value = Integer.parseInt(System.getProperty(name, Integer.toString(fallback)));
        if (value < minimum || value > maximum)
            throw new IllegalArgumentException(name + " 超出测试限定范围");
        return value;
    }

    private static int[] levels(String name, String fallback, int minimum, int maximum) {
        int[] values =
                Arrays.stream(System.getProperty(name, fallback).split(","))
                        .mapToInt(value -> Integer.parseInt(value.trim()))
                        .toArray();
        if (values.length < 2 || values.length > 5)
            throw new IllegalArgumentException(name + " 需配置2至5档");
        int previous = 0;
        for (int value : values) {
            if (value < minimum || value > maximum || value <= previous)
                throw new IllegalArgumentException(name + " 必须在限定范围内递增");
            previous = value;
        }
        return values;
    }

    private static Map<String, Object> statistics(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("samples", sorted.size());
        result.put("p50Millis", sorted.get((int) Math.ceil(sorted.size() * 0.50) - 1));
        result.put("p95Millis", sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1));
        result.put("minMillis", sorted.getFirst());
        result.put("maxMillis", sorted.getLast());
        result.put("rawMillis", values);
        return result;
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> Math.max(0, bean.getCollectionCount()))
                .sum();
    }

    private static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> Math.max(0, bean.getCollectionTime()))
                .sum();
    }

    /** 只观察当前测试JVM堆；采样峰值是下界，不能解释为数据库、浏览器或整个部署的峰值。 */
    private static final class HeapSampler implements AutoCloseable {
        private final long before =
                ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        private final AtomicLong peak = new AtomicLong(before);
        private final ScheduledExecutorService sampler =
                Executors.newSingleThreadScheduledExecutor();

        private HeapSampler() {
            sampler.scheduleAtFixedRate(
                    () ->
                            peak.accumulateAndGet(
                                    ManagementFactory.getMemoryMXBean()
                                            .getHeapMemoryUsage()
                                            .getUsed(),
                                    Math::max),
                    0,
                    10,
                    TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() {
            sampler.shutdownNow();
        }
    }
}
