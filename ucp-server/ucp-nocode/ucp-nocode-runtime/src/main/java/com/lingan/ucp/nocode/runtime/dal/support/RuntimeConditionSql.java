package com.lingan.ucp.nocode.runtime.dal.support;

import com.lingan.ucp.nocode.runtime.dal.query.ReportRootScope;
import com.lingan.ucp.nocode.runtime.dal.query.ReportStatement;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 将 XML 中受控的 PostgreSQL 条件接入底座 QueryWrapper。 复用当前 SqlSessionFactory 的映射和语言驱动，只渲染已登记片段，不创建数据源或执行查询。
 * 外部值仍由 QueryWrapper 绑定；此处的标识符和字段表达式仅来自已授权对象及下列模板。
 */
@Component
public class RuntimeConditionSql {
    @Resource private SqlSessionFactory sqlSessionFactory;
    private final Map<Template, SqlSource> templates = new EnumMap<>(Template.class);

    enum Template {
        FIELD,
        CREATOR,
        ALWAYS_FALSE,
        ALWAYS_TRUE,
        CONTAINS_ANY,
        CONTAINS_ALL,
        MULTI_EQUALS,
        MULTI_NOT_EQUALS,
        DISTINCT_FROM,
        BLANK_TEXT,
        NOT_BLANK_TEXT,
        EMPTY_LIST,
        NOT_EMPTY_LIST,
        SCOPE_SCALAR,
        FIXED_SCALAR,
        FIXED_RELATION,
        RELATION_FILTER,
        CALCULATION_RELATION,
        CORRELATION_EQUAL,
        CORRELATION_LINK,
        KEY_IN,
        REPORT_ROOT_SCOPE
    }

    @PostConstruct
    void initialize() {
        org.apache.ibatis.session.Configuration configuration =
                sqlSessionFactory.getConfiguration();
        XMLLanguageDriver driver = new XMLLanguageDriver();
        for (RuntimeConditionSql.Template template : Template.values()) {
            String id =
                    "com.lingan.ucp.nocode.runtime.dal.RuntimeConditionFragments."
                            + template.name();
            org.apache.ibatis.parsing.XNode node = configuration.getSqlFragments().get(id);
            if (node == null) throw new IllegalStateException("缺少运行条件 XML：" + id);
            templates.put(template, driver.createSqlSource(configuration, node, Map.class));
        }
    }

    private String render(Template template, Map<String, Object> parameters) {
        return templates.get(template).getBoundSql(parameters).getSql();
    }

    /** 已校验标识符的字段表达式；强制文本比较仅在原调用规则要求时开启。 */
    public String column(String alias, String name, boolean text) {
        return render(
                Template.FIELD,
                Map.of("alias", alias, "column", RuntimeSqlParameters.quote(name), "text", text));
    }

    public String creator(String alias) {
        return render(Template.CREATOR, Map.of("alias", alias));
    }

    public String alwaysFalse() {
        return render(Template.ALWAYS_FALSE, Map.of());
    }

    public String alwaysTrue() {
        return render(Template.ALWAYS_TRUE, Map.of());
    }

    /** column 必须来自本组件或底座已校验的字段映射；值占位符由 QueryWrapper 处理。 */
    public String containsAny(String column, boolean castJson) {
        return render(Template.CONTAINS_ANY, Map.of("column", column, "castJson", castJson));
    }

    public String containsAll(String column) {
        return render(Template.CONTAINS_ALL, Map.of("column", column));
    }

    public String multiEquals(String column, boolean negate) {
        return render(
                negate ? Template.MULTI_NOT_EQUALS : Template.MULTI_EQUALS,
                Map.of("column", column));
    }

    /** 空值安全的「不等于」：列为 NULL 的行也算不等于。column 须来自 column(...)；值由 QueryWrapper 绑定。 */
    public String distinctFrom(String column) {
        return render(Template.DISTINCT_FROM, Map.of("column", column));
    }

    /** 文本形态列的「为空 / 不为空」：NULL 与空串都算空。column 须是 column(..., true) 的文本表达式。 */
    public String blankText(String column, boolean negate) {
        return render(
                negate ? Template.NOT_BLANK_TEXT : Template.BLANK_TEXT, Map.of("column", column));
    }

    /** 多选（jsonb 数组）列的「为空 / 不为空」：NULL、JSON null 与空数组都算空。 */
    public String emptyList(String column, boolean negate) {
        return render(
                negate ? Template.NOT_EMPTY_LIST : Template.EMPTY_LIST, Map.of("column", column));
    }

    public String scopeScalar(
            String column, String schema, String table, String name, String operator) {
        if (!Set.of("=", "<>", ">", ">=", "<", "<=").contains(operator))
            throw new IllegalArgumentException("无效范围算子");
        return render(
                Template.SCOPE_SCALAR,
                Map.of(
                        "column",
                        column,
                        "schema",
                        RuntimeSqlParameters.quote(schema),
                        "table",
                        RuntimeSqlParameters.quote(table),
                        "name",
                        RuntimeSqlParameters.quote(name),
                        "operator",
                        operator));
    }

    public String fixedScalar(String column, String schema, String table, String name) {
        return render(
                Template.FIXED_SCALAR,
                Map.of(
                        "column",
                        column,
                        "schema",
                        RuntimeSqlParameters.quote(schema),
                        "table",
                        RuntimeSqlParameters.quote(table),
                        "name",
                        RuntimeSqlParameters.quote(name)));
    }

    public String fixedRelation(String schema, String table, String key) {
        return relation(Template.FIXED_RELATION, schema, table, key);
    }

    public String relationFilter(String schema, String table, String key) {
        return relation(Template.RELATION_FILTER, schema, table, key);
    }

    /** 主键属于给定集合；key 为已校验的主键列，集合经 QueryWrapper 绑定为 JSON 数组。 */
    public String keyIn(String key) {
        return render(Template.KEY_IN, Map.of("key", RuntimeSqlParameters.quote(key)));
    }

    /**
     * 只编译既有 keys 映射，不执行主键查询；from/drill、指标和授权条件与明细保持唯一来源。 原 MyBatis 绑定值重新绑定到内部范围参数，不能把用户值或物理来源拼成
     * HTTP SQL。
     */
    public ReportRootScope reportRootScope(ReportStatement source, String key) {
        org.apache.ibatis.session.Configuration configuration =
                sqlSessionFactory.getConfiguration();
        BoundSql bound =
                configuration
                        .getMappedStatement(
                                "com.lingan.ucp.nocode.runtime.dal.mapper.ReportMapper.keys")
                        .getBoundSql(source);
        MetaObject parameters = configuration.newMetaObject(source);
        List<Object> values = new ArrayList<>();
        for (ParameterMapping mapping : bound.getParameterMappings()) {
            String property = mapping.getProperty();
            values.add(
                    bound.hasAdditionalParameter(property)
                            ? bound.getAdditionalParameter(property)
                            : parameters.getValue(property));
        }
        String sql =
                render(
                        Template.REPORT_ROOT_SCOPE,
                        Map.of("source", bound.getSql(), "key", RuntimeSqlParameters.quote(key)));
        return new ReportRootScope(rebindScope(sql, values.size()), values);
    }

    /** 引号内的问号是标识符或常量；只替换 MyBatis 生成的 JDBC 值占位符。 */
    private String rebindScope(String sql, int count) {
        StringBuilder result = new StringBuilder();
        char quote = 0;
        int parameter = 0;
        for (int i = 0; i < sql.length(); i++) {
            char value = sql.charAt(i);
            if (quote != 0) {
                result.append(value);
                if (value == quote) {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == quote)
                        result.append(sql.charAt(++i));
                    else quote = 0;
                }
            } else if (value == '\'' || value == '"') {
                quote = value;
                result.append(value);
            } else if (value == '?') {
                result.append("#{dashboardScope.values[").append(parameter++).append("]}");
            } else result.append(value);
        }
        if (parameter != count || quote != 0) throw new IllegalStateException("报表根记录范围参数绑定不完整");
        return result.toString();
    }

    public String calculationRelation(String schema, String table, String key) {
        return relation(Template.CALCULATION_RELATION, schema, table, key);
    }

    private String relation(Template template, String schema, String table, String key) {
        return render(
                template,
                Map.of(
                        "schema",
                        RuntimeSqlParameters.quote(schema),
                        "table",
                        RuntimeSqlParameters.quote(table),
                        "key",
                        RuntimeSqlParameters.quote(key)));
    }

    public String correlation(String column, String rootColumn) {
        return render(
                Template.CORRELATION_EQUAL,
                Map.of(
                        "column",
                        RuntimeSqlParameters.quote(column),
                        "rootColumn",
                        RuntimeSqlParameters.quote(rootColumn)));
    }

    public String correlation(
            String schema, String table, String key, String rootKey, boolean incoming) {
        return render(
                Template.CORRELATION_LINK,
                Map.of(
                        "schema",
                        RuntimeSqlParameters.quote(schema),
                        "table",
                        RuntimeSqlParameters.quote(table),
                        "key",
                        RuntimeSqlParameters.quote(key),
                        "rootKey",
                        RuntimeSqlParameters.quote(rootKey),
                        "incoming",
                        incoming));
    }
}
