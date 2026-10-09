package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.richuang.os.nocode.runtime.dal.support.RuntimeConditionSql;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.*;
import java.util.regex.Pattern;

/** 对照旧 Java 条件文本，验证 XML 排版不改变 SQL 词元及 QueryWrapper 的重复值绑定。 */
class RuntimeConditionXmlTest {
    private static final Pattern TOKEN =
            Pattern.compile(
                    "'(?:[^']|'')*'|\"(?:[^\"]|\"\")*\"|[A-Za-z_][A-Za-z_0-9$]*|[0-9]+|[+*/<>=~!@#%^&|:\\-]+|\\S");

    @Test
    void templatesPreserveOldPostgresPredicatesAndValueBinding() throws Exception {
        var factory = Mockito.mock(SqlSessionFactory.class);
        Mockito.when(factory.getConfiguration())
                .thenReturn(ProviderXmlCompatibilityTest.configuration());
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(SqlSessionFactory.class, () -> factory);
            context.register(RuntimeConditionSql.class);
            context.refresh();
            var sql = context.getBean(RuntimeConditionSql.class);
            String column = "t.\"field\"";
            var cases = new ArrayList<Map.Entry<String, String>>();
            cases.add(Map.entry(sql.column("t", "field", true), column + "::text"));
            cases.add(
                    Map.entry(sql.column("r0", "quoted\"field", false), "r0.\"quoted\"\"field\""));
            cases.add(Map.entry(sql.creator("r0"), "r0.creator::text"));
            cases.add(Map.entry(sql.alwaysFalse(), "1=0"));
            cases.add(Map.entry(sql.alwaysTrue(), "1=1"));
            for (boolean cast : List.of(false, true))
                cases.add(
                        Map.entry(
                                sql.containsAny(column, cast),
                                "EXISTS (SELECT 1 FROM jsonb_array_elements_text("
                                        + column
                                        + (cast ? "::jsonb" : "")
                                        + ") v WHERE v.value IN (SELECT"
                                        + " jsonb_array_elements_text(CAST({0} AS jsonb))))"));
            cases.add(Map.entry(sql.containsAll(column), column + " @> CAST({0} AS jsonb)"));
            for (boolean negate : List.of(false, true))
                cases.add(
                        Map.entry(
                                sql.multiEquals(column, negate),
                                (negate ? "NOT " : "")
                                        + "("
                                        + column
                                        + " @> CAST({0} AS jsonb) AND "
                                        + column
                                        + " <@ CAST({0} AS jsonb))"));
            // 对象规则的空值口径（2026-10-01）：不等于对空值安全，文本形态的空串与多选的空数组都算空。
            cases.add(Map.entry(sql.distinctFrom(column), column + " IS DISTINCT FROM {0}"));
            cases.add(
                    Map.entry(
                            sql.blankText(column, false),
                            "(" + column + " IS NULL OR " + column + " = '')"));
            cases.add(
                    Map.entry(
                            sql.blankText(column, true),
                            "(" + column + " IS NOT NULL AND " + column + " <> '')"));
            for (boolean negate : List.of(false, true))
                cases.add(
                        Map.entry(
                                sql.emptyList(column, negate),
                                "COALESCE(CASE WHEN jsonb_typeof("
                                        + column
                                        + ") = 'array' THEN jsonb_array_length("
                                        + column
                                        + ") END, 0) "
                                        + (negate ? ">" : "=")
                                        + " 0"));
            for (String op : List.of("=", "<>", ">", ">=", "<", "<="))
                cases.add(
                        Map.entry(
                                sql.scopeScalar(column, "public", "biz", "field", op),
                                column
                                        + " "
                                        + op
                                        + " (jsonb_populate_record(NULL::\"public\".\"biz\",CAST({0}"
                                        + " AS jsonb))).\"field\""));
            cases.add(
                    Map.entry(
                            sql.fixedScalar(column, "public", "biz", "field"),
                            column
                                    + " IS NOT DISTINCT FROM"
                                    + " (jsonb_populate_record(NULL::\"public\".\"biz\",CAST({0} AS"
                                    + " jsonb))).\"field\""));
            cases.add(
                    Map.entry(
                            sql.fixedRelation("public", "link", "id"),
                            "EXISTS (SELECT 1 FROM \"public\".\"link\" l WHERE l.deleted=0 AND"
                                    + " l.source_id::text=t.\"id\"::text AND l.target_id::text IN"
                                    + " (SELECT jsonb_array_elements_text(CAST({0} AS jsonb))))"));
            cases.add(
                    Map.entry(
                            sql.relationFilter("public", "link", "id"),
                            "EXISTS (SELECT 1 FROM \"public\".\"link\" r WHERE r.deleted=0 AND"
                                    + " r.source_id=t.\"id\" AND r.target_id::text IN (SELECT"
                                    + " jsonb_array_elements_text(CAST({0} AS jsonb))))"));
            cases.add(
                    Map.entry(
                            sql.calculationRelation("public", "link", "id"),
                            "EXISTS (SELECT 1 FROM \"public\".\"link\" l WHERE l.deleted=0 AND"
                                    + " l.source_id::text={0} AND l.target_id=t.\"id\")"));
            cases.add(Map.entry(sql.correlation("parent", "id"), "t.\"parent\"=r.\"id\""));
            cases.add(
                    Map.entry(
                            sql.correlation("public", "link", "id", "pk", true),
                            "EXISTS (SELECT 1 FROM \"public\".\"link\" l WHERE l.deleted=0 AND"
                                    + " l.source_id=t.\"id\" AND l.target_id=r.\"pk\")"));
            cases.add(
                    Map.entry(
                            sql.correlation("public", "link", "id", "pk", false),
                            "EXISTS (SELECT 1 FROM \"public\".\"link\" l WHERE l.deleted=0 AND"
                                    + " l.target_id=t.\"id\" AND l.source_id=r.\"pk\")"));
            assertThat(cases).hasSize(28);
            for (var test : cases) {
                var expected = wrapper(test.getValue());
                var actual = wrapper(test.getKey());
                assertThat(tokens(actual.getSqlSegment()))
                        .isEqualTo(tokens(expected.getSqlSegment()));
                assertThat(actual.getParamNameValuePairs())
                        .isEqualTo(expected.getParamNameValuePairs());
            }
            Mockito.verify(factory, Mockito.times(1)).getConfiguration();
            Mockito.verifyNoMoreInteractions(factory);
        }
    }

    private static QueryWrapper<Object> wrapper(String sql) {
        var wrapper = new QueryWrapper<Object>();
        wrapper.setParamAlias("dynamicQuery");
        if (sql.contains("{0}")) wrapper.apply(sql, "['user value t. and \\" + "']");
        else wrapper.apply(sql);
        return wrapper;
    }

    private static List<String> tokens(String sql) {
        return TOKEN.matcher(sql).results().map(m -> m.group()).toList();
    }
}
