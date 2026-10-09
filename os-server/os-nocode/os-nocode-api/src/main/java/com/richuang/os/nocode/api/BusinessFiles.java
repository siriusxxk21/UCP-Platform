package com.richuang.os.nocode.api;

import java.util.List;

/**
 * 业务文件浏览读模型
 *
 * <p>网盘业务文件入口与服务端统一契约：全部结果在单业务入口（应用或数据维护上下文）内 先按完整业务授权链过滤后再返回；目录名称按当前访问者授权重新解析，名称来源字段或
 * 关联记录不可读时只返回受限标签，不返回后台保存时的目录名称。
 */
public final class BusinessFiles {

    private BusinessFiles() {}

    /** 入口选择：applicationId 为空表示数据维护上下文（另行校验对象管理权限） */
    public record EntryQuery(String applicationId) {}

    /**
     * 目录浏览位置
     *
     * <p>位置由规则版本、分组键与记录/明细身份逐级限定：未给 ruleVersion 时列出规则版本节点； 分组键按层级顺序提供，受限分组使用服务端令牌；recordId
     * 为记录目录；detailId/rowId 逐级进入明细区与明细行。pageNo/pageSize 只作用于可增长的层级（分组、记录、明细行）。
     */
    public record DirectoryQuery(
            String applicationId,
            String objectId,
            Integer ruleVersion,
            List<String> groupKeys,
            String recordId,
            String detailId,
            String rowId,
            int pageNo,
            int pageSize) {}

    /**
     * 文件列表与按文件名搜索
     *
     * <p>过滤条件与目录浏览一致；fieldId 限定单个附件字段，search 按文件名模糊匹配。 不提供位置条件时在入口可见范围内搜索全部业务文件。
     */
    public record FileQuery(
            String applicationId,
            String objectId,
            Integer ruleVersion,
            List<String> groupKeys,
            String recordId,
            String detailId,
            String rowId,
            String fieldId,
            String search,
            int pageNo,
            int pageSize) {}

    /**
     * 内容读取位置
     *
     * <p>文件必须来自已授权浏览返回的结果：objectId 与记录/明细/字段位置共同限定绑定位，entryId 为网盘节点身份； 不提供按 fileId
     * 或节点编号单独读取的入口，位置任一项在当前入口授权下不可见即拒绝。
     */
    public record ContentQuery(
            String applicationId,
            String objectId,
            String recordId,
            String detailId,
            String rowId,
            String fieldId,
            Long entryId) {}

    /**
     * 收藏切换位置
     *
     * <p>位置身份与内容读取一致：先按完整授权链重验可见绑定，再写当前用户的收藏标记； 标记按用户独立保存，不改变文件对任何人的可见性，也不作为读取依据。
     */
    public record FavoriteQuery(
            String applicationId,
            String objectId,
            String recordId,
            String detailId,
            String rowId,
            String fieldId,
            Long entryId,
            Boolean favorite) {}

    /**
     * 收藏/最近访问列表查询
     *
     * <p>applicationId 为空表示数据维护上下文；markType 取 FAVORITE 或 RECENT。 结果只列出当前仍可见的文件，名称、位置与数量都按入口授权重新解析。
     */
    public record MarkQuery(String applicationId, String objectId, String markType) {}

    /**
     * 受权内容元信息
     *
     * <p>名称与 MIME 类型以受管节点记录为准；length 为存储侧确认的内容长度，无法确认时为 null， 调用方按 size 降级处理 Range 与内容长度响应头。
     */
    public record Content(
            Long entryId, Long fileId, String name, long size, String mimeType, Long length) {}

    /**
     * 临时上传（受保护内容 + 上传会话登记）
     *
     * <p>位置身份与保存绑定保持一致：applicationId 为空表示数据维护上下文；recordId 为空表示新增记录； detailId 为空表示主表字段，否则为内部明细字段。
     * sessionKey 由表单编辑会话生成，同一会话内的临时文件在保存前仅上传者可读。
     */
    public record UploadQuery(
            String applicationId,
            String objectId,
            String recordId,
            String detailId,
            String fieldId,
            String sessionKey,
            String idempotencyKey,
            String fileName,
            String contentType,
            long size) {}

    /** 临时上传结果：fileId 写入业务字段值；保存成功后由服务端建立网盘节点与目录 */
    public record Uploaded(Long fileId, String name, long size, String mimeType) {}

    /**
     * 临时上传内容读取位置
     *
     * <p>只能读取本人上传会话内的临时文件：会话键、对象、字段与文件编号必须与登记一致； 本入口不按 fileId 提供业务文件读取能力。
     */
    public record TemporaryQuery(String objectId, String fieldId, String sessionKey, Long fileId) {}

    /** 临时上传内容元信息：entryId 尚未建立，读取依据是上传会话归属 */
    public record TemporaryContent(
            Long fileId, String name, long size, String mimeType, Long length) {}

    /**
     * 入口内一个业务空间
     *
     * <p>对象与业务空间按规则多对一：多个对象可绑定同一空间（按业务领域建空间，不按应用重复建）。 数量与大小均为当前入口授权过滤后的可见值；currentRuleVersion
     * 为当前发布版本， 新上传记录固定使用该版本规则。
     */
    public record Space(
            String objectId,
            String objectName,
            String spaceName,
            List<String> fixedPath,
            Integer currentRuleVersion,
            int fileCount,
            long totalSize,
            int recordCount) {}

    /**
     * 目录项
     *
     * <p>kind 取值：VERSION 规则版本根、GROUP 业务分组、RECORD 记录目录、 FIELD 附件字段目录、REGION 明细区目录、ROW 明细行目录。 GROUP
     * 项以 groupKey 作为下一层导航键；RECORD 项以 recordId 进入记录内目录。 restricted 为 true 表示名称来源字段/关联不可读，label
     * 为受限可区分标签。
     */
    public record Directory(
            String kind,
            Integer ruleVersion,
            String groupKey,
            String recordId,
            String detailId,
            String rowId,
            String fieldId,
            String label,
            boolean restricted,
            int fileCount,
            long totalSize,
            int recordCount,
            String labelStatus) {
        /** 兼容不涉及记录标题状态的目录构造。 */
        public Directory(
                String kind,
                Integer ruleVersion,
                String groupKey,
                String recordId,
                String detailId,
                String rowId,
                String fieldId,
                String label,
                boolean restricted,
                int fileCount,
                long totalSize,
                int recordCount) {
            this(
                    kind,
                    ruleVersion,
                    groupKey,
                    recordId,
                    detailId,
                    rowId,
                    fieldId,
                    label,
                    restricted,
                    fileCount,
                    totalSize,
                    recordCount,
                    (restricted
                                    ? com.richuang.os.nocode.enums.BusinessFileLabelStatusEnum
                                            .RESTRICTED
                                    : com.richuang.os.nocode.enums.BusinessFileLabelStatusEnum
                                            .NORMAL)
                            .getCode());
        }
    }

    /**
     * 文件项
     *
     * <p>文件身份为 fileId（表单字段值）与 entryId（网盘节点，预览/下载/定位用）； 关系身份为对象 + 记录 + 明细区/行 + 字段。recordLabel
     * 为按当前访问者授权解析的记录名；submitter 为登记该附件的保存提交人，不作为文件读取的授权依据。
     */
    public record File(
            Long fileId,
            Long entryId,
            Long spaceId,
            String name,
            long size,
            String mimeType,
            String recordId,
            String recordLabel,
            boolean recordRestricted,
            String detailId,
            String rowId,
            String fieldId,
            String submitter,
            String uploadedAt,
            String recordLabelStatus,
            String fieldLabel,
            String detailLabel,
            String rowLabel) {}

    /** 对象设计器的业务空间选项；发布规则应保存 id，name 仅展示。 */
    public record ConfigSpace(Long id, String name, Integer status) {}
}
