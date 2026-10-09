package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationRecords.*;
import com.richuang.os.nocode.application.service.application.ApplicationService;
import com.richuang.os.nocode.application.service.resource.ApplicationDateTriggers;
import com.richuang.os.nocode.metadata.service.formula.FormulaDates;
import com.richuang.os.nocode.runtime.job.application.DateTriggerScheduler;
import com.richuang.os.nocode.runtime.service.record.RecordDateTriggers;
import com.richuang.os.nocode.runtime.service.record.RecordService;

import org.junit.jupiter.api.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * 业务动作「按日期自动执行」与放宽后的字段共写：连本机测试库，只创建、删除随机前缀的自有对象和应用。
 *
 * <p>业务场景（派单书）：房间状态 = 空房待清扫 / 空房已清扫 / 入住中 / 入住待清扫。入住日到了 → 入住中；退房日到了 → 空房待清扫； 新增退房清扫 →
 * 空房已清扫；手改入住待清扫后新增追加清扫 → 入住中。四条规则写同一个字段（两条按日期 + 两条事件赋值）。
 */
class DateTriggerIntegrationTest {
    private static final String DIRTY = "dirty", CLEAN = "clean", OCCUPIED = "occupied";
    private static final String OCCUPIED_DIRTY = "occupied_dirty";

    private NocodeIntegrationSupport fixture;
    private ApplicationService apps;
    private RecordService runtime;
    private DataObjectApi objects;
    private RecordDateTriggers triggers;
    private DateTriggerScheduler scheduler;
    private DataCenter.Definition room, stay, cleaning;
    private String app, stayRoom, cleaningRoom;
    private int serial;
    private LocalDate today;
    private LocalDateTime noon;
    private final RecordDateTriggers.Settings settings =
            new RecordDateTriggers.Settings(LocalTime.of(0, 10), 10);

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
        triggers = servicesContext.getBean(RecordDateTriggers.class);
        scheduler = servicesContext.getBean(DateTriggerScheduler.class);
        today = FormulaDates.today();
        noon = today.atTime(12, 0);
    }

    @AfterEach
    void cleanup() {
        for (Long id :
                jdbc.queryForList(
                        "SELECT id FROM public.nocode_application WHERE app_code LIKE ?",
                        Long.class,
                        fixture.prefix + "%")) {
            jdbc.update("DELETE FROM public.nocode_date_trigger_done WHERE application_id=?", id);
            jdbc.update("DELETE FROM public.nocode_date_trigger_state WHERE application_id=?", id);
            jdbc.update(
                    "DELETE FROM public.nocode_object_application_grant_log WHERE application_id=?",
                    id);
            jdbc.update(
                    "DELETE FROM public.nocode_object_application_grant WHERE application_id=?",
                    id);
            jdbc.update("DELETE FROM public.nocode_application_access WHERE application_id=?", id);
            jdbc.update("DELETE FROM public.nocode_linkage_trigger WHERE application_id=?", id);
            jdbc.update("DELETE FROM public.nocode_application_version WHERE application_id=?", id);
            jdbc.update("DELETE FROM public.nocode_application WHERE id=?", id);
        }
        fixture.clean();
    }

    // ── 夹具 ──

    private final Map<String, String> tables = new HashMap<>();

    private DataCenter.Definition object(
            String name,
            List<FieldDefinition> fields,
            List<DataCenter.Relation> relations,
            Map<String, DataCenter.FieldOptions> options) {
        var request = fixture.createRequest("object" + serial++);
        var design =
                designs.save(
                        new DataCenter.SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        request.objectCode(),
                                        name,
                                        null,
                                        request.tableName(),
                                        "name",
                                        fields,
                                        List.of()),
                                DataCenter.Settings.defaults(),
                                options,
                                relations,
                                List.of(),
                                List.of()),
                        10001);
        var plan =
                publisher.plan(
                        new DataCenter.Revision(
                                design.draft().id(), design.draft().lockVersion(), null),
                        10001);
        assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "按日期执行测试"), 10001)
                                .state())
                .isEqualTo("SUCCEEDED");
        tables.put(design.draft().id(), request.tableName());
        return objects.getPublished(design.draft().id());
    }

    private FieldDefinition f(String code, String type) {
        return fixture.field(code, code, type, serial++);
    }

    private static DataCenter.FieldOptions select(String... codes) {
        List<DataCenter.Option> options = new ArrayList<>();
        for (String code : codes) options.add(new DataCenter.Option(code, code, false));
        return new DataCenter.FieldOptions(
                null, null, null, null, null, null, null, "ACTIVE", options, null, null, null, null,
                false, false);
    }

    private static DataCenter.Relation reference(String code, DataCenter.Definition target) {
        return new DataCenter.Relation(
                null,
                code,
                "所属" + code,
                "REFERENCE",
                target.objectId(),
                null,
                null,
                false,
                "SET_NULL",
                null);
    }

    private static String field(DataCenter.Definition d, String code) {
        return d.fields().stream()
                .filter(f -> f.code().equals(code))
                .findFirst()
                .orElseThrow()
                .id();
    }

    private ApplicationCenter.ObjectReference ref(DataCenter.Definition d) {
        var v = objects.getVersion(d.objectId(), null);
        return new ApplicationCenter.ObjectReference(v.objectId(), v.versionNo(), v.checksum());
    }

    private static ApplicationCenter.Resource resource(
            String id, ApplicationAutomations.Config config) {
        return new ApplicationCenter.Resource(
                id,
                "AUTOMATION",
                id,
                "规则" + id,
                mapper.convertValue(config, new TypeReference<Map<String, Object>>() {}));
    }

    /** 按日期：通过来源上的引用找到目标（OUTGOING）。 */
    private ApplicationAutomations.Config dated(
            DataCenter.Definition source,
            DataCenter.Definition target,
            String relation,
            String dateField,
            int offset,
            DataScope conditions,
            String targetField,
            Object value) {
        return new ApplicationAutomations.Config(
                source.objectId(),
                target.objectId(),
                true,
                "DATE",
                Set.of(),
                conditions,
                relation == null
                        ? new ApplicationAutomations.Binding(null, ApplicationAutomations.SELF)
                        : new ApplicationAutomations.Binding(relation, "OUTGOING"),
                List.of(
                        new ApplicationAutomations.Assignment(
                                field(target, targetField), "VALUE", null, value, null)),
                field(source, dateField),
                offset);
    }

    private ApplicationAutomations.Config event(
            DataCenter.Definition source,
            DataCenter.Definition target,
            String relation,
            Set<String> events,
            DataScope conditions,
            String targetField,
            Object value) {
        return new ApplicationAutomations.Config(
                source.objectId(),
                target.objectId(),
                true,
                "EVENT",
                events,
                conditions,
                new ApplicationAutomations.Binding(relation, "OUTGOING"),
                List.of(
                        new ApplicationAutomations.Assignment(
                                field(target, targetField), "VALUE", null, value, null)));
    }

    private String application(
            List<DataCenter.Definition> referenced, List<ApplicationCenter.Resource> rules) {
        var a =
                apps.save(
                        new ApplicationCenter.Save(
                                null,
                                null,
                                fixture.prefix + "app" + serial++,
                                "按日期执行测试",
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        referenced.stream().map(this::ref).toList(), rules)),
                        10001);
        grantApplicationObjects(a.application().id());
        apps.publish(
                new ApplicationCenter.Revision(
                        a.application().id(), a.application().revision(), "测试"),
                10001);
        return a.application().id();
    }

    private static DataScope kind(DataCenter.Definition d, String value) {
        return new DataScope(
                "AND", List.of(new DataScope.Condition(field(d, "kind"), "eq", value)), List.of());
    }

    /**
     * 房间 / 入住记录 / 清扫记录，以及写「房间状态」的四条规则。按日期两条在前，且「入住日」排在「退房日」之前：同一天的规则按列表先后执行、后执行的为准， 业务方 2026-10-03
     * 定的口径是同日先退房后入住的房间当天显示「空房待清扫」，所以退房那条要排在后面。
     */
    private void hotel() {
        room =
                object(
                        "房间",
                        List.of(f("name", "TEXT"), f("status", "SELECT"), f("floor", "TEXT")),
                        List.of(),
                        Map.of("status", select(DIRTY, CLEAN, OCCUPIED, OCCUPIED_DIRTY)));
        stay =
                object(
                        "入住记录",
                        List.of(f("name", "TEXT"), f("checkin", "DATE"), f("checkout", "DATE")),
                        List.of(reference("stay_room", room)),
                        Map.of());
        cleaning =
                object(
                        "清扫记录",
                        List.of(f("name", "TEXT"), f("kind", "SELECT")),
                        List.of(reference("clean_room", room)),
                        Map.of("kind", select("checkout", "extra")));
        stayRoom = stay.relations().getFirst().id();
        cleaningRoom = cleaning.relations().getFirst().id();
        app =
                application(
                        List.of(room, stay, cleaning),
                        List.of(
                                resource(
                                        "checkin_due",
                                        dated(
                                                stay, room, stayRoom, "checkin", 0, null, "status",
                                                OCCUPIED)),
                                resource(
                                        "checkout_due",
                                        dated(
                                                stay,
                                                room,
                                                stayRoom,
                                                "checkout",
                                                0,
                                                null,
                                                "status",
                                                DIRTY)),
                                resource(
                                        "cleaned",
                                        event(
                                                cleaning,
                                                room,
                                                cleaningRoom,
                                                Set.of("CREATE"),
                                                kind(cleaning, "checkout"),
                                                "status",
                                                CLEAN)),
                                resource(
                                        "extra_cleaned",
                                        event(
                                                cleaning,
                                                room,
                                                cleaningRoom,
                                                Set.of("CREATE"),
                                                kind(cleaning, "extra"),
                                                "status",
                                                OCCUPIED))));
    }

    private Row create(DataCenter.Definition d, Map<String, Object> values) {
        return runtime.save(new Save(app, d.objectId(), null, null, values, null), 10001).record();
    }

    private Row room(String name, String status) {
        return create(room, Map.of(field(room, "name"), name, field(room, "status"), status));
    }

    private Row stay(Row target, LocalDate checkin, LocalDate checkout) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(field(stay, "name"), "入住" + serial++);
        values.put(field(stay, "checkin"), checkin.toString());
        values.put(field(stay, "checkout"), checkout.toString());
        values.put(stay.relations().getFirst().fieldId(), target.id());
        return create(stay, values);
    }

    private Row clean(Row target, String kind) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(field(cleaning, "name"), "清扫" + serial++);
        values.put(field(cleaning, "kind"), kind);
        values.put(cleaning.relations().getFirst().fieldId(), target.id());
        return create(cleaning, values);
    }

    private Row read(DataCenter.Definition d, String id) {
        return runtime.get(app, d.objectId(), id, 10001).record();
    }

    private Object status(Row target) {
        return read(room, target.id()).values().get(field(room, "status"));
    }

    private int dated(DataCenter.Definition d, Row row) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM public.nocode_record_history WHERE object_id=? AND"
                        + " record_id::text=? AND source_json->>'kind'='DATE_TRIGGER'",
                Integer.class,
                Long.valueOf(d.objectId()),
                row.id());
    }

    private int done(String outcome) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM public.nocode_date_trigger_done WHERE application_id=? AND"
                        + " (CAST(? AS text) IS NULL OR outcome=CAST(? AS text))",
                Integer.class,
                Long.valueOf(app),
                outcome,
                outcome);
    }

    private void rewind(int days) {
        jdbc.update(
                "UPDATE public.nocode_date_trigger_state SET closed_date=closed_date-?,"
                        + " last_scan_at=NULL WHERE application_id=?",
                days,
                Long.valueOf(app));
    }

    private LocalDate closed(String resource) {
        return jdbc.queryForObject(
                "SELECT closed_date FROM public.nocode_date_trigger_state WHERE application_id=?"
                        + " AND resource_id=?",
                LocalDate.class,
                Long.valueOf(app),
                resource);
    }

    private DateTriggerRuns.Result run(LocalDateTime now) {
        return triggers.run(now, settings, null, null, false);
    }

    // ── 用例 ──

    /** 派单书验收场景：四个状态、下周预订不受影响、按日期规则不随数据变化触发、当天重扫不重复、人工可改。 */
    @Test
    void hotelRoomStatusFollowsDatesAndCleaning() {
        hotel();
        // 发布即建账：从今天起算（封账日 = 昨天）。
        assertThat(closed("checkout_due")).isEqualTo(today.minusDays(1));
        assertThat(closed("checkin_due")).isEqualTo(today.minusDays(1));
        var arriving = room("101", CLEAN);
        var leaving = room("102", OCCUPIED);
        var nextWeek = room("103", CLEAN);
        var turnover = room("104", CLEAN);
        stay(arriving, today, today.plusDays(2));
        stay(leaving, today.minusDays(3), today);
        stay(nextWeek, today.plusDays(7), today.plusDays(9));
        stay(turnover, today.minusDays(2), today); // 上一位今天退房
        stay(turnover, today, today.plusDays(3)); // 下一位今天入住
        // 按日期规则不进事件钩子：新增入住记录当下不改房间。
        assertThat(status(arriving)).isEqualTo(CLEAN);
        assertThat(status(leaving)).isEqualTo(OCCUPIED);

        var result = run(noon);

        assertThat(result.businessDate()).isEqualTo(today.toString());
        assertThat(status(arriving)).isEqualTo(OCCUPIED);
        assertThat(status(leaving)).isEqualTo(DIRTY);
        assertThat(status(nextWeek)).as("下周的预订今天不受影响").isEqualTo(CLEAN);
        assertThat(status(turnover)).as("同日先退房后入住：当天显示空房待清扫（后执行的为准）").isEqualTo(DIRTY);
        assertThat(dated(room, turnover)).as("空房已清扫 → 入住中 → 空房待清扫：两条规则按列表先后各写一次").isEqualTo(2);
        assertThat(dated(room, arriving)).isEqualTo(1);
        assertThat(dated(room, nextWeek)).isZero();
        var history =
                jdbc.queryForMap(
                        "SELECT source_json->>'name' AS name, source_json->>'businessDate' AS day,"
                                + " creator FROM public.nocode_record_history WHERE object_id=? AND"
                                + " record_id::text=? AND source_json->>'kind'='DATE_TRIGGER'",
                        Long.valueOf(room.objectId()),
                        arriving.id());
        assertThat(history.get("name")).isEqualTo("规则checkin_due");
        assertThat(history.get("day")).isEqualTo(today.toString());

        clean(leaving, "checkout");
        assertThat(status(leaving)).isEqualTo(CLEAN);
        // 「入住待清扫」靠人工：被一次性赋值写的字段允许手改。
        var current = read(room, arriving.id());
        runtime.save(
                new Save(
                        app,
                        room.objectId(),
                        arriving.id(),
                        current.revision(),
                        Map.of(field(room, "status"), OCCUPIED_DIRTY),
                        null),
                10001);
        assertThat(status(arriving)).isEqualTo(OCCUPIED_DIRTY);
        clean(arriving, "extra");
        assertThat(status(arriving)).isEqualTo(OCCUPIED);

        // 当天重扫（间隔已过）：处理过的记录不再处理，人工 / 事件改过的状态不被改回。
        int before = done(null);
        runtime.save(
                new Save(
                        app,
                        room.objectId(),
                        leaving.id(),
                        read(room, leaving.id()).revision(),
                        Map.of(field(room, "status"), OCCUPIED_DIRTY),
                        null),
                10001);
        jdbc.update(
                "UPDATE public.nocode_date_trigger_state SET last_scan_at=NULL WHERE"
                        + " application_id=?",
                Long.valueOf(app));
        run(noon.plusMinutes(30));
        assertThat(done(null)).isEqualTo(before);
        assertThat(status(leaving)).isEqualTo(OCCUPIED_DIRTY);
        assertThat(dated(room, arriving)).isEqualTo(1);
        assertThat(closed("checkin_due")).as("当天不封账").isEqualTo(today.minusDays(1));
    }

    /** 当天晚些时候录入的、日期为今天的记录：下一次重扫补上；执行时刻之前不处理今天。 */
    @Test
    void lateEntriesOfTodayAreCaughtByRescanButNotBeforeRunTime() {
        hotel();
        var early = room("201", CLEAN);
        stay(early, today, today.plusDays(1));
        // 00:05：今天的执行时刻 00:10 未到，当前业务日是昨天（昨天已封账，无事可做）。
        var before = run(today.atTime(0, 5));
        assertThat(before.businessDate()).isEqualTo(today.minusDays(1).toString());
        assertThat(status(early)).isEqualTo(CLEAN);
        run(today.atTime(0, 10));
        assertThat(status(early)).isEqualTo(OCCUPIED);

        var late = room("202", CLEAN);
        stay(late, today, today.plusDays(1));
        run(today.atTime(0, 11)); // 距上次扫描不到 10 分钟：节流，不扫
        assertThat(status(late)).isEqualTo(CLEAN);
        jdbc.update(
                "UPDATE public.nocode_date_trigger_state SET last_scan_at=last_scan_at - interval"
                        + " '11 minutes' WHERE application_id=?",
                Long.valueOf(app));
        run(today.atTime(0, 30));
        assertThat(status(late)).isEqualTo(OCCUPIED);
        assertThat(dated(room, early)).isEqualTo(1);
    }

    /** 补跑：把账本往前拨 3 天模拟停机；漏掉的每一天按日期先后执行（后一天覆盖前一天），再拨回去跑一次不重复。 */
    @Test
    void catchesUpMissedDaysInDateOrderExactlyOnce() {
        hotel();
        var a = room("301", CLEAN); // 前天入住
        var b = room("302", OCCUPIED); // 昨天退房
        var c = room("303", CLEAN); // 3 天前退房（上一位）、前天入住（下一位） ⇒ 入住中
        var d = room("304", CLEAN); // 3 天前入住、昨天退房 ⇒ 空房待清扫
        var e = room("305", CLEAN); // 4 天前的日期：封账日之前，不补
        stay(a, today.minusDays(2), today.plusDays(1));
        stay(b, today.minusDays(5), today.minusDays(1));
        stay(c, today.minusDays(6), today.minusDays(3));
        stay(c, today.minusDays(2), today.plusDays(3));
        stay(d, today.minusDays(3), today.minusDays(1));
        stay(e, today.minusDays(4), today.plusDays(4));
        rewind(3); // 封账日 = 今天 − 4：漏掉了 今天−3、今天−2、今天−1

        run(noon);

        assertThat(status(a)).isEqualTo(OCCUPIED);
        assertThat(status(b)).isEqualTo(DIRTY);
        assertThat(status(c)).as("日期在外层：前天的入住覆盖 3 天前的退房").isEqualTo(OCCUPIED);
        assertThat(status(d)).as("昨天的退房覆盖 3 天前的入住").isEqualTo(DIRTY);
        assertThat(status(e)).as("封账日之前的日期不补").isEqualTo(CLEAN);
        assertThat(closed("checkin_due")).isEqualTo(today.minusDays(1));
        assertThat(
                        jdbc.queryForList(
                                "SELECT DISTINCT business_date FROM public.nocode_date_trigger_done"
                                        + " WHERE application_id=? ORDER BY 1",
                                LocalDate.class,
                                Long.valueOf(app)))
                .containsExactly(today.minusDays(3), today.minusDays(2), today.minusDays(1));
        int rows = done(null);
        int histories = dated(room, c);
        assertThat(histories).isEqualTo(2);

        rewind(3);
        run(noon.plusHours(1));

        assertThat(done(null)).as("同一天同一条记录不重复执行").isEqualTo(rows);
        assertThat(dated(room, c)).isEqualTo(histories);
        assertThat(status(c)).isEqualTo(OCCUPIED);
    }

    /** 两个实例同时到点（直接并发调运行，不经 Redisson 锁）：每条来源记录只被处理一次。 */
    @Test
    void concurrentRunsProcessEachRecordOnce() throws Exception {
        hotel();
        List<Row> rooms = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            var r = room("4" + i, CLEAN);
            stay(r, today, today.plusDays(1));
            rooms.add(r);
        }
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            List<Future<DateTriggerRuns.Result>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++)
                results.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return run(noon);
                                }));
            start.countDown();
            int success = 0, unchanged = 0;
            for (var r : results) {
                var result = r.get(120, TimeUnit.SECONDS);
                success += result.success();
                unchanged += result.unchanged();
                assertThat(result.failed()).isZero();
            }
            assertThat(success).isEqualTo(12);
            // 后到的一方在认领上让路（跳过），不会再把同一条记录处理一遍（处理一遍就是「已是该值」）。
            assertThat(unchanged).as("同一条记录不被两边各处理一次").isZero();
        } finally {
            pool.shutdownNow();
        }
        for (var r : rooms) {
            assertThat(status(r)).isEqualTo(OCCUPIED);
            assertThat(dated(room, r)).as("历史只有一份").isEqualTo(1);
        }
        assertThat(done("SUCCESS")).isEqualTo(12);
    }

    /** 单条失败不影响其他，失败原因可查；立即按今天执行会重试失败的那一条。 */
    @Test
    void oneFailureDoesNotStopOthersAndManualRunRetriesIt() {
        hotel();
        var ok =
                create(
                        room,
                        Map.of(
                                field(room, "name"),
                                "501",
                                field(room, "status"),
                                CLEAN,
                                field(room, "floor"),
                                "5F"));
        var broken = room("502", CLEAN);
        stay(ok, today, today.plusDays(1));
        stay(broken, today, today.plusDays(1));
        // 让一间房的保存必然失败：库表上加一条「没填楼层的房间不能是入住中」的约束（NOT VALID：不检查已有行）。
        String table = tables.get(room.objectId());
        String column = column(room, "floor"), state = column(room, "status");
        jdbc.execute(
                "ALTER TABLE public."
                        + table
                        + " ADD CONSTRAINT dt_fixture_floor CHECK ("
                        + column
                        + " IS NOT NULL OR "
                        + state
                        + " IS DISTINCT FROM '"
                        + OCCUPIED
                        + "') NOT VALID");
        try {
            var result = run(noon);
            assertThat(result.success()).isEqualTo(1);
            assertThat(result.failed()).isEqualTo(1);
            assertThat(status(ok)).isEqualTo(OCCUPIED);
            assertThat(status(broken)).isEqualTo(CLEAN);
            var status =
                    servicesContext.getBean(ApplicationDateTriggers.class).status(app).stream()
                            .filter(s -> s.resourceId().equals("checkin_due"))
                            .findFirst()
                            .orElseThrow();
            assertThat(status.failed()).isEqualTo(1);
            assertThat(status.success()).isEqualTo(1);
            assertThat(status.failures()).hasSize(1);
            assertThat(status.failures().getFirst().message()).isNotBlank();
            // 失败行当天不自动重试。
            jdbc.update(
                    "UPDATE public.nocode_date_trigger_state SET last_scan_at=NULL WHERE"
                            + " application_id=?",
                    Long.valueOf(app));
            assertThat(run(noon.plusHours(1)).failed()).isZero();
            assertThat(done("FAILED")).isEqualTo(1);
        } finally {
            jdbc.execute("ALTER TABLE public." + table + " DROP CONSTRAINT dt_fixture_floor");
        }
        var retried = scheduler.runNow(app, "checkin_due");
        assertThat(retried.success()).isEqualTo(1);
        assertThat(retried.failed()).isZero();
        assertThat(status(broken)).isEqualTo(OCCUPIED);
        assertThat(done("FAILED")).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT source_json->>'manual' FROM public.nocode_record_history"
                                        + " WHERE object_id=? AND record_id::text=? AND"
                                        + " source_json->>'kind'='DATE_TRIGGER'",
                                String.class,
                                Long.valueOf(room.objectId()),
                                broken.id()))
                .isEqualTo("true");
    }

    private String column(DataCenter.Definition d, String code) {
        var id = field(d, code);
        return Objects.toString(
                d.fieldOptions().getOrDefault(id, DataCenter.FieldOptions.defaults()).columnName(),
                code);
    }

    /** 以系统身份执行：应用对房间的共享授权在发布后被收回，按日期规则照样写入（不受某个用户 / 应用授权限制）。 */
    @Test
    void writesAsSystemEvenWhenTheApplicationGrantIsWithdrawn() {
        hotel();
        var r = room("701", CLEAN);
        stay(r, today, today.plusDays(1));
        jdbc.update(
                "DELETE FROM public.nocode_object_application_grant WHERE application_id=? AND"
                        + " object_id=?",
                Long.valueOf(app),
                Long.valueOf(room.objectId()));
        var result = run(noon);
        assertThat(result.failed()).isZero();
        assertThat(result.success()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT after_json->'values'->>? FROM public.nocode_record_history"
                                        + " WHERE object_id=? AND record_id::text=? AND"
                                        + " source_json->>'kind'='DATE_TRIGGER'",
                                String.class,
                                field(room, "status"),
                                Long.valueOf(room.objectId()),
                                r.id()))
                .isEqualTo(OCCUPIED);
    }

    /** 目标就是来源本身（到期日前 3 天 → 本条状态 = 即将到期），偏移天数为负。 */
    @Test
    void updatesTheSourceRecordItselfWithOffset() {
        var contract =
                object(
                        "合同",
                        List.of(f("name", "TEXT"), f("due", "DATE"), f("state", "TEXT")),
                        List.of(),
                        Map.of());
        app =
                application(
                        List.of(contract),
                        List.of(
                                resource(
                                        "due_soon",
                                        dated(
                                                contract, contract, null, "due", -3, null, "state",
                                                "即将到期"))));
        var hit =
                create(
                        contract,
                        Map.of(
                                field(contract, "name"),
                                "三天后到期",
                                field(contract, "due"),
                                today.plusDays(3).toString()));
        var miss =
                create(
                        contract,
                        Map.of(
                                field(contract, "name"),
                                "四天后到期",
                                field(contract, "due"),
                                today.plusDays(4).toString()));
        run(noon);
        assertThat(read(contract, hit.id()).values().get(field(contract, "state")))
                .isEqualTo("即将到期");
        assertThat(read(contract, miss.id()).values().get(field(contract, "state"))).isNull();
    }

    /** 发布不回溯：发布前已过去的日期不补；停用后再启用从当天重新起算。 */
    @Test
    void publishingDoesNotBackfillAndReenableRestartsFromToday() {
        hotel();
        var yesterday = room("601", OCCUPIED);
        stay(yesterday, today.minusDays(5), today.minusDays(1));
        run(noon);
        assertThat(status(yesterday)).as("发布前的日期不补").isEqualTo(OCCUPIED);

        // 停用应用：账本失效；拨回 5 天后再启用，从今天重新起算（暂停期间不补）。
        var head = apps.get(app).application();
        apps.status(new ApplicationCenter.Revision(app, head.revision(), "暂停"), "DISABLED", 10001);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT bool_or(armed) FROM public.nocode_date_trigger_state WHERE"
                                        + " application_id=?",
                                Boolean.class,
                                Long.valueOf(app)))
                .isFalse();
        rewind(5);
        head = apps.get(app).application();
        apps.status(new ApplicationCenter.Revision(app, head.revision(), "恢复"), "ACTIVE", 10001);
        assertThat(closed("checkout_due")).isEqualTo(today.minusDays(1));
        run(noon);
        assertThat(status(yesterday)).isEqualTo(OCCUPIED);
    }

    /** 放宽后的共写：事件赋值与按日期可以多条写同一字段；持续维护仍独占，不能与它们共写。 */
    @Test
    void oneShotRulesShareFieldsButMaintainStaysExclusive() {
        hotel(); // 已含 2 条按日期 + 2 条同来源、同事件（新增）的事件赋值写同一字段
        assertThatThrownBy(
                        () ->
                                application(
                                        List.of(room, stay),
                                        List.of(
                                                resource(
                                                        "maintained",
                                                        new ApplicationAutomations.Config(
                                                                stay.objectId(),
                                                                room.objectId(),
                                                                true,
                                                                "MAINTAIN",
                                                                Set.of(
                                                                        "CREATE", "UPDATE",
                                                                        "DELETE"),
                                                                null,
                                                                new ApplicationAutomations.Binding(
                                                                        stayRoom, "OUTGOING"),
                                                                List.of(
                                                                        new ApplicationAutomations
                                                                                .Assignment(
                                                                                field(
                                                                                        room,
                                                                                        "status"),
                                                                                "EXISTS",
                                                                                null,
                                                                                OCCUPIED,
                                                                                CLEAN)))))))
                .hasMessageContaining("同一目标字段");
    }

    /** 按日期规则的配置校验：日期字段类型、SELF 与目标对象一致、偏移范围、只用固定值 / 来源字段。 */
    @Test
    void rejectsInvalidDateRules() {
        hotel();
        assertThatThrownBy(
                        () ->
                                application(
                                        List.of(room, stay),
                                        List.of(
                                                resource(
                                                        "not_date",
                                                        dated(
                                                                stay, room, stayRoom, "name", 0,
                                                                null, "status", DIRTY)))))
                .hasMessageContaining("日期");
        assertThatThrownBy(
                        () ->
                                application(
                                        List.of(room, stay),
                                        List.of(
                                                resource(
                                                        "far",
                                                        dated(
                                                                stay, room, stayRoom, "checkin",
                                                                400, null, "status", DIRTY)))))
                .hasMessageContaining("偏移天数");
        var self = dated(stay, stay, null, "checkin", 0, null, "name", "x");
        var mismatched =
                new ApplicationAutomations.Config(
                        stay.objectId(),
                        room.objectId(),
                        true,
                        "DATE",
                        Set.of(),
                        null,
                        self.binding(),
                        List.of(
                                new ApplicationAutomations.Assignment(
                                        field(room, "status"), "VALUE", null, DIRTY, null)),
                        field(stay, "checkin"),
                        0);
        assertThatThrownBy(
                        () ->
                                application(
                                        List.of(room, stay),
                                        List.of(resource("mismatch", mismatched))))
                .hasMessageContaining("目标对象必须就是来源对象");
    }

    // ── R5 衔接：按日期规则的来源条件里的相对日期（feat/relative-date）──

    /** 预订：入住日到了，若「预订日」满足相对日期条件，就把本条的状态改掉（SELF）。 */
    private DataCenter.Definition bookings(String relative, String marker) {
        var booking =
                object(
                        "预订",
                        List.of(
                                f("name", "TEXT"),
                                f("checkin", "DATE"),
                                f("booked", "DATE"),
                                f("state", "TEXT")),
                        List.of(),
                        Map.of());
        var condition =
                new DataScope(
                        "AND",
                        List.of(
                                new DataScope.Condition(
                                        field(booking, "booked"),
                                        "eq",
                                        Map.of(RelativeDates.KEY, relative))),
                        List.of());
        app =
                application(
                        List.of(booking),
                        List.of(
                                resource(
                                        "booked_" + relative.toLowerCase(Locale.ROOT),
                                        dated(
                                                booking, booking, null, "checkin", 0, condition,
                                                "state", marker))));
        return booking;
    }

    private Row booking(DataCenter.Definition d, LocalDate checkin, LocalDate booked) {
        return create(
                d,
                Map.of(
                        field(d, "name"),
                        "预订" + serial++,
                        field(d, "checkin"),
                        checkin.toString(),
                        field(d, "booked"),
                        booked.toString()));
    }

    private Object state(DataCenter.Definition d, Row row) {
        return read(d, row.id()).values().get(field(d, "state"));
    }

    /** 「今天」= 正在处理的业务日，不是机器当前日期：补跑上周的同一天 D 时「本周」= D 所在的那一周。与机器今天不在同一周，所以按机器日期换算会把两条结果颠倒。 */
    @Test
    void relativeDateConditionsUseTheBusinessDayBeingProcessed() {
        var booking = bookings("THIS_WEEK", "本周预订");
        LocalDate d = today.minusDays(7);
        var dHit = booking(booking, d, d); // D 入住、D 预订 ⇒ 补跑 D 时命中
        var dMiss = booking(booking, d, today); // D 入住、今天预订 ⇒ 补跑 D 时不命中（按机器日期会误命中）
        var todayHit = booking(booking, today, today); // 今天入住、今天预订 ⇒ 今天这一轮命中
        var todayMiss = booking(booking, today, d); // 今天入住、上周预订 ⇒ 今天这一轮不命中
        rewind(7); // 封账日 = 今天 − 8：补跑 今天−7 … 今天

        run(noon);

        assertThat(state(booking, dHit)).as("补跑 D：本周 = D 所在的周").isEqualTo("本周预订");
        assertThat(state(booking, dMiss)).as("补跑 D：机器今天所在的周不算本周").isNull();
        assertThat(state(booking, todayHit)).isEqualTo("本周预订");
        assertThat(state(booking, todayMiss)).isNull();
        assertThat(
                        jdbc.queryForList(
                                "SELECT business_date::text || ':' || outcome FROM"
                                        + " public.nocode_date_trigger_done WHERE application_id=?"
                                        + " ORDER BY 1",
                                String.class,
                                Long.valueOf(app)))
                .containsExactly(d + ":SUCCESS", today + ":SUCCESS");
    }

    /** 派单书的例子：补跑 2026-10-01 时「本周」按 10-01 所在周（09-28 周一 … 10-04 周日）算；运行时刻在下一周的 10-08。 */
    @Test
    void catchingUpOctoberFirstUsesItsOwnWeek() {
        var booking = bookings("THIS_WEEK", "本周预订");
        var monday = booking(booking, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 9, 28));
        var sunday = booking(booking, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 4));
        var lastWeek = booking(booking, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 9, 27));
        var runWeek = booking(booking, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8));
        jdbc.update(
                "UPDATE public.nocode_date_trigger_state SET closed_date=DATE '2026-09-30',"
                        + " last_scan_at=NULL WHERE application_id=?",
                Long.valueOf(app));

        run(LocalDateTime.of(2026, 10, 8, 12, 0));

        assertThat(state(booking, monday)).isEqualTo("本周预订");
        assertThat(state(booking, sunday)).isEqualTo("本周预订");
        assertThat(state(booking, lastWeek)).isNull();
        assertThat(state(booking, runWeek)).as("运行时刻所在的周（10-05 … 10-11）不是 10-01 的本周").isNull();
        assertThat(closed("booked_this_week")).isEqualTo(LocalDate.of(2026, 10, 7));
    }

    /** 补跑 9 月 30 日时「本月」= 9 月，而不是运行时刻所在的 10 月。 */
    @Test
    void catchingUpSeptemberThirtiethUsesSeptemberAsThisMonth() {
        var booking = bookings("THIS_MONTH", "本月预订");
        var september = booking(booking, LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 1));
        var october = booking(booking, LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 2));
        jdbc.update(
                "UPDATE public.nocode_date_trigger_state SET closed_date=DATE '2026-09-29',"
                        + " last_scan_at=NULL WHERE application_id=?",
                Long.valueOf(app));

        run(LocalDateTime.of(2026, 10, 2, 12, 0));

        assertThat(state(booking, september)).isEqualTo("本月预订");
        assertThat(state(booking, october)).isNull();
    }

    /** 衔接后的校验：按日期规则的来源条件接受相对日期；不能与相对日期组合的比较方式、持续维护仍拒绝。 */
    @Test
    void dateRulesAcceptRelativeConditionsButMaintainStillRejects() {
        hotel();
        var inList =
                new DataScope(
                        "AND",
                        List.of(
                                new DataScope.Condition(
                                        field(stay, "checkout"),
                                        "in",
                                        List.of(Map.of(RelativeDates.KEY, "TODAY")))),
                        List.of());
        assertThatThrownBy(
                        () ->
                                application(
                                        List.of(room, stay),
                                        List.of(
                                                resource(
                                                        "bad_operator",
                                                        dated(
                                                                stay, room, stayRoom, "checkin", 0,
                                                                inList, "status", DIRTY)))))
                .isInstanceOf(RuntimeException.class);
        var thisWeek =
                new DataScope(
                        "AND",
                        List.of(
                                new DataScope.Condition(
                                        field(stay, "checkout"),
                                        "eq",
                                        Map.of(RelativeDates.KEY, "THIS_WEEK"))),
                        List.of());
        assertThatThrownBy(
                        () ->
                                application(
                                        List.of(room, stay),
                                        List.of(
                                                resource(
                                                        "maintained",
                                                        new ApplicationAutomations.Config(
                                                                stay.objectId(),
                                                                room.objectId(),
                                                                true,
                                                                "MAINTAIN",
                                                                Set.of(
                                                                        "CREATE", "UPDATE",
                                                                        "DELETE"),
                                                                thisWeek,
                                                                new ApplicationAutomations.Binding(
                                                                        stayRoom, "OUTGOING"),
                                                                List.of(
                                                                        new ApplicationAutomations
                                                                                .Assignment(
                                                                                field(
                                                                                        room,
                                                                                        "floor"),
                                                                                "EXISTS",
                                                                                null,
                                                                                "有",
                                                                                "无")))))))
                .hasMessageContaining("持续维护不支持相对日期");
        var accepted =
                application(
                        List.of(room, stay),
                        List.of(
                                resource(
                                        "week_checkout",
                                        dated(
                                                stay, room, stayRoom, "checkin", 0, thisWeek,
                                                "status", DIRTY))));
        assertThat(accepted).isNotBlank();
    }
}
