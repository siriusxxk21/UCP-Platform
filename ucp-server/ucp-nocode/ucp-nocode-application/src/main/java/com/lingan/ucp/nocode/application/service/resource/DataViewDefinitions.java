package com.lingan.ucp.nocode.application.service.resource;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.service.form.DetailForms;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;

/** 多对象列表引用现有关系；字段类型和查询能力由服务端根据发布对象推导。 */
@Component
public class DataViewDefinitions {
    @Resource private ApplicationPageBindings bindings;
    @Resource private ApplicationReportValidator conditions;

    public DataViews.Composition validate(
            ApplicationUi.View view,
            Map<String, DataCenter.Definition> definitions,
            List<ApplicationCenter.Resource> resources) {
        var c = view.composition();
        if (c == null) return null;
        var root = definitions.get(view.objectId());
        var grain = ViewGrainEnum.fromCode(c.grain());
        var sections = c.sections() == null ? List.<DataViews.Section>of() : c.sections();
        var columns = c.columns() == null ? List.<DataViews.Column>of() : c.columns();
        if (sections.size() > 12 || columns.size() > 40) throw invalid("最多配置 12 组子表、40 个关联列");
        if (grain == ViewGrainEnum.DETAIL) detail(root, c.detailId());
        else if (c.detailId() != null) throw invalid("主记录视图不能指定明细粒度");
        var ids = new HashSet<String>();
        for (var section : sections) {
            if (section == null
                    || section.id() == null
                    || !section.id().matches("[A-Za-z][A-Za-z0-9_]{0,63}")
                    || !ids.add(section.id())) throw invalid("子表标识无效或重复");
            if (section.name() == null || section.name().isBlank() || section.name().length() > 80)
                throw invalid("请填写子表名称（最多 80 字）");
            if (section.pageSize() < 1 || section.pageSize() > 100)
                throw invalid("子表每页条数应为 1 至 100");
            var target = owner(root, section, definitions);
            if (section.detailId() != null) {
                if (section.objectId() != null
                        || section.binding() != null
                        || section.viewId() != null) throw invalid("内部明细和独立关联对象不能同时配置");
            } else {
                if (Objects.equals(section.objectId(), root.objectId()))
                    throw invalid("关联区块需要选择其他数据对象");
                var resolved =
                        bindings.resolve(
                                root.objectId(),
                                section.objectId(),
                                section.binding(),
                                definitions::get);
                if (resolved.relation().sourceDetailId() != null) throw invalid("独立关联区块需要主表上的关系");
                if (section.viewId() != null) {
                    var resource =
                            resources.stream()
                                    .filter(
                                            r ->
                                                    r.id().equals(section.viewId())
                                                            && ApplicationResourceKindEnum.VIEW
                                                                    .matches(r.kind()))
                                    .findFirst()
                                    .orElseThrow(() -> invalid("关联子表视图不存在"));
                    Object objectId = resource.config().get("objectId");
                    if (!Objects.equals(objectId, section.objectId())) throw invalid("关联子表视图对象不匹配");
                    if (resource.config().get("composition") != null)
                        throw invalid("关联区块请选择目标对象的普通视图，避免循环组合");
                }
            }
            var fieldIds =
                    target.fields().stream()
                            .map(FieldDefinition::id)
                            .collect(java.util.stream.Collectors.toSet());
            if (section.fieldIds() == null
                    || section.fieldIds().isEmpty()
                    || section.fieldIds().size() > 80
                    || new HashSet<>(section.fieldIds()).size() != section.fieldIds().size()
                    || !fieldIds.containsAll(section.fieldIds())) throw invalid("子表展示字段无效");
            conditions.validateConditions(
                    section.conditions(), target, Map.of(target.objectId(), target), fieldIds);
        }
        if (grain == ViewGrainEnum.DETAIL
                && sections.stream().noneMatch(s -> Objects.equals(s.detailId(), c.detailId())))
            throw invalid("明细粒度需要添加对应内部明细子表");
        var normalized = new ArrayList<DataViews.Column>();
        ids.clear();
        for (var col : columns) {
            if (col == null
                    || col.id() == null
                    || !col.id().matches("view_[A-Za-z][A-Za-z0-9_]{0,55}")
                    || !ids.add(col.id())) throw invalid("关联列标识无效或重复");
            if (col.name() == null || col.name().isBlank() || col.name().length() > 80)
                throw invalid("请填写关联列名称");
            var kind = ViewColumnKindEnum.fromCode(col.kind());
            var section =
                    sections.stream()
                            .filter(s -> s.id().equals(col.sectionId()))
                            .findFirst()
                            .orElseThrow(() -> invalid("关联列需要指定子表来源"));
            var target = owner(root, section, definitions);
            String type;
            if (kind == ViewColumnKindEnum.COUNT) type = FieldTypeEnum.INTEGER.getCode();
            else {
                FieldDefinition field = conditions.field(target, col.fieldId());
                if (MemberStateEnum.INACTIVE.matches(
                                target.fieldOptions()
                                        .getOrDefault(
                                                field.id(), DataCenter.FieldOptions.defaults())
                                        .state())
                        || !RecordQueryOperatorEnum.supports(FieldTypeEnum.fromCode(field.type()))
                        || com.lingan.ucp.nocode.metadata.service.formula.Calculations.live(
                                target.fieldOptions().get(field.id())))
                    throw invalid("关联列需要可查询的有效标量字段");
                type = field.type();
                if (kind == ViewColumnKindEnum.SUM && !numeric(type)) throw invalid("求和只能选择数值字段");
                if (kind == ViewColumnKindEnum.SUM && FieldTypeEnum.INTEGER.matches(type))
                    type = FieldTypeEnum.DECIMAL.getCode();
            }
            if (kind == ViewColumnKindEnum.DETAIL
                    && (grain != ViewGrainEnum.DETAIL
                            || !Objects.equals(section.detailId(), c.detailId())))
                throw invalid("明细列必须来自当前明细粒度");
            if (kind == ViewColumnKindEnum.LOOKUP) {
                if (section.detailId() != null
                        || !RelationDirectionEnum.OUTGOING.matches(section.binding().direction()))
                    throw invalid("直接引用需要当前主表指向目标的单值关系");
                var r =
                        bindings.resolve(
                                        root.objectId(),
                                        section.objectId(),
                                        section.binding(),
                                        definitions::get)
                                .relation();
                if (BusinessFields.multiple(r)) throw invalid("多值关系请使用子表或汇总列");
            }
            normalized.add(
                    new DataViews.Column(
                            col.id(),
                            col.name(),
                            col.sectionId(),
                            col.fieldId(),
                            col.kind(),
                            type));
        }
        return new DataViews.Composition(
                grain.getCode(), c.detailId(), List.copyOf(sections), normalized);
    }

    public static DataCenter.Detail detail(DataCenter.Definition root, String id) {
        return root.details().stream()
                .filter(d -> Objects.equals(d.id(), id))
                .findFirst()
                .orElseThrow(() -> invalid("内部明细表不存在"));
    }

    public static DataCenter.Definition owner(
            DataCenter.Definition root,
            DataViews.Section section,
            Map<String, DataCenter.Definition> definitions) {
        if (section.detailId() != null)
            return DetailForms.definition(root, detail(root, section.detailId()));
        var target = definitions.get(section.objectId());
        if (target == null) throw invalid("关联对象不属于应用");
        return target;
    }

    public static boolean numeric(String type) {
        return Set.of(
                        FieldTypeEnum.INTEGER,
                        FieldTypeEnum.DECIMAL,
                        FieldTypeEnum.MONEY,
                        FieldTypeEnum.PERCENT)
                .contains(FieldTypeEnum.fromCode(type));
    }

    public static FieldDefinition field(DataViews.Column col) {
        return new FieldDefinition(
                null, col.id(), col.id(), col.name(), col.type(), 2000, 38, 8, false, false, 0);
    }
}
