package com.richuang.os.nocode.runtime.service.rules;

import com.richuang.os.nocode.api.DataCenter;
import com.richuang.os.nocode.api.FieldRules;
import com.richuang.os.nocode.runtime.service.access.ApplicationRuntimePolicy;

import java.util.Set;

/** 对象规则运行契约：运行、设计预览与候选范围均复用服务端固定版本和实时权限。 */
public interface FieldRuleService extends FieldRuleEvaluator {
    /** 按当前应用固定对象版本求值，不接收客户端规则配置。 */
    FieldRules.Evaluation evaluate(FieldRules.EvaluateQuery query, long actor);

    /** 设计预览同样核验对象引用、设计权限与数据读取权限。 */
    FieldRules.Evaluation preview(FieldRules.EvaluatePreview request, long actor);

    /** 引用筛选依赖字段按固定版本和有效授权解析。 */
    Set<String> referenceFilterFields(RuleContext context, DataCenter.Relation relation);

    /** 可参与规则条件的查询字段，必须满足有序计算校准状态。 */
    Set<String> queryableFields(
            DataCenter.Definition target, ApplicationRuntimePolicy.Access access);

    /** 关系字段上的规则配置位：主表字段取主表扩展属性，明细字段取所属明细的扩展属性。 */
    static DataCenter.FieldOptions fieldOptions(
            DataCenter.Definition d, DataCenter.Relation relation) {
        if (relation == null || relation.fieldId() == null) return null;
        if (relation.sourceDetailId() == null) return d.fieldOptions().get(relation.fieldId());
        return d.details().stream()
                .filter(t -> t.id().equals(relation.sourceDetailId()))
                .findFirst()
                .map(t -> t.fieldOptions().get(relation.fieldId()))
                .orElse(null);
    }
}
