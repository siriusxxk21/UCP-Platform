package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.api.FieldDefinition;
import com.lingan.ucp.nocode.api.TableBinding;
import com.lingan.ucp.nocode.schema.service.compile.SchemaFieldConstraintChecks;

import org.junit.jupiter.api.*;

import java.util.*;

/** 使用当前开发库验证实际数值和正则查询；仅读写本测试随机前缀的物理夹具。 */
class FieldConstraintPreflightIntegrationTest {
    private final List<String> tables = new ArrayList<>();
    private String tableName;
    private SchemaFieldConstraintChecks inspector;
    private Definition original;

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
        tableName =
                "biz_test_constraint_"
                        + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        tables.add(tableName);
        jdbc.execute(
                "CREATE TABLE public.\""
                        + tableName
                        + "\" (id bigint PRIMARY KEY, amount numeric(18,2), label text, deleted"
                        + " boolean NOT NULL DEFAULT false)");
        inspector = servicesContext.getBean(SchemaFieldConstraintChecks.class);
        original =
                new Definition(
                        "fixture",
                        "fixture",
                        "约束测试对象",
                        null,
                        "public",
                        tableName,
                        "GENERATED",
                        false,
                        "label",
                        Settings.defaults(),
                        List.of(field("amount", "DECIMAL"), field("label", "TEXTAREA")),
                        Map.of(),
                        List.of(),
                        List.of(),
                        List.of());
    }

    @AfterEach
    void cleanup() {
        for (String owned : tables) jdbc.execute("DROP TABLE IF EXISTS public." + quoted(owned));
        tables.clear();
    }

    @Test
    void reportsCompleteCountsAndTenIdsIncludingDeletedRowsWithoutReturningValues() {
        for (long id = 1; id <= 12; id++) insert(id, "-1", "private-history-secret", id == 12);
        insert(13, "10", "OK", false);
        insert(14, null, null, false);
        Definition current = rules(original, "5", "8", "^OK$");
        List<Check> checks = inspector.inspect(current, original, Set.of());
        assertThat(checks).hasSize(3).allMatch(Check::blocking);
        assertThat(
                        checks.stream()
                                .filter(c -> c.message().contains("最小值"))
                                .findFirst()
                                .orElseThrow()
                                .message())
                .contains("约束测试对象", "金额", "12 条历史数据冲突", "逻辑删除", "1、2、3、4、5、6、7、8、9、10", "仅列出前 10 条")
                .doesNotContain("、11");
        assertThat(
                        checks.stream()
                                .filter(c -> c.message().contains("最大值"))
                                .findFirst()
                                .orElseThrow()
                                .message())
                .contains("1 条历史数据冲突", "记录 ID：13");
        assertThat(
                        checks.stream()
                                .filter(c -> c.message().contains("正则规则"))
                                .findFirst()
                                .orElseThrow()
                                .message())
                .contains("12 条历史数据冲突", "^OK$");
        assertThat(checks).noneMatch(c -> c.message().contains("private-history-secret"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.\"" + tableName + "\"", Integer.class))
                .isEqualTo(14);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT label FROM public.\"" + tableName + "\" WHERE id=1",
                                String.class))
                .isEqualTo("private-history-secret");
    }

    @Test
    void skipsUnchangedAndLoosenedRulesAndChecksNewTighterRules() {
        insert(1, "0", "OK-extra", false);
        insert(2, "100", "OK", false);
        Definition previous = rules(original, "0", "100", "^OK");
        assertThat(inspector.inspect(previous, previous, Set.of())).isEmpty();
        assertThat(inspector.inspect(rules(original, "-5", "200", "^OK"), previous, Set.of()))
                .isEmpty();
        assertThat(inspector.inspect(rules(original, "0.00", "100.0", "^OK"), previous, Set.of()))
                .isEmpty();
        assertThat(inspector.inspect(rules(original, null, null, null), previous, Set.of()))
                .isEmpty();
        List<Check> tightened =
                inspector.inspect(rules(original, "1", "99", "^OK$"), previous, Set.of());
        assertThat(tightened).hasSize(3).allMatch(c -> c.message().contains("1 条历史数据冲突"));
    }

    @Test
    void skipsClearedConversionsChangedPhysicalTypesMissingAndInactiveColumns() {
        insert(1, "-1", "invalid", false);
        Definition current = rules(original, "0", null, "^OK$");
        assertThat(inspector.inspect(current, original, Set.of("amount", "label"))).isEmpty();
        Definition numericOnly = rules(original, "0", null, null);
        Definition changedType =
                copy(
                        numericOnly,
                        List.of(field("amount", "INTEGER")),
                        numericOnly.fieldOptions(),
                        List.of());
        assertThat(inspector.inspect(changedType, original, Set.of())).isEmpty();
        FieldDefinition missing = field("not_created", "DECIMAL");
        Definition newColumn =
                copy(
                        original,
                        List.of(missing),
                        Map.of(missing.id(), option("0", null, null, "ACTIVE")),
                        List.of());
        assertThat(inspector.inspect(newColumn, original, Set.of())).isEmpty();
        Definition inactive =
                copy(
                        original,
                        original.fields(),
                        Map.of("amount", option("0", null, null, "INACTIVE")),
                        List.of());
        assertThat(inspector.inspect(inactive, original, Set.of())).isEmpty();
    }

    @Test
    void checksActiveDetailWithItsMappedKeyAndSkipsInactiveDetail() {
        String detailTable = tableName + "_lines";
        tables.add(detailTable);
        jdbc.execute(
                "CREATE TABLE public.\""
                        + detailTable
                        + "\" (line_key text PRIMARY KEY, label text, deleted boolean DEFAULT"
                        + " false)");
        jdbc.update(
                "INSERT INTO public.\""
                        + detailTable
                        + "\" (line_key,label,deleted) VALUES (?,?,?)",
                "detail-record",
                "bad",
                true);
        TableBinding binding =
                new TableBinding(
                        "ADOPTED", "public", "line_key", null, "RETAIN", false, false, null);
        Detail previous =
                new Detail(
                        "detail",
                        "lines",
                        "内部明细",
                        detailTable,
                        "ACTIVE",
                        List.of(field("label", "TEXTAREA")),
                        Map.of(),
                        List.of(),
                        binding);
        Detail active =
                new Detail(
                        previous.id(),
                        previous.code(),
                        previous.name(),
                        detailTable,
                        "ACTIVE",
                        previous.fields(),
                        Map.of("label", option(null, null, "^OK$", "ACTIVE")),
                        List.of(),
                        binding);
        Definition old =
                copy(original, original.fields(), original.fieldOptions(), List.of(previous));
        Definition current =
                copy(original, original.fields(), original.fieldOptions(), List.of(active));
        List<Check> checks = inspector.inspect(current, old, Set.of());
        assertThat(checks)
                .singleElement()
                .satisfies(
                        c ->
                                assertThat(c.message())
                                        .contains("约束测试对象 / 内部明细", "1 条历史数据冲突", "detail-record"));
        Detail inactive =
                new Detail(
                        active.id(),
                        active.code(),
                        active.name(),
                        detailTable,
                        "INACTIVE",
                        active.fields(),
                        active.fieldOptions(),
                        List.of(),
                        binding);
        assertThat(
                        inspector.inspect(
                                copy(
                                        current,
                                        current.fields(),
                                        current.fieldOptions(),
                                        List.of(inactive)),
                                old,
                                Set.of()))
                .isEmpty();
    }

    @Test
    void supportsAdoptedMainTableQuotedNamesAndActualTextKey() {
        String adoptedTable = tableName + "_Quoted \"源\"";
        String key = "记录 \"ID\"";
        String column = "金额 \"旧值\"";
        tables.add(adoptedTable);
        String physical = "public." + quoted(adoptedTable);
        jdbc.execute(
                "CREATE TABLE "
                        + physical
                        + " ("
                        + quoted(key)
                        + " text PRIMARY KEY, "
                        + quoted(column)
                        + " numeric(18,2))");
        jdbc.update(
                "INSERT INTO "
                        + physical
                        + " ("
                        + quoted(key)
                        + ","
                        + quoted(column)
                        + ") VALUES (?,?)",
                "quoted-record",
                -2);
        TableBinding binding =
                new TableBinding("ADOPTED", "public", key, null, "RETAIN", false, false, null);
        FieldOptions previousOptions =
                new FieldOptions(
                        column,
                        "NORMAL",
                        null,
                        null,
                        null,
                        null,
                        null,
                        "ACTIVE",
                        List.of(),
                        null,
                        null,
                        "NONE",
                        "numeric(18,2)",
                        false,
                        false);
        FieldOptions currentOptions =
                new FieldOptions(
                        column,
                        "NORMAL",
                        null,
                        null,
                        null,
                        "0",
                        null,
                        "ACTIVE",
                        List.of(),
                        null,
                        null,
                        "NONE",
                        "numeric(18,2)",
                        false,
                        false);
        Definition previous =
                new Definition(
                        "adopted",
                        "adopted",
                        "纳管旧表",
                        null,
                        "public",
                        adoptedTable,
                        "ADOPTED",
                        false,
                        "amount",
                        Settings.defaults(),
                        List.of(field("amount", "DECIMAL")),
                        Map.of("amount", previousOptions),
                        List.of(),
                        List.of(),
                        List.of(),
                        binding);
        Definition current =
                copy(previous, previous.fields(), Map.of("amount", currentOptions), List.of());
        assertThat(inspector.inspect(current, previous, Set.of()))
                .singleElement()
                .satisfies(
                        c ->
                                assertThat(c.message())
                                        .contains("纳管旧表", "金额", "1 条历史数据冲突", "quoted-record"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT " + quoted(column) + " FROM " + physical, Integer.class))
                .isEqualTo(-2);
    }

    private static String quoted(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private void insert(long id, String amount, String label, boolean deleted) {
        jdbc.update(
                "INSERT INTO public.\""
                        + tableName
                        + "\" (id,amount,label,deleted) VALUES (?,CAST(? AS numeric),?,?)",
                id,
                amount,
                label,
                deleted);
    }

    private static FieldDefinition field(String id, String type) {
        return new FieldDefinition(
                id, id, id, id.equals("amount") ? "金额" : "文本", type, null, 18, 2, false, false, 0);
    }

    private static FieldOptions option(
            String minimum, String maximum, String pattern, String state) {
        return new FieldOptions(
                null, "NORMAL", null, null, pattern, minimum, maximum, state, List.of(), null, null,
                "NONE", null, false, false);
    }

    private static Definition rules(
            Definition definition, String minimum, String maximum, String pattern) {
        return copy(
                definition,
                definition.fields(),
                Map.of(
                        "amount",
                        option(minimum, maximum, null, "ACTIVE"),
                        "label",
                        option(null, null, pattern, "ACTIVE")),
                definition.details());
    }

    private static Definition copy(
            Definition original,
            List<FieldDefinition> fields,
            Map<String, FieldOptions> options,
            List<Detail> details) {
        return new Definition(
                original.objectId(),
                original.objectCode(),
                original.objectName(),
                original.description(),
                original.schemaName(),
                original.tableName(),
                original.source(),
                original.readOnly(),
                original.titleFieldId(),
                original.settings(),
                fields,
                options,
                original.relations(),
                original.indexes(),
                details,
                original.mainBinding());
    }
}
