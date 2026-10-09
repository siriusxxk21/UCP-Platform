package com.richuang.os.module.msg.api;

import lombok.Data;

import java.util.Objects;

/**
 * 消息接收者 DTO，封装接收者类型、ID 和名称。
 *
 * <p>通过 {@code equals()} 和 {@code hashCode()} 按类型与 ID 标识同一接收者，名称变化不影响去重。
 *
 * @author jun
 */
@Data
public class MsgReceiver {

    private MsgReceiverType receiverType;

    private String receiverId;

    private String receiverName;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MsgReceiver that = (MsgReceiver) o;
        return receiverType == that.receiverType && Objects.equals(receiverId, that.receiverId);
    }

    /** 与接收者唯一标识保持一致，确保集合按类型和 ID 正确去重。 */
    @Override
    public int hashCode() {
        return Objects.hash(receiverType, receiverId);
    }
}
