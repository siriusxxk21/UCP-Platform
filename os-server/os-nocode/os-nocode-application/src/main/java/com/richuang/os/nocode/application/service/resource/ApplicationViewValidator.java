package com.richuang.os.nocode.application.service.resource;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.enums.*;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;

/** 列表交互、可用查询字段与排序布局规则；不改变已发布配置的默认值。 */
@Component
public class ApplicationViewValidator {
    @Resource private ApplicationResourceContext resourceContext;

    ApplicationUi.ViewInteraction viewInteraction(
            ApplicationUi.View view, Map<String, ApplicationCenter.Resource> resources) {
        var config = view.interaction();
        if (config == null) return null; // 历史视图保持原有默认按钮与打开方式。
        if (config.buttons() == null
                || config.buttons().size() > 6
                || new HashSet<>(config.buttons()).size() != config.buttons().size())
            throw invalid("视图操作按钮无效或重复");
        config.buttons().forEach(com.richuang.os.nocode.enums.ViewButtonEnum::fromCode);
        com.richuang.os.nocode.enums.RecordOpenModeEnum.fromCode(config.editMode());
        com.richuang.os.nocode.enums.RecordOpenModeEnum.fromCode(config.detailMode());
        if (config.actionIds() == null
                || config.actionIds().size() > 30
                || new HashSet<>(config.actionIds()).size() != config.actionIds().size())
            throw invalid("视图业务动作无效或重复");
        for (String id : config.actionIds()) {
            var action =
                    resourceContext.decode(
                            resourceContext
                                    .resource(resources, id, ApplicationResourceKindEnum.ACTION)
                                    .config(),
                            ApplicationBusiness.Action.class);
            if (!Objects.equals(view.objectId(), action.objectId()))
                throw invalid("视图业务动作必须属于同一对象");
        }
        return new ApplicationUi.ViewInteraction(
                List.copyOf(config.buttons()),
                List.copyOf(config.actionIds()),
                config.editMode(),
                config.detailMode());
    }

    ApplicationUi.ViewList viewList(ApplicationUi.View view, Map<String, FieldDefinition> fields) {
        var list = view.list();
        if (list == null) return null;
        var basic = queryIds(list.queryFieldIds(), fields, 6);
        var advanced =
                list.advancedFieldIds() == null
                        ? null
                        : queryIds(list.advancedFieldIds(), fields, 100);
        if (advanced != null
                && advanced.stream()
                        .anyMatch(
                                id ->
                                        !com.richuang.os.nocode.enums.RecordQueryOperatorEnum
                                                .supports(
                                                        FieldTypeEnum.fromCode(
                                                                fields.get(id).type()))))
            throw invalid("高级检索暂不支持汇总及多值字段");
        var widths = list.columnWidths() == null ? Map.<String, Integer>of() : list.columnWidths();
        if (!view.fieldIds().containsAll(widths.keySet())
                || widths.values().stream().anyMatch(w -> w == null || w < 80 || w > 800))
            throw invalid("默认列宽须为 80 到 800，且属于显示字段");
        return new ApplicationUi.ViewList(
                basic,
                advanced,
                Map.copyOf(widths),
                list.batchDelete(),
                ListOverflowEnum.optional(list.overflow()));
    }

    List<String> queryIds(List<String> ids, Map<String, FieldDefinition> fields, int limit) {
        if (ids == null
                || ids.size() > limit
                || new HashSet<>(ids).size() != ids.size()
                || !fields.keySet().containsAll(ids)
                || ids.stream()
                        .anyMatch(id -> FieldTypeEnum.SUMMARY.matches(fields.get(id).type())))
            throw invalid("列表查询字段无效、重复或超过数量限制");
        return List.copyOf(ids);
    }
}
