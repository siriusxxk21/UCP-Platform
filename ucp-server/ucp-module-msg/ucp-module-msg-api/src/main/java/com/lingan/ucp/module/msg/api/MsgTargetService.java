package com.lingan.ucp.module.msg.api;

import java.util.List;

public interface MsgTargetService {
    MsgTargetType support();

    List<MsgReceiver> getMsgReceivers(MsgTarget target);
}
