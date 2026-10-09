package com.lingan.ucp.nocode.runtime.service.rules;

import com.lingan.ucp.nocode.api.DataCenter;
import com.lingan.ucp.nocode.api.RecordTitles;

import java.util.Map;
import java.util.Objects;

/** 挑对象候选的显示名：配置了显示名字段且当前行可读、非空时用它；否则回落目标对象标题模板（模板为空时仍是「未提供可见标题」）。 */
public final class FieldRuleLabels {
    private FieldRuleLabels() {}

    /** values 必须是已按当前操作者裁剪的行值；不可读字段不在其中，因此自然回落。 */
    public static String label(
            DataCenter.Definition target, Map<String, Object> values, String labelFieldId) {
        if (labelFieldId != null && values.containsKey(labelFieldId)) {
            String text = Objects.toString(values.get(labelFieldId), "");
            if (!text.isBlank()) return text;
        }
        return RecordTitles.render(target, values);
    }

    /** 关系字段上配置的显示名字段；未配置时为 null。 */
    public static String labelFieldId(DataCenter.Definition source, DataCenter.Relation relation) {
        if (relation == null || relation.fieldId() == null) return null;
        var options = FieldRuleService.fieldOptions(source, relation);
        var rules = options == null ? null : options.rules();
        return rules == null || rules.reference() == null ? null : rules.reference().labelFieldId();
    }
}
