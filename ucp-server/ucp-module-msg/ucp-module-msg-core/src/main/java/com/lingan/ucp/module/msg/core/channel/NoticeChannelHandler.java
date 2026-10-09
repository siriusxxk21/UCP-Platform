package com.lingan.ucp.module.msg.core.channel;

import com.alibaba.fastjson.JSONObject;
import com.lingan.ucp.module.msg.core.MsgNoticeTask;

/**
 * 消息通知类型
 */
public interface NoticeChannelHandler {

    NoticeChannel support();

    void execute(MsgNoticeTask noticeTask, JSONObject template) throws NoticeException;

}
