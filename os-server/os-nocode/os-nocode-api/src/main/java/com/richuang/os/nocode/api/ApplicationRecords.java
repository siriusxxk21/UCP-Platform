package com.richuang.os.nocode.api;

import com.richuang.os.common.dto.DynamicConditionDTO;

import java.util.List;
import java.util.Map;

/** 业务记录使用稳定字段 ID；主键、数字精度与版本标记不经过浏览器浮点转换。 */
public final class ApplicationRecords {
    private ApplicationRecords() {}

    /** 定位已发布页面的关联块；关联含义和目标由服务端解析，不能由请求自行定义。 */
    public record Context(String pageId, String nodeId, String recordId) {}

    /**
     * 已发布应用中的记录查询，字段定位使用稳定字段 ID。
     *
     * @param applicationId 应用 ID
     * @param objectId 应用已引用的对象 ID
     * @param pageNo 页码，从 1 开始；范围由运行服务校验
     * @param pageSize 每页条数，沿用原运行服务上限
     * @param search 文本搜索条件
     * @param equal 等值过滤，键为稳定字段 ID
     * @param sortFieldId 排序字段 ID，必须属于可查询字段
     * @param descending 是否降序
     * @param viewId 已发布视图 ID；视图固定范围与请求过滤共同生效
     * @param context 关联页面的父记录定位，由服务端验证
     * @param conditions 动态过滤条件，不能覆盖授权条件
     * @param childFilters 视图子表过滤，按发布配置解析
     * @param reportDrill 统计下钻；必须同时指定 viewId，记录集与 report-details 相同后再与视图条件取交集
     * @param dashboardDrill 应用固定看板业务明细，目标必须为该图表已发布绑定的业务视图
     */
    public record Query(
            String applicationId,
            String objectId,
            int pageNo,
            int pageSize,
            String search,
            Map<String, Object> equal,
            String sortFieldId,
            boolean descending,
            String viewId,
            Context context,
            DynamicConditionDTO conditions,
            List<DataViews.ChildFilter> childFilters,
            ApplicationReports.Drill reportDrill,
            ApplicationDashboards.Drill dashboardDrill) {
        /** 兼容原统计下钻及所有既有内部调用，新增范围缺省关闭。 */
        public Query(
                String applicationId,
                String objectId,
                int pageNo,
                int pageSize,
                String search,
                Map<String, Object> equal,
                String sortFieldId,
                boolean descending,
                String viewId,
                Context context,
                DynamicConditionDTO conditions,
                List<DataViews.ChildFilter> childFilters,
                ApplicationReports.Drill reportDrill) {
            this(
                    applicationId,
                    objectId,
                    pageNo,
                    pageSize,
                    search,
                    equal,
                    sortFieldId,
                    descending,
                    viewId,
                    context,
                    conditions,
                    childFilters,
                    reportDrill,
                    null);
        }

        public Query(
                String applicationId,
                String objectId,
                int pageNo,
                int pageSize,
                String search,
                Map<String, Object> equal,
                String sortFieldId,
                boolean descending,
                String viewId,
                Context context,
                DynamicConditionDTO conditions,
                List<DataViews.ChildFilter> childFilters) {
            this(
                    applicationId,
                    objectId,
                    pageNo,
                    pageSize,
                    search,
                    equal,
                    sortFieldId,
                    descending,
                    viewId,
                    context,
                    conditions,
                    childFilters,
                    null);
        }

        public Query(
                String applicationId,
                String objectId,
                int pageNo,
                int pageSize,
                String search,
                Map<String, Object> equal,
                String sortFieldId,
                boolean descending,
                String viewId,
                Context context,
                DynamicConditionDTO conditions) {
            this(
                    applicationId,
                    objectId,
                    pageNo,
                    pageSize,
                    search,
                    equal,
                    sortFieldId,
                    descending,
                    viewId,
                    context,
                    conditions,
                    List.of());
        }

        public Query(
                String applicationId,
                String objectId,
                int pageNo,
                int pageSize,
                String search,
                Map<String, Object> equal,
                String sortFieldId,
                boolean descending,
                String viewId,
                Context context) {
            this(
                    applicationId,
                    objectId,
                    pageNo,
                    pageSize,
                    search,
                    equal,
                    sortFieldId,
                    descending,
                    viewId,
                    context,
                    null);
        }

        public Query(
                String applicationId,
                String objectId,
                int pageNo,
                int pageSize,
                String search,
                Map<String, Object> equal,
                String sortFieldId,
                boolean descending,
                String viewId) {
            this(
                    applicationId,
                    objectId,
                    pageNo,
                    pageSize,
                    search,
                    equal,
                    sortFieldId,
                    descending,
                    viewId,
                    null);
        }

        public Query(
                String applicationId,
                String objectId,
                int pageNo,
                int pageSize,
                String search,
                Map<String, Object> equal,
                String sortFieldId,
                boolean descending) {
            this(
                    applicationId,
                    objectId,
                    pageNo,
                    pageSize,
                    search,
                    equal,
                    sortFieldId,
                    descending,
                    null);
        }
    }

    /**
     * 带统计下钻的列表分页：list/total 与普通分页同义（已叠加视图自身条件）；drillTotal 仅按下钻条件命中的记录数，不叠加视图自身条件，
     * 供前端提示「下钻视图自身的筛选条件排除了 N 条」。
     */
    public record DrillPage(List<Row> list, long total, long drillTotal) {}

    /**
     * 应用固定看板业务明细分页。drillTotal 始终为授权交集内的看板根记录数，total 为叠加业务视图与请求条件后的行数； 明细粒度视图通过 drillDifferentGrain
     * 标记两者不能相减。
     */
    public record DashboardDrillPage(
            List<Row> list, long total, long drillTotal, boolean drillDifferentGrain) {}

    public record Row(
            String id,
            String revision,
            Map<String, Object> values,
            ApplicationAuthorization.Capabilities permissions,
            Map<String, String> displayValues,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    String clientRowKey,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    String parentId) {
        public Row(
                String id,
                String revision,
                Map<String, Object> values,
                ApplicationAuthorization.Capabilities permissions,
                Map<String, String> displayValues,
                String clientRowKey) {
            this(id, revision, values, permissions, displayValues, clientRowKey, null);
        }

        public Row(
                String id,
                String revision,
                Map<String, Object> values,
                ApplicationAuthorization.Capabilities permissions,
                Map<String, String> displayValues) {
            this(id, revision, values, permissions, displayValues, null);
        }

        public Row(
                String id,
                String revision,
                Map<String, Object> values,
                ApplicationAuthorization.Capabilities permissions) {
            this(id, revision, values, permissions, Map.of());
        }

        public Row(String id, String revision, Map<String, Object> values) {
            this(id, revision, values, null);
        }
    }

    public record Process(
            String businessKey,
            String name,
            String instanceId,
            String status,
            java.time.LocalDateTime createTime,
            java.time.LocalDateTime endTime) {}

    public record Aggregate(
            Row record,
            Map<String, List<Row>> details,
            List<Process> processes,
            Map<String, List<String>> relations) {
        public Aggregate(Row record, Map<String, List<Row>> details) {
            this(record, details, List.of(), Map.of());
        }

        public Aggregate(Row record, Map<String, List<Row>> details, List<Process> processes) {
            this(record, details, processes, Map.of());
        }
    }

    /** 成功收据属于当前操作者；result 经当前权限重新裁剪，保留原保存修订。 */
    public record SaveReceipt(
            String requestKey,
            String status,
            String operationId,
            String recordId,
            String revision,
            String policyVersion,
            Aggregate result) {}

    /** BPM 业务表单定位信息仍经过应用记录授权，不因持有业务键而获得额外权限。 */
    public record ProcessRecord(String applicationId, String objectId, String recordId) {}

    /**
     * 整单保存命令。提交的明细分组按整组替换；未提交分组保持原样，每条已有明细必须携带版本。
     *
     * @param applicationId 应用 ID，按其发布版本解释输入
     * @param objectId 业务对象 ID
     * @param id 主记录 ID；为空表示新建
     * @param expectedRevision 已有主记录的预期修订，防止覆盖并发修改
     * @param values 主记录输入，键为稳定字段 ID
     * @param details 按稳定明细表 ID 分组的完整输入，空分组与未提交分组语义不同
     * @param relations 按关系 ID 提交的目标记录 ID
     * @param context 关联页面上下文，必须经过服务端解析
     * @param formId 使用的发布表单 ID
     * @param requestKey 原保存请求键，重试保留同一键
     * @param actionCode 已配置的业务动作编码
     * @param relatedRecords 按发布关联区域 ID 分组的独立对象输入
     */
    public record Save(
            String applicationId,
            String objectId,
            String id,
            String expectedRevision,
            Map<String, Object> values,
            Map<String, List<Row>> details,
            Map<String, List<String>> relations,
            Context context,
            String formId,
            String requestKey,
            String actionCode,
            Map<String, List<RelatedForms.Row>> relatedRecords) {
        public Save(
                String applicationId,
                String objectId,
                String id,
                String expectedRevision,
                Map<String, Object> values,
                Map<String, List<Row>> details,
                Map<String, List<String>> relations,
                Context context,
                String formId,
                String requestKey,
                String actionCode) {
            this(
                    applicationId,
                    objectId,
                    id,
                    expectedRevision,
                    values,
                    details,
                    relations,
                    context,
                    formId,
                    requestKey,
                    actionCode,
                    null);
        }

        public Save(
                String applicationId,
                String objectId,
                String id,
                String expectedRevision,
                Map<String, Object> values,
                Map<String, List<Row>> details,
                Map<String, List<String>> relations,
                Context context,
                String formId) {
            this(
                    applicationId,
                    objectId,
                    id,
                    expectedRevision,
                    values,
                    details,
                    relations,
                    context,
                    formId,
                    null,
                    null);
        }

        public Save(
                String applicationId,
                String objectId,
                String id,
                String expectedRevision,
                Map<String, Object> values,
                Map<String, List<Row>> details,
                Map<String, List<String>> relations,
                Context context) {
            this(
                    applicationId,
                    objectId,
                    id,
                    expectedRevision,
                    values,
                    details,
                    relations,
                    context,
                    null);
        }

        public Save(
                String applicationId,
                String objectId,
                String id,
                String expectedRevision,
                Map<String, Object> values,
                Map<String, List<Row>> details,
                Map<String, List<String>> relations) {
            this(applicationId, objectId, id, expectedRevision, values, details, relations, null);
        }

        public Save(
                String applicationId,
                String objectId,
                String id,
                String expectedRevision,
                Map<String, Object> values,
                Map<String, List<Row>> details) {
            this(applicationId, objectId, id, expectedRevision, values, details, null);
        }
    }

    public record Delete(
            String applicationId, String objectId, String id, String expectedRevision) {}

    public record Model(
            DataCenter.Definition object,
            boolean writable,
            boolean generatedKey,
            String keyFieldId,
            String keyType,
            Map<String, TableModel> details,
            ApplicationAuthorization.Capabilities permissions,
            List<String> managedFieldIds,
            List<OrderedCalculations.State> orderedStates) {
        public Model(
                DataCenter.Definition object,
                boolean writable,
                boolean generatedKey,
                String keyFieldId,
                String keyType,
                Map<String, TableModel> details,
                ApplicationAuthorization.Capabilities permissions,
                List<String> managedFieldIds) {
            this(
                    object,
                    writable,
                    generatedKey,
                    keyFieldId,
                    keyType,
                    details,
                    permissions,
                    managedFieldIds,
                    List.of());
        }

        public Model(
                DataCenter.Definition object,
                boolean writable,
                boolean generatedKey,
                String keyFieldId,
                String keyType,
                Map<String, TableModel> details,
                ApplicationAuthorization.Capabilities permissions) {
            this(
                    object,
                    writable,
                    generatedKey,
                    keyFieldId,
                    keyType,
                    details,
                    permissions,
                    List.of());
        }
    }

    public record TableModel(
            boolean writable, boolean generatedKey, String keyFieldId, String keyType) {}
}
