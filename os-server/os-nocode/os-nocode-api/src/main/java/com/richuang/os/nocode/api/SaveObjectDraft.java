package com.richuang.os.nocode.api;

import java.util.List;

/**
 * 保存整个对象草稿的命令，不接受操作者或发布状态等受保护属性。
 *
 * @param id 创建时为空，修改时为当前对象 ID
 * @param expectedLockVersion 最近读到的整体修订号，用于防止覆盖其他人的修改
 * @param titleFieldKey 可以引用本次新增字段 key 或保留字段的稳定 ID
 * @param fields 新增及修改字段；没有出现在数组中的既有字段保持原样
 * @param removedFieldIds 明确请求移除的稳定字段 ID，不能与 fields 中的修改重叠
 */
public record SaveObjectDraft(
        String id,
        Integer expectedLockVersion,
        String objectCode,
        String objectName,
        String description,
        String tableName,
        String titleFieldKey,
        List<FieldDefinition> fields,
        List<String> removedFieldIds,
        String titleTemplate,
        String category) {
    /** 兼容省略分类的既有调用。 */
    public SaveObjectDraft(
            String id,
            Integer expectedLockVersion,
            String objectCode,
            String objectName,
            String description,
            String tableName,
            String titleFieldKey,
            List<FieldDefinition> fields,
            List<String> removedFieldIds,
            String titleTemplate) {
        this(
                id,
                expectedLockVersion,
                objectCode,
                objectName,
                description,
                tableName,
                titleFieldKey,
                fields,
                removedFieldIds,
                titleTemplate,
                null);
    }

    public SaveObjectDraft(
            String id,
            Integer expectedLockVersion,
            String objectCode,
            String objectName,
            String description,
            String tableName,
            String titleFieldKey,
            List<FieldDefinition> fields,
            List<String> removedFieldIds) {
        this(
                id,
                expectedLockVersion,
                objectCode,
                objectName,
                description,
                tableName,
                titleFieldKey,
                fields,
                removedFieldIds,
                null);
    }
}
