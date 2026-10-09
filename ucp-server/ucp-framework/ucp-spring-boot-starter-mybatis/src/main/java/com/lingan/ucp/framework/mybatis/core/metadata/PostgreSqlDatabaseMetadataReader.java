package com.lingan.ucp.framework.mybatis.core.metadata;

import com.baomidou.mybatisplus.annotation.DbType;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** 当前 PostgreSQL 数据源的目录适配；由 Spring 注入 Mapper，不自行建连接或使用静态服务定位器。 */
public class PostgreSqlDatabaseMetadataReader implements DatabaseMetadataReader {
    private final PostgreSqlDatabaseMetadataMapper mapper;

    public PostgreSqlDatabaseMetadataReader(PostgreSqlDatabaseMetadataMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public DbType databaseType() {
        return DbType.POSTGRE_SQL;
    }

    @Override
    public List<String> listSchemas() {
        return List.copyOf(mapper.selectSchemas());
    }

    @Override
    public boolean relationExists(String schema, String name) {
        return mapper.relationExists(identifier(schema), identifier(name));
    }

    @Override
    public boolean isReservedIdentifier(String name) {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0)
            throw new IllegalArgumentException("待检查名称不能为空或包含空字符");
        // 逻辑对象编码可以超过数据库标识符长度；这种名称不可能等于保留关键字。
        if (name.getBytes(StandardCharsets.UTF_8).length > 63) return false;
        return mapper.isReservedIdentifier(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public List<DatabaseMetadata.Relation> listRelations(
            String schema, String nameContains, int limit) {
        identifier(schema);
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("元数据查询数量必须在 1–1000");
        if (nameContains != null
                && (nameContains.length() > 128 || nameContains.indexOf('\0') >= 0))
            throw new IllegalArgumentException("表名筛选条件无效");
        return List.copyOf(mapper.selectRelations(schema, nameContains, limit));
    }

    @Override
    public Optional<DatabaseMetadata.Table> readTable(String schema, String name) {
        identifier(schema);
        identifier(name);
        var relation = mapper.selectRelation(schema, name);
        if (relation == null) return Optional.empty();
        return Optional.of(
                new DatabaseMetadata.Table(
                        relation,
                        mapper.selectColumns(schema, name),
                        mapper.selectConstraints(schema, name),
                        mapper.selectIndexes(schema, name),
                        mapper.selectTriggers(schema, name),
                        mapper.selectStatistics(schema, name)));
    }

    private static String identifier(String value) {
        // 元数据读取需支持已有表的大小写、空格等名称；精确绑定，不把名称当作 SQL 标识符拼接。
        if (value == null
                || value.isEmpty()
                || value.indexOf('\0') >= 0
                || value.getBytes(StandardCharsets.UTF_8).length > 63)
            throw new IllegalArgumentException("数据库对象名称必填且最多 63 字节");
        return value;
    }
}
