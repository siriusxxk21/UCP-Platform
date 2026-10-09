package com.lingan.ucp.module.msg.core;

import com.lingan.ucp.module.msg.web.entity.SysMsgNotice;
import lombok.Data;

import java.util.Objects;

/**
 * <h3>功能说明</h3>
 * <p>消息通知任务，继承 {@code MsgSendTask}，增加通知接收者信息。</p>
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>携带通知接收者（{@code receiverType + receiverId + receiverName}）</li>
 *   <li>{@code equals}：以 noticeId 唯一标识通知任务</li>
 * </ul>
 *
 * @author jun
 */
@Data
public class MsgNoticeTask extends MsgSendTask {

    private SysMsgNotice notice;

//    @Override
//    public boolean equals(Object o) {
//        if (this == o) return true;
//        if (o == null || getClass() != o.getClass()) return false;
//        MsgNoticeTask that = (MsgNoticeTask) o;
//        return Objects.equals(this.noticeId, that.noticeId);
//    }
}
