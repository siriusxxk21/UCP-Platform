package com.lingan.ucp.nocode.api;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import java.util.*;

/** 联动清空与关联带入共用有向依赖；明细字段 ID 唯一，主表只能向各行传播。 */
public final class FormDependencies {
    private FormDependencies() {}

    public static void validate(ApplicationUi.Form form, DataCenter.Definition definition) {
        var adjacency = new LinkedHashMap<String, List<String>>();
        Map<String, DataCenter.FieldOptions> options =
                new LinkedHashMap<>(definition.fieldOptions());
        definition.details().forEach(detail -> options.putAll(detail.fieldOptions()));
        add(form.nodes(), adjacency, options);
        if (form.detailNodes() != null)
            form.detailNodes().values().forEach(nodes -> add(nodes, adjacency, options));
        // 旧选择联动、旧带入与对象规则共用一个环检查，避免两套各自无环却相互触发。
        FieldRuleGraph graph = FieldRuleGraph.of(definition);
        for (String target : graph.topo())
            for (String source : graph.dependsOn(target)) edge(source, target, adjacency);
        var names = new HashMap<String, String>();
        definition.fields().forEach(field -> names.put(field.id(), field.name()));
        definition
                .details()
                .forEach(
                        detail ->
                                detail.fields()
                                        .forEach(
                                                field ->
                                                        names.put(
                                                                field.id(),
                                                                detail.name()
                                                                        + " → "
                                                                        + field.name())));
        var state = new HashMap<String, Integer>();
        var path = new ArrayList<String>();
        for (var id : adjacency.keySet()) {
            if (state.getOrDefault(id, 0) == 0) visit(id, adjacency, state, path, names);
        }
    }

    private static void add(
            List<ApplicationUi.Node> nodes,
            Map<String, List<String>> adjacency,
            Map<String, DataCenter.FieldOptions> options) {
        SelectionFields.presentations(nodes)
                .forEach(
                        (target, p) -> {
                            if (p == null) return;
                            if (p.selection() != null)
                                edge(p.selection().linkFieldId(), target, adjacency);
                            if (p.fill() != null && !FieldRules.hasValueRule(options.get(target)))
                                edge(p.fill().sourceFieldId(), target, adjacency);
                        });
    }

    private static void edge(String source, String target, Map<String, List<String>> adjacency) {
        if (source != null && !source.isBlank())
            adjacency.computeIfAbsent(source, key -> new ArrayList<>()).add(target);
    }

    private static void visit(
            String id,
            Map<String, List<String>> adjacency,
            Map<String, Integer> state,
            List<String> path,
            Map<String, String> names) {
        state.put(id, 1);
        path.add(id);
        for (var target : adjacency.getOrDefault(id, List.of())) {
            if (state.getOrDefault(target, 0) == 1) {
                var cycle = new ArrayList<>(path.subList(path.indexOf(target), path.size()));
                cycle.add(target);
                throw invalid(
                        "关联带入存在循环联动（选择器与带入联合依赖）："
                                + String.join(
                                        " → ",
                                        cycle.stream()
                                                .map(field -> names.getOrDefault(field, field))
                                                .toList()));
            }
            if (state.getOrDefault(target, 0) == 0) visit(target, adjacency, state, path, names);
        }
        path.removeLast();
        state.put(id, 2);
    }
}
