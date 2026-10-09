package com.richuang.os.nocode.api;

import java.time.OffsetDateTime;
import java.util.List;

/** 完整草稿快照；字段定义与对象修订号来自同一次受锁保护的读取。 */
public record ObjectDraft(
        String id,
        String objectCode,
        String objectName,
        String description,
        String tableName,
        String titleFieldId,
        String state,
        int lockVersion,
        int versionNo,
        OffsetDateTime updatedAt,
        List<FieldDefinition> fields,
        String category) {
    /** 兼容省略分类的既有调用。 */
    public ObjectDraft(
            String id,
            String objectCode,
            String objectName,
            String description,
            String tableName,
            String titleFieldId,
            String state,
            int lockVersion,
            int versionNo,
            OffsetDateTime updatedAt,
            List<FieldDefinition> fields) {
        this(
                id,
                objectCode,
                objectName,
                description,
                tableName,
                titleFieldId,
                state,
                lockVersion,
                versionNo,
                updatedAt,
                fields,
                null);
    }
}
