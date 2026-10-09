package com.lingan.ucp.module.msg.web.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_msg")
public class SysMsg extends BaseDO {

    @TableId
    private Long id;

    /**
     * 消息类型ID
     */
    private Long msgTemplateId;

    private String title;

    private String content;

    private String url;

    private Integer priority;

    /**
     * sourceType
     */
    private String sourceType;

    /**
     * sourceId
     */
    private String sourceId;

    /**
     * 消息发送者姓名ID
     */
    private String ownerId;

    /**
     * 消息发送者姓名
     */
    private String ownerName;

    /**
     * 消息数据
     */
    private String msgData;

    /**
     * properties
     */
    private String properties;

    /**
     * 状态
     */
    private String status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime sendTime;


    @TableField(exist = false)
    private String msgTypeName;


}
