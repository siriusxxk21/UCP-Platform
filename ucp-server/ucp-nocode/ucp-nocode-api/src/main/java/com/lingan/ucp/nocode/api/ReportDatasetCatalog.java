package com.lingan.ucp.nocode.api;

import java.util.List;

/** 编辑器的结构发现投影；不返回物理表列、对象草稿或业务记录。 */
public final class ReportDatasetCatalog {
    private ReportDatasetCatalog() {}

    public record ObjectItem(String id, String code, String name, Integer publishedVersion) {}

    public record Field(String id, String code, String name, String type, boolean measure) {}

    public record Relation(String id, String name, String fieldId, String targetObjectId) {}

    public record ObjectVersion(
            ReportDatasets.ObjectReference reference,
            String name,
            List<Field> fields,
            List<Relation> relations) {}

    /** 对象共享管理员只发现授权目标摘要，不因此获得该数据集的草稿或取数权限。 */
    public record AuthorizationTarget(
            String id, String name, String status, List<ReportDatasets.ObjectReference> sources) {}

    /** 仅投影授权所需的当前字段；对象失效时保留 ID 和说明，以便撤销历史上限。 */
    public record AuthorizationObject(
            String id, String name, ObjectVersion definition, String unavailableReason) {}
}
