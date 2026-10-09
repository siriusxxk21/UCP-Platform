package com.richuang.os.module.msg.web.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.richuang.os.framework.mybatis.core.dataobject.BaseDO;
import lombok.Data;

import java.util.Date;

/**
 * 消息通知实体，对应 sys_msg_notice 表。
 */
@Data
@TableName("sys_msg_notice")
public class SysMsgNotice {

    @TableId
    private Long id;

    private Long msgId;

    private String receiverType;

    private String receiverId;

    private String receiverName;

    private String status;

    private String noticeChannel;

    private Date noticeTime;

    private String noticeData;

    private Integer hasRead;

    private Date readTime;
}
