package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 注解 SQL 迁入 XML 的离线兼容回归。
 *
 * <p>基线由迁移前实际 MyBatis 映射生成；逐语句核对动态分支 SQL、参数顺序/类型及执行元数据。 本测试不连接数据库，不能替代真实事务和数据库回归。
 */
class MapperXmlCompatibilityTest {
    // 忽略词元间空白，保留引号内内容和连续运算符；适用于本批不含 SQL 注释或美元引号的原始模板。
    private static final Pattern SQL_TOKEN =
            Pattern.compile(
                    "'(?:[^']|'')*'|\"(?:[^\"]|\"\")*\"|[A-Za-z_][A-Za-z_0-9$]*"
                            + "|(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"
                            + "|[+*/<>=~!@#%^&|:\\-]+|\\S");

    @Test
    void formattingComparisonPreservesLiteralsAndTokenBoundaries() {
        assertThat(tokens("SELECT id,name FROM sample WHERE id=?"))
                .isEqualTo(tokens("SELECT id,\n    name\nFROM sample\nWHERE id = ?"));
        assertThat(tokens("SELECT 'a b', \"A B\", 'it''s'"))
                .containsExactly("SELECT", "'a b'", ",", "\"A B\"", ",", "'it''s'");
        assertThat(tokens("SELECT 'a b'")).isNotEqualTo(tokens("SELECT 'ab'"));
        assertThat(tokens("SELECT a b")).isNotEqualTo(tokens("SELECT ab"));
        assertThat(tokens("SELECT value->>'kind'")).isNotEqualTo(tokens("SELECT value-> >'kind'"));
    }

    @TestFactory
    Stream<DynamicTest> migratedStatementsKeepTheirOriginalContract() throws Exception {
        Map<String, Map<String, Object>> expected;
        try (var input = getClass().getResourceAsStream("/sql-baseline/annotation-mappings.json")) {
            assertThat(input).as("迁移前固定基线必须存在").isNotNull();
            expected = new ObjectMapper().readValue(input, new TypeReference<>() {});
        }
        try (var input =
                getClass()
                        .getResourceAsStream("/sql-baseline/additional-annotation-mappings.json")) {
            assertThat(input).as("全限定注解的补充原始基线必须存在").isNotNull();
            expected.putAll(
                    new ObjectMapper()
                            .readValue(
                                    input,
                                    new TypeReference<Map<String, Map<String, Object>>>() {}));
        }
        var configuration = configuration();
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
        assertThat(expected).hasSize(67);
        return expected.entrySet().stream()
                .map(
                        entry ->
                                DynamicTest.dynamicTest(
                                        entry.getKey(),
                                        () -> {
                                            var actual =
                                                    configuration.getMappedStatement(
                                                            SqlBaselineNames.current(
                                                                    entry.getKey()));
                                            assertThat(actual.getResource()).endsWith(".xml]");
                                            var original = new LinkedHashMap<>(entry.getValue());
                                            String template =
                                                    (String) original.remove("sqlTemplate");
                                            // 保留迁移前原始基线；数据中心无应用上下文的幂等收据后来支持了空应用 ID。
                                            if (entry.getKey()
                                                    .endsWith("DocumentReceiptMapper.find")) {
                                                template =
                                                        template.replace(
                                                                "application_id=CAST(#{app} AS"
                                                                        + " bigint)",
                                                                "application_id IS NOT DISTINCT"
                                                                        + " FROM CAST(#{app} AS"
                                                                        + " bigint)");
                                            }
                                            String type = (String) original.remove("parameterType");
                                            Class<?> parameterType =
                                                    type == null
                                                            ? null
                                                            : "long".equals(type)
                                                                    ? long.class
                                                                    : Class.forName(
                                                                            SqlBaselineNames
                                                                                    .current(type));
                                            var source =
                                                    new XMLLanguageDriver()
                                                            .createSqlSource(
                                                                    configuration,
                                                                    template,
                                                                    parameterType);
                                            original.put("cases", cases(source));
                                            assertThat(snapshot(actual))
                                                    .isEqualTo(
                                                            SqlBaselineNames.resultTypes(original));
                                        }));
    }

    static MybatisConfiguration configuration() {
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        GlobalConfigUtils.setGlobalConfig(
                configuration, new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig()));
        return configuration;
    }

    /** 仅为基线采集和断言共享表达方式；期望值来自迁移前编译产物，不由新 XML 反算。 */
    static Map<String, Object> snapshot(MappedStatement statement) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        result.put("command", statement.getSqlCommandType().name());
        result.put("flushCache", statement.isFlushCacheRequired());
        result.put("affectData", statement.isDirtySelect());
        result.put("timeout", statement.getTimeout());
        result.put("statementType", statement.getStatementType().name());
        result.put("resultSetType", statement.getResultSetType().name());
        result.put("keyGenerator", statement.getKeyGenerator().getClass().getSimpleName());
        result.put(
                "keyProperties",
                statement.getKeyProperties() == null
                        ? null
                        : List.of(statement.getKeyProperties()));
        result.put(
                "keyColumns",
                statement.getKeyColumns() == null ? null : List.of(statement.getKeyColumns()));
        // INSERT/UPDATE 的返回值是影响行数，不使用查询结果映射或二级查询缓存。
        if (statement.getSqlCommandType() == SqlCommandType.SELECT) {
            result.put("useCache", statement.isUseCache());
            result.put(
                    "resultTypes",
                    statement.getResultMaps().stream()
                            .map(map -> map.getType().getName())
                            .toList());
        }
        result.put("cases", cases(statement.getSqlSource()));
        return result;
    }

    /** 使用保存的原注解模板独立编译对照；格式变化只允许发生在词元之间。 */
    static List<String> cases(SqlSource source) throws Exception {
        var cases = new ArrayList<String>();
        for (int variant = 0; variant < 32; variant++) {
            var parameters = new HashMap<String, Object>();
            parameters.put("state", (variant & 1) == 0 ? null : "DRAFT");
            parameters.put("app", (variant & 2) == 0 ? null : "1");
            parameters.put("records", (variant & 4) == 0 ? null : List.of("1", "2"));
            parameters.put("fields", (variant & 8) != 0);
            parameters.put("rows", List.of("1", "2"));
            var query = new HashMap<String, Object>();
            query.put(
                    "before",
                    (variant & 16) == 0
                            ? null
                            : Map.of("createdAt", "2026-09-13T12:00:00", "id", "1"));
            parameters.put("query", query);
            var sql = source.getBoundSql(parameters);
            var bindings =
                    sql.getParameterMappings().stream()
                            .map(
                                    parameter ->
                                            List.of(
                                                    parameter.getProperty(),
                                                    parameter.getJavaType().getName(),
                                                    Objects.toString(parameter.getJdbcType(), ""),
                                                    parameter
                                                            .getTypeHandler()
                                                            .getClass()
                                                            .getName()))
                            .toList();
            var payload =
                    new ObjectMapper().writeValueAsString(List.of(tokens(sql.getSql()), bindings));
            cases.add(
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(payload.getBytes(StandardCharsets.UTF_8))));
        }
        return cases.stream().distinct().toList();
    }

    private static List<String> tokens(String sql) {
        return SQL_TOKEN.matcher(sql).results().map(match -> match.group()).toList();
    }
}
