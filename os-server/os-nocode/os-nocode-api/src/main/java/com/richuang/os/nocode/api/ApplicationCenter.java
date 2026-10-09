package com.richuang.os.nocode.api;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 应用配置与不可变发布契约。业务数据始终留在全局对象中。 */
public final class ApplicationCenter {
    private ApplicationCenter() {}

    /** 引用固定发布版本和校验和；更新对象需显式同步应用草稿。 */
    public record ObjectReference(String objectId, int versionNo, String checksum) {}

    /** 稳定资源身份与编码分离；config 按 kind 由对应服务严格校验。 */
    public record Resource(
            String id, String kind, String code, String name, Map<String, Object> config) {}

    public record Definition(List<ObjectReference> objects, List<Resource> resources) {
        public static Definition empty() {
            return new Definition(List.of(), List.of());
        }
    }

    public record Save(
            String id,
            Integer expectedRevision,
            String code,
            String name,
            String description,
            String icon,
            Definition definition,
            String category) {
        /** 兼容省略分类的既有调用。 */
        public Save(
                String id,
                Integer expectedRevision,
                String code,
                String name,
                String description,
                String icon,
                Definition definition) {
            this(id, expectedRevision, code, name, description, icon, definition, null);
        }
    }

    public record Revision(String id, int expectedRevision, String reason) {}

    /** 恢复指定历史配置并生成新的发布版本，不回退数据或成员授权。 */
    public record Restore(String id, int expectedRevision, int sourceVersion, String reason) {}

    public record Row(
            String id,
            String code,
            String name,
            String description,
            String icon,
            String status,
            int revision,
            Integer publishedVersion,
            LocalDateTime updateTime,
            String category,
            boolean recoveryPending,
            boolean recoveryNeedsEdit) {
        public Row(
                String id,
                String code,
                String name,
                String description,
                String icon,
                String status,
                int revision,
                Integer publishedVersion,
                LocalDateTime updateTime,
                String category) {
            this(
                    id,
                    code,
                    name,
                    description,
                    icon,
                    status,
                    revision,
                    publishedVersion,
                    updateTime,
                    category,
                    false,
                    false);
        }

        /** 兼容省略分类的既有调用。 */
        public Row(
                String id,
                String code,
                String name,
                String description,
                String icon,
                String status,
                int revision,
                Integer publishedVersion,
                LocalDateTime updateTime) {
            this(
                    id,
                    code,
                    name,
                    description,
                    icon,
                    status,
                    revision,
                    publishedVersion,
                    updateTime,
                    null);
        }
    }

    /** 回收站只展示应用头及删除审计，不开放运行资源。 */
    public record RecycleRow(
            String id,
            String code,
            String name,
            String description,
            String icon,
            String status,
            int revision,
            Integer publishedVersion,
            LocalDateTime updateTime,
            String category,
            boolean recoveryPending,
            boolean recoveryNeedsEdit,
            LocalDateTime deletedAt,
            String deletedBy,
            String deletedByName) {}

    /** 删除前展示影响范围；提交删除时仍须在锁内重新检查阻断条件。 */
    public record DeletePreview(
            Row application,
            int objectCount,
            int resourceCount,
            int taskEntryCount,
            List<String> blockers) {}

    public record Release(
            int versionNo,
            String checksum,
            String reason,
            String creator,
            LocalDateTime createTime) {}

    /** 应用名称等呈现配置随发布冻结，不能从可变应用头直接带入运行端。 */
    public record Snapshot(
            String code, String name, String description, String icon, Definition definition) {}

    /** 固定版本与对象当前结构的兼容差异；草稿允许保留，应用发布与运行前必须处理。 */
    public record ObjectIssue(
            String objectId,
            String objectName,
            int versionNo,
            int latestVersionNo,
            List<String> messages) {}

    /** 发布记录由 releases 分页接口按需查询，草稿读写不再附带全量版本快照。 */
    public record Detail(Row application, Definition draft, List<ObjectIssue> issues) {}

    /** 与发布绑定的运行契约，不包含未发布草稿；warnings 只提示固定版本与对象结构的差异。 */
    public record Published(
            Row application,
            int versionNo,
            String checksum,
            Definition definition,
            List<String> warnings) {}
}
