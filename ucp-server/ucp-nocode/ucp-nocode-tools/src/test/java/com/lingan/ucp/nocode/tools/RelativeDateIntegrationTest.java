package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.FieldRuleFixture.*;
import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.common.dto.DynamicConditionDTO;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.application.service.resource.ApplicationAutomationValidator;
import com.lingan.ucp.nocode.runtime.service.report.ApplicationReportService;

import org.junit.jupiter.api.*;

import java.util.*;

/**
 * 条件里的相对日期，当前开发库真实读写（2026-10-03）。「今天」由系统属性 nocode.relative-date.today
 * 注入，同一份配置换一天再查，结果跟着变——证明存的是相对表达、
 * 每次执行按当天换算。覆盖「查询时现算」的五条执行路径（视图固定范围、运行端条件、统计条件、引用筛选、读取时计算）、「结果会存下来」的三处拒绝（持续维护、数据联动、保存时计算）、
 * 以及具体日期条件不变。
 *
 * <p>退房日（DATE）夹具：A 09-27（上周日）、B 10-02、C 10-03、D 10-04（本周日）、E 10-05（下周一）、F 10-10、G 空。 10-03 是星期六。
 */
class RelativeDateIntegrationTest {
    private static final String SAT = "2026-10-03";
    private static final String NEXT_MON = "2026-10-05";

    private FieldRuleFixture f;
    private DataCenter.Definition stay;
    private DataCenter.Definition room;
    private String app;
    private String reference;
    private com.fasterxml.jackson.databind.ObjectMapper mapper;

    @BeforeAll
    static void open() throws Exception {
        connect();
    }

    @AfterAll
    static void shutdown() {
        close();
    }

    private static Map<String, Object> rel(String code) {
        return Map.of("relative", code);
    }

    private static Map<String, Object> rel(String code, int n) {
        return Map.of("relative", code, "n", n);
    }

    private static void today(String day) {
        System.setProperty(RelativeDates.TODAY_PROPERTY, day);
    }

    @BeforeEach
    void setup() {
        today(SAT);
        mapper = servicesContext.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
        f = new FieldRuleFixture();
        stay =
                f.object(
                        "stay",
                        List.of(
                                field("checkout", "退房日", "DATE"),
                                field("arrived", "到达时间", "DATETIME")),
                        Map.of(),
                        List.of(),
                        List.of());
        room =
                f.object(
                        "room",
                        List.of(field("today_out", "今日退房数", "FORMULA")),
                        Map.of("today_out", liveCount(stay, rel("TODAY"), "LIVE")),
                        List.of(reference("stay", stay)),
                        List.of());
        reference = relationField(room, "stay");
        app = f.app(resources(), stay, room);
        stayRow("A", "2026-09-27", null);
        stayRow("B", "2026-10-02", null);
        stayRow("C", "2026-10-03", "2026-10-03 23:30:00");
        stayRow("D", "2026-10-04", "2026-10-04 00:00:00");
        stayRow("E", "2026-10-05", null);
        stayRow("F", "2026-10-10", null);
        stayRow("G", null, null);
    }

    @AfterEach
    void cleanup() {
        System.clearProperty(RelativeDates.TODAY_PROPERTY);
        f.cleanup();
    }

    private static DataCenter.FieldOptions liveCount(
            DataCenter.Definition target, Object value, String updateMode) {
        return formula(null, "INTEGER")
                .withCalculation(
                        new CalculationOptions(
                                "LOOKUP",
                                updateMode,
                                target.objectId(),
                                null,
                                null,
                                "COUNT",
                                "AND",
                                List.of(
                                        new CalculationOptions.Match(
                                                "checkout", "eq", null, value)),
                                false,
                                List.of(),
                                null,
                                null));
    }

    private void stayRow(String name, String checkout, String arrived) {
        var values = values(id(stay, "name"), name);
        if (checkout != null) values.put(id(stay, "checkout"), checkout);
        if (arrived != null) values.put(id(stay, "arrived"), arrived);
        f.save(app, stay, values);
    }

    private List<ApplicationCenter.Resource> resources() {
        var view =
                new ApplicationUi.View(
                        stay.objectId(),
                        List.of(id(stay, "name")),
                        Map.of(),
                        null,
                        false,
                        50,
                        null,
                        Map.of(),
                        null,
                        null,
                        null,
                        new ViewQueryOptions(
                                List.of(
                                        new DataScope.Condition(
                                                id(stay, "checkout"), "eq", rel("THIS_WEEK"))),
                                null,
                                null));
        var fixed =
                new ApplicationUi.View(
                        stay.objectId(),
                        List.of(id(stay, "name")),
                        Map.of(),
                        null,
                        false,
                        50,
                        null,
                        Map.of(),
                        null,
                        null,
                        null,
                        new ViewQueryOptions(
                                List.of(new DataScope.Condition(id(stay, "checkout"), "eq", SAT)),
                                null,
                                null));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("objectId", stay.objectId());
        report.put("dimensions", List.of());
        report.put("metrics", List.of(Map.of("id", "cnt", "name", "条数", "operation", "COUNT")));
        report.put("equal", Map.of());
        report.put("filterFieldIds", List.of());
        report.put("timeZone", "Asia/Shanghai");
        report.put("display", "METRIC");
        report.put("descending", false);
        report.put("limit", 30);
        report.put(
                "conditions", tree(condition(id(stay, "checkout"), "eq", rel("NEXT_N_DAYS", 7))));
        return List.of(
                new ApplicationCenter.Resource(
                        "week_view",
                        "VIEW",
                        "week_view",
                        "本周退房",
                        mapper.convertValue(view, Map.class)),
                new ApplicationCenter.Resource(
                        "fixed_view",
                        "VIEW",
                        "fixed_view",
                        "具体日期",
                        mapper.convertValue(fixed, Map.class)),
                new ApplicationCenter.Resource("report", "REPORT", "report", "未来七天", report));
    }

    private static DynamicConditionDTO.Item condition(String field, String op, Object value) {
        var item = new DynamicConditionDTO.Item();
        item.setType("condition");
        item.setField(field);
        item.setOperator(op);
        item.setValue(value);
        return item;
    }

    private static DynamicConditionDTO tree(DynamicConditionDTO.Item... items) {
        var tree = new DynamicConditionDTO();
        tree.setLogic(DynamicConditionDTO.Logic.AND);
        tree.setItems(new ArrayList<>(List.of(items)));
        return tree;
    }

    private List<String> names(String view, DynamicConditionDTO conditions) {
        return f
                .runtime
                .page(
                        new ApplicationRecords.Query(
                                app,
                                stay.objectId(),
                                1,
                                50,
                                null,
                                Map.of(),
                                null,
                                false,
                                view,
                                null,
                                conditions,
                                null),
                        10001)
                .getList()
                .stream()
                .map(r -> String.valueOf(r.values().get(id(stay, "name"))))
                .sorted()
                .toList();
    }

    /** 视图固定范围（DataScope → ScopeConditions SQL）。 */
    @Test
    void viewFixedScopeFollowsToday() {
        assertThat(names("week_view", null)).containsExactly("B", "C", "D");
        today(NEXT_MON);
        assertThat(names("week_view", null)).containsExactly("E", "F");
    }

    /** 运行端 / 数据视图条件（DynamicConditionDTO → RecordConditions 原位展开）。 */
    @Test
    void conditionTreesFollowToday() {
        var past7 = tree(condition(id(stay, "checkout"), "eq", rel("PAST_N_DAYS", 7)));
        var notToday = tree(condition(id(stay, "checkout"), "neq", rel("TODAY")));
        var afterToday = tree(condition(id(stay, "checkout"), "gt", rel("TODAY")));
        var notAfterWeek = tree(condition(id(stay, "checkout"), "lte", rel("THIS_WEEK")));
        var arrivedToday = tree(condition(id(stay, "arrived"), "eq", rel("TODAY")));
        assertThat(names(null, past7)).containsExactly("A", "B", "C");
        assertThat(names(null, notToday)).containsExactly("A", "B", "D", "E", "F");
        assertThat(names(null, afterToday)).containsExactly("D", "E", "F");
        assertThat(names(null, notAfterWeek)).containsExactly("A", "B", "C", "D");
        // 日期时间：23:30 是今天，次日 00:00 不是。
        assertThat(names(null, arrivedToday)).containsExactly("C");
        today(NEXT_MON);
        assertThat(names(null, past7)).containsExactly("B", "C", "D", "E");
        assertThat(names(null, notToday)).containsExactly("A", "B", "C", "D", "F");
        assertThat(names(null, afterToday)).containsExactly("F");
        assertThat(names(null, notAfterWeek)).containsExactly("A", "B", "C", "D", "E", "F");
        assertThat(names(null, arrivedToday)).isEmpty();
    }

    /** 相对日期叶子在 OR 组里展开后仍是一个整体（不会把边界拆散到外层 OR）。 */
    @Test
    void expansionStaysInsideItsOwnGroup() {
        var or = new DynamicConditionDTO();
        or.setLogic(DynamicConditionDTO.Logic.OR);
        or.setItems(
                new ArrayList<>(
                        List.of(
                                condition(id(stay, "checkout"), "eq", rel("TODAY")),
                                condition(id(stay, "name"), "eq", "F"))));
        assertThat(names(null, or)).containsExactly("C", "F");
    }

    /** 统计视图固定条件（ApplicationReportService.compilePredicate）。 */
    @Test
    void reportConditionsFollowToday() {
        var reports = servicesContext.getBean(ApplicationReportService.class);
        var query =
                new ApplicationReports.Query(app, "report", null, null, null, null, null, 1, 20);
        assertThat(reports.query(query, 10001).recordCount()).isEqualTo(3); // C D E
        today(NEXT_MON);
        assertThat(reports.query(query, 10001).recordCount()).isEqualTo(2); // E F
    }

    /** 引用筛选候选（FieldRuleConditions → RecordConditions.appendRule）。 */
    @Test
    void referenceFilterFollowsToday() {
        room =
                rules(
                        room,
                        reference,
                        filter(
                                id(stay, "name"),
                                constant(id(stay, "checkout"), "lt", rel("TODAY"))));
        assertThat(candidates()).containsExactly("A", "B");
        today(NEXT_MON);
        assertThat(candidates()).containsExactly("A", "B", "C", "D");
        room =
                rules(
                        room,
                        reference,
                        filter(
                                id(stay, "name"),
                                constant(id(stay, "checkout"), "between", rel("NEXT_WEEK"))));
        today(SAT);
        assertThat(candidates()).containsExactly("E", "F");
    }

    private List<String> candidates() {
        return f.selection(app, room, reference, Map.of(), List.of()).options().stream()
                .map(SelectionFields.Option::label)
                .sorted()
                .toList();
    }

    /** 读取时计算（Calculation LIVE → ScopeConditions SQL）。 */
    @Test
    void liveCalculationFollowsToday() {
        String roomId = f.save(app, room, values(id(room, "name"), "101")).id();
        assertThat(todayOut(roomId)).isEqualTo("1");
        today(NEXT_MON);
        assertThat(todayOut(roomId)).isEqualTo("1");
        today("2026-10-06");
        assertThat(todayOut(roomId)).isEqualTo("0");
    }

    private String todayOut(String roomId) {
        Object v =
                f.runtime
                        .get(app, room.objectId(), roomId, 10001)
                        .record()
                        .values()
                        .get(id(room, "today_out"));
        return String.valueOf(v);
    }

    /** 结果会存下来的三处：保存时计算、数据联动、持续维护 —— 一律拒绝并说明。 */
    @Test
    void storedEntriesRejectRelativeDates() {
        assertThatThrownBy(
                        () ->
                                f.object(
                                        "snap",
                                        List.of(field("cnt", "快照数", "FORMULA")),
                                        Map.of("cnt", liveCount(stay, rel("TODAY"), "ON_SAVE")),
                                        List.of(),
                                        List.of()))
                .hasMessageContaining("读取时计算");
        assertThatThrownBy(
                        () ->
                                f.object(
                                        "link",
                                        List.of(field("day", "退房日", "DATE")),
                                        Map.of(
                                                "day",
                                                FieldRuleFixture.options(List.of())
                                                        .withRules(
                                                                linkage(
                                                                        stay,
                                                                        id(stay, "checkout"),
                                                                        "FIRST",
                                                                        List.of(
                                                                                constant(
                                                                                        id(
                                                                                                stay,
                                                                                                "checkout"),
                                                                                        "lt",
                                                                                        rel(
                                                                                                "TODAY")))))),
                                        List.of(),
                                        List.of()))
                .hasMessageContaining("数据联动不支持相对日期");
        var validator = servicesContext.getBean(ApplicationAutomationValidator.class);
        var relation = room.relations().getFirst();
        var conditions =
                new DataScope(
                        "AND",
                        List.of(new DataScope.Condition(id(stay, "checkout"), "eq", rel("TODAY"))),
                        List.of());
        var maintain =
                new ApplicationAutomations.Config(
                        stay.objectId(),
                        room.objectId(),
                        true,
                        "MAINTAIN",
                        Set.of("CREATE", "UPDATE", "DELETE"),
                        conditions,
                        new ApplicationAutomations.Binding(relation.id(), "INCOMING"),
                        List.of(
                                new ApplicationAutomations.Assignment(
                                        id(room, "name"), "COUNT", null, null, null)));
        var definitions = Map.of(stay.objectId(), stay, room.objectId(), room);
        assertThatThrownBy(() -> validator.validate(maintain, definitions))
                .hasMessageContaining("持续维护不支持相对日期");
        var event =
                new ApplicationAutomations.Config(
                        stay.objectId(),
                        room.objectId(),
                        true,
                        "EVENT",
                        Set.of("UPDATE"),
                        conditions,
                        new ApplicationAutomations.Binding(relation.id(), "INCOMING"),
                        List.of(
                                new ApplicationAutomations.Assignment(
                                        id(room, "name"), "VALUE", null, "已退房", null)));
        assertThatCode(() -> validator.validate(event, definitions)).doesNotThrowAnyException();
    }

    /** 存量具体日期条件：换日前后结果都不变。 */
    @Test
    void concreteDatesAreUnchanged() {
        var range =
                tree(
                        condition(
                                id(stay, "checkout"),
                                "between",
                                List.of("2026-10-01", "2026-10-04")));
        assertThat(names("fixed_view", null)).containsExactly("C");
        assertThat(names(null, range)).containsExactly("B", "C", "D");
        today(NEXT_MON);
        assertThat(names("fixed_view", null)).containsExactly("C");
        assertThat(names(null, range)).containsExactly("B", "C", "D");
    }
}
