package com.richuang.os.nocode.metadata.service.form;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.enums.*;

import java.util.*;

/** 表单关联带入只沿已有单值关系，字段类型和发布对象均在发布时检查。 */
public final class FormFillBindings {
    private FormFillBindings() {}

    /** 标量目标允许的逻辑类型；单值引用目标改为按来源与目标是否引用同一对象判定。 */
    private static final Set<FieldTypeEnum> SCALAR_TARGETS =
            Set.of(
                    FieldTypeEnum.TEXT,
                    FieldTypeEnum.TEXTAREA,
                    FieldTypeEnum.INTEGER,
                    FieldTypeEnum.DECIMAL,
                    FieldTypeEnum.MONEY,
                    FieldTypeEnum.PERCENT,
                    FieldTypeEnum.BOOLEAN,
                    FieldTypeEnum.DATE,
                    FieldTypeEnum.DATETIME,
                    FieldTypeEnum.SELECT);

    public static void validate(
            ApplicationUi.Form form,
            DataCenter.Definition object,
            Map<String, DataCenter.Definition> definitions) {
        var nodes = SelectionFields.presentations(form.nodes());
        for (var entry : nodes.entrySet()) {
            var p = entry.getValue();
            if (p == null
                    || p.fill() == null
                    || FieldRules.hasValueRule(object.fieldOptions().get(entry.getKey()))) continue;
            var fill = p.fill();
            FormFillModeEnum.fromCode(fill.mode());
            var targetRelation = BusinessFields.relation(object, entry.getKey());
            boolean multipleTarget =
                    targetRelation != null && BusinessFields.multiple(targetRelation);
            var field = field(object, entry.getKey()).orElse(null);
            var targetName =
                    multipleTarget ? targetRelation.name() : field == null ? null : field.name();
            if (targetName == null) throw invalid("关联带入目标字段不存在，请重新选择");
            // 目标名进入后续每条提示，便于设计器定位是哪个字段的带入配置有误。
            var requirement = "“" + targetName + "”的关联带入";
            if (Boolean.TRUE.equals(p.readOnly())) throw invalid(requirement + "目标不能是只读字段");
            if (multipleTarget) throw invalid(requirement + "暂不支持多选关系目标");
            if (field == null || !active(object, entry.getKey()))
                throw invalid(requirement + "目标字段已停用");
            var type = FieldTypeEnum.fromCode(field.type());
            if (type.isComputed() || type == FieldTypeEnum.AUTO_NUMBER)
                throw invalid(requirement + "目标不能是计算或自动编号字段");
            var sourceFieldId = fill.sourceFieldId();
            if (sourceFieldId == null || sourceFieldId.isBlank())
                throw invalid(requirement + "尚未选择关联来源字段");
            if (!nodes.containsKey(sourceFieldId)) throw invalid(requirement + "来源必须是本表单的关联字段");
            var relation =
                    object.relations().stream()
                            .filter(
                                    r ->
                                            Objects.equals(r.fieldId(), sourceFieldId)
                                                    && r.sourceDetailId() == null)
                            .findFirst()
                            .orElseThrow(() -> invalid(requirement + "来源必须是主表单的关联字段"));
            if (RelationTypeEnum.MANY_TO_MANY.matches(relation.kind()))
                throw invalid(requirement + "来源不能是多选关系");
            var source = definitions.get(relation.targetObjectId());
            if (source == null) throw invalid(requirement + "来源对象尚未加入应用");
            var valueFieldId = fill.valueFieldId();
            if (valueFieldId == null || valueFieldId.isBlank())
                throw invalid(requirement + "尚未选择来源字段");
            var sourceField =
                    field(source, valueFieldId)
                            .orElseThrow(() -> invalid(requirement + "来源字段不存在，请重新选择"));
            if (!active(source, valueFieldId))
                throw invalid(requirement + "来源字段“" + sourceField.name() + "”已停用");
            if (targetRelation != null) {
                // 引用目标只取来源记录自身的引用字段，且双方必须指向同一对象。
                var sourceRelation = BusinessFields.relation(source, valueFieldId);
                if (sourceRelation == null || BusinessFields.multiple(sourceRelation))
                    throw invalid(requirement + "目标是引用字段时，来源必须是来源对象上的单值引用字段");
                if (!Objects.equals(
                        sourceRelation.targetObjectId(), targetRelation.targetObjectId()))
                    throw invalid(requirement + "来源与目标必须引用同一对象");
            } else {
                var sourceType = sourceField.type();
                if (FieldTypeEnum.fromCode(sourceType).isComputed())
                    sourceType =
                            source.fieldOptions()
                                    .getOrDefault(
                                            sourceField.id(), DataCenter.FieldOptions.defaults())
                                    .resultType();
                if (!Objects.equals(field.type(), sourceType)
                        && !(type.isNumeric()
                                && sourceType != null
                                && FieldTypeEnum.fromCode(sourceType).isNumeric()))
                    throw invalid(requirement + "来源与目标类型不匹配");
                if (!SCALAR_TARGETS.contains(type)) throw invalid(requirement + "暂不支持此字段类型");
                if (type == FieldTypeEnum.SELECT
                        && !SelectionCompatibility.compatible(
                                field,
                                object.fieldOptions()
                                        .getOrDefault(
                                                field.id(), DataCenter.FieldOptions.defaults()),
                                sourceField,
                                source.fieldOptions()
                                        .getOrDefault(
                                                sourceField.id(),
                                                DataCenter.FieldOptions.defaults())))
                    throw invalid(requirement + "单选来源与目标必须使用同一字典或一致的选项编码和标签");
            }
        }
        FormDependencies.validate(form, object);
    }

    /** 仅对本次从非空变为空的来源执行显式清空，保留历史空来源下的人工快照。 */
    public static Map<String, Object> clearEmptySources(
            ApplicationUi.Form form,
            Map<String, Object> input,
            Map<String, Object> previous,
            Set<String> writable) {
        return clearEmptySources(form, input, previous, writable, Map.of());
    }

    /** 新对象规则接管的目标不再执行旧带入清空，历史配置仍保留以便维护。 */
    public static Map<String, Object> clearEmptySources(
            ApplicationUi.Form form,
            Map<String, Object> input,
            Map<String, Object> previous,
            Set<String> writable,
            Map<String, DataCenter.FieldOptions> options) {
        if (form == null) return input;
        var result = new LinkedHashMap<>(input);
        var cleared = new HashSet<String>();
        input.forEach(
                (id, value) -> {
                    if (empty(value) && !empty(previous.get(id))) cleared.add(id);
                });
        var presentations = SelectionFields.presentations(form.nodes());
        // 逐层传播清空，带入目标自身也可以是下一层关联来源。
        for (int pass = 0; pass < presentations.size(); pass++) {
            boolean changed = false;
            for (var entry : presentations.entrySet()) {
                var p = entry.getValue();
                if (p == null
                        || p.fill() == null
                        || FieldRules.hasValueRule(options.get(entry.getKey()))
                        || !Boolean.TRUE.equals(p.fill().clearOnSourceEmpty())
                        || !cleared.contains(p.fill().sourceFieldId())) continue;
                var id = entry.getKey();
                var value = result.containsKey(id) ? result.get(id) : previous.get(id);
                if (empty(value)) continue;
                if (!writable.contains(id) || Boolean.TRUE.equals(p.readOnly()))
                    throw invalid("当前权限无法清空关联带入目标，请联系管理员调整表单");
                result.put(id, null);
                cleared.add(id);
                changed = true;
            }
            if (!changed) break;
        }
        return result;
    }

    private static boolean empty(Object value) {
        return value == null
                || value instanceof String text && text.isBlank()
                || value instanceof Collection<?> collection && collection.isEmpty();
    }

    private static Optional<FieldDefinition> field(DataCenter.Definition object, String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        return object.fields().stream().filter(f -> Objects.equals(f.id(), id)).findFirst();
    }

    /** 已停用字段不参与带入，避免发布通过后运行端读不到值。 */
    private static boolean active(DataCenter.Definition object, String id) {
        return !MemberStateEnum.INACTIVE.matches(
                object.fieldOptions().getOrDefault(id, DataCenter.FieldOptions.defaults()).state());
    }
}
