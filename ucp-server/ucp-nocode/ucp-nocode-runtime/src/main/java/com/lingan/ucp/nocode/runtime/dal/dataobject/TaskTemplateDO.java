package com.lingan.ucp.nocode.runtime.dal.dataobject;

import com.baomidou.mybatisplus.annotation.*;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 模板草稿与发布指针；发布节点另外封存在版本表。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_task_template", schema = "public")
public class TaskTemplateDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;

    private String name;
    private String description;
    private String kind;
    private Integer lockVersion;
    private Integer publishedVersion;

    /** 默认发起版本，与单调递增的最新发布序号分离。 */
    private Integer primaryVersion;

    private String nodesJson;

    /** 新协议独立保存总任务，旧模板保持空值，不能把第一个节点误认成总任务。 */
    private String rootJson;

    private String authorizationJson;
}
