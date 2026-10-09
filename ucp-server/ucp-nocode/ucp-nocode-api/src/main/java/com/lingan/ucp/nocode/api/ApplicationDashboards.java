package com.lingan.ucp.nocode.api;

import java.util.List;
import java.util.Map;

/** 应用只声明固定看板引用与有界输入绑定；运行请求不得替换引用或提交筛选算子。 */
public final class ApplicationDashboards {
    private ApplicationDashboards() {}

    public record Reference(String id, int versionNo, String checksum) {}

    /** 设计候选只返回当前发布摘要，不包含草稿、拥有者或业务记录。 */
    public record Candidate(
            String id, String name, int versionNo, String checksum, int chartCount) {}

    public record CatalogRequest(
            Reference reference, List<ApplicationCenter.ObjectReference> objects) {}

    /** contextObjectId 必须已在应用中；缺省集合由应用校验器规范为空集合。 */
    public record Config(
            Reference dashboard,
            String contextObjectId,
            List<InputBinding> inputBindings,
            List<DetailView> detailViews) {}

    /** 参数名只用于 PARAMETER；RECORD_FIELD 使用固定上下文对象字段，RECORD_ID 不接受字段。 */
    public record InputBinding(String filterId, String source, String parameter, String fieldId) {}

    /** 目标是同一应用发布的业务视图；没有绑定的图表只提供只读明细。 */
    public record DetailView(String chartId, String viewId) {}

    /** 模型标记绑定应用发布版本及资源配置；查询必须携带同一标记。 */
    public record Model(
            String applicationId,
            String resourceId,
            String stamp,
            ReportDashboards.Release dashboard,
            List<String> boundFilterIds,
            Config config) {}

    /** 参数只能提供有界原值，筛选身份及算子取自发布绑定。 */
    public record InputValue(List<String> values, String from, String to) {}

    /** 普通应用入口不接收看板、数据集或版本引用，全部从服务器发布资源还原。 */
    public record Query(
            String applicationId,
            String resourceId,
            String chartId,
            String stamp,
            Map<String, InputValue> parameters,
            String recordId,
            List<ReportDashboards.FilterValue> filterValues,
            List<ReportDashboards.Selection> selections,
            List<String> drillPath) {}

    public record Options(Query query, String filterId, int pageNo, int pageSize, String search) {}

    public record Details(
            Query query,
            List<String> group,
            List<String> columnGroup,
            String metricId,
            int pageNo,
            int pageSize) {}

    /** 业务明细只携带受控运行条件；目标视图由已发布 detailViews 绑定决定。 */
    public record Drill(
            Query query, List<String> group, List<String> columnGroup, String metricId) {}

    /** 目录结果只有发布定义与逻辑来源，不携带物理表列、业务记录或授权表达式。 */
    public record Catalog(
            Reference reference,
            ReportDashboards.Content content,
            List<DatasetContract> datasets) {}

    public record DatasetContract(
            ReportDashboards.Dataset reference,
            ReportDatasets.Content content,
            ReportDatasets.ResolvedSource source) {}
}
