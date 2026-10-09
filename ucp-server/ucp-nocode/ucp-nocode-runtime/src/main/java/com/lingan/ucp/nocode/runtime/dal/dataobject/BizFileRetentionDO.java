package com.lingan.ucp.nocode.runtime.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文件保留引用 DO
 *
 * <p>历史修订/工作草稿/提交材料等持有者对文件的登记；有有效引用的文件不进入物理清理。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_biz_file_retention", schema = "public")
public class BizFileRetentionDO extends BaseDO {
    public static final String HOLDER_RECORD_HISTORY = "RECORD_HISTORY";
    public static final String HOLDER_WORK_DRAFT = "WORK_DRAFT";
    public static final String HOLDER_WORK_SUBMISSION = "WORK_SUBMISSION";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long fileId;

    /** 持有者类型：RECORD_HISTORY 记录历史、WORK_DRAFT 工作草稿、WORK_SUBMISSION 提交材料 */
    private String holderType;

    private String holderId;
    private String objectId;
    private String recordId;
}
