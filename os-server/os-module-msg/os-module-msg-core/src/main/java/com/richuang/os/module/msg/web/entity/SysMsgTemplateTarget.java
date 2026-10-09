package com.richuang.os.module.msg.web.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.richuang.os.framework.tenant.core.db.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 消息模板默认接收对象，按租户隔离用户或角色配置。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_msg_template_target")
public class SysMsgTemplateTarget extends TenantBaseDO {

    @TableId
    private Long id;

    private Long msgTemplateId;

    private String targetType;

    private String targetId;

    private String targetName;
}
