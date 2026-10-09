package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationRecords.*;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.application.service.sharing.ObjectSharingService;
import com.lingan.ucp.nocode.runtime.service.record.RecordService;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** 独立运行的大组完整事务测量；不是单次窗口 SELECT 的微基准。 */
@Tag("ordered-performance")
class OrderedCalculationPerformanceTest {
    private NocodeIntegrationSupport fixture;
    private RecordService runtime;
    private DataCenter.Definition definition;
    private String application;

    @BeforeAll
    static void open() throws Exception {
        connect();
        session.getSqlSessionFactory().getConfiguration().addInterceptor(new StatementCounter());
    }

    @AfterAll
    static void shutdown() {
        close();
    }

    @BeforeEach
    void announce(TestInfo test) {
        System.out.println("ORDERED_PERFORMANCE starting=" + test.getDisplayName());
    }

    @BeforeEach
    void setup() {
        fixture = new NocodeIntegrationSupport();
        fixture.name();
        runtime = servicesContext.getBean(RecordService.class);
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

    @Test
    void runningTotalGroupWithOneHundredRows() {
        measureGroup("RUNNING_TOTAL", 100);
    }

    @Test
    void runningTotalGroupWithOneThousandRows() {
        measureGroup("RUNNING_TOTAL", 1000);
    }

    @Test
    void safeFiveHundredRowImportComputesGroupOnceAndSealsFinalHistory() {
        configure("RUNNING_TOTAL");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 1; index <= 500; index++)
            rows.add(
                    Map.of(
                            field("name"),
                            "批量" + index,
                            field("account"),
                            "A",
                            field("sequence"),
                            index,
                            field("incoming"),
                            "1"));
        Map<String, Long> statements = new LinkedHashMap<>();
        long started = System.nanoTime();
        StatementCounter.ACTIVE.set(statements);
        try {
            assertThat(runtime.importRecords(application, definition.objectId(), rows, 10001))
                    .isEqualTo(500);
        } finally {
            StatementCounter.ACTIVE.remove();
            System.out.println(
                    "ORDERED_PERFORMANCE algorithm=RUNNING_TOTAL rows=500 operation=import"
                            + " elapsedMs="
                            + (System.nanoTime() - started) / 1_000_000
                            + " statements="
                            + statements);
        }
        assertThat(statements.getOrDefault("runningTotals", 0L)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_record_history WHERE"
                                        + " object_id=? AND operation='CREATE'",
                                Long.class,
                                Long.parseLong(definition.objectId())))
                .isEqualTo(500);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_record_history WHERE"
                                        + " object_id=? AND operation='CREATE' AND"
                                        + " (after_json->'values'->>?) IS NULL",
                                Long.class,
                                Long.parseLong(definition.objectId()),
                                field("balance")))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT max(balance) FROM public.\""
                                        + definition.tableName()
                                        + "\"",
                                BigDecimal.class))
                .isEqualByComparingTo("500");
    }

    @Test
    void runningTotalGroupWithFiftyThousandRows() {
        measureGroup("RUNNING_TOTAL", 50000);
    }

    @Test
    void storedPageOfTwentyRowsDoesNotExecuteAnyOrderedWindow() {
        configure("RUNNING_TOTAL");
        seed(50000);
        measured("RUNNING_TOTAL", 50000, "list-20", this::storedPageWithoutOrderedWindows);
    }

    @Test
    void normalTailAppendAfterHistoryBaselineAndStoredListWithFiftyThousandRows() {
        configure("RUNNING_TOTAL");
        seed(50000);
        // 首次正常保存建立历史基线，其一次性成本不混入日常追加测量。
        Row initialized = save(null, 50001, "1");
        assertThat(new BigDecimal(initialized.values().get(field("balance")).toString()))
                .isEqualByComparingTo("50001");
        Row appended =
                measured(
                        "RUNNING_TOTAL",
                        50001,
                        "normal-tail-insert",
                        () -> {
                            Row result = save(null, 50002, "1");
                            assertThat(StatementCounter.ACTIVE.get().getOrDefault("baseline", 0L))
                                    .isZero();
                            assertThat(
                                            StatementCounter.ACTIVE
                                                    .get()
                                                    .getOrDefault("runningTotals", 0L))
                                    .isEqualTo(1);
                            return result;
                        });
        assertThat(new BigDecimal(appended.values().get(field("balance")).toString()))
                .isEqualByComparingTo("50002");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT balance FROM public.\""
                                        + definition.tableName()
                                        + "\" WHERE sequence=50002",
                                BigDecimal.class))
                .isEqualByComparingTo("50002");
        measured("RUNNING_TOTAL", 50002, "normal-list-20", this::storedPageWithoutOrderedWindows);
    }

    private Row storedPageWithoutOrderedWindows() {
        List<Row> page =
                runtime.page(
                                new Query(
                                        application,
                                        definition.objectId(),
                                        1,
                                        20,
                                        null,
                                        Map.of(),
                                        field("sequence"),
                                        false),
                                10001)
                        .getList();
        assertThat(page).hasSize(20);
        for (String query :
                List.of("runningTotal", "runningTotals", "sequenceRows", "sequenceValues"))
            assertThat(StatementCounter.ACTIVE.get().getOrDefault(query, 0L)).as(query).isZero();
        return page.getFirst();
    }

    @Test
    void sequenceGroupReachesTenThousandRowsWithoutPartialResults() {
        // 为首、中、尾三个新增留出位置，最终精确达到既有 10000 行边界。
        measureGroup("SEQUENCE", 9997);
        List<String> before = stored();
        assertThatThrownBy(() -> save(null, 20000, "1")).hasMessageContaining("10000");
        assertThat(stored()).isEqualTo(before);
    }

    private void measureGroup(String algorithm, int rows) {
        configure(algorithm);
        seed(rows);
        measureSeededGroup(algorithm, rows);
    }

    private void seed(int rows) {
        jdbc.execute(
                "INSERT INTO public.\""
                        + definition.tableName()
                        + "\" (name, account, sequence, incoming, balance, creator, updater,"
                        + " create_time, update_time, deleted) SELECT '种子' || n, 'A', n, 1, n,"
                        + " '10001', '10001', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0 FROM"
                        + " generate_series(1,"
                        + rows
                        + ") n");
    }

    private void measureSeededGroup(String algorithm, int rows) {
        // SQL 只构造可准确识别的测试初始数据；被测变更全部经过正式保存和历史链路。
        measured(algorithm, rows, "tail-insert", () -> save(null, rows + 1, "1"));
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
        measured(algorithm, rows + 1, "first-change", () -> save(first, 1, "2"));
        measured(algorithm, rows + 1, "head-insert", () -> save(null, 0, "1"));
        measured(algorithm, rows + 2, "middle-insert", () -> save(null, rows / 2, "1"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM (SELECT balance, SUM(incoming) OVER (ORDER BY"
                                    + " sequence, id ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT"
                                    + " ROW) AS expected FROM public.\""
                                        + definition.tableName()
                                        + "\" WHERE deleted=0) t"
                                        + " WHERE balance IS DISTINCT FROM expected",
                                Long.class))
                .isZero();
    }

    private Row measured(String algorithm, int rows, String operation, Supplier<Row> action) {
        Map<String, Long> statements = new LinkedHashMap<>();
        StatementCounter.ACTIVE.set(statements);
        long started = System.nanoTime();
        Row result;
        try {
            result = action.get();
        } finally {
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            StatementCounter.ACTIVE.remove();
            System.out.println(
                    "ORDERED_PERFORMANCE algorithm="
                            + algorithm
                            + " rows="
                            + rows
                            + " operation="
                            + operation
                            + " elapsedMs="
                            + elapsed
                            + " statements="
                            + statements);
        }
        assertThat(result.revision())
                .isEqualTo(
                        runtime.get(application, definition.objectId(), result.id(), 10001)
                                .record()
                                .revision());
        return result;
    }

    private Row save(Row previous, int sequence, String amount) {
        Map<String, Object> values =
                Map.of(
                        field("name"),
                        "测量" + sequence,
                        field("account"),
                        "A",
                        field("sequence"),
                        sequence,
                        field("incoming"),
                        amount);
        return runtime.save(
                        new Save(
                                application,
                                definition.objectId(),
                                previous == null ? null : previous.id(),
                                previous == null ? null : previous.revision(),
                                values,
                                null),
                        10001)
                .record();
    }

    private void configure(String algorithm) {
        List<FieldDefinition> fields =
                List.of(
                        new FieldDefinition(
                                "name", null, "name", "名称", "TEXT", 100, null, null, false, false,
                                0),
                        new FieldDefinition(
                                "account", null, "account", "分组", "TEXT", 100, null, null, false,
                                false, 1),
                        fixture.field("sequence", "sequence", "INTEGER", 2),
                        new FieldDefinition(
                                "incoming",
                                null,
                                "incoming",
                                "数值",
                                "DECIMAL",
                                null,
                                38,
                                10,
                                false,
                                false,
                                3),
                        fixture.field("balance", "balance", "FORMULA", 4));
        boolean sequence = "SEQUENCE".equals(algorithm);
        CalculationOptions calculation =
                new CalculationOptions(
                        algorithm,
                        "ON_SAVE",
                        null,
                        null,
                        sequence ? null : "incoming",
                        sequence ? null : "SUM",
                        "AND",
                        List.of(),
                        false,
                        List.of("account"),
                        sequence
                                ? null
                                : new CalculationOptions.RunningTotal(
                                        "sequence", null, null, "0", null),
                        sequence
                                ? new CalculationOptions.Sequence(
                                        "sequence", null, "PREVIOUS", "CUMULATIVE", "0")
                                : null);
        DataCenter.FieldOptions option =
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
                        sequence ? "incoming" : null,
                        "DECIMAL",
                        "NONE",
                        null,
                        false,
                        false,
                        null,
                        calculation);
        SaveObjectDraft base = fixture.createRequest("orderedperf");
        DataCenter.Design design =
                designs.save(
                        new DataCenter.SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        base.objectCode(),
                                        "有序完整事务性能夹具",
                                        null,
                                        base.tableName(),
                                        "name",
                                        fields,
                                        List.of()),
                                DataCenter.Settings.defaults(),
                                Map.of("balance", option),
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
                                .execute(new DataCenter.ExecutePlan(plan.id(), "有序性能夹具"), 10001)
                                .state())
                .isEqualTo("SUCCEEDED");
        DataObjectApi.PublishedObject version =
                servicesContext.getBean(DataObjectApi.class).getVersion(design.draft().id(), null);
        definition = version.definition();
        System.out.println(
                "ORDERED_PERFORMANCE_FIXTURE objectId="
                        + definition.objectId()
                        + " prefix="
                        + fixture.prefix
                        + " table="
                        + definition.tableName()
                        + " algorithm="
                        + algorithm);
        ApplicationService applications = servicesContext.getBean(ApplicationService.class);
        ApplicationCenter.Detail app =
                applications.save(
                        new ApplicationCenter.Save(
                                null,
                                null,
                                fixture.prefix + "perfapp",
                                "有序性能验证",
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        List.of(
                                                new ApplicationCenter.ObjectReference(
                                                        version.objectId(),
                                                        version.versionNo(),
                                                        version.checksum())),
                                        List.of())),
                        10001);
        application = app.application().id();
        grantApplicationObjects(application);
        ObjectSharingService sharing = servicesContext.getBean(ObjectSharingService.class);
        ObjectSharing.Grant previous =
                sharing.forApplication(application).stream()
                        .filter(item -> item.objectId().equals(definition.objectId()))
                        .findFirst()
                        .orElseThrow();
        ApplicationAuthorization.ObjectGrant grant =
                NocodeIntegrationSupport.resolvedPermission(definition.objectId(), application);
        sharing.save(
                new ObjectSharing.Save(
                        definition.objectId(),
                        application,
                        previous.revision(),
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
                        "性能夹具计算取数"),
                10001);
        applications.publish(new ApplicationCenter.Revision(application, 0, "性能夹具"), 10001);
    }

    private String field(String code) {
        return definition.fields().stream()
                .filter(item -> item.code().equals(code))
                .findFirst()
                .orElseThrow()
                .id();
    }

    private List<String> stored() {
        return jdbc.queryForList(
                "SELECT to_jsonb(t)::text FROM public.\""
                        + definition.tableName()
                        + "\" t ORDER BY id",
                String.class);
    }

    /** 计数包含最终读取及历史写入；仅测量线程激活时统计，避免影响其他回归。 */
    @Intercepts({
        @Signature(
                type = Executor.class,
                method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(
                type = Executor.class,
                method = "update",
                args = {MappedStatement.class, Object.class}),
        @Signature(
                type = StatementHandler.class,
                method = "prepare",
                args = {Connection.class, Integer.class})
    })
    public static class StatementCounter implements Interceptor {
        static final ThreadLocal<Map<String, Long>> ACTIVE = new ThreadLocal<>();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Map<String, Long> current = ACTIVE.get();
            if (current != null) {
                if (invocation.getArgs()[0] instanceof MappedStatement mapped) {
                    current.merge("allMapperCalls", 1L, Long::sum);
                    String id = mapped.getId();
                    if (id.contains("RecordMapper.") || id.contains("RecordHistoryMapper."))
                        current.merge(id.substring(id.lastIndexOf('.') + 1), 1L, Long::sum);
                } else current.merge("preparedStatements", 1L, Long::sum);
            }
            return invocation.proceed();
        }
    }
}
