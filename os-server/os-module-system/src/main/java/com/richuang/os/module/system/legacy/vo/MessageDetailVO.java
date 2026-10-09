package com.richuang.os.module.system.legacy.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 消息详情VO
 */
@Data
public class MessageDetailVO {

    private String id;

    /**
     * 消息标题
     */
    private String title;

    /**
     * 消息内容(富文本)
     */
    private String content;

    /**
     * 内容类型: HTML/STRUCTURED
     */
    private String contentType;

    /**
     * 结构化内容(JSON结构)
     */
    private Object contentStructure;

    /**
     * 变量快照
     */
    private Map<String, Object> variableSnapshot;

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
     * 发送类型:1-指定用户 2-全租户 3-按组织 4-按角色 5-按部门
     */
    private Integer sendType;

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
     * 附件列表
     */
    private List<MessageVO.AttachmentVO> attachments;
}
