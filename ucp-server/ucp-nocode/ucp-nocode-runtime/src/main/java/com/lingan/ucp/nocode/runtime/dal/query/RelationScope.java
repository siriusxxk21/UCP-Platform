package com.lingan.ucp.nocode.runtime.dal.query;

/** 公共运行服务从已发布关系和实际目录编译的关联表过滤，字段不是 HTTP 参数。 */
public record RelationScope(
        String schema,
        String table,
        String matchingColumn,
        String contextColumn,
        String contextId) {}
