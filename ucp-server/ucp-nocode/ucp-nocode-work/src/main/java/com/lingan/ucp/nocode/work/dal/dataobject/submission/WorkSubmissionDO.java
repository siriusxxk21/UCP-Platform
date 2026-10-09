package com.lingan.ucp.nocode.work.dal.dataobject.submission;

import com.baomidou.mybatisplus.annotation.*;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 一次提交的不可变业务材料；无普通更新服务。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_work_submission", schema = "public")
public class WorkSubmissionDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;

    private String draftId;
    private String idempotencyKey;
    private String requestDigest;
    private String materialJson;
}
