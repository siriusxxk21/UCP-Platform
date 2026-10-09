package com.richuang.os.module.msg.web.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 消息目标实体，对应 sys_msg_targets 表。
 */
@Data
@TableName("sys_msg_targets")
public class SysMsgTargets {

    @TableId
    private Long id;

    private Long msgId;

    private String targetType;

    private String targetId;

    private String targetName;

}
