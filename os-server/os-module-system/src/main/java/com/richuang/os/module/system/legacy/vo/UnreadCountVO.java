package com.richuang.os.module.system.legacy.vo;

import lombok.Data;

/**
 * 未读消息统计VO
 */
@Data
public class UnreadCountVO {

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
}
