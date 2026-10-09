package com.lingan.ucp.nocode.metadata.service.object;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.*;

import com.lingan.ucp.framework.mybatis.core.metadata.DatabaseMetadataReader;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.enums.*;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/** 对象和字段定义的领域校验；数据库保留名称通过底座元数据服务查询。 */
@Component
public class DraftValidator {
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Set<String> SYSTEM =
            Set.of(
                    "id",
                    "creator",
                    "create_time",
                    "updater",
                    "update_time",
                    "created_by",
                    "created_at",
                    "updated_by",
                    "updated_at",
                    "version_no",
                    "lock_version",
                    "is_deleted",
                    "deleted",
                    "tenant_id",
                    "app_id",
                    "parent_id");
    @Resource private DatabaseMetadataReader databaseMetadata;

    public String text(String value, String path, int max) {
        if (value == null || value.isBlank() || value.length() > max)
            throw invalid(path + " 必填且不能超过 " + max + " 字符");
        return value.trim();
    }

    public String code(String value, String path, int max, boolean field) {
        String result = text(value, path, max);
        if (!CODE.matcher(result).matches()) throw invalid(path + " 只能以小写字母开头，包含小写字母、数字、下划线");
        if (field && SYSTEM.contains(result)) throw invalid(path + " 与系统字段冲突");
        if (databaseMetadata.isReservedIdentifier(result)) throw invalid(path + " 是数据库保留名称");
        return result;
    }

    public long id(String value, String path) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw invalid(path + " 不是有效 ID");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw invalid(path + " 超出 ID 范围");
        }
    }

    public void basic(SaveObjectDraft request, boolean create) {
        basic(request, create, false);
    }

    public void basic(SaveObjectDraft request, boolean create, boolean adopted) {
        basic(request, create, adopted, null);
    }

    /** 历史物理名只允许原样保留；新建或更换候选名必须采用 biz_。 */
    public void generatedTableName(String tableName, String previousTableName) {
        code(tableName, "物理表名", 63, false);
        if (!tableName.matches("biz_[a-z][a-z0-9_]*")
                && !(tableName.equals(previousTableName)
                        && tableName.matches("nocode_data_[a-z][a-z0-9_]*")))
            throw invalid("新生成的物理表名必须以 biz_ 开头，后缀以小写字母开头");
    }

    public void basic(
            SaveObjectDraft request, boolean create, boolean adopted, String previousTableName) {
        if (request == null) throw invalid("请求不能为空");
        code(request.objectCode(), "对象编码", 64, false);
        text(request.objectName(), "对象名称", 128);
        // 生成表使用独立命名空间，防止占用底座或无代码元数据表的名称。
        String tableName =
                adopted
                        ? text(request.tableName(), "物理表名", 63)
                        : code(request.tableName(), "物理表名", 63, false);
        if (!adopted) generatedTableName(tableName, create ? null : previousTableName);
        if (request.description() != null && request.description().length() > 1000)
            throw invalid("说明最多 1000 字符");
        text(request.titleFieldKey(), "记录标题", 100);
        if (request.titleTemplate() != null && request.titleTemplate().length() > 512)
            throw invalid("标题模板最多 512 字符");
        if (request.fields() == null || request.fields().size() > 200)
            throw invalid("fields 必须是最多 200 项的数组");
        if (request.removedFieldIds() != null && request.removedFieldIds().size() > 200)
            throw invalid("删除字段最多 200 项");
        if (create) {
            if (request.id() != null
                    || request.expectedLockVersion() != null
                    || request.removedFieldIds() != null && !request.removedFieldIds().isEmpty())
                throw invalid("创建不能带对象 ID、修订号或删除字段");
        } else {
            id(request.id(), "对象 ID");
            if (request.expectedLockVersion() == null || request.expectedLockVersion() < 0)
                throw invalid("预期修订号必填");
        }
    }

    public FieldDefinition field(FieldDefinition f, String stableId) {
        if (f == null) throw invalid("字段不能为空");
        String code = code(f.code(), "字段编码", 63, true);
        String name = text(f.name(), "字段名称", 128);
        if (!FieldTypeEnum.containsCode(f.type())) throw invalid(name + "：不支持的字段类型");
        if (f.sort() == null || f.sort() < 0 || f.sort() > 10000)
            throw invalid(name + "：排序必须在 0–10000");
        Integer length = f.length(), precision = f.precision(), scale = f.scale();
        if (FieldTypeEnum.TEXT.matches(f.type())) {
            length = length == null ? 200 : length;
            if (length < 1 || length > 4000) throw invalid(name + "：长度必须在 1–4000");
        } else if (length != null) throw invalid(name + "：当前类型不接受长度");
        if (FieldTypeEnum.fromCode(f.type()).isDecimal()) {
            precision = precision == null ? 18 : precision;
            scale = scale == null ? 2 : scale;
            if (precision < 1 || precision > 38 || scale < 0 || scale > precision)
                throw invalid(name + "：精度必须在 1–38，小数位不能大于精度");
        } else if (precision != null || scale != null) throw invalid(name + "：当前类型不接受精度/小数位");
        return new FieldDefinition(
                stableId,
                stableId,
                code,
                name,
                f.type(),
                length,
                precision,
                scale,
                Boolean.TRUE.equals(f.required()),
                Boolean.TRUE.equals(f.unique()),
                f.sort());
    }
}
