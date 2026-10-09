package com.richuang.os.nocode.runtime.dal.support;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.nocode.runtime.dal.query.*;

import java.util.ArrayList;
import java.util.List;

/** Mapper XML 的受控标识符和投影参数；不生成 SELECT、条件或写入语句。 */
public final class RuntimeSqlParameters {
    private RuntimeSqlParameters() {}

    /** 延续原动态记录查询的校验与引用规则，不额外收紧既有数据库目录中的名称。 */
    public static String quote(String name) {
        if (name == null || name.isBlank() || name.indexOf(0) >= 0)
            throw new IllegalArgumentException("缺少数据库标识符");
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    /**
     * PostgreSQL 字符串字面量：jsonb 取键（to_jsonb(t)->>key）等按值引用的位置必须用字面量， 标识符写法会被解析成同名列的当前值，键不匹配时静默返回空值。
     */
    public static String literal(String value) {
        if (value == null || value.isBlank() || value.indexOf(0) >= 0)
            throw new IllegalArgumentException("缺少数据库标识符");
        return "'" + value.replace("'", "''") + "'";
    }

    /** 专用有序读写只定位已读出的单个主键，禁止静默丢弃普通查询的授权或筛选范围。 */
    public static RecordStatement requireOrderedIdentity(RecordStatement statement) {
        if (statement == null || statement.id() == null || statement.id().isBlank())
            throw invalid("有序派生操作缺少已读取的记录主键");
        String conditions =
                statement.dynamicQuery() == null ? null : statement.dynamicQuery().getSqlSegment();
        if (statement.parentId() != null
                || statement.creator() != null
                || statement.search() != null
                || statement.filterColumns() != null && !statement.filterColumns().isEmpty()
                || statement.relationScope() != null
                || statement.dashboardScope() != null
                || conditions != null && !conditions.isBlank()
                || statement.offset() != 0
                || statement.limit() < 1) throw invalid("有序派生主键操作不能携带额外记录范围或分页条件");
        return statement;
    }

    /** 使用专用字段参数，避免 MyBatis foreach 将 Map.Entry 自动拆为键和值。 */
    public record ProjectionField(String id, String column) {}

    /** PostgreSQL 单函数最多 100 个参数；按原规则每 40 个键值对分组，保留字段顺序。 */
    public static List<List<ProjectionField>> projectionGroups(RecordStatement statement) {
        var fields = new ArrayList<ProjectionField>();
        for (var field : statement.fields().entrySet()) {
            if (!field.getKey().matches("[1-9][0-9]*"))
                throw new IllegalArgumentException("字段 ID 无效");
            quote(field.getValue());
            fields.add(new ProjectionField(field.getKey(), field.getValue()));
        }
        var groups = new ArrayList<List<ProjectionField>>();
        for (int from = 0; from < fields.size(); from += 40)
            groups.add(fields.subList(from, Math.min(from + 40, fields.size())));
        return groups;
    }
}
