package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.framework.mybatis.core.metadata.PostgreSqlCommands.*;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.framework.mybatis.core.metadata.PostgreSqlCommandMapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** 结构命令迁入 XML 前的固定 SQL 与参数校验基线；真实 DDL 另由开发库集成测试验证。 */
class DdlXmlCompatibilityTest {
    record Case(String key, String method, Supplier<Command> factory) {}

    static List<Case> cases() {
        var tests = new ArrayList<Case>();
        for (int v = 0; v < 8; v++) {
            int n = v;
            String schema = v % 2 == 0 ? "public" : "schema\" quoted";
            String name = "biz_表" + v;
            String column = "amount\" " + v;
            String value = v % 2 == 0 ? "x ' text \\ newline\n结束" : null;
            var fields =
                    List.of(
                            new Column(column, "numeric(18,2)", v % 2 == 0, value, false, false),
                            new Column("id", "bigint", true, null, true, true));
            add(tests, v, "createTable", "execute", () -> createTable(schema, name, fields));
            add(
                    tests,
                    v,
                    "businessTable",
                    "execute",
                    () ->
                            createBusinessTable(
                                    schema,
                                    name,
                                    List.of(
                                            fields.getFirst(),
                                            new Column(
                                                    "computed",
                                                    "numeric(18,2)",
                                                    false,
                                                    null,
                                                    false,
                                                    false,
                                                    expression(n)))));
            String[] baseColumns = {"creator", "create_time", "updater", "update_time", "deleted"};
            for (String base : baseColumns)
                add(tests, v, "base-" + base, "execute", () -> addBaseDOColumn(schema, name, base));
            add(
                    tests,
                    v,
                    "unlinked",
                    "check",
                    () -> unlinkedRows(schema, name, column, "parent", "table", "key", n % 2 == 0));
            add(tests, v, "addColumn", "execute", () -> addColumn(schema, name, fields.getFirst()));
            add(tests, v, "alterType", "execute", () -> alterType(schema, name, column, "BIGINT"));
            add(tests, v, "required", "execute", () -> required(schema, name, column, n % 2 == 0));
            add(
                    tests,
                    v,
                    "defaultValue",
                    "execute",
                    () -> defaultValue(schema, name, column, "text", value));
            add(tests, v, "defaultNow", "execute", () -> defaultNow(schema, name, column));
            add(
                    tests,
                    v,
                    "index",
                    "execute",
                    () -> index(schema, name, "idx", List.of(column, "id"), n % 2 == 0));
            add(tests, v, "dropIndex", "execute", () -> dropIndex(schema, "idx"));
            for (String action : List.of("CASCADE", "SET_NULL", "RESTRICT"))
                add(
                        tests,
                        v,
                        "fk-" + action,
                        "execute",
                        () ->
                                foreignKey(
                                        schema, name, "fk", column, "other", "target", "pk",
                                        action));
            add(tests, v, "dropConstraint", "execute", () -> dropConstraint(schema, name, "fk"));
            for (String operator : List.of(">=", "<=", "~"))
                add(
                        tests,
                        v,
                        "check-" + operator,
                        "execute",
                        () -> check(schema, name, "ck", column, operator, value));
            add(
                    tests,
                    v,
                    "comment",
                    "execute",
                    () -> comment(schema, name, n % 2 == 0 ? column : null, value));
            add(tests, v, "lock", "execute", () -> lock(schema, name));
            add(tests, v, "statementTimeout", "execute", () -> statementTimeout());
            add(tests, v, "lockTimeout", "execute", () -> lockTimeout());
            add(tests, v, "hasRows", "check", () -> hasRows(schema, name));
            add(tests, v, "hasNull", "check", () -> hasNull(schema, name, column));
            add(
                    tests,
                    v,
                    "duplicates",
                    "check",
                    () -> duplicates(schema, name, List.of(column, "id")));
            add(
                    tests,
                    v,
                    "rows",
                    "rows",
                    () ->
                            rows(
                                    schema,
                                    name,
                                    List.of(column, "id"),
                                    n % 2 == 0 ? List.of("id") : List.of(),
                                    100,
                                    n * 20,
                                    n % 3 == 0));
        }
        addInvalid(tests, "schema", () -> hasRows(" ", "t"));
        addInvalid(tests, "identifier-bytes", () -> hasRows("public", "中".repeat(22)));
        addInvalid(tests, "identifier-null-byte", () -> lock("public", "a\0b"));
        addInvalid(tests, "empty-columns", () -> createTable("public", "t", List.of()));
        addInvalid(tests, "null-columns", () -> createTable("public", "t", null));
        addInvalid(tests, "invalid-type", () -> alterType("public", "t", "c", "integer;drop"));
        addInvalid(
                tests,
                "bad-identity",
                () -> addColumn("public", "t", new Column("c", "text", false, null, true, false)));
        addInvalid(
                tests,
                "reserved-field",
                () ->
                        createBusinessTable(
                                "public",
                                "t",
                                List.of(new Column("creator", "text", false, null, false, false))));
        addInvalid(tests, "not-base", () -> addBaseDOColumn("public", "t", "id"));
        addInvalid(
                tests,
                "foreign-action",
                () -> foreignKey("public", "t", "fk", "c", "public", "target", "id", "INVALID"));
        addInvalid(tests, "check-operator", () -> check("public", "t", "ck", "c", "OR", "0"));
        addInvalid(tests, "constant-length", () -> comment("public", "t", null, "x".repeat(8001)));
        addInvalid(tests, "constant-null-byte", () -> comment("public", "t", null, "x\0y"));
        addInvalid(tests, "limit", () -> rows("public", "t", List.of("id"), List.of(), 101, 0));
        addInvalid(tests, "offset", () -> rows("public", "t", List.of("id"), List.of(), 1, 100001));
        addInvalid(tests, "empty-names", () -> index("public", "t", "i", List.of(), false));
        addInvalid(
                tests,
                "invalid-expression",
                () ->
                        addColumn(
                                "public",
                                "t",
                                new Column(
                                        "c",
                                        "text",
                                        false,
                                        null,
                                        false,
                                        false,
                                        new FunctionValue(
                                                "unknown", List.of(new TextValue("test"))))));
        return tests;
    }

    private static Expression expression(int n) {
        return switch (n % 4) {
            case 0 ->
                    new BinaryValue(
                            "+",
                            new FieldValue("amount\" " + n),
                            new NumberValue(new BigDecimal("1.50")));
            case 1 ->
                    new FunctionValue(
                            "round",
                            List.of(
                                    new BinaryValue(
                                            "/",
                                            new NumberValue(new BigDecimal("10")),
                                            new NumberValue(new BigDecimal("3"))),
                                    new NumberValue(new BigDecimal("2"))));
            case 2 ->
                    new FunctionValue(
                            "coalesce",
                            List.of(
                                    new FieldValue("amount\" " + n),
                                    new NumberValue(BigDecimal.ZERO)));
            default ->
                    new FunctionValue(
                            "abs",
                            List.of(
                                    new BinaryValue(
                                            "-",
                                            new NumberValue(BigDecimal.ONE),
                                            new NumberValue(BigDecimal.TEN))));
        };
    }

    private static void add(
            List<Case> tests, int n, String name, String method, Supplier<Command> factory) {
        tests.add(new Case(name + "/" + n, method, factory));
    }

    private static void addInvalid(List<Case> tests, String name, Supplier<Command> factory) {
        tests.add(new Case("invalid/" + name, "execute", factory));
    }

    static MybatisConfiguration configuration() throws Exception {
        var configuration = MapperXmlCompatibilityTest.configuration();
        for (var resource :
                new PathMatchingResourcePatternResolver()
                        .getResources("classpath*:mapper/database/*.xml")) {
            try (var stream = resource.getInputStream()) {
                new XMLMapperBuilder(
                                stream,
                                configuration,
                                resource.toString(),
                                configuration.getSqlFragments())
                        .parse();
            }
        }
        if (!configuration.hasMapper(PostgreSqlCommandMapper.class))
            configuration.addMapper(PostgreSqlCommandMapper.class);
        return configuration;
    }

    static Map<String, Object> snapshot(MybatisConfiguration configuration, Case test)
            throws Exception {
        Command command;
        try {
            command = test.factory().get();
        } catch (Exception ex) {
            return Map.of(
                    "error",
                    ex.getClass().getName(),
                    "message",
                    Objects.toString(ex.getMessage(), ""));
        }
        return ProviderXmlCompatibilityTest.snapshot(
                configuration,
                configuration.getMappedStatement(
                        PostgreSqlCommandMapper.class.getName() + "." + test.method()),
                Map.of("command", command));
    }

    @TestFactory
    Stream<DynamicTest> retainsOriginalSqlAndValidation() throws Exception {
        Map<String, Map<String, Object>> expected;
        try (var stream = getClass().getResourceAsStream("/sql-baseline/ddl-mappings.json")) {
            assertThat(stream).as("迁移前 DDL 基线必须存在").isNotNull();
            expected = new ObjectMapper().readValue(stream, new TypeReference<>() {});
        }
        var configuration = configuration();
        var cases = cases();
        assertThat(expected.keySet())
                .containsExactlyInAnyOrderElementsOf(cases.stream().map(Case::key).toList());
        return cases.stream()
                .map(
                        test ->
                                DynamicTest.dynamicTest(
                                        test.key(),
                                        () -> {
                                            assertThat(
                                                            configuration
                                                                    .getMappedStatement(
                                                                            PostgreSqlCommandMapper
                                                                                            .class
                                                                                            .getName()
                                                                                    + "."
                                                                                    + test.method())
                                                                    .getResource())
                                                    .endsWith(".xml]");
                                            assertThat(snapshot(configuration, test))
                                                    .isEqualTo(expected.get(test.key()));
                                        }));
    }
}
