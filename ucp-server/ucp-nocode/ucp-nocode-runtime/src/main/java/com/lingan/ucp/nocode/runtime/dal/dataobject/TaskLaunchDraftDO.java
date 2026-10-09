package com.lingan.ucp.nocode.runtime.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 私有任务编排草稿；只保存待发布命令，不占用业务工作草稿或创建运行任务。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_task_launch_draft", schema = "public")
public class TaskLaunchDraftDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;

    private Integer lockVersion;
    private String contentJson;

    /** 发布后保留任务和请求键，断网重试也只能恢复同一次发起。 */
    private String publishedTaskId;

    private String publishKey;
}
