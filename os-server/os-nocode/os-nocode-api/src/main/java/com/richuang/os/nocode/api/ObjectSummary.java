package com.richuang.os.nocode.api;

import java.time.OffsetDateTime;

/** 管理列表的对象摘要；不包含任何业务记录数据。 */
public record ObjectSummary(
        String id,
        String objectCode,
        String objectName,
        String tableName,
        String state,
        int lockVersion,
        int fieldCount,
        OffsetDateTime updatedAt) {}
