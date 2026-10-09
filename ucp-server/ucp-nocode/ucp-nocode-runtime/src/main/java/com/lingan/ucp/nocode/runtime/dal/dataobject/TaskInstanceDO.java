package com.lingan.ucp.nocode.runtime.dal.dataobject;

import com.baomidou.mybatisplus.annotation.*;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 工作节点持久化；层级和依赖分离，业务内容仅保留公共记录引用。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_task_instance", schema = "public")
public class TaskInstanceDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;

    private String rootId;
    private String parentId;
    private String title;
    private Long assigneeId;
    private String status;
    private String kind;
    private Integer lockVersion;

    /** 计划独立修订，改派或计划变更递增；不复用执行状态修订。 */
    private Integer scheduleVersion;

    private String configJson;
    private String businessJson;
    private String projectJson;

    /** 仅根任务保存服务端批准的数据授权快照，子节点不接受独立授权。 */
    private String authorizationJson;

    /** 整棵任务显式选择的应用归属；空值兼容主业务/项目推导，反馈入口不决定归属。 */
    private String applicationId;

    private String templateId;
    private Integer templateVersion;

    /** 实例节点对应的模板原始标识；包装根和实例运行时新增节点为空。 */
    private String templateNodeId;

    private LocalDateTime t0;

    /** 新协议的显式计划起点；不复用创建时刻。 */
    private LocalDateTime plannedStart;

    private LocalDateTime baselineStart;
    private LocalDateTime baselineEnd;
    private LocalDateTime expectedStart;
    private LocalDateTime expectedEnd;
    private LocalDateTime actualStart;
    private LocalDateTime actualEnd;
    private String requestKey;
    private String requestHash;

    @TableField(exist = false)
    private String explicitLinkId;

    @TableField(exist = false)
    private LocalDateTime lastHandledAt;

    /** 个人树查询的直属显示下级数，不改变实际父子关系。 */
    @TableField(exist = false)
    private Integer matchingChildCount;

    /** 与个人树显示下级同一权限范围内的成功完成数，不计隐藏中间节点。 */
    @TableField(exist = false)
    private Integer completedChildCount;
}
