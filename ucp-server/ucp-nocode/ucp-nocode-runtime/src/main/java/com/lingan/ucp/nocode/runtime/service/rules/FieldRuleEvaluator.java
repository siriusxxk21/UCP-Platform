package com.lingan.ucp.nocode.runtime.service.rules;

import com.lingan.ucp.nocode.api.DataCenter;
import com.lingan.ucp.nocode.api.FieldRules;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface FieldRuleEvaluator {
    /** 主表：按拓扑序求值；targets 为空表示全部。creating 不再影响求值（2026-09-29 起公式默认值新建与编辑都求值），保留参数兼容调用方。 */
    List<FieldRules.Result> evaluate(
            RuleContext ctx,
            Map<String, Object> values,
            Set<String> targets,
            Set<String> overridable,
            boolean creating);

    /** 明细：masterValues 是已含本次主表新结果的主表值；逐行求值，结果带 detailId 和 rowKey。 */
    List<FieldRules.Result> evaluateRows(
            RuleContext ctx,
            String detailId,
            Map<String, Object> masterValues,
            Set<String> masterChanged,
            List<FieldRules.RowInput> rows);

    /** values 是按全局字段 ID 合并的值（明细场景 = 主表值 + 本行值）。 */
    ReferenceScope referenceScope(
            RuleContext ctx,
            String detailId,
            DataCenter.Relation relation,
            Map<String, Object> values);

    Set<String> idsWithinScope(
            RuleContext ctx,
            DataCenter.Relation relation,
            ReferenceScope scope,
            Collection<String> ids);
}
