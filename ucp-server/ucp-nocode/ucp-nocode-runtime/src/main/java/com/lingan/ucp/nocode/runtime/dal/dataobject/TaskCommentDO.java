package com.lingan.ucp.nocode.runtime.dal.dataobject;

import com.baomidou.mybatisplus.annotation.*;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 评论采用纯文本及明确成员ID，通知不自动授予任务权限。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_task_comment", schema = "public")
public class TaskCommentDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;

    private String taskId;
    private String parentId;
    private String content;
    private String mentionedJson;
    private String requestKey;
    private String requestHash;
}
