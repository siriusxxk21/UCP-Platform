package com.lingan.ucp.common.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

/**
 * PostgreSQL 数组字段 ↔ List&lt;String&gt; 双向映射 TypeHandler。
 *
 * <p>读取时兼容两种格式：
 * <ul>
 *   <li>PostgreSQL 数组字面量：['Epic','Feature','Story']</li>
 *   <li>标准 JSON 数组：["Epic","Feature","Story"]</li>
 * </ul>
 * 写入时统一输出为标准 JSON 数组格式。
 *
 * <p>使用方式：在实体字段上标注
 * {@code @TableField(typeHandler = PgArrayTypeHandler.class)}
 *
 * @author jun
 * @since 2026-05-29
 */
public class PgArrayTypeHandler extends BaseTypeHandler<List<String>> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<String> parameter, JdbcType jdbcType)
            throws SQLException {
        try {
            ps.setString(i, OBJECT_MAPPER.writeValueAsString(parameter));
        } catch (Exception e) {
            throw new SQLException("Failed to serialize List to JSON", e);
        }
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public List<String> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    /**
     * 将数据库值解析为 List&lt;String&gt;。
     *
     * <p>兼容两种格式：
     * <ol>
     *   <li>先尝试直接按 JSON 解析（兼容双引号标准格式）</li>
     *   <li>失败则将单引号替换为双引号后重试（兼容 PostgreSQL 数组字面量）</li>
     * </ol>
     */
    private List<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return OBJECT_MAPPER.readValue(raw, new TypeReference<List<String>>() {
            });
        } catch (Exception firstAttempt) {
            // PostgreSQL 数组字面量使用单引号，替换为双引号后重试
            try {
                String json = raw.replace('\'', '"');
                return OBJECT_MAPPER.readValue(json, new TypeReference<List<String>>() {
                });
            } catch (Exception secondAttempt) {
                return Collections.emptyList();
            }
        }
    }
}
