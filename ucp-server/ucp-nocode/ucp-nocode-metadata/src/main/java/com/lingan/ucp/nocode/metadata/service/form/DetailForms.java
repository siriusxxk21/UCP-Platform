package com.lingan.ucp.nocode.metadata.service.form;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.enums.ApplicationNodeKindEnum;
import com.lingan.ucp.nocode.enums.InternalDetailModeEnum;
import com.lingan.ucp.nocode.enums.MemberStateEnum;

import java.util.*;

/** 明细配置复用同一表单协议；范围仅为一行，仍随主记录整单保存。 */
public final class DetailForms {
    private DetailForms() {}

    /** 保存、发布与设计预览共用布局约束；完全没有明细节点的旧表单沿用底部明细呈现。 */
    public static void validateLayout(ApplicationUi.Form form, DataCenter.Definition definition) {
        var selected = form.detailIds() == null ? List.<String>of() : form.detailIds();
        var available = new HashSet<String>();
        definition.details().stream()
                .filter(detail -> MemberStateEnum.ACTIVE.matches(detail.state()))
                .forEach(detail -> available.add(detail.id()));
        if (new HashSet<>(selected).size() != selected.size() || !available.containsAll(selected))
            throw invalid("表单内部明细不存在、已停用或重复");
        var used = new HashSet<String>();
        validateLayoutNodes(form.nodes(), null, true, selected, used, 0, new int[] {0});
        if (!used.isEmpty() && !used.equals(new HashSet<>(selected)))
            throw invalid("内部明细节点与表单已选明细必须一致，请将全部明细放入画布");
        if (form.detailNodes() != null) {
            if (!selected.containsAll(form.detailNodes().keySet())) throw invalid("明细字段配置必须属于当前表单");
            for (var nodes : form.detailNodes().values())
                validateLayoutNodes(
                        nodes, null, false, selected, new HashSet<>(), 0, new int[] {0});
        }
    }

    private static void validateLayoutNodes(
            List<ApplicationUi.Node> nodes,
            ApplicationNodeKindEnum parent,
            boolean rootForm,
            List<String> selected,
            Set<String> used,
            int depth,
            int[] count) {
        if (nodes == null || depth > 8) throw invalid("页面节点为空或布局嵌套超过 8 层");
        for (var node : nodes) {
            if (node == null || ++count[0] > 200) throw invalid("页面节点为空或超过 200 个");
            var kind = ApplicationNodeKindEnum.fromCode(node.type());
            var children =
                    node.children() == null ? List.<ApplicationUi.Node>of() : node.children();
            if (kind == ApplicationNodeKindEnum.INTERNAL_DETAIL) {
                if (!rootForm) throw invalid("内部明细列配置不能再嵌套内部明细");
                if (parent != null
                        && !Set.of(
                                        ApplicationNodeKindEnum.CARD,
                                        ApplicationNodeKindEnum.TAB,
                                        ApplicationNodeKindEnum.COLUMN)
                                .contains(parent)) throw invalid("内部明细只能放在表单顶层、卡片、页签或列中");
                if (node.detail() == null
                        || node.detail().detailId() == null
                        || !selected.contains(node.detail().detailId()))
                    throw invalid("内部明细节点必须绑定当前对象已选的启用明细");
                InternalDetailModeEnum.fromCode(node.detail().mode());
                if (!used.add(node.detail().detailId())) throw invalid("同一内部明细在表单画布中只能放置一次");
                if (!children.isEmpty()) throw invalid("内部明细节点不能包含子节点，请使用明细列配置");
                if (node.fieldId() != null
                        || node.resourceId() != null
                        || node.binding() != null
                        || node.presentation() != null
                        || node.style() != null
                        || node.display() != null
                        || node.action() != null) throw invalid("内部明细节点不能绑定字段、业务资源或页面属性");
            } else if (node.detail() != null) throw invalid("仅内部明细节点允许配置明细绑定");
            if (!children.isEmpty())
                validateLayoutNodes(children, kind, rootForm, selected, used, depth + 1, count);
        }
    }

    public static ApplicationUi.Form form(ApplicationUi.Form root, String detailId) {
        if (root == null || root.detailNodes() == null || !root.detailNodes().containsKey(detailId))
            return null;
        if (root.detailIds() == null || !root.detailIds().contains(detailId))
            throw invalid("明细未加入当前表单");
        return new ApplicationUi.Form(
                root.objectId(), root.detailNodes().get(detailId), List.of(), root.options());
    }

    /** 投影用于校验字段配置，不发布为新对象，也不改变物理存储或权限。 */
    public static DataCenter.Definition definition(
            DataCenter.Definition root, DataCenter.Detail detail) {
        var relations =
                root.relations().stream()
                        .filter(r -> Objects.equals(r.sourceDetailId(), detail.id()))
                        .map(
                                r ->
                                        new DataCenter.Relation(
                                                r.id(),
                                                r.code(),
                                                r.name(),
                                                r.kind(),
                                                r.targetObjectId(),
                                                r.fieldId(),
                                                r.targetFieldId(),
                                                r.required(),
                                                r.onDelete()))
                        .toList();
        return new DataCenter.Definition(
                root.objectId(),
                root.objectCode(),
                detail.name(),
                root.description(),
                root.schemaName(),
                detail.tableName(),
                root.source(),
                root.readOnly(),
                null,
                root.settings(),
                detail.fields(),
                detail.fieldOptions(),
                relations,
                detail.indexes(),
                List.of(),
                detail.binding());
    }

    /** 候选范围可引用主表当前输入；全局字段 ID 区分主字段和当前行字段。 */
    public static DataCenter.Definition selectionDefinition(
            DataCenter.Definition root, DataCenter.Detail detail) {
        var row = definition(root, detail);
        var fields = new ArrayList<>(root.fields());
        fields.addAll(row.fields());
        var options = new LinkedHashMap<>(root.fieldOptions());
        options.putAll(row.fieldOptions());
        // 联动上游可来自主表引用；只投影字段而丢失关系会把不同对象的 ID 误认为兼容。
        var relations = new ArrayList<DataCenter.Relation>();
        root.relations().stream().filter(r -> r.sourceDetailId() == null).forEach(relations::add);
        relations.addAll(row.relations());
        return new DataCenter.Definition(
                row.objectId(),
                row.objectCode(),
                row.objectName(),
                row.description(),
                row.schemaName(),
                row.tableName(),
                row.source(),
                row.readOnly(),
                null,
                row.settings(),
                fields,
                options,
                relations,
                row.indexes(),
                List.of(),
                row.mainBinding());
    }

    public static ApplicationUi.Form selectionForm(ApplicationUi.Form root, String detailId) {
        var row = form(root, detailId);
        if (row == null) return null;
        var nodes = new ArrayList<ApplicationUi.Node>();
        SelectionFields.presentations(root.nodes())
                .keySet()
                .forEach(
                        id ->
                                nodes.add(
                                        new ApplicationUi.Node(
                                                "main_" + id,
                                                "FIELD",
                                                id,
                                                null,
                                                null,
                                                null,
                                                List.of())));
        nodes.addAll(row.nodes());
        return new ApplicationUi.Form(root.objectId(), nodes, List.of());
    }

    public static Map<String, List<ApplicationUi.Node>> visible(
            ApplicationUi.Form form, Set<String> readableDetails) {
        var result = new LinkedHashMap<String, List<ApplicationUi.Node>>();
        if (form.detailNodes() != null)
            form.detailNodes()
                    .forEach(
                            (id, nodes) -> {
                                if (readableDetails.contains(id)) result.put(id, nodes);
                            });
        return result;
    }
}
