package com.lingan.ucp.module.system.msg;

import com.lingan.ucp.module.msg.api.*;
import com.lingan.ucp.module.system.controller.admin.user.vo.user.UserPageReqVO;
import com.lingan.ucp.module.system.service.user.AdminUserService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <h3>功能说明</h3>
 * <p>全体用户消息目标解析策略。将 {@code ALL} 类型的目标展开为所有用户的接收者列表。</p>
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>支持 {@code MsgTargetType.ALL} 类型</li>
 *   <li>查询所有用户并转换为 {@code MsgReceiver} 列表</li>
 * </ul>
 *
 * @author jun
 */
@Service
public class MsgAllTargetService implements MsgTargetService {

    private final AdminUserService adminUserService;

    public MsgAllTargetService(AdminUserService adminUserService) {

        this.adminUserService = adminUserService;
    }

    @Override
    public MsgTargetType support() {
        return MsgTargetType.ALL;
    }

    @Override
    public List<MsgReceiver> getMsgReceivers(MsgTarget target) {
        UserPageReqVO reqVO = new UserPageReqVO();
        reqVO.setPageSize(1000000);
        return adminUserService.getUserPage(reqVO).getList().stream().map(user -> {
            MsgReceiver receiver = new MsgReceiver();
            receiver.setReceiverType(MsgReceiverType.USER);
            receiver.setReceiverId(String.valueOf(user.getId()));
            receiver.setReceiverName(user.getNickname());
            return receiver;
        }).toList();
    }
}
