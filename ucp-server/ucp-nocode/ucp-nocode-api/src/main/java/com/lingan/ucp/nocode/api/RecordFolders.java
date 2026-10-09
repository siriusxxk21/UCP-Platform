package com.lingan.ucp.nocode.api;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 记录文件夹的接口契约：对象上的文件夹来源配置，以及表单下方嵌入的文件夹浏览。
 *
 * <p>网盘节点编号用 Long；其余 ID 以字符串传输，避免浏览器整数精度丢失。根文件夹自身的编号不出现在任何返回值里：浏览接口里 parentId / targetParentId 为 0
 * 表示「这个文件夹的根」。
 */
public final class RecordFolders {
    private RecordFolders() {}

    /**
     * 字段背后的单值关联：对象主表上以这个字段为关联列、且不是多对多的关系；没有返回 null。
     *
     * <p>单值关联在对象定义里是「一条关系 + 一个关联列字段」：关联列字段的类型可能是整数或文本（按对方主键），也可能是 REFERENCE。
     * 文件夹的「用关联记录的文件夹」与命名模板里的「引用」都按这条关系认，不按字段类型认。
     */
    public static DataCenter.Relation singleRelation(
            DataCenter.Definition definition, String fieldId) {
        if (definition == null || fieldId == null || fieldId.isBlank()) return null;
        for (DataCenter.Relation relation : definition.relations())
            if (relation.sourceDetailId() == null
                    && fieldId.equals(relation.fieldId())
                    && !com.lingan.ucp.nocode.enums.RelationTypeEnum.MANY_TO_MANY.matches(
                            relation.kind())) return relation;
        return null;
    }

    /** 一个来源；回显字段只在读取配置时填写 */
    public record Source(
            String id,
            String objectId,
            String kind,
            String placement,
            String label,
            Long spaceId,
            Long entryId,
            String relationFieldId,
            String targetSourceId,
            String createMode,
            NameTemplate nameTemplate,
            String displayLabel,
            String folderPath,
            String relationName,
            String targetObjectId,
            String targetObjectName,
            String targetLabel,
            String problem) {}

    public record SourceInput(
            String id,
            String kind,
            String placement,
            String label,
            Long spaceId,
            Long entryId,
            String relationFieldId,
            String targetSourceId,
            String createMode,
            NameTemplate nameTemplate) {}

    /** 命名模板；null 表示用记录名称 */
    public record NameTemplate(String separator, List<NamePart> parts) {}

    /** kind = FIELD 时用 fieldId；kind = TEXT 时用 text */
    public record NamePart(String kind, String fieldId, String text) {}

    public record NameField(String fieldId, String name, String type) {}

    public record Backfill(String objectId, String sourceId, String cursor, Integer limit) {}

    public record BackfillResult(
            String cursor,
            boolean done,
            int scanned,
            int created,
            int existing,
            int skipped,
            int failed,
            List<BackfillFailure> failures) {}

    public record BackfillFailure(String recordId, String message) {}

    public record SaveConfig(String objectId, List<SourceInput> sources) {}

    public record Candidate(
            String relationFieldId,
            String relationName,
            String targetObjectId,
            String targetObjectName,
            List<CandidateSource> sources,
            String disabledReason) {}

    public record CandidateSource(String id, String label) {}

    public record OpenQuery(String applicationId, String objectId, String recordId) {}

    public record Opened(List<Tab> tabs) {}

    /**
     * 表单下方的一个页签。writable：凭这条记录能写（能改这条记录，且文件夹已建或可以建）；canWrite：本人在这个页签里能不能写 =
     * writable，或文件夹已建、本人在它上面的网盘有效角色达到可编辑——与服务端写操作的最终判定同一口径（凭记录的角色与本人网盘角色取大；还没建时只有能改这条记录的人能建）。
     */
    public record Tab(
            String sourceId,
            String label,
            String state,
            String message,
            boolean writable,
            boolean canWrite) {}

    /** 凭据四项 + 各操作自己的参数；不用的字段传 null */
    public record EntryQuery(
            String applicationId,
            String objectId,
            String recordId,
            String sourceId,
            Long id,
            Long parentId,
            Long targetParentId,
            List<Long> ids,
            String name,
            Integer limit) {}

    public record UploadQuery(
            String applicationId,
            String objectId,
            String recordId,
            String sourceId,
            Long parentId,
            String fileName,
            String contentType,
            long size) {}

    public record ContentQuery(
            String applicationId, String objectId, String recordId, String sourceId, Long id) {}

    public record Entry(
            Long id,
            Long spaceId,
            Long parentId,
            String name,
            String type,
            Long size,
            String mimeType,
            String role,
            String creator,
            LocalDateTime createTime,
            LocalDateTime updateTime,
            LocalDateTime trashedAt,
            String trashedBy,
            boolean modifiable) {}

    /** 只在控制器内部传递，不序列化给客户端：带着解析好的根，内容流按它重建限定子树 */
    public record Content(
            Long entryId,
            String name,
            String mimeType,
            long size,
            Long length,
            Long spaceId,
            Long rootEntryId,
            String role,
            String originKey) {}
}
