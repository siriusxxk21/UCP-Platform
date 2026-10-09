package com.richuang.os.nocode.runtime.service.rules;

import com.richuang.os.nocode.api.DataCenter;
import com.richuang.os.nocode.api.FieldRules;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * 路 C 开发期的求值器替身：按字段登记脚本，结果只由调用方传入的值决定。
 *
 * <p>用于钉住保存时强制逻辑本身（取服务端合并值、按状态处理、触发条件）；真实求值由路 B 的实现负责，合并后另行端到端回归。 引用筛选以 recordKeys 表示范围内的记录
 * ID，hasRecordKey=false 表示不限。
 */
public class ScriptedFieldRuleEvaluator implements FieldRuleEvaluator {
    /** 一次求值调用；values 为求值器实际收到的值（明细为主表值与本行值合并）。 */
    public record Call(
            String kind,
            String detailId,
            String fieldId,
            String rowKey,
            Boolean creating,
            Map<String, Object> values) {}

    /** 脚本结果。 */
    public record Outcome(String state, Object value, String message, List<String> pending) {}

    public final List<Call> calls = new CopyOnWriteArrayList<>();

    /** evaluateRows 的调用次数：同一明细组应整组一次求值。 */
    public final java.util.concurrent.atomic.AtomicInteger rowBatches =
            new java.util.concurrent.atomic.AtomicInteger();

    private final Map<String, Function<Map<String, Object>, Outcome>> linkages =
            new ConcurrentHashMap<>();
    private final Map<String, Function<Map<String, Object>, ReferenceScope>> scopes =
            new ConcurrentHashMap<>();

    public static Outcome applied(Object value) {
        return new Outcome("APPLIED", value, null, List.of());
    }

    public static Outcome pending(String... fields) {
        return new Outcome("PENDING_ROW_VALUE", null, "请先填写依赖字段", List.of(fields));
    }

    public static Outcome noMatch() {
        return new Outcome("NO_MATCH", null, "没有匹配的来源记录", List.of());
    }

    public static Outcome failed(String state, String message) {
        return new Outcome(state, null, message, List.of());
    }

    public static ReferenceScope within(String... ids) {
        return new ReferenceScope("APPLIED", null, List.of(), null, true, List.of(ids));
    }

    public static ReferenceScope scopePending(String... fields) {
        return new ReferenceScope(
                "PENDING_ROW_VALUE", "请先填写依赖字段", List.of(fields), null, false, List.of());
    }

    public static ReferenceScope scopeFailed(String state, String message) {
        return new ReferenceScope(state, message, List.of(), null, false, List.of());
    }

    public ScriptedFieldRuleEvaluator linkage(
            String fieldId, Function<Map<String, Object>, Outcome> script) {
        linkages.put(fieldId, script);
        return this;
    }

    public ScriptedFieldRuleEvaluator reference(
            String fieldId, Function<Map<String, Object>, ReferenceScope> script) {
        scopes.put(fieldId, script);
        return this;
    }

    public List<Call> calls(String kind, String fieldId) {
        return calls.stream()
                .filter(c -> c.kind().equals(kind) && c.fieldId().equals(fieldId))
                .toList();
    }

    @Override
    public List<FieldRules.Result> evaluate(
            RuleContext ctx,
            Map<String, Object> values,
            Set<String> targets,
            Set<String> overridable,
            boolean creating) {
        List<FieldRules.Result> results = new ArrayList<>();
        for (String id : targets.isEmpty() ? linkages.keySet() : targets) {
            var script = linkages.get(id);
            if (script == null) continue;
            var snapshot = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            calls.add(new Call("LINKAGE", null, id, null, creating, snapshot));
            results.add(result(id, script.apply(snapshot), null, null));
        }
        return results;
    }

    @Override
    public List<FieldRules.Result> evaluateRows(
            RuleContext ctx,
            String detailId,
            Map<String, Object> masterValues,
            Set<String> masterChanged,
            List<FieldRules.RowInput> rows) {
        rowBatches.incrementAndGet();
        Set<String> detailFields = new HashSet<>();
        ctx.definition().details().stream()
                .filter(d -> d.id().equals(detailId))
                .forEach(d -> d.fields().forEach(f -> detailFields.add(f.id())));
        List<FieldRules.Result> results = new ArrayList<>();
        for (var row : rows) {
            Map<String, Object> merged = new LinkedHashMap<>(masterValues);
            merged.putAll(row.values());
            var snapshot = Collections.unmodifiableMap(merged);
            var targets =
                    row.changed() == null || row.changed().isEmpty()
                            ? linkages.keySet()
                            : new LinkedHashSet<>(row.changed());
            for (String id : targets) {
                var script = linkages.get(id);
                if (script == null || !detailFields.contains(id)) continue;
                calls.add(
                        new Call("LINKAGE", detailId, id, row.rowKey(), row.creating(), snapshot));
                results.add(result(id, script.apply(snapshot), detailId, row.rowKey()));
            }
        }
        return results;
    }

    @Override
    public ReferenceScope referenceScope(
            RuleContext ctx,
            String detailId,
            DataCenter.Relation relation,
            Map<String, Object> values) {
        var snapshot = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        calls.add(new Call("REFERENCE", detailId, relation.fieldId(), null, null, snapshot));
        var script = scopes.get(relation.fieldId());
        return script == null
                ? new ReferenceScope("APPLIED", null, List.of(), null, false, List.of())
                : script.apply(snapshot);
    }

    @Override
    public Set<String> idsWithinScope(
            RuleContext ctx,
            DataCenter.Relation relation,
            ReferenceScope scope,
            Collection<String> ids) {
        Set<String> result = new LinkedHashSet<>(ids);
        if (scope.hasRecordKey()) result.retainAll(scope.recordKeys());
        return result;
    }

    private static FieldRules.Result result(
            String id, Outcome outcome, String detailId, String rowKey) {
        boolean applied = "APPLIED".equals(outcome.state());
        return new FieldRules.Result(
                id,
                "LINKAGE",
                outcome.state(),
                applied ? outcome.value() : null,
                applied ? 1 : 0,
                applied,
                outcome.message(),
                outcome.pending(),
                detailId,
                rowKey,
                null);
    }
}
