package com.lingan.ucp.nocode.metadata.service.object;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.lingan.ucp.nocode.api.DataCenter;
import com.lingan.ucp.nocode.api.FieldDefinition;
import com.lingan.ucp.nocode.api.FieldRules;
import com.lingan.ucp.nocode.enums.FieldTypeEnum;
import com.lingan.ucp.nocode.enums.LinkageMultiRowEnum;
import com.lingan.ucp.nocode.enums.MemberStateEnum;
import com.lingan.ucp.nocode.enums.MoneyRoundingEnum;
import com.lingan.ucp.nocode.enums.RuleValueSourceEnum;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 数据联动自动更新的反向索引推导（纯函数，不连库）：从一组固定的对象定义得出「哪些目标字段开启了自动更新、来源是谁、按哪个字段锚定」。
 *
 * <p>输入是不可变的（应用发布快照固定的对象版本），所以同一输入永远得出同一组行；索引表只是这份推导结果的落库副本，登记后不修改。
 */
public final class LinkageTriggerPlan {
    /** 「来源对象的关联字段 等于 当前记录」：锚点字段在来源对象上，来源行自己就指出牵动哪条目标。 */
    public static final String ANCHOR_CURRENT_RECORD = "CURRENT_RECORD";

    /** 「按记录匹配 等于 当前字段」：锚点字段在目标对象上，要反查「哪些目标指向这条来源」。 */
    public static final String ANCHOR_RECORD_KEY = "RECORD_KEY";

    private static final ObjectMapper CANONICAL =
            new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private LinkageTriggerPlan() {}

    /** 一个开启了自动更新的目标字段。 */
    public record Row(
            String sourceObjectId,
            String targetObjectId,
            int targetObjectVersion,
            String targetFieldId,
            String anchor,
            String anchorFieldId,
            String signature) {}

    /**
     * @param pinned 固定的对象定义，键为对象 ID
     * @param versions 各对象固定的版本号，键为对象 ID
     * @return 按目标对象 ID、目标字段 ID 排序的行；没有锚点或来源对象不在固定集合里的规则不出行（发布校验会拒绝这种配置）
     */
    public static List<Row> derive(
            Map<String, DataCenter.Definition> pinned, Map<String, Integer> versions) {
        List<Row> rows = new ArrayList<>();
        for (var d : pinned.values()) {
            Integer version = versions.get(d.objectId());
            if (version == null) throw invalid("缺少对象「" + d.objectName() + "」的固定版本号");
            for (var field : d.fields()) {
                var options = d.fieldOptions().get(field.id());
                var linkage = linkage(options);
                if (linkage == null || !linkage.autoUpdateOn()) continue;
                var anchor = FieldRuleValidator.anchor(linkage);
                var source = pinned.get(linkage.sourceObjectId());
                if (anchor == null || source == null) continue;
                boolean current = RuleValueSourceEnum.CURRENT_RECORD.matches(anchor.valueSource());
                rows.add(
                        new Row(
                                source.objectId(),
                                d.objectId(),
                                version,
                                field.id(),
                                current ? ANCHOR_CURRENT_RECORD : ANCHOR_RECORD_KEY,
                                current ? anchor.fieldId() : anchor.formFieldId(),
                                signature(d, field, options, source)));
            }
        }
        // ID 是十进制数字串：先比长度再比字面，等价于按数值升序，且不要求一定能转成 long。
        rows.sort(
                Comparator.comparing((Row r) -> r.targetObjectId().length())
                        .thenComparing(Row::targetObjectId)
                        .thenComparing(r -> r.targetFieldId().length())
                        .thenComparing(Row::targetFieldId));
        return List.copyOf(rows);
    }

    /**
     * 规则签名：键有序 JSON 的 sha256。只含决定求值结果的内容——目标字段的类型与位数、联动的来源/条件/取值字段/多行档位/空值填入、取整档位、
     * 被用到的来源字段与当前字段的类型；不含显示名、对象版本号与应用信息。同一目标字段在不同应用版本或不同应用里签名相同即规则相同。
     */
    public static String signature(
            DataCenter.Definition target,
            FieldDefinition field,
            DataCenter.FieldOptions options,
            DataCenter.Definition source) {
        var rules = options.rules();
        var l = rules.linkage();
        Map<String, Object> content = new TreeMap<>();
        content.put("targetObjectId", target.objectId());
        content.put("targetField", fieldShape(field, options, true));
        content.put("sourceObjectId", l.sourceObjectId());
        List<Map<String, Object>> conditions = new ArrayList<>();
        Map<String, Object> sourceFields = new TreeMap<>();
        Map<String, Object> currentFields = new TreeMap<>();
        sourceField(source, l.valueFieldId(), sourceFields);
        for (var c : l.conditions() == null ? List.<FieldRules.Condition>of() : l.conditions()) {
            if (c == null) continue;
            Map<String, Object> condition = new LinkedHashMap<>();
            condition.put("fieldId", c.fieldId());
            condition.put("operator", c.operator());
            condition.put("valueSource", c.valueSource());
            condition.put("value", c.value());
            condition.put("formFieldId", c.formFieldId());
            conditions.add(condition);
            if (!FieldRules.RECORD_KEY.equals(c.fieldId()))
                sourceField(source, c.fieldId(), sourceFields);
            if (c.formFieldId() != null)
                for (var f : target.fields())
                    if (f.id().equals(c.formFieldId()))
                        currentFields.put(
                                f.id(),
                                fieldShape(
                                        f,
                                        target.fieldOptions()
                                                .getOrDefault(
                                                        f.id(), DataCenter.FieldOptions.defaults()),
                                        false));
        }
        content.put("conditions", conditions);
        content.put("valueFieldId", l.valueFieldId());
        content.put(
                "multiRow",
                l.multiRow() == null ? LinkageMultiRowEnum.CONCAT.getCode() : l.multiRow());
        content.put("emptyValue", l.emptyValue());
        content.put(
                "rounding",
                FieldTypeEnum.MONEY.matches(field.type())
                        ? rules.rounding() == null
                                ? MoneyRoundingEnum.FLOOR.getCode()
                                : rules.rounding()
                        : null);
        content.put("sourceFields", sourceFields);
        content.put("currentFields", currentFields);
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            CANONICAL
                                                    .writeValueAsString(content)
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw invalid("数据联动规则签名无法计算");
        }
    }

    private static void sourceField(
            DataCenter.Definition source, String fieldId, Map<String, Object> out) {
        if (fieldId == null) return;
        for (var f : source.fields())
            if (f.id().equals(fieldId))
                out.put(
                        f.id(),
                        fieldShape(
                                f,
                                source.fieldOptions()
                                        .getOrDefault(f.id(), DataCenter.FieldOptions.defaults()),
                                false));
    }

    /** 字段形状：类型与（计算字段的）结果类型；目标字段另带长度、位数与小数位。 */
    private static Map<String, Object> fieldShape(
            FieldDefinition field, DataCenter.FieldOptions options, boolean target) {
        Map<String, Object> shape = new TreeMap<>();
        shape.put("id", field.id());
        shape.put("type", field.type());
        if (target) {
            shape.put("length", field.length());
            shape.put("precision", field.precision());
            shape.put("scale", field.scale());
        } else shape.put("resultType", options == null ? null : options.resultType());
        return shape;
    }

    private static FieldRules.Linkage linkage(DataCenter.FieldOptions options) {
        if (options == null
                || MemberStateEnum.INACTIVE.matches(options.state())
                || options.rules() == null) return null;
        return options.rules().linkage();
    }
}
