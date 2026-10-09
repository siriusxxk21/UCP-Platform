package com.richuang.os.nocode.runtime.dal.query;

/** 关联表只由已发布关系和实际数据库目录解析，不接受客户端表名。 */
public record RelationStatement(
        String schema,
        String table,
        String sourceSchema,
        String sourceTable,
        String sourceKey,
        String sourceId,
        String targetId,
        String payload,
        String actor) {}
