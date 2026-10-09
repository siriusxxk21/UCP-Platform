package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.nocode.api.ApplicationReports;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.dal.mapper.SelectionMigrationMapper;
import com.lingan.ucp.nocode.runtime.dal.dataobject.*;
import com.lingan.ucp.nocode.runtime.dal.mapper.*;
import com.lingan.ucp.nocode.runtime.dal.mapper.RecordHistoryMapper;
import com.lingan.ucp.nocode.runtime.dal.query.*;
import com.lingan.ucp.nocode.runtime.dal.support.*;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Provider 迁入 XML 的固定行为基线，覆盖宽字段投影、嵌套参数、权限条件、报表公式和迁移分支。 基线来自改动前实际 MyBatis Provider；只检查绑定后的 SQL
 * 与参数，不连接数据库。
 */
class ProviderXmlCompatibilityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SQL_TOKEN =
            Pattern.compile(
                    "'(?:[^']|'')*'|\"(?:[^\"]|\"\")*\"|[A-Za-z_][A-Za-z_0-9$]*"
                            + "|(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"
                            + "|[+*/<>=~!@#%^&|:\\-]+|\\S");
    static final List<Class<?>> MAPPERS =
            List.of(
                    RecordMapper.class,
                    RecordHistoryMapper.class,
                    DataViewMapper.class,
                    ReportMapper.class,
                    SelectionMigrationMapper.class);

    record Case(String statement, String scenario, Object parameters) {
        String key() {
            return SqlBaselineNames.original(statement) + "/" + scenario;
        }
    }

    @Test
    void identifierEscapingAndExistingValidationLimitsArePreserved() {
        assertThat(RuntimeSqlParameters.quote("table\"name")).isEqualTo("\"table\"\"name\"");
        assertThat(RuntimeSqlParameters.quote("a".repeat(64))).hasSize(66);
        assertThatThrownBy(() -> RuntimeSqlParameters.quote(" ")).hasMessage("缺少数据库标识符");
        assertThatThrownBy(() -> RuntimeSqlParameters.quote("name\0value")).hasMessage("缺少数据库标识符");
        assertThat(SelectionMigrationMapper.Identifiers.quote("中".repeat(63))).hasSize(65);
        assertThatThrownBy(() -> SelectionMigrationMapper.Identifiers.quote("a".repeat(64)))
                .hasMessage("无效标识符");
    }

    @Test
    void metricExpansionStillRejectsCyclesAndMissingReferences() {
        var statement = report(0);
        statement
                .metrics()
                .set(
                        0,
                        new ApplicationReports.Metric(
                                "metric_0",
                                "cycle",
                                "FORMULA",
                                null,
                                null,
                                new ApplicationReports.Formula("ADD", "metric_0", "metric_3"),
                                null));
        assertThatThrownBy(() -> ReportMetricExpressions.expand(statement))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cyclic report metric");
        statement
                .metrics()
                .set(
                        0,
                        new ApplicationReports.Metric(
                                "metric_0",
                                "missing",
                                "FORMULA",
                                null,
                                null,
                                new ApplicationReports.Formula("ADD", "missing", "metric_3"),
                                null));
        assertThatThrownBy(() -> ReportMetricExpressions.expand(statement))
                .isInstanceOf(NoSuchElementException.class);
    }

    @TestFactory
    Stream<DynamicTest> providerStatementsKeepTheirOriginalContract() throws Exception {
        Map<String, Map<String, Object>> expected;
        try (var input = getClass().getResourceAsStream("/sql-baseline/provider-mappings.json")) {
            assertThat(input).as("迁移前的 Provider 基线必须存在").isNotNull();
            expected = JSON.readValue(input, new TypeReference<>() {});
        }
        var configuration = configuration();
        var cases = cases();
        assertThat(cases.stream().map(Case::statement).distinct()).hasSize(24);
        assertThat(expected.keySet())
                .containsExactlyInAnyOrderElementsOf(cases.stream().map(Case::key).toList());
        return cases.stream()
                .map(
                        test ->
                                DynamicTest.dynamicTest(
                                        test.key(),
                                        () -> {
                                            var statement =
                                                    configuration.getMappedStatement(
                                                            test.statement());
                                            assertThat(statement.getResource()).endsWith(".xml]");
                                            assertThat(
                                                            snapshot(
                                                                    configuration,
                                                                    statement,
                                                                    test.parameters()))
                                                    .isEqualTo(
                                                            SqlBaselineNames.resultTypes(
                                                                    currentContract(
                                                                            test,
                                                                            expected.get(
                                                                                    test.key()))));
                                        }));
    }

    static MybatisConfiguration configuration() throws Exception {
        var configuration = MapperXmlCompatibilityTest.configuration();
        for (var resource :
                new PathMatchingResourcePatternResolver()
                        .getResources("classpath*:mapper/nocode/*.xml")) {
            try (var input = resource.getInputStream()) {
                new XMLMapperBuilder(
                                input,
                                configuration,
                                resource.toString(),
                                configuration.getSqlFragments())
                        .parse();
            }
        }
        for (var mapper : MAPPERS)
            if (!configuration.hasMapper(mapper)) configuration.addMapper(mapper);
        return configuration;
    }

    /**
     * 保留原 Provider 快照；明确列出既有选项迁移修复后的契约，不能从被测 XML 反算期望值。 列须带表别名以避免与 LATERAL 的 value
     * 重名，多选旧值为标量时按空数组处理。
     */
    private static Map<String, Object> currentContract(Case test, Map<String, Object> original) {
        if (!test.statement().endsWith("SelectionMigrationMapper.values")) return original;
        SelectionMigrationMapper.Statement parameters =
                (SelectionMigrationMapper.Statement) test.parameters();
        String column = "\"sample\".\"value\"\"column\"";
        String array =
                parameters.oldMultiple()
                        ? "CASE WHEN jsonb_typeof("
                                + column
                                + "::jsonb) = 'array' THEN "
                                + column
                                + "::jsonb ELSE '[]'::jsonb END"
                        : "jsonb_build_array(" + column + "::text)";
        String sql =
                "SELECT DISTINCT v.value FROM \"schema\"\"special\".\"sample\" "
                        + "CROSS JOIN LATERAL jsonb_array_elements_text("
                        + array
                        + ") v WHERE "
                        + column
                        + " IS NOT NULL ORDER BY v.value LIMIT 1001";
        Map<String, Object> expected = new LinkedHashMap<>(original);
        expected.put(
                "sqlTokens",
                SQL_TOKEN.matcher(sql).results().map(java.util.regex.MatchResult::group).toList());
        return expected;
    }

    /** 参数路径可因 foreach 改名；比较实际按序绑定的值、Java/JDBC 类型及处理器，不忽略参数内容。 */
    static Map<String, Object> snapshot(
            MybatisConfiguration configuration, MappedStatement statement, Object parameter)
            throws Exception {
        var result = new LinkedHashMap<String, Object>();
        result.put("command", statement.getSqlCommandType().name());
        result.put("flushCache", statement.isFlushCacheRequired());
        result.put("affectData", statement.isDirtySelect());
        result.put("timeout", statement.getTimeout());
        result.put("statementType", statement.getStatementType().name());
        result.put("resultSetType", statement.getResultSetType().name());
        result.put("keyGenerator", statement.getKeyGenerator().getClass().getName());
        result.put("keyProperties", statement.getKeyProperties());
        result.put("keyColumns", statement.getKeyColumns());
        if (statement.getSqlCommandType() == SqlCommandType.SELECT) {
            result.put("useCache", statement.isUseCache());
            result.put(
                    "resultTypes",
                    statement.getResultMaps().stream().map(m -> m.getType().getName()).toList());
        }
        var sql = statement.getBoundSql(parameter);
        result.put(
                "sqlTokens",
                SQL_TOKEN.matcher(sql.getSql()).results().map(m -> m.group()).toList());
        var bindings = new ArrayList<Map<String, Object>>();
        for (var mapping : sql.getParameterMappings()) {
            String property = mapping.getProperty();
            Object value =
                    sql.hasAdditionalParameter(property)
                            ? sql.getAdditionalParameter(property)
                            : configuration.newMetaObject(parameter).getValue(property);
            var binding = new LinkedHashMap<String, Object>();
            binding.put("value", value);
            binding.put("javaType", mapping.getJavaType().getName());
            binding.put("jdbcType", Objects.toString(mapping.getJdbcType(), ""));
            binding.put("handler", mapping.getTypeHandler().getClass().getName());
            bindings.add(binding);
        }
        result.put("bindings", bindings);
        // 统一 JSON 数值/数组表示，使基线文件反序列化与当次内存快照一致。
        return JSON.convertValue(result, new TypeReference<>() {});
    }

    static List<Case> cases() {
        var cases = new ArrayList<Case>();
        for (int v = 0; v < 32; v++) {
            var record = record(v, true, "root");
            for (String method : List.of("rows", "count", "insert", "update", "delete"))
                add(cases, RecordMapper.class, method, v, record);
            add(
                    cases,
                    RecordHistoryMapper.class,
                    "baseline",
                    v,
                    Map.of(
                            "statement",
                            record(v, false, "history"),
                            "object",
                            "101",
                            "definition",
                            "{\"fields\":[]}",
                            "actor",
                            "history-actor"));
            var relation =
                    new RelationStatement(
                            "schema\"special",
                            "relations",
                            "public",
                            "source",
                            "custom_id",
                            "source-1",
                            (v & 1) == 0 ? null : "target-1",
                            "{\"source_id\":1,\"target_id\":2}",
                            "actor-1");
            for (String method : List.of("relationTargets", "relationSources", "attach", "detach"))
                add(cases, RecordMapper.class, method, v, relation);
            add(
                    cases,
                    RecordMapper.class,
                    "relationBatchTargets",
                    v,
                    Map.of("relation", relation, "ids", "[\"1\",\"2\"]"));
            add(
                    cases,
                    RecordMapper.class,
                    "summaries",
                    v,
                    new SummaryStatement(
                            "public",
                            "detail",
                            "parent",
                            "amount",
                            SummaryOperationEnum.values()[v % SummaryOperationEnum.values().length],
                            "[\"1\",\"2\"]",
                            (v & 1) != 0));
            var view = view(v);
            for (String method : List.of("rows", "count"))
                add(cases, DataViewMapper.class, method, v, view);
            var child =
                    new DataViewChildStatement(
                            view,
                            v % 2,
                            "parent-100",
                            (v & 1) == 0 ? null : "amount",
                            (v & 2) != 0,
                            20,
                            40);
            for (String method : List.of("childRows", "childCount"))
                add(cases, DataViewMapper.class, method, v, child);
            for (String method : List.of("result", "rows", "count"))
                add(cases, ReportMapper.class, method, v, report(v));
            var selection =
                    new SelectionMigrationMapper.Statement(
                            "schema\"special",
                            "sample",
                            "value\"column",
                            List.of("text", "bigint", "uuid", "numeric(12,2)", "jsonb").get(v % 5),
                            (v & 1) != 0,
                            (v & 2) != 0,
                            (v & 4) != 0,
                            "{\"a\":[\"b\"]}",
                            "selection-actor",
                            (v & 8) != 0);
            for (String method : List.of("values", "conflicts", "toText", "mapValues", "toTarget"))
                add(cases, SelectionMigrationMapper.class, method, v, selection);
        }
        return cases;
    }

    private static void add(
            List<Case> cases, Class<?> mapper, String method, int variant, Object parameter) {
        cases.add(new Case(mapper.getName() + "." + method, "variant-" + variant, parameter));
    }

    private static RecordStatement record(int variant, boolean scoped, String source) {
        var fields = new LinkedHashMap<String, String>();
        int count = new int[] {0, 2, 41, 81}[variant % 4];
        for (int i = 1; i <= count; i++)
            fields.put(String.valueOf(i), i == 1 ? "name" : i == 2 ? "amount" : "field_" + i);
        QueryWrapper<Object> dynamic = null;
        if (scoped && (variant & 16) != 0) {
            dynamic = new QueryWrapper<>();
            dynamic.setParamAlias("dynamicQuery");
            dynamic.eq("t.\"status\"", source + "-active")
                    .and(w -> w.eq("t.\"enabled\"", true).or().isNull("t.\"enabled\""));
        }
        return new RecordStatement(
                "schema\"special",
                "table\"name",
                "custom_id",
                fields,
                (variant & 2) == 0 ? List.of() : List.of("name", "description"),
                List.of("amount"),
                (variant & 1) != 0,
                scoped && (variant & 2) != 0 ? source + "-id" : null,
                scoped && (variant & 4) != 0 ? "parent_id" : null,
                scoped && (variant & 8) != 0 ? source + "-parent" : null,
                scoped && (variant & 8) != 0 ? source + "-creator" : null,
                scoped && (variant & 4) != 0 ? "%needle!_%" : null,
                scoped && (variant & 2) != 0 ? List.of("name", "amount") : List.of(),
                "{\"name\":\"" + source + "\",\"amount\":12.30}",
                (variant & 4) == 0 ? null : "amount",
                (variant & 8) != 0,
                20,
                40,
                (variant & 1) == 0 ? List.of() : List.of("name", "amount"),
                "{\"name\":\"text\",\"amount\":123.45}",
                source + "-actor",
                (variant & 16) != 0,
                scoped && (variant & 16) != 0
                        ? new RelationScope(
                                "public", "links", "source_id", "target_id", source + "-context")
                        : null,
                dynamic);
    }

    private static DataViewStatement view(int variant) {
        var sections =
                List.of(
                        new DataViewStatement.Section(
                                record(variant, true, "section-0"),
                                "t.\"parent_id\"::text=r.\"custom_id\"::text",
                                (variant & 1) != 0),
                        new DataViewStatement.Section(
                                record(31 - variant, true, "section-1"),
                                "t.\"customer_id\"::text=r.\"customer_id\"::text",
                                (variant & 2) != 0));
        var columns = new ArrayList<DataViewStatement.Column>();
        if ((variant & 4) != 0)
            for (var kind : ViewColumnKindEnum.values()) {
                if (kind == ViewColumnKindEnum.DETAIL && (variant & 16) == 0) continue;
                columns.add(
                        new DataViewStatement.Column(
                                "extra_" + kind.name(),
                                kind.ordinal() % 2,
                                "amount",
                                kind.getCode(),
                                kind != ViewColumnKindEnum.LOOKUP));
            }
        QueryWrapper<Object> condition = null;
        if ((variant & 8) != 0) {
            condition = new QueryWrapper<>();
            condition.setParamAlias("dynamicQuery");
            condition.eq("t.\"name\"", "view-condition");
        }
        return new DataViewStatement(
                record(variant, true, "view-root"),
                sections,
                columns,
                (variant & 16) == 0 ? null : variant % 2,
                condition,
                (variant & 2) == 0 ? null : "amount",
                (variant & 1) != 0,
                10,
                30);
    }

    private static ReportStatement report(int variant) {
        var dimensions = new ArrayList<ReportStatement.Dimension>();
        if ((variant & 1) != 0)
            dimensions.add(
                    new ReportStatement.Dimension(
                            "t",
                            "date_value",
                            ReportBucketEnum.values()[
                                    (variant / 2) % ReportBucketEnum.values().length],
                            (variant & 2) != 0));
        if ((variant & 4) != 0)
            dimensions.add(
                    new ReportStatement.Dimension("r0", "category", ReportBucketEnum.VALUE, false));
        var joins =
                (variant & 4) == 0
                        ? List.<ReportStatement.Join>of()
                        : List.of(
                                new ReportStatement.Join(
                                        "public",
                                        "customer",
                                        "id",
                                        "t",
                                        "customer_id",
                                        (variant & 2) == 0 ? null : "join-actor-0",
                                        true),
                                new ReportStatement.Join(
                                        "public",
                                        "region",
                                        "key",
                                        "r0",
                                        "region_id",
                                        "join-actor-1",
                                        false));
        var filters =
                (variant & 8) == 0
                        ? List.<ReportStatement.Filter>of()
                        : List.of(
                                new ReportStatement.Filter(
                                        "t",
                                        "public",
                                        "orders",
                                        "name",
                                        "{\"name\":\"filter-root\"}"),
                                new ReportStatement.Filter(
                                        "r0",
                                        "public",
                                        "customer",
                                        "name",
                                        "{\"name\":\"filter-customer\"}"));
        var metrics = new ArrayList<ApplicationReports.Metric>();
        var columns = new ArrayList<String>();
        for (var op : ReportOperationEnum.values()) {
            // COUNT_ROOT 不进 SQL 模板：统计引擎在编译时把它换成对主表主键的去重计数（见 ApplicationReportService.prepare）。
            if (op == ReportOperationEnum.FORMULA || op == ReportOperationEnum.COUNT_ROOT) continue;
            metrics.add(
                    new ApplicationReports.Metric(
                            "metric_" + metrics.size(), "Metric", op.getCode(), "1"));
            columns.add(op == ReportOperationEnum.COUNT ? null : "amount");
        }
        for (var op : ReportFormulaEnum.values()) {
            int size = metrics.size();
            metrics.add(
                    new ApplicationReports.Metric(
                            "metric_" + size,
                            "Formula",
                            "FORMULA",
                            null,
                            null,
                            new ApplicationReports.Formula(
                                    op.getCode(),
                                    size == 7 ? "metric_0" : "metric_" + (size - 1),
                                    "metric_3"),
                            null));
            columns.add(null);
        }
        var predicates = new LinkedHashMap<String, QueryWrapper<Object>>();
        if ((variant & 16) != 0)
            for (String key : List.of("fixed", "user", "m0", "m3", "access_r0", "access_r1")) {
                var predicate = new QueryWrapper<Object>();
                predicate.setParamAlias("predicates." + key);
                predicate.eq("t.\"status\"", key + "-value");
                predicates.put(key, predicate);
            }
        var group = new ArrayList<String>();
        for (int i = 0; i < dimensions.size(); i++) group.add(i == 0 ? null : "group-value");
        return new ReportStatement(
                record(variant, true, "report-base"),
                dimensions,
                joins,
                filters,
                metrics,
                columns,
                "created_at",
                (variant & 2) != 0,
                "Asia/Shanghai",
                (variant & 2) == 0 ? null : "2026-01-01",
                (variant & 8) == 0 ? null : "2026-10-01",
                (variant & 16) == 0 ? null : group,
                50,
                (variant & 8) == 0 ? null : 3,
                (variant & 4) != 0,
                predicates,
                (variant & 16) == 0 ? null : variant % 4);
    }
}
