package com.richuang.os.nocode.runtime.service.report;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.common.dto.DynamicConditionDTO;
import com.richuang.os.nocode.api.ApplicationReports;
import com.richuang.os.nocode.application.service.resource.ApplicationReportValidator;
import com.richuang.os.nocode.application.service.resource.ReportSourceMessages;
import com.richuang.os.nocode.enums.ReportOperationEnum;

import java.util.*;

/**
 * 多个数据来源统计在运行期的无状态部分：用户请求按来源投影（契约第 7、8 章）、下钻落到哪个来源（第 9 章）、空占位的物理类型白名单（L24）。 来源投影配置本身由 {@link
 * ApplicationReportValidator#project} 给出，保存校验与运行期同一份。
 */
final class ReportMultiSources {
    private ReportMultiSources() {}

    /** L24 的白名单：小写字母开头，字母、数字、空格，可带一个 (n) 或 (n,m)。 */
    private static final java.util.regex.Pattern NATIVE_TYPE =
            java.util.regex.Pattern.compile("[a-z][a-z0-9 ]*(\\([0-9]+(,[0-9]+)?\\))?");

    /**
     * 下钻落到哪个来源：sourceId 与 metricId 都为空 ⇒ null（不是下钻，或由调用方报 L19）；只给 sourceId ⇒ 它（不存在 ⇒ L20）；给了
     * metricId ⇒ 该指标所属来源（指标不存在 ⇒「下钻指标不存在」，计算指标 ⇒ 原句，与 sourceId 不一致 ⇒ L20）。来源 1 返回 main。
     */
    static String target(ApplicationReports.Config c, ApplicationReports.Query q) {
        if (q.sourceId() == null && q.metricId() == null) return null;
        Set<String> ids = new LinkedHashSet<>();
        ids.add(ReportSourceMessages.MAIN);
        for (ApplicationReports.Source s : c.extraSources()) ids.add(s.id());
        if (q.sourceId() != null && !ids.contains(q.sourceId()))
            throw invalid(ReportSourceMessages.DRILL_SOURCE);
        if (q.metricId() == null) return q.sourceId();
        ApplicationReports.Metric metric =
                c.metrics().stream()
                        .filter(m -> Objects.equals(m.id(), q.metricId()))
                        .findFirst()
                        .orElseThrow(() -> invalid("下钻指标不存在"));
        if (ReportOperationEnum.FORMULA == ReportOperationEnum.fromCode(metric.operation()))
            throw invalid("计算指标请分别查看其引用指标的明细");
        String owner = metric.sourceId() == null ? ReportSourceMessages.MAIN : metric.sourceId();
        if (q.sourceId() != null && !q.sourceId().equals(owner))
            throw invalid(ReportSourceMessages.DRILL_SOURCE);
        return owner;
    }

    /**
     * 用户请求按来源投影：equal 与 conditions 叶子的字段键换成该来源的键（{@link ApplicationReportValidator#filterKey}，映射不到
     * ⇒ L14）；dateFrom / dateTo、group / columnGroup、分页、上下文原样；metricId 只在指标属于该来源时保留；sort、sourceId
     * 不投影。
     */
    static ApplicationReports.Query query(
            ApplicationReportValidator validator,
            ObjectMapper json,
            ApplicationReports.Config c,
            ApplicationReports.Source source,
            ApplicationReports.Query q,
            String sourceId) {
        String metricId = null;
        if (q.metricId() != null)
            for (ApplicationReports.Metric m : c.metrics())
                if (m.id().equals(q.metricId())
                        && Objects.equals(
                                m.sourceId() == null ? ReportSourceMessages.MAIN : m.sourceId(),
                                sourceId)) metricId = m.id();
        Map<String, Object> equal = q.equal();
        DynamicConditionDTO conditions = q.conditions();
        if (source != null) {
            if (equal != null) {
                Map<String, Object> mapped = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : equal.entrySet())
                    mapped.put(map(validator, c, source, entry.getKey()), entry.getValue());
                equal = mapped;
            }
            if (conditions != null) {
                conditions = json.convertValue(conditions, DynamicConditionDTO.class);
                rename(validator, c, source, conditions.getItems());
            }
        }
        return new ApplicationReports.Query(
                q.applicationId(),
                q.reportId(),
                equal,
                q.dateFrom(),
                q.dateTo(),
                q.context(),
                q.group(),
                q.pageNo(),
                q.pageSize(),
                conditions,
                metricId,
                q.columnGroup(),
                null,
                null);
    }

    /** 运行期的 L14：用户筛选键在该来源里映射不到（只会出现在早先发布、没有经过现行校验的快照上）。 */
    static String map(
            ApplicationReportValidator validator,
            ApplicationReports.Config c,
            ApplicationReports.Source source,
            String key) {
        String target = validator.filterKey(c, source, key);
        if (target == null) throw invalid(ReportSourceMessages.filterMissing(key, source.name()));
        return target;
    }

    private static void rename(
            ApplicationReportValidator validator,
            ApplicationReports.Config c,
            ApplicationReports.Source source,
            List<DynamicConditionDTO.Item> items) {
        if (items == null) return;
        for (DynamicConditionDTO.Item item : items) {
            if (item == null) continue;
            if (item.isGroup()) rename(validator, c, source, item.getGroupItems());
            // 不在开放筛选里的字段原样留下，由该来源的编译照常拒绝（「只能使用统计视图开放的筛选字段」）。
            else if (item.getField() != null
                    && c.filterFieldIds() != null
                    && c.filterFieldIds().contains(item.getField()))
                item.setField(map(validator, c, source, item.getField()));
        }
    }

    /** 其它来源分支里指标列的空占位类型：物理列类型过白名单（L24），防止目录里的异常值进 SQL。 */
    static String nullType(String nativeType) {
        String type = nativeType == null ? "" : nativeType.trim().toLowerCase(Locale.ROOT);
        if (!NATIVE_TYPE.matcher(type).matches()) throw invalid(ReportSourceMessages.NATIVE_TYPE);
        return type;
    }
}
