package com.richuang.os.nocode.runtime.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.richuang.os.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 业务附件受保护上传会话 DO
 *
 * <p>保存成功前仅上传者可用；有效期默认 24 小时。普通系统附件不登记会话。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_biz_upload_session", schema = "public")
public class BizUploadSessionDO extends BaseDO {
    public static final String STATE_TEMPORARY = "TEMPORARY";
    public static final String STATE_BINDING = "BINDING";
    public static final String STATE_BOUND = "BOUND";
    public static final String STATE_EXPIRED = "EXPIRED";
    public static final String STATE_CLEANED = "CLEANED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 表单编辑会话标识，客户端生成，同一编辑会话的续期按此批量生效 */
    private String sessionKey;

    private Long userId;
    private String objectId;
    private String fieldId;

    /** 编辑既有记录时的记录标识，仅作来源信息 */
    private String recordId;

    private Long fileId;
    private String fileName;

    /** TEMPORARY 待保存、BINDING 保存事务已占用、BOUND 已绑定、EXPIRED 待清理、CLEANED 已清理 */
    private String state;

    private LocalDateTime expiresAt;

    /** 上传幂等键，同一用户重复提交不重复登记；空串表示不使用幂等 */
    private String idempotencyKey;
}
