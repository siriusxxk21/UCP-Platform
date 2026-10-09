package com.lingan.ucp.nocode.runtime.service.record;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.DocumentPolicy.*;
import com.lingan.ucp.nocode.enums.DocumentRuleScopeEnum;
import com.lingan.ucp.nocode.metadata.service.form.DocumentPolicies;

import java.math.BigDecimal;
import java.util.*;

/** 受控状态按修改前状态保护整张单据；客户端不能先改状态再绕过锁定。 */
public final class DocumentStates {
    private DocumentStates() {}

    public static Map<String, Object> prepare(
            DataCenter.Definition d,
            Map<String, Object> before,
            Map<String, Object> input,
            String actionCode,
            Set<String> permissions,
            boolean insert) {
        var policy = DocumentPolicies.policy(d);
        var lifecycle = policy == null ? null : policy.lifecycle();
        if (lifecycle == null) {
            if (actionCode != null) throw invalid("当前对象未配置状态动作");
            return input;
        }
        String current =
                insert
                        ? lifecycle.initialState()
                        : Objects.toString(before.get(lifecycle.fieldId()), null);
        if (lifecycle.states().stream().noneMatch(s -> s.code().equals(current)))
            throw invalid("单据当前状态无效，请先处理存量兼容问题");
        if (input.containsKey(lifecycle.fieldId())
                && !Objects.equals(current, Objects.toString(input.get(lifecycle.fieldId()), null)))
            throw invalid("状态由平台动作维护，请使用已配置的状态操作");
        var result = new LinkedHashMap<>(input);
        if (insert) result.put(lifecycle.fieldId(), current);
        if (actionCode != null) {
            var action =
                    lifecycle.actions().stream()
                            .filter(a -> a.code().equals(actionCode))
                            .findFirst()
                            .orElseThrow(() -> invalid("状态动作不存在"));
            if (!permissions.contains(action.permission())
                    || !action.fromStates().contains(current)) throw invalid("无权执行此状态动作或当前状态不允许");
            result.put(lifecycle.fieldId(), action.toState());
        }
        return result;
    }

    public static void requireWrite(
            DataCenter.Definition d, DocumentPolicies.Input before, DocumentPolicies.Input after) {
        var policy = DocumentPolicies.policy(d);
        if (policy == null || policy.lifecycle() == null || before.values().isEmpty()) return;
        var lifecycle = policy.lifecycle();
        String current = Objects.toString(before.values().get(lifecycle.fieldId()), null);
        var state =
                lifecycle.states().stream()
                        .filter(s -> s.code().equals(current))
                        .findFirst()
                        .orElseThrow(() -> invalid("单据原状态无效"));
        for (String field : state.lockedFields())
            if (!field.equals(lifecycle.fieldId())
                    && !same(before.values().get(field), after.values().get(field)))
                throw DocumentValidation.error(
                        List.of(
                                new Problem(
                                        null,
                                        DocumentRuleScopeEnum.FIELD.getCode(),
                                        null,
                                        null,
                                        null,
                                        field,
                                        "当前状态不允许修改该字段")));
        for (String detail : state.lockedDetails())
            if (!sameRows(
                    before.details().getOrDefault(detail, List.of()),
                    after.details().getOrDefault(detail, List.of())))
                throw DocumentValidation.error(
                        List.of(
                                new Problem(
                                        null,
                                        DocumentRuleScopeEnum.DETAIL.getCode(),
                                        detail,
                                        null,
                                        null,
                                        null,
                                        "当前状态不允许修改内部明细")));
    }

    public static void requireDelete(DataCenter.Definition d, Map<String, Object> values) {
        var policy = DocumentPolicies.policy(d);
        if (policy == null || policy.lifecycle() == null) return;
        String current = Objects.toString(values.get(policy.lifecycle().fieldId()), null);
        if (policy.lifecycle().states().stream()
                .noneMatch(s -> s.code().equals(current) && s.allowDelete()))
            throw invalid("当前状态不允许删除整张单据");
    }

    private static boolean sameRows(
            List<DocumentPolicies.InputRow> a, List<DocumentPolicies.InputRow> b) {
        if (a.size() != b.size()
                || !a.stream()
                        .map(DocumentPolicies.InputRow::id)
                        .toList()
                        .equals(b.stream().map(DocumentPolicies.InputRow::id).toList()))
            return false;
        Map<String, Map<String, Object>> rows = new HashMap<>();
        a.forEach(r -> rows.put(r.id(), r.values()));
        return b.stream()
                .allMatch(
                        r ->
                                r.id() != null
                                        && rows.containsKey(r.id())
                                        && same(rows.get(r.id()), r.values()));
    }

    static boolean same(Object a, Object b) {
        if (a instanceof Number && b instanceof Number)
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
        if (a instanceof Map<?, ?> am && b instanceof Map<?, ?> bm)
            return am.keySet().equals(bm.keySet())
                    && am.keySet().stream().allMatch(k -> same(am.get(k), bm.get(k)));
        return Objects.equals(a, b);
    }
}
