package com.lingan.ucp.nocode.work.dal.dataobject.draft;

import com.baomidou.mybatisplus.annotation.*;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 工作输入的持久化记录；creator 同时限定当前版本的个人草稿归属。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_work_draft", schema = "public")
public class WorkDraftDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;

    private String sourceType;
    private String sourceId;
    private String state;
    private Integer lockVersion;
    private String resourceJson;
    private String objectId;
    private String recordId;
    private String baseRecordRevision;
    private String valuesJson;
    private String detailsJson;
    private String relatedJson;
}
