package com.lingan.ucp.nocode.metadata.service.form;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.service.formula.FormulaEvaluator;

import java.util.*;

/** 对象级审批策略使用既有受控条件，不能通过切换表单、任务或应用绕过。 */
public final class BusinessHandlingPolicies {
    private BusinessHandlingPolicies() {}

    public static BusinessHandling.Policy remap(
            BusinessHandling.Policy p, Map<String, String> ids) {
        return p == null
                ? null
                : new BusinessHandling.Policy(remap(p.create(), ids), remap(p.update(), ids));
    }

    private static BusinessHandling.Rule remap(
            BusinessHandling.Rule rule, Map<String, String> ids) {
        if (rule == null) return null;
        var variables = new LinkedHashMap<String, String>();
        rule.variables()
                .forEach((name, field) -> variables.put(name, ids.getOrDefault(field, field)));
        return new BusinessHandling.Rule(
                rule.mode(),
                rule.processDefinitionId(),
                DocumentPolicies.remap(rule.condition(), ids),
                variables);
    }

    public static BusinessHandling.Rule rule(DataCenter.Definition d, boolean create) {
        var document = DocumentPolicies.policy(d);
        var policy = document == null ? null : document.handling();
        return policy == null ? null : create ? policy.create() : policy.update();
    }

    public static void validate(DataCenter.Definition d) {
        validate(rule(d, true), d);
        validate(rule(d, false), d);
    }

    private static void validate(BusinessHandling.Rule rule, DataCenter.Definition d) {
        if (rule == null) return;
        var mode = HandlingModeEnum.fromCode(rule.mode());
        if (mode == HandlingModeEnum.DIRECT) {
            if (rule.processDefinitionId() != null
                    || rule.condition() != null
                    || !rule.variables().isEmpty()) throw invalid("直接办理不配置审批流程或条件");
            return;
        }
        if (rule.processDefinitionId() == null
                || rule.processDefinitionId().isBlank()
                || rule.processDefinitionId().length() > 200) throw invalid("请选择审批流程的已发布版本");
        if (mode == HandlingModeEnum.CONDITIONAL) {
            if (rule.condition() == null) throw invalid("条件审批需要配置触发条件");
            DocumentPolicies.validateCondition(rule.condition(), d.fields(), d.fieldOptions());
        } else if (rule.condition() != null) throw invalid("始终审批不配置触发条件");
        if (rule.variables().size() > 30) throw invalid("审批变量最多 30 项");
        for (var variable : rule.variables().entrySet()) {
            if (!variable.getKey().matches("[A-Za-z][A-Za-z0-9_]{0,63}")) throw invalid("审批变量名称无效");
            if (d.fields().stream()
                    .noneMatch(
                            f ->
                                    f.id().equals(variable.getValue())
                                            && !MemberStateEnum.INACTIVE.matches(
                                                    d.fieldOptions()
                                                            .getOrDefault(
                                                                    f.id(),
                                                                    DataCenter.FieldOptions
                                                                            .defaults())
                                                            .state())))
                throw invalid("审批变量引用的字段不存在或已停用");
        }
    }

    public static boolean required(
            DataCenter.Definition d, boolean create, Map<String, Object> candidate) {
        var rule = rule(d, create);
        if (rule == null || HandlingModeEnum.DIRECT.matches(rule.mode())) return false;
        if (HandlingModeEnum.APPROVAL.matches(rule.mode())) return true;
        var typed = new HashMap<>(candidate);
        for (var field : d.fields()) {
            var type = FieldTypeEnum.fromCode(field.type());
            var options =
                    d.fieldOptions().getOrDefault(field.id(), DataCenter.FieldOptions.defaults());
            if ((type == FieldTypeEnum.SUMMARY || type == FieldTypeEnum.FORMULA)
                    && options.resultType() != null)
                type = FieldTypeEnum.fromCode(options.resultType());
            Object value = typed.get(field.id());
            if (type.isNumeric() && value != null && !value.toString().isBlank())
                typed.put(field.id(), FormulaEvaluator.number(value));
        }
        return DocumentPolicies.matches(rule.condition(), typed);
    }
}
