package com.lingan.ucp.nocode.api;

/** 数据集与仪表板分类目录；省略类别保持数据集旧契约，目录不授予内容权限。 */
public final class ReportFolders {
    private ReportFolders() {}

    public record Item(String id, String parentId, String name, int sortNo, int revision) {}

    public record Save(
            String id,
            int expectedRevision,
            String parentId,
            String name,
            int sortNo,
            String reason,
            String resourceKind) {
        public Save(
                String id,
                int expectedRevision,
                String parentId,
                String name,
                int sortNo,
                String reason) {
            this(id, expectedRevision, parentId, name, sortNo, reason, null);
        }
    }

    public record Delete(String id, int expectedRevision, String reason, String resourceKind) {
        public Delete(String id, int expectedRevision, String reason) {
            this(id, expectedRevision, reason, null);
        }
    }

    public record Deleted(String id, int revision) {}
}
