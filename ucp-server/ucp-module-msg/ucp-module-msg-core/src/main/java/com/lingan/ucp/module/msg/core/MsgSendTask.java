package com.lingan.ucp.module.msg.core;

import com.lingan.ucp.module.msg.api.MsgTarget;
import com.lingan.ucp.module.msg.web.entity.SysMsg;
import com.lingan.ucp.module.msg.web.entity.SysMsgTargets;
import com.lingan.ucp.module.msg.web.entity.SysMsgTemplate;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

/**
 * <h3>功能说明</h3>
 * <p>消息发送任务，作为 {@code DelayQueue} 的元素，支持按发送时间延迟排序。</p>
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>实现 {@code Delayed} 接口，按 msgTime + DELAY_TIME 计算延迟</li>
 *   <li>{@code compareTo}：先按延迟时间排序，相同延迟按优先级（priority）排序</li>
 *   <li>{@code equals}：以 msgId 唯一标识任务</li>
 * </ul>
 *
 * @author jun
 */
@Data
public class MsgSendTask implements Delayed {

    private SysMsg msg;

    private List<MsgTarget> targets;

    private SysMsgTemplate msgTemplate;

    private Map<String, Object> properties;

    public long getDelay(TimeUnit unit) {
        Object delay = 0;
        if (properties != null && properties.containsKey("DELAY_TIME")) {
            delay = properties.get("DELAY_TIME");
        }
        return Long.parseLong(delay.toString());
    }

    @Override
    public int compareTo(Delayed o) {
        if (this.getDelay(TimeUnit.MILLISECONDS) > o.getDelay(TimeUnit.MILLISECONDS)) {
            return 1;
        } else if (this.getDelay(TimeUnit.MILLISECONDS) < o.getDelay(TimeUnit.MILLISECONDS)) {
            return -1;
        } else {
            MsgSendTask _o = (MsgSendTask)o;
            return this.getMsg().getPriority() - _o.getMsg().getPriority();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MsgSendTask that = (MsgSendTask) o;
        return Objects.equals(this.getMsg().getId(), that.getMsg().getId());
    }
}
