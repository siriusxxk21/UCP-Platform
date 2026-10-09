package com.richuang.os.nocode.metadata.service.object;

import static com.richuang.os.nocode.api.NocodeErrorCodes.DUPLICATE;

import com.richuang.os.framework.common.exception.ServiceException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;

import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对象设计保存的最后一道兜底：元数据表的唯一约束冲突（含提交时才检查的延迟约束）转换为说人话的业务报错，不以「系统异常」透给界面。
 *
 * <p>正常的删后重建、改目标等路径已在保存逻辑里让出或复用编码；走到这里说明还有未覆盖的占用， 报错只说明被占用的设计编码，不带 SQL 和库内主键。
 */
final class ObjectDesignConstraintErrors {
    private static final Logger LOG = LoggerFactory.getLogger(ObjectDesignConstraintErrors.class);
    private static final Pattern KEY_VALUES = Pattern.compile("\\(([^()]*)\\)=\\(([^()]*)\\)");
    private static final Map<String, String> MESSAGES =
            Map.of(
                    "nocode_relation_object_version_id_relation_code_key",
                    "关系编码 %s 已被占用，请刷新后重试或更换关系编码",
                    "nocode_index_definition_object_version_id_index_code_key",
                    "索引编码 %s 已被占用，请刷新后重试或更换索引编码",
                    "nocode_field_object_table_id_field_code_key",
                    "字段编码 %s 已被占用（可能是已停用字段或关系生成的引用列），请恢复原字段或更换编码",
                    "nocode_field_object_table_id_column_name_key",
                    "物理列 %s 已被同一张表的其他字段（含已停用字段或改过编码的已发布字段）占用，请更换字段编码",
                    "nocode_object_table_object_version_id_table_code_key",
                    "明细编码 %s 已被占用，请更换明细编码",
                    "nocode_object_table_object_version_id_table_name_key",
                    "物理表名 %s 已被占用，请更换物理表名");

    private ObjectDesignConstraintErrors() {}

    static <T> T translate(Supplier<T> save) {
        try {
            return save.get();
        } catch (DuplicateKeyException error) {
            String text = String.valueOf(error.getMostSpecificCause().getMessage());
            String constraint =
                    MESSAGES.keySet().stream().filter(text::contains).findFirst().orElse(null);
            LOG.warn("对象设计保存违反唯一约束 {}，已转为业务报错", constraint, error);
            String message =
                    constraint == null
                            ? "对象设计中的编码或物理名称已被占用，请刷新后重试"
                            : String.format(MESSAGES.get(constraint), conflictingCode(text));
            ServiceException translated = new ServiceException(DUPLICATE, message);
            translated.initCause(error);
            throw translated;
        }
    }

    /** 约束列的最后一列是设计编码（前面是版本或表的库内主键）；取不到时用通用说法。 */
    private static String conflictingCode(String text) {
        Matcher matcher = KEY_VALUES.matcher(text);
        if (!matcher.find()) return "（同名）";
        String[] values = matcher.group(2).split(",");
        return "“" + values[values.length - 1].trim() + "”";
    }
}
