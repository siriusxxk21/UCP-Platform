package com.lingan.ucp.nocode.metadata.service.form;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.enums.FieldTypeEnum;
import com.lingan.ucp.nocode.metadata.service.formula.FormulaEvaluator;

import java.util.*;

/** 发布检查和公共保存共用的动态表单规则，不替代对象完整性约束或字段授权。 */
public final class FormBehaviors {
    private FormBehaviors() {}

    public static void validate(ApplicationUi.Form form, DataCenter.Definition definition) {
        var presentations = SelectionFields.presentations(form.nodes());
        for (var entry : presentations.entrySet()) {
            var p = entry.getValue();
            if (p == null || p.behavior() == null) continue;
            var b = p.behavior();
            if (entry.getKey().startsWith("relation_")) throw invalid("多选关系请使用对象规则校验，暂不支持动态表单属性");
            var field =
                    definition.fields().stream()
                            .filter(f -> f.id().equals(entry.getKey()))
                            .findFirst()
                            .orElseThrow();
            if (b.clearWhenHidden()
                    && (b.showWhen() == null
                            || Boolean.TRUE.equals(field.required())
                            || Boolean.TRUE.equals(p.readOnly())))
                throw invalid("隐藏清空需要显示条件，且不能用于对象必填或只读字段：" + field.name());
            for (var condition : Arrays.asList(b.showWhen(), b.requiredWhen(), b.readOnlyWhen())) {
                DocumentPolicies.validateCondition(
                        condition, definition.fields(), definition.fieldOptions());
                if (!presentations.keySet().containsAll(references(condition)))
                    throw invalid("表单条件只能引用当前表单中的字段");
            }
        }
    }

    public static Set<String> references(DocumentPolicy.Expression expression) {
        Set<String> ids = new HashSet<>();
        if (expression == null) return ids;
        if (expression.fieldId() != null) ids.add(expression.fieldId());
        expression.args().forEach(a -> ids.addAll(references(a)));
        return ids;
    }

    /** 使用提交后的候选值判断；未提交字段取原值。无读取权限的条件不泄露隐藏字段值。 */
    public static Map<String, Object> apply(
            ApplicationUi.Form form,
            Map<String, Object> input,
            Map<String, Object> previous,
            Set<String> readable,
            List<FieldDefinition> fields) {
        if (form == null) return input;
        var result = new LinkedHashMap<>(input);
        var candidate = new HashMap<>(previous);
        candidate.putAll(input);
        // 浏览器数字以字符串传输；两个数值字段互比也必须使用十进制语义。
        for (var field : fields) {
            Object value = candidate.get(field.id());
            if (FieldTypeEnum.fromCode(field.type()).isNumeric() && !empty(value))
                candidate.put(field.id(), FormulaEvaluator.number(value));
        }
        var presentations = SelectionFields.presentations(form.nodes());
        // 清空只能把已提交的非空值变成空值；最多每字段一次，避免规则顺序影响结果。
        for (int pass = 0; pass < presentations.size(); pass++) {
            boolean changed = false;
            for (var entry : presentations.entrySet()) {
                var p = entry.getValue();
                if (p == null || p.behavior() == null) continue;
                var b = p.behavior();
                if (b.clearWhenHidden()
                        && b.showWhen() != null
                        && input.containsKey(entry.getKey())
                        && candidate.get(entry.getKey()) != null
                        && !DocumentPolicies.matches(b.showWhen(), candidate)) {
                    candidate.put(entry.getKey(), null);
                    result.put(entry.getKey(), null);
                    changed = true;
                }
            }
            if (!changed) break;
        }
        for (var entry : SelectionFields.presentations(form.nodes()).entrySet()) {
            var presentation = entry.getValue();
            if (presentation == null || presentation.behavior() == null) continue;
            var b = presentation.behavior();
            for (var condition : Arrays.asList(b.showWhen(), b.requiredWhen(), b.readOnlyWhen()))
                if (!readable.containsAll(references(condition)))
                    throw invalid("当前权限无法计算表单条件，请联系管理员调整表单");
            String id = entry.getKey();
            boolean visible =
                    b.showWhen() == null || DocumentPolicies.matches(b.showWhen(), candidate);
            boolean readOnly = DocumentPolicies.matches(b.readOnlyWhen(), candidate);
            if (readOnly && input.containsKey(id) && !same(input.get(id), previous.get(id)))
                throw invalid("当前条件下字段为只读：" + Objects.toString(presentation.label(), id));
            if (!visible && b.clearWhenHidden() && input.containsKey(id)) {
                if (readOnly && !empty(previous.get(id))) throw invalid("字段同时要求只读和清空，请检查表单规则");
                result.put(id, null);
            }
        }
        return result;
    }

    /** 默认值和编号准备完成后再校验必填，避免把服务端默认值误判为缺失。 */
    public static void require(
            ApplicationUi.Form form, Map<String, Object> candidate, List<FieldDefinition> fields) {
        if (form == null) return;
        var typed = new HashMap<>(candidate);
        for (var field : fields)
            if (FieldTypeEnum.fromCode(field.type()).isNumeric() && !empty(typed.get(field.id())))
                typed.put(field.id(), FormulaEvaluator.number(typed.get(field.id())));
        for (var entry : SelectionFields.presentations(form.nodes()).entrySet()) {
            var p = entry.getValue();
            if (p == null || p.behavior() == null) continue;
            var b = p.behavior();
            if ((b.showWhen() == null || DocumentPolicies.matches(b.showWhen(), typed))
                    && DocumentPolicies.matches(b.requiredWhen(), typed)
                    && empty(typed.get(entry.getKey()))) {
                String name = p.label();
                if (name == null || name.isBlank())
                    name =
                            fields.stream()
                                    .filter(f -> f.id().equals(entry.getKey()))
                                    .map(FieldDefinition::name)
                                    .findFirst()
                                    .orElse(entry.getKey());
                throw invalid("请填写条件必填字段：" + name);
            }
        }
    }

    private static boolean empty(Object value) {
        return value == null
                || value instanceof String s && s.isBlank()
                || value instanceof Collection<?> c && c.isEmpty();
    }

    private static boolean same(Object a, Object b) {
        if (a == null || b == null) return empty(a) && empty(b);
        if (a instanceof Number || b instanceof Number)
            return FormulaEvaluator.number(a).compareTo(FormulaEvaluator.number(b)) == 0;
        return Objects.equals(a, b);
    }
}
