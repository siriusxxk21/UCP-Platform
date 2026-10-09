package com.richuang.os.nocode.runtime.service.taskcenter;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.nocode.api.TaskWorkEntries;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** 预计工作量仅用于预算，不生成办理事实；先合计再向上取整为整数分钟。 */
public final class TaskWorkBudgets {
    private TaskWorkBudgets() {}

    public static void validate(TaskWorkEntries.WorkRule rule) {
        if (rule == null || rule.plannedQuantity() == null) return;
        BigDecimal quantity = rule.plannedQuantity();
        if (quantity.signum() < 0
                || quantity.compareTo(new BigDecimal("999999")) > 0
                || quantity.scale() > 6) throw invalid("预计工作量须为 0 至 999999，最多保留 6 位小数");
        if (rule.mode() != TaskWorkEntries.WorkRuleMode.QUANTITY
                && quantity.stripTrailingZeros().scale() > 0) throw invalid("预计记录数和预计达标记录数必须为整数");
    }

    public static Integer total(List<TaskWorkEntries.Config> entries) {
        BigDecimal sum = BigDecimal.ZERO;
        boolean complete = entries != null && !entries.isEmpty();
        for (TaskWorkEntries.Config entry :
                entries == null ? List.<TaskWorkEntries.Config>of() : entries) {
            if (entry == null) throw invalid("任务办理入口配置不能为空");
            TaskWorkEntries.WorkRule rule = entry.workRule();
            if (rule == null) {
                complete = false;
                continue;
            }
            validate(rule);
            if (rule.plannedQuantity() == null || rule.effectiveMinutes() == 0) {
                complete = false;
                continue;
            }
            sum =
                    sum.add(
                            rule.plannedQuantity()
                                    .multiply(BigDecimal.valueOf(rule.effectiveMinutes())));
        }
        if (sum.compareTo(new BigDecimal("599999")) > 0) throw invalid("任务标准总工时不能超过 9999 小时 59 分钟");
        int total = sum.setScale(0, RoundingMode.CEILING).intValueExact();
        // 未配置或只配置部分预算时总额未知，不把缺项当作零，也不阻断任务运行。
        return complete && total > 0 ? total : null;
    }
}
