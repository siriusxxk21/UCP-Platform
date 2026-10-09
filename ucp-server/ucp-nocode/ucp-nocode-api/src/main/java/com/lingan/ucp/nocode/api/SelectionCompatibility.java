package com.lingan.ucp.nocode.api;

import com.lingan.ucp.nocode.enums.FieldTypeEnum;
import com.lingan.ucp.nocode.enums.SelectionSourceEnum;

import java.util.*;

/** 比较稳定编码的业务含义；停用状态另由运行时目录检查，不作为编码身份。 */
public final class SelectionCompatibility {
    private SelectionCompatibility() {}

    public static boolean compatible(
            FieldDefinition left,
            DataCenter.FieldOptions leftOptions,
            FieldDefinition right,
            DataCenter.FieldOptions rightOptions) {
        if (!FieldTypeEnum.SELECT.matches(left.type())
                || !FieldTypeEnum.SELECT.matches(right.type())) return false;
        var a = SelectionFields.source(left, leftOptions);
        var b = SelectionFields.source(right, rightOptions);
        if (a == null || b == null || !Objects.equals(a.kind(), b.kind())) return false;
        if (SelectionSourceEnum.SYSTEM_DICTIONARY.matches(a.kind()))
            return a.dictionaryType() != null
                    && !a.dictionaryType().isBlank()
                    && Objects.equals(a.dictionaryType(), b.dictionaryType());
        return SelectionSourceEnum.LOCAL_OPTIONS.matches(a.kind())
                && sameLocalOptions(leftOptions.options(), rightOptions.options());
    }

    /** 两套局部选项是否同一套：编码→标签逐项一致（顺序、停用状态不计）。表单带入与对象数据联动共用这一判据。 */
    public static boolean sameLocalOptions(
            List<DataCenter.Option> left, List<DataCenter.Option> right) {
        return identities(left).equals(identities(right));
    }

    private static Map<String, String> identities(List<DataCenter.Option> options) {
        var result = new HashMap<String, String>();
        if (options != null) for (var option : options) result.put(option.code(), option.label());
        return result;
    }
}
