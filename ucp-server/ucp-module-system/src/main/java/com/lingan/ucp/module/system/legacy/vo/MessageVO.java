package com.lingan.ucp.module.system.legacy.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息列表VO
 */
@Data
public class MessageVO {

    private String id;

    /**
     * 消息标题
     */
    private String title;

    /**
     * 消息内容摘要
     */
    private String contentSummary;

    /**
     * 消息类型:1-系统公告 2-业务通知 3-个人消息 4-待办提醒
     */
    private Integer messageType;

    /**
     * 优先级:0-普通 1-重要 2-紧急
     */
    private Integer priority;

    /**
     * 发送人ID
     */
    private String senderId;

    /**
     * 发送人姓名
     */
    private String senderName;

    /**
     * 阅读状态:0-未读 1-已读
     */
    private Integer readStatus;

    /**
     * 阅读时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime readTime;

    /**
     * 是否标星
     */
    private Integer isStarred;

    /**
     * 发送时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime sentTime;

    /**
     * 发送类型:1-指定用户 2-全租户 3-按组织 4-按角色 5-按部门
     */
    private Integer sendType;

    /**
     * 总接收人数
     */
    private Integer totalCount;

    /**
     * 已读人数
     */
    private Integer readCount;

    /**
     * 附件列表
     */
    private List<AttachmentVO> attachments;

    /**
     * 附件VO
     */
    @Data
    public static class AttachmentVO {
        private String id;
        private String fileName;
        private Long fileSize;
        private String filePath;
    }
}
