package com.lingan.ucp.nocode.runtime.dal.query;

import com.lingan.ucp.nocode.enums.SummaryOperationEnum;

/** 同一页记录批量聚合，父记录 ID 在查询权限裁剪后传入。 */
public record SummaryStatement(
        String schema,
        String table,
        String parentColumn,
        String column,
        SummaryOperationEnum operation,
        String parents,
        boolean logicalDelete) {}
