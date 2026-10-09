package com.richuang.os.module.system.legacy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 消息发送DTO
 */
@Data
public class MessageDTO {

    private String id;

    /**
     * 消息标题
     */
    @NotBlank(message = "消息标题不能为空")
    private String title;

    /**
     * 消息内容(富文本)
     */
    private String content;

    /**
     * 消息类型:1-系统公告 2-业务通知 3-个人消息 4-待办提醒
     */
    @NotNull(message = "消息类型不能为空")
    private Integer messageType;

    /**
     * 优先级:0-普通 1-重要 2-紧急
     */
    private Integer priority;

    /**
     * 发送类型:1-指定用户 2-全租户 3-按组织 4-按角色 5-按部门
     */
    @NotNull(message = "发送类型不能为空")
    private Integer sendType;

    /**
     * 发送目标ID列表
     */
    private List<String> sendTargetIds;

    /**
     * 附件ID列表
     */
    private List<String> attachmentIds;

    /**
     * 定时发送时间
     */
    private String scheduledTime;

    /**
     * 是否立即发送
     */
    private Boolean sendNow = true;
}
