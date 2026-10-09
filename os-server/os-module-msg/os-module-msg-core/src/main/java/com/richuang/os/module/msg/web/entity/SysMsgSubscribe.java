package com.richuang.os.module.msg.web.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.richuang.os.framework.mybatis.core.dataobject.BaseDO;
import lombok.Data;

/**
 * 消息订阅实体，对应 sys_msg_subscribe 表。
 */
@Data
@TableName("sys_msg_subscribe")
public class SysMsgSubscribe extends BaseDO {

    @TableId
    private Long id;

    private Long msgTemplateId;

    private String userId;

    private String userName;

    private String properties;
}
