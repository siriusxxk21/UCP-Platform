package com.richuang.os.nocode.application.dal.dataobject;

import com.baomidou.mybatisplus.annotation.*;
import com.richuang.os.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 应用头和编辑草稿；公共字段来自底座，发布快照另存不可变版本。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_application", schema = "public")
public class NocodeApplicationDO extends BaseDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String appCode;
    private String appName;
    private String description;

    /** 管理分类独立于不可变发布快照。 */
    private String category;

    private String icon;
    private String status;
    private Integer lockVersion;
    private Integer publishedVersion;
    private String designJson;

    /** 删除审计独立保存，不随恢复或后续编辑覆写。 */
    private java.time.LocalDateTime deletedAt;

    private String deletedBy;
    private String deletedReason;
    private java.time.LocalDateTime restoredAt;
    private String restoredBy;
    private String restoredReason;
    private Boolean recoveryPending;
    private Boolean recoveryNeedsEdit;
}
