package com.richuang.os.module.system.msg;

import com.richuang.os.module.msg.api.*;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <h3>功能说明</h3>
 * <p>指定用户消息目标解析策略。将 {@code USER} 类型的目标 ID 直接作为接收者。</p>
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>支持 {@code MsgTargetType.USER} 类型</li>
 *   <li>根据用户 ID 查询用户信息并构建接收者</li>
 * </ul>
 *
 * @author jun
 */
@Service
public class MsgUserTargetService implements MsgTargetService {


    @Override
    public MsgTargetType support() {
        return MsgTargetType.USER;
    }

    @Override
    public List<MsgReceiver> getMsgReceivers(MsgTarget target) {
        MsgReceiver receiver = new MsgReceiver();
        receiver.setReceiverType(MsgReceiverType.USER);
        receiver.setReceiverId(target.getTargetId());
        receiver.setReceiverName(target.getTargetName());
        return List.of(receiver);
    }
}
