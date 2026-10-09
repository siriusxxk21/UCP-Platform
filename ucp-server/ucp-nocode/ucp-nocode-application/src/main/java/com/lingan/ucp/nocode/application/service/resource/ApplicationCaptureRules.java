package com.lingan.ucp.nocode.application.service.resource;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.service.form.DocumentPolicies;

import java.util.*;

/** 留存动作只把同对象公式的结果写到普通空字段；规则随应用版本发布。 */
public final class ApplicationCaptureRules {
    private ApplicationCaptureRules() {}

    public static ApplicationBusiness.Action validate(
            ApplicationBusiness.Action action, DataCenter.Definition definition) {
        if (action.captures().isEmpty() || action.captures().size() > 20)
            throw invalid("留存动作需要 1 到 20 组来源公式与目标字段");
        if (action.values() != null && !action.values().isEmpty()
                || action.processDefinitionId() != null
                || action.variables() != null && !action.variables().isEmpty())
            throw invalid("留存动作不能同时配置固定赋值或流程");
        for (Map.Entry<String, String> capture : action.captures().entrySet()) {
            FieldDefinition target = DataScope.field(definition, capture.getKey());
            FieldDefinition source = DataScope.field(definition, capture.getValue());
            DataCenter.FieldOptions targetOptions =
                    definition
                            .fieldOptions()
                            .getOrDefault(target.id(), DataCenter.FieldOptions.defaults());
            DataCenter.FieldOptions sourceOptions =
                    definition
                            .fieldOptions()
                            .getOrDefault(source.id(), DataCenter.FieldOptions.defaults());
            FieldTypeEnum targetType = FieldTypeEnum.fromCode(target.type());
            if (!FieldTypeEnum.FORMULA.matches(source.type())
                    || MemberStateEnum.INACTIVE.matches(sourceOptions.state()))
                throw invalid("留存来源必须是启用的公式字段：" + source.name());
            if (!(targetType.isNumeric()
                            || targetType == FieldTypeEnum.TEXT
                            || targetType == FieldTypeEnum.TEXTAREA)
                    || Boolean.TRUE.equals(targetOptions.generated())
                    || Boolean.TRUE.equals(targetOptions.primaryKey())
                    || Boolean.TRUE.equals(target.required())
                    || targetOptions.defaultValue() != null
                    || MemberStateEnum.INACTIVE.matches(targetOptions.state())
                    || definition.relations().stream()
                            .anyMatch(r -> target.id().equals(r.fieldId()))
                    || Objects.equals(
                            ObjectTables.main(definition).keyColumn(),
                            Objects.toString(targetOptions.columnName(), target.code())))
                throw invalid("留存目标须为可空、无默认值的普通数值或文本字段：" + target.name());
            DocumentPolicy document = DocumentPolicies.policy(definition);
            if (document != null
                    && document.lifecycle() != null
                    && target.id().equals(document.lifecycle().fieldId()))
                throw invalid("留存动作不能写入单据生命周期状态字段");
            FieldTypeEnum sourceType = FieldTypeEnum.fromCode(sourceOptions.resultType());
            boolean compatible =
                    sourceType.isNumeric()
                                    && targetType.isNumeric()
                                    && !(sourceType.isDecimal()
                                            && targetType == FieldTypeEnum.INTEGER)
                            || sourceType == FieldTypeEnum.TEXT
                                    && (targetType == FieldTypeEnum.TEXT
                                            || targetType == FieldTypeEnum.TEXTAREA);
            if (!compatible) throw invalid("留存来源与目标结果类型不兼容：" + target.name());
            List<DataClassificationEnum> levels =
                    List.of(
                            DataClassificationEnum.NORMAL,
                            DataClassificationEnum.INTERNAL,
                            DataClassificationEnum.SENSITIVE,
                            DataClassificationEnum.SECRET);
            if (levels.indexOf(
                            DataClassificationEnum.fromCode(
                                    Objects.toString(
                                            targetOptions.classification(),
                                            DataClassificationEnum.NORMAL.getCode())))
                    < levels.indexOf(
                            DataClassificationEnum.fromCode(
                                    Objects.toString(
                                            sourceOptions.classification(),
                                            DataClassificationEnum.NORMAL.getCode()))))
                throw invalid("留存目标的数据分类不能低于来源公式：" + target.name());
        }
        return new ApplicationBusiness.Action(
                action.objectId(), action.kind(), Map.of(), null, Map.of(), action.captures());
    }
}
