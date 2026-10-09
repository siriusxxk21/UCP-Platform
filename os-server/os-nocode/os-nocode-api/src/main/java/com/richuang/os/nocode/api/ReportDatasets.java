package com.richuang.os.nocode.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/** 独立分析数据集的设计与来源契约；不携带数据库连接、SQL 或有效数据权限。 */
public final class ReportDatasets {
    public static final int SOURCE_SCHEMA_VERSION = 1;
    public static final int MAX_RELATION_DEPTH = 2;
    public static final int MAX_RELATIONS = 50;
    public static final int MAX_FIELDS = 200;

    private ReportDatasets() {}

    /** 保存完整草稿；首次创建 id 为空且 expectedRevision=0，允许来源未配置。 */
    public record Save(
            String id,
            int expectedRevision,
            String name,
            String description,
            Source source,
            Analysis analysis,
            String folderId) {
        public Save(
                String id,
                int expectedRevision,
                String name,
                String description,
                Source source,
                Analysis analysis) {
            this(id, expectedRevision, name, description, source, analysis, null);
        }

        public Save(
                String id, int expectedRevision, String name, String description, Source source) {
            this(id, expectedRevision, name, description, source, null, null);
        }
    }

    /** 草稿内容参与统一摘要；恢复历史版本时写入新草稿，不倒拨发布指针。 */
    public record Content(
            String name,
            String description,
            Source source,
            @JsonInclude(JsonInclude.Include.NON_NULL) Analysis analysis) {
        public Content(String name, String description, Source source) {
            this(name, description, source, null);
        }
    }

    /** 分析配置独立版本化；固定条件使用数据集字段 ID，只允许常量，运行请求只能继续收窄。 */
    public record Analysis(
            int schemaVersion,
            List<ReportDatasetQueries.Metric> metrics,
            DataScope fixedConditions,
            Map<String, ApplicationReports.Format> fieldFormats,
            String timeZone) {
        public Analysis {
            metrics = metrics == null ? List.of() : List.copyOf(metrics);
            fieldFormats = fieldFormats == null ? Map.of() : Map.copyOf(fieldFormats);
        }
    }

    public record Detail(
            String id,
            String ownerId,
            String status,
            int revision,
            Integer publishedVersion,
            String checksum,
            boolean modified,
            Content draft,
            String folderId) {
        public Detail(
                String id,
                String ownerId,
                String status,
                int revision,
                Integer publishedVersion,
                String checksum,
                boolean modified,
                Content draft) {
            this(id, ownerId, status, revision, publishedVersion, checksum, modified, draft, null);
        }
    }

    /** 分类位置不属于发布内容；移动独立修订，不改分析摘要或授权。 */
    public record Move(String id, int expectedRevision, String folderId, String reason) {}

    /** requestId 在同一资源与操作者下唯一；同键异参拒绝。 */
    public record Publish(String id, int expectedRevision, String requestId, String reason) {}

    public record Release(
            String datasetId,
            int versionNo,
            String checksum,
            Content definition,
            String reason,
            java.time.LocalDateTime createTime) {}

    public record Restore(String id, int expectedRevision, int versionNo, String reason) {}

    public record ChangeStatus(String id, int expectedRevision, String status, String reason) {}

    /** 复制当前已保存草稿；不复制发布快照、资源 ACL、对象上限或成员授权。 */
    public record Copy(String id, int expectedRevision, String name, String reason) {}

    public record Delete(String id, int expectedRevision, String reason) {}

    /** 不返回无权引用资源的名称或配置；真正删除时持锁重新检查，预检不构成授权凭据。 */
    public record DeletePreview(String id, int revision, long referenceCount, boolean canDelete) {}

    public record Deleted(String id, int revision, boolean deleted) {}

    /** 不允许省略版本后自动读取“最新”；checksum 与实际发布快照必须一致。 */
    public record ObjectReference(String objectId, int versionNo, String checksum) {}

    /** id 是数据集内的关系别名；parentPath 是别名路径，relationId 是源对象真实关系身份。 */
    public record Relation(
            String id, List<String> parentPath, String relationId, ObjectReference target) {}

    /** path 是别名路径；不接受客户端指定源类型或物理列名。 */
    public record Field(
            String id, List<String> path, String sourceFieldId, String name, String role) {}

    /** 主对象记录粒度的来源；可复用指标、固定条件及格式保存在同一内容快照的 analysis。 */
    public record Source(
            int schemaVersion,
            ObjectReference root,
            List<Relation> relations,
            List<Field> fields) {}

    /** 解析后的真实字段路径可供后续查询编译使用；此结果本身不构成任何数据读取授权。 */
    public record ResolvedField(
            String id,
            String name,
            String role,
            String type,
            String objectId,
            int objectVersion,
            List<String> relationPath,
            String sourceFieldId) {}

    /** 不返回物理表、SQL 或记录；固定引用按首次使用顺序去重。 */
    public record ResolvedSource(
            Source source, List<ObjectReference> objects, List<ResolvedField> fields) {}
}
