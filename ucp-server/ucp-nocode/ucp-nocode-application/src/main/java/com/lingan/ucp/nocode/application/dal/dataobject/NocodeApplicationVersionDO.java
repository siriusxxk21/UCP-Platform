package com.lingan.ucp.nocode.application.dal.dataobject;

import com.baomidou.mybatisplus.annotation.*;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 不可变应用发布快照；回退只切换运行指针，不覆盖历史内容。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_application_version", schema = "public")
public class NocodeApplicationVersionDO extends BaseDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long applicationId;
    private Integer versionNo;
    private String definitionJson;
    private String checksum;
    private String reason;
}
