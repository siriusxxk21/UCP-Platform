package com.lingan.ucp.module.system.legacy.dto;

import lombok.Data;

/**
 * 消息查询DTO
 */
@Data
public class MessageQueryDTO {

    /**
     * 当前页
     */
    private Integer pageNum = 1;

    /**
     * 每页大小
     */
    private Integer pageSize = 10;

    /**
     * 消息类型:1-系统公告 2-业务通知 3-个人消息 4-待办提醒
     */
    private Integer messageType;

    /**
     * 阅读状态:0-未读 1-已读
     */
    private Integer readStatus;

    /**
     * 是否标星
     */
    private Integer isStarred;

    /**
     * 是否在回收站
     */
    private Integer isDeleted;

    /**
     * 消息标题(模糊查询)
     */
    private String title;

    /**
     * 发送人名称
     */
    private String senderName;

    /**
     * 开始时间
     */
    private String startTime;

    /**
     * 结束时间
     */
    private String endTime;
}
