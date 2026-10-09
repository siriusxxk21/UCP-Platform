package com.richuang.os.framework.mybatis.core.metadata;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** PostgreSQL 目录查询；复用底座 Mapper 扫描，所有名称均以查询值绑定。 */
@Mapper
public interface PostgreSqlDatabaseMetadataMapper {
    List<String> selectSchemas();

    List<DatabaseMetadata.Trigger> selectTriggers(
            @Param("schema") String schema, @Param("name") String name);

    DatabaseMetadata.Statistics selectStatistics(
            @Param("schema") String schema, @Param("name") String name);

    boolean relationExists(@Param("schema") String schema, @Param("name") String name);

    boolean isReservedIdentifier(@Param("name") String name);

    List<DatabaseMetadata.Relation> selectRelations(
            @Param("schema") String schema,
            @Param("nameContains") String nameContains,
            @Param("limit") int limit);

    DatabaseMetadata.Relation selectRelation(
            @Param("schema") String schema, @Param("name") String name);

    List<DatabaseMetadata.Column> selectColumns(
            @Param("schema") String schema, @Param("name") String name);

    List<DatabaseMetadata.Constraint> selectConstraints(
            @Param("schema") String schema, @Param("name") String name);

    List<DatabaseMetadata.Index> selectIndexes(
            @Param("schema") String schema, @Param("name") String name);
}
