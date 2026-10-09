package com.richuang.os.nocode.api;

import com.richuang.os.nocode.enums.FieldTypeEnum;
import com.richuang.os.nocode.enums.MemberStateEnum;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 依赖检查的值契约：展示仍可读取字段，写入与类型运算需要保持原值语义。 */
public final class FieldConversionCompatibility {
    private FieldConversionCompatibility() {}

    public record Field(FieldDefinition definition, DataCenter.FieldOptions options) {}

    public static Map<String, Field> fields(DataCenter.Definition definition) {
        Map<String, Field> result = new LinkedHashMap<>();
        add(result, definition.fields(), definition.fieldOptions());
        for (DataCenter.Detail detail : definition.details())
            if (!MemberStateEnum.INACTIVE.matches(detail.state()))
                add(result, detail.fields(), detail.fieldOptions());
        return result;
    }

    private static void add(
            Map<String, Field> result,
            java.util.List<FieldDefinition> fields,
            Map<String, DataCenter.FieldOptions> options) {
        for (FieldDefinition field : fields) {
            DataCenter.FieldOptions option =
                    options.getOrDefault(field.id(), DataCenter.FieldOptions.defaults());
            if (!MemberStateEnum.INACTIVE.matches(option.state()))
                result.put(field.id(), new Field(field, option));
        }
    }

    /** 同数值/纯文本家族保留基本读写语义；选择来源、单多值及对象引用不互相替代。 */
    public static boolean valueCompatible(Field before, Field after) {
        if (before == null || after == null) return false;
        if (!SelectionFields.identity(before.definition(), before.options())
                .equals(SelectionFields.identity(after.definition(), after.options())))
            return false;
        FieldTypeEnum from = FieldTypeEnum.fromCode(before.definition().type());
        FieldTypeEnum to = FieldTypeEnum.fromCode(after.definition().type());
        if (from == to) return true;
        if (from.isNumeric() && to.isNumeric()) return true;
        return Set.of(FieldTypeEnum.TEXT, FieldTypeEnum.TEXTAREA).contains(from)
                && Set.of(FieldTypeEnum.TEXT, FieldTypeEnum.TEXTAREA).contains(to);
    }

    public static boolean storageChanged(Field before, Field after) {
        return before == null
                || after == null
                || !Objects.equals(
                        FieldStorage.sqlType(before.definition(), before.options()),
                        FieldStorage.sqlType(after.definition(), after.options()));
    }

    /** 固定版本表单可能继续提交原类型的任意值，缩小数值精度或文本容量不能视作兼容写入。 */
    public static boolean writeCompatible(Field before, Field after) {
        if (!valueCompatible(before, after)) return false;
        FieldDefinition old = before.definition();
        FieldDefinition next = after.definition();
        if (FieldTypeEnum.INTEGER.matches(next.type())
                && !FieldTypeEnum.INTEGER.matches(old.type())) return false;
        if (next.length() != null && (old.length() == null || next.length() < old.length()))
            return false;
        if (old.scale() != null && next.scale() != null && next.scale() < old.scale()) return false;
        return old.precision() == null
                || next.precision() == null
                || next.precision() - Objects.requireNonNullElse(next.scale(), 0)
                        >= old.precision() - Objects.requireNonNullElse(old.scale(), 0);
    }
}
