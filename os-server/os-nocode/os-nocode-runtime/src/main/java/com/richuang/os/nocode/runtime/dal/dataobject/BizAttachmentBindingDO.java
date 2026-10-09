package com.richuang.os.nocode.runtime.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.richuang.os.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 业务附件绑定 DO
 *
 * <p>字段值中的 fileId 与网盘文件节点的对应关系；移除附件转 HISTORY，不物理删除内容。 HISTORY 行保留原 entryId 仅作追溯，节点本身已删除。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_biz_attachment_binding", schema = "public")
public class BizAttachmentBindingDO extends BaseDO {
    public static final String STATE_ACTIVE = "ACTIVE";
    public static final String STATE_HISTORY = "HISTORY";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String objectId;
    private String recordId;

    /** 所属内部明细稳定 ID，空串表示主表 */
    private String detailId;

    /** 内部明细行持久 ID，空串表示主表字段 */
    private String rowId;

    private String fieldId;
    private Long fileId;
    private Long entryId;
    private Long spaceId;

    /** 受管网盘节点展示名；绑定时从网盘返回写入，绑定后不可变（受管节点禁止重命名） */
    private String fileName;

    /** 内容大小（字节）；绑定时自文件底座冗余，用于权限过滤后的容量统计 */
    private Long fileSize;

    /** 内容 MIME 类型；仅用于展示 */
    private String mimeType;

    /** ACTIVE 当前有效、HISTORY 已从当前值移除但历史引用仍保留 */
    private String state;

    /** 上传来源入口标识（应用/维护/任务），仅作来源信息，不参与归属判定 */
    private String sourceEntry;
}
