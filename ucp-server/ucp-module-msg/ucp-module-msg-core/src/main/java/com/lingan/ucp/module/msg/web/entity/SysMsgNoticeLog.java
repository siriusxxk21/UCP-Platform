package com.lingan.ucp.module.msg.web.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 消息通知日志实体，对应 sys_msg_notice_log 表。
 */
@Data
@TableName("sys_msg_notice_log")
public class SysMsgNoticeLog {

    @TableId
    private Long id;

    private Long noticeId;

    private String noticeChannel;

    private String channelInfo;

    private String logInfo;

    private Date logTime;
}
