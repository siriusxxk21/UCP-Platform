package com.richuang.os.nocode.api;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.nocode.enums.FieldTypeEnum;
import com.richuang.os.nocode.enums.RecordQueryOperatorEnum;

import java.math.*;
import java.time.*;
import java.util.*;

/** 统计配置保存与运行查询共用值转换，阻止格式无效的条件进入发布快照。 */
public final class RecordConditionValues {
    private RecordConditionValues() {}

    /** 按字段定义转换绑定值；文本通配符按字面匹配，空值运算无需输入。 */
    public static Object value(
            FieldDefinition field,
            DataCenter.FieldOptions options,
            Object raw,
            RecordQueryOperatorEnum operator) {
        if (operator == RecordQueryOperatorEnum.IS_NULL
                || operator == RecordQueryOperatorEnum.NOT_NULL) return null;
        if (raw == null || raw instanceof Collection<?> || raw instanceof Map<?, ?>)
            throw invalid("请填写查询值：" + field.name());
        String text = raw.toString();
        if (text.isBlank() || text.length() > 2000) throw invalid("查询值不能为空或超过 2000 字符");
        try {
            var type = FieldTypeEnum.fromCode(field.type());
            return switch (type) {
                case INTEGER -> new BigInteger(text).longValueExact();
                case DECIMAL, MONEY, PERCENT -> new BigDecimal(text);
                case BOOLEAN -> {
                    if (!(raw instanceof Boolean)) throw new IllegalArgumentException();
                    yield raw;
                }
                case DATE -> LocalDate.parse(text);
                case TIME -> LocalTime.parse(text);
                case DATETIME ->
                        options.nativeType() != null
                                        && options.nativeType().contains("with time zone")
                                ? OffsetDateTime.parse(text)
                                : LocalDateTime.parse(text.replace(" ", "T"));
                default ->
                        switch (operator) {
                            // 底座 like 自动添加通配符；用户输入的百分号和下划线按字面匹配。
                            case LIKE, NOT_LIKE, START_WITH, END_WITH ->
                                    text.replace("\\", "\\\\")
                                            .replace("%", "\\%")
                                            .replace("_", "\\_");
                            default -> text;
                        };
            };
        } catch (RuntimeException e) {
            throw invalid("查询值格式无效：" + field.name());
        }
    }
}
