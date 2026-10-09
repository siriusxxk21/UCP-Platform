package com.lingan.ucp.nocode.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.lingan.ucp.nocode.enums.ReportDashboardEntryEnum;
import com.lingan.ucp.nocode.enums.ReportResourceStatusEnum;

import java.util.List;

/** 独立仪表板配置只引用固定数据集版本；组件查询由服务端还原，客户端不提交数据源。 */
public final class ReportDashboards {
    private ReportDashboards() {}

    public record Dataset(String id, int versionNo, String checksum) {}

    public record Chart(
            String id,
            String title,
            String display,
            Dataset dataset,
            List<ReportDatasetQueries.Dimension> dimensions,
            List<String> metricIds,
            int x,
            int y,
            int w,
            int h,
            @JsonInclude(JsonInclude.Include.NON_NULL)
                    List<ReportDatasetQueries.Dimension> columnDimensions,
            @JsonInclude(JsonInclude.Include.NON_NULL) ApplicationReports.Pivot pivot,
            @JsonInclude(JsonInclude.Include.NON_NULL)
                    List<ReportDatasetQueries.Dimension> drillDimensions,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<Link> links) {
        public Chart(
                String id,
                String title,
                String display,
                Dataset dataset,
                List<ReportDatasetQueries.Dimension> dimensions,
                List<String> metricIds,
                int x,
                int y,
                int w,
                int h,
                List<ReportDatasetQueries.Dimension> columnDimensions,
                ApplicationReports.Pivot pivot) {
            this(
                    id,
                    title,
                    display,
                    dataset,
                    dimensions,
                    metricIds,
                    x,
                    y,
                    w,
                    h,
                    columnDimensions,
                    pivot,
                    null,
                    null);
        }

        public Chart(
                String id,
                String title,
                String display,
                Dataset dataset,
                List<ReportDatasetQueries.Dimension> dimensions,
                List<String> metricIds,
                int x,
                int y,
                int w,
                int h) {
            this(id, title, display, dataset, dimensions, metricIds, x, y, w, h, null, null);
        }
    }

    /** 只读下钻只接收固定组件的行列键前缀和指标身份。 */
    public record Details(
            Query query,
            List<String> group,
            List<String> columnGroup,
            String metricId,
            int pageNo,
            int pageSize) {}

    public record DetailColumn(String id, String name) {}

    public record DetailRow(String id, List<String> values, List<String> labels) {}

    public record DetailPage(
            List<DetailColumn> columns,
            List<DetailRow> list,
            long total,
            int pageNo,
            int pageSize) {}

    /** 公共筛选只映射固定组件的发布字段；多值筛选保留空值原键。 */
    public record Filter(
            String id,
            String name,
            String kind,
            List<Mapping> mappings,
            @JsonInclude(JsonInclude.Include.NON_NULL) FilterDefault defaultValue) {
        public Filter(String id, String name, String kind, List<Mapping> mappings) {
            this(id, name, kind, mappings, null);
        }
    }

    /** 默认值仅初始化可清除的界面条件；查询服务不自动补入未提交的条件。 */
    public record FilterDefault(List<String> values, String from, String to) {}

    public record Mapping(String chartId, String fieldId) {}

    /** 联动来源限基础 VALUE 维度，完整 group 的位置由配置决定。 */
    public record Link(String targetChartId, String sourceFieldId, String targetFieldId) {}

    public record FilterValue(String filterId, List<String> values, String from, String to) {}

    public record Selection(String chartId, List<String> group) {}

    public record Options(Query query, String filterId, int pageNo, int pageSize, String search) {}

    public record Content(
            int schemaVersion,
            String name,
            String description,
            List<Chart> charts,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<Filter> filters,
            @JsonInclude(JsonInclude.Include.NON_NULL) Navigation navigation) {
        public Content(
                int schemaVersion,
                String name,
                String description,
                List<Chart> charts,
                List<Filter> filters) {
            this(schemaVersion, name, description, charts, filters, null);
        }

        public Content(int schemaVersion, String name, String description, List<Chart> charts) {
            this(schemaVersion, name, description, charts, null);
        }
    }

    /** 独立看板菜单随发布生效；菜单身份由服务端生成，不接收底座菜单编号。 */
    public record Navigation(
            Boolean showInMenu,
            String platformParentId,
            String menuName,
            String icon,
            Integer sort) {}

    public record Save(
            String id,
            int expectedRevision,
            Content content,
            @JsonInclude(JsonInclude.Include.NON_NULL) String folderId) {
        public Save(String id, int expectedRevision, Content content) {
            this(id, expectedRevision, content, null);
        }
    }

    public record Publish(String id, int expectedRevision, String requestId) {}

    /** 生命周期命令使用资源头修订号，目录和状态不写入发布内容。 */
    public record Copy(String id, int expectedRevision, String name, String reason) {}

    public record Move(String id, int expectedRevision, String folderId, String reason) {}

    public record ChangeStatus(String id, int expectedRevision, String status, String reason) {}

    public record Restore(String id, int expectedRevision, int versionNo, String reason) {}

    public record Delete(String id, int expectedRevision, String reason) {}

    public record DeletePreview(String id, int revision, boolean canDelete, long referenceCount) {}

    public record Deleted(String id, int revision, boolean deleted) {}

    public record Detail(
            String id,
            String ownerId,
            int revision,
            Integer publishedVersion,
            String checksum,
            boolean modified,
            Content draft,
            @JsonInclude(JsonInclude.Include.NON_NULL) Capabilities capabilities,
            @JsonInclude(JsonInclude.Include.NON_NULL) String status,
            @JsonInclude(JsonInclude.Include.NON_NULL) String folderId) {
        public Detail(
                String id,
                String ownerId,
                int revision,
                Integer publishedVersion,
                String checksum,
                boolean modified,
                Content draft,
                Capabilities capabilities) {
            this(
                    id,
                    ownerId,
                    revision,
                    publishedVersion,
                    checksum,
                    modified,
                    draft,
                    capabilities,
                    ReportResourceStatusEnum.ACTIVE.getCode(),
                    null);
        }

        public Detail(
                String id,
                String ownerId,
                int revision,
                Integer publishedVersion,
                String checksum,
                boolean modified,
                Content draft) {
            this(id, ownerId, revision, publishedVersion, checksum, modified, draft, null);
        }
    }

    /** 列表能力只描述全局操作与资源 ACL 的交集，图表导出还必须通过实时数据授权。 */
    public record Capabilities(
            boolean canView,
            boolean canEdit,
            boolean canPublish,
            boolean canGrant,
            boolean canExport,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean canDelete) {
        public Capabilities(
                boolean canView,
                boolean canEdit,
                boolean canPublish,
                boolean canGrant,
                boolean canExport) {
            this(canView, canEdit, canPublish, canGrant, canExport, null);
        }
    }

    /** 普通 VIEW 受众只看到发布摘要，revision=null 且不暴露草稿差异。 */
    public record AvailableItem(
            String id,
            String name,
            String ownerId,
            Integer publishedVersion,
            int chartCount,
            boolean modified,
            Integer revision,
            Capabilities capabilities,
            @JsonInclude(JsonInclude.Include.NON_NULL) String status,
            @JsonInclude(JsonInclude.Include.NON_NULL) String folderId) {
        public AvailableItem(
                String id,
                String name,
                String ownerId,
                Integer publishedVersion,
                int chartCount,
                boolean modified,
                Integer revision,
                Capabilities capabilities) {
            this(
                    id,
                    name,
                    ownerId,
                    publishedVersion,
                    chartCount,
                    modified,
                    revision,
                    capabilities,
                    ReportResourceStatusEnum.ACTIVE.getCode(),
                    null);
        }
    }

    public record Release(String id, int versionNo, String checksum, Content content) {}

    public record Query(
            String id,
            String chartId,
            boolean preview,
            Integer versionNo,
            String checksum,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<FilterValue> filterValues,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<Selection> selections,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<String> drillPath) {
        public Query(
                String id, String chartId, boolean preview, Integer versionNo, String checksum) {
            this(id, chartId, preview, versionNo, checksum, null, null, null);
        }
    }

    /** stamp 在查询返回前复核；不允许在数据集持锁取数外层嵌套仪表板事务。 */
    public record Resolved(
            Query request,
            int revision,
            String stamp,
            Chart chart,
            Content content,
            @JsonIgnore ReportDashboardEntryEnum entry) {
        public Resolved(Query request, int revision, String stamp, Chart chart, Content content) {
            this(
                    request,
                    revision,
                    stamp,
                    chart,
                    content,
                    request != null && request.preview()
                            ? ReportDashboardEntryEnum.PREVIEW
                            : ReportDashboardEntryEnum.CURRENT);
        }

        public Resolved(Query request, int revision, String stamp, Chart chart) {
            this(request, revision, stamp, chart, null);
        }
    }

    /** 内部执行参数由固定配置还原；HTTP 查询不接受字段、数据集或筛选算子。 */
    public record Execution(
            Chart chart,
            DataScope filters,
            List<ReportDatasetQueries.Dimension> pathDimensions,
            List<String> path,
            List<TextFilter> textFilters,
            @JsonInclude(JsonInclude.Include.NON_NULL) Resolved resolved) {
        public Execution(
                Chart chart,
                DataScope filters,
                List<ReportDatasetQueries.Dimension> pathDimensions,
                List<String> path,
                List<TextFilter> textFilters) {
            this(chart, filters, pathDimensions, path, textFilters, null);
        }
    }

    public record TextFilter(String fieldId, String value) {}
}
