package com.richuang.os.nocode.application.service.resource;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.nocode.api.ApplicationAutomations;
import com.richuang.os.nocode.api.CalculationOptions;
import com.richuang.os.nocode.api.DataCenter;
import com.richuang.os.nocode.api.DataScope;
import com.richuang.os.nocode.api.FieldDefinition;
import com.richuang.os.nocode.api.FieldRuleGraph;
import com.richuang.os.nocode.api.FieldRules;
import com.richuang.os.nocode.enums.AutomationModeEnum;
import com.richuang.os.nocode.enums.CalculationModeEnum;
import com.richuang.os.nocode.enums.MemberStateEnum;
import com.richuang.os.nocode.enums.RelationDirectionEnum;
import com.richuang.os.nocode.metadata.service.formula.Calculations;
import com.richuang.os.nocode.metadata.service.formula.FieldExpressions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据联动自动更新的字段级成环检查（应用发布时，范围 = 本应用固定的全部对象版本；纯函数，不连库）。
 *
 * <p>节点是「对象 · 字段」；边的含义是「左边变了，右边会被系统改写」：开启自动更新的联动（来源取值字段、条件字段、当前字段 →
 * 目标字段）、对象内规则依赖（只读联动与公式默认值）、落库计算（来源字段 → 计算字段）、本应用的自动更新规则 MAINTAIN（来源字段、条件字段、关系字段 →
 * 赋值目标字段）。没开自动更新的联动不连跨对象的边。
 *
 * <p>只报经过至少一条「自动更新联动」跨对象边的环：没有开启自动更新的应用一条边都不建，存量应用的发布不受影响。 对象双向、字段无环（例如凭证从流水取值、流水从凭证取状态）是合法配置。
 */
public final class LinkageCycles {
    private LinkageCycles() {}

    private record Node(String objectId, String fieldId) {}

    /**
     * @param pinned 本应用固定的对象定义，键为对象 ID
     * @param automations 本应用里的自动更新规则配置（停用的、非持续维护的会被忽略）
     */
    public static void check(
            Map<String, DataCenter.Definition> pinned,
            List<ApplicationAutomations.Config> automations) {
        Map<Node, Set<Node>> edges = new LinkedHashMap<>();
        List<Node[]> linkages = new ArrayList<>();
        for (var d : pinned.values()) linkageEdges(d, pinned, edges, linkages);
        if (linkages.isEmpty()) return;
        for (var d : pinned.values()) {
            ruleEdges(d, edges);
            calculationEdges(d, pinned, edges);
        }
        for (var config : automations) maintainEdges(config, pinned, edges);
        for (var edge : linkages) {
            var back = path(edge[1], edge[0], edges);
            if (back == null) continue;
            List<Node> cycle = new ArrayList<>();
            cycle.add(edge[0]);
            cycle.addAll(back);
            throw invalid(
                    "数据联动自动更新存在循环："
                            + String.join(" → ", cycle.stream().map(n -> label(n, pinned)).toList())
                            + "；请关闭其中一条联动的自动更新");
        }
    }

    /** 开启自动更新的联动 T.f ← S：S 的取值字段与条件字段、T 的当前字段 → T.f。 */
    private static void linkageEdges(
            DataCenter.Definition target,
            Map<String, DataCenter.Definition> pinned,
            Map<Node, Set<Node>> edges,
            List<Node[]> linkages) {
        for (var field : target.fields()) {
            var l = linkage(target.fieldOptions().get(field.id()));
            if (l == null || !l.autoUpdateOn() || !pinned.containsKey(l.sourceObjectId())) continue;
            var to = new Node(target.objectId(), field.id());
            Set<String> sources = new LinkedHashSet<>();
            if (l.valueFieldId() != null) sources.add(l.valueFieldId());
            for (var c :
                    l.conditions() == null ? List.<FieldRules.Condition>of() : l.conditions()) {
                if (c == null) continue;
                if (c.fieldId() != null && !FieldRules.RECORD_KEY.equals(c.fieldId()))
                    sources.add(c.fieldId());
                if (c.formFieldId() != null)
                    add(edges, new Node(target.objectId(), c.formFieldId()), to);
            }
            for (String source : sources) {
                var from = new Node(l.sourceObjectId(), source);
                add(edges, from, to);
                linkages.add(new Node[] {from, to});
            }
        }
    }

    /** 对象内规则依赖：只读联动与公式默认值的依赖字段 → 规则字段（保存时由服务端重算）。 */
    private static void ruleEdges(DataCenter.Definition d, Map<Node, Set<Node>> edges) {
        var graph = FieldRuleGraph.of(d);
        Map<String, DataCenter.FieldOptions> options = new HashMap<>(d.fieldOptions());
        for (var detail : d.details() == null ? List.<DataCenter.Detail>of() : d.details())
            if (!MemberStateEnum.INACTIVE.matches(detail.state()) && detail.fieldOptions() != null)
                options.putAll(detail.fieldOptions());
        options.forEach(
                (id, o) -> {
                    if (o == null
                            || o.rules() == null
                            || MemberStateEnum.INACTIVE.matches(o.state())
                            || !o.rules().effectiveReadOnly()) return;
                    for (String dependency : graph.direct(id))
                        add(edges, new Node(d.objectId(), dependency), new Node(d.objectId(), id));
                });
    }

    /** 落库计算：来源字段 → 计算字段。解析不了的计算配置由对象自身的校验报错，这里跳过，不因此挡住应用发布。 */
    private static void calculationEdges(
            DataCenter.Definition d,
            Map<String, DataCenter.Definition> pinned,
            Map<Node, Set<Node>> edges) {
        for (FieldDefinition field : d.fields()) {
            var o = d.fieldOptions().get(field.id());
            CalculationOptions c = o == null ? null : o.calculation();
            if (c == null || Calculations.live(o) || MemberStateEnum.INACTIVE.matches(o.state()))
                continue;
            var to = new Node(d.objectId(), field.id());
            try {
                if (CalculationModeEnum.LOCAL.matches(c.mode())) {
                    Map<String, String> names = new HashMap<>();
                    d.fields().forEach(f -> names.put(f.code(), f.id()));
                    for (String code : FieldExpressions.parse(o.expression(), names).references())
                        add(edges, new Node(d.objectId(), names.get(code)), to);
                } else if (Calculations.sequence(c)) {
                    for (String id : Calculations.sequenceSourceFields(d, field, c))
                        add(edges, new Node(d.objectId(), id), to);
                } else {
                    String sourceId = Calculations.target(d, c);
                    var source = sourceId.equals(d.objectId()) ? d : pinned.get(sourceId);
                    if (source == null) continue;
                    for (String id : Calculations.sourceFields(source, c))
                        add(edges, new Node(source.objectId(), id), to);
                }
            } catch (RuntimeException unparsable) {
                // 见方法说明。
            }
        }
    }

    /** 本应用的持续维护规则：来源字段、条件字段、关系字段 → 每个赋值目标字段。 */
    private static void maintainEdges(
            ApplicationAutomations.Config c,
            Map<String, DataCenter.Definition> pinned,
            Map<Node, Set<Node>> edges) {
        if (c == null
                || Boolean.FALSE.equals(c.enabled())
                || !AutomationModeEnum.MAINTAIN.matches(c.mode())
                || c.assignments() == null) return;
        var source = pinned.get(c.objectId());
        var target = pinned.get(c.targetObjectId());
        if (source == null || target == null) return;
        Set<Node> from = new LinkedHashSet<>();
        Set<String> conditionFields = new LinkedHashSet<>();
        conditionFields(c.conditions(), conditionFields);
        conditionFields.forEach(id -> from.add(new Node(source.objectId(), id)));
        try {
            var relation = ApplicationAutomationValidator.relation(c, source, target);
            boolean outgoing = RelationDirectionEnum.OUTGOING.matches(c.binding().direction());
            from.add(
                    new Node(outgoing ? source.objectId() : target.objectId(), relation.fieldId()));
        } catch (RuntimeException unresolved) {
            // 关系解析不了由自动更新自身的发布校验报错。
        }
        for (var assignment : c.assignments()) {
            var to = new Node(target.objectId(), assignment.fieldId());
            if (assignment.sourceFieldId() != null)
                add(edges, new Node(source.objectId(), assignment.sourceFieldId()), to);
            for (var node : from) add(edges, node, to);
        }
    }

    private static void conditionFields(DataScope scope, Set<String> fields) {
        if (scope == null) return;
        scope.conditions().forEach(c -> fields.add(c.fieldId()));
        scope.groups().forEach(group -> conditionFields(group, fields));
    }

    /** 从 start 到 goal 的一条路径（含两端）；到不了返回 null。按建边顺序广度优先，结果确定。 */
    private static List<Node> path(Node start, Node goal, Map<Node, Set<Node>> edges) {
        Map<Node, Node> previous = new HashMap<>();
        var queue = new ArrayDeque<Node>();
        queue.add(start);
        previous.put(start, start);
        while (!queue.isEmpty()) {
            var node = queue.poll();
            if (node.equals(goal)) {
                var path = new ArrayList<Node>();
                for (var at = goal; ; at = previous.get(at)) {
                    path.add(0, at);
                    if (at.equals(start)) return path;
                }
            }
            for (var next : edges.getOrDefault(node, Set.of()))
                if (previous.putIfAbsent(next, node) == null) queue.add(next);
        }
        return null;
    }

    private static void add(Map<Node, Set<Node>> edges, Node from, Node to) {
        if (from.fieldId() == null || from.equals(to)) return;
        edges.computeIfAbsent(from, key -> new LinkedHashSet<>()).add(to);
    }

    private static String label(Node node, Map<String, DataCenter.Definition> pinned) {
        var d = pinned.get(node.objectId());
        if (d == null) return node.objectId() + " · " + node.fieldId();
        for (var f : d.fields())
            if (f.id().equals(node.fieldId())) return d.objectName() + " · " + f.name();
        for (var detail : d.details() == null ? List.<DataCenter.Detail>of() : d.details())
            for (var f : detail.fields())
                if (f.id().equals(node.fieldId()))
                    return d.objectName() + " · " + detail.name() + " · " + f.name();
        return d.objectName() + " · " + node.fieldId();
    }

    private static FieldRules.Linkage linkage(DataCenter.FieldOptions options) {
        if (options == null
                || MemberStateEnum.INACTIVE.matches(options.state())
                || options.rules() == null) return null;
        return options.rules().linkage();
    }
}
