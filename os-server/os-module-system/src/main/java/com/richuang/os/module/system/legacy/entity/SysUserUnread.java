package com.richuang.os.module.system.legacy.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户未读消息统计表
 */
@Data
@TableName("sys_user_unread")
public class SysUserUnread {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 租户ID
     */
    private String tenantId;

    /**
     * 未读消息总数
     */
    private Integer unreadCount;

    /**
     * 系统公告未读数
     */
    private Integer unreadSystem;

    /**
     * 业务通知未读数
     */
    private Integer unreadBusiness;

    /**
     * 个人消息未读数
     */
    private Integer unreadPersonal;

    /**
     * 待办提醒未读数
     */
    private Integer unreadTodo;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updateTime;
}
