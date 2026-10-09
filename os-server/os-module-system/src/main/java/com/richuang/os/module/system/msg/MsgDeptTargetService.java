package com.richuang.os.module.system.msg;

import com.richuang.os.module.msg.api.*;
import com.richuang.os.module.system.controller.admin.user.vo.user.UserPageReqVO;
import com.richuang.os.module.system.service.user.AdminUserService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <h3>功能说明</h3>
 * <p>指定部门消息目标解析策略。将 {@code DEPT} 类型的目标展开为部门下所有用户的接收者列表。</p>
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>支持 {@code MsgTargetType.DEPT} 类型</li>
 *   <li>查询部门下所有用户并转换为 {@code MsgReceiver} 列表</li>
 * </ul>
 *
 * @author jun
 */
@Service
public class MsgDeptTargetService implements MsgTargetService {

    private final AdminUserService adminUserService;

    public MsgDeptTargetService(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @Override
    public MsgTargetType support() {
        return MsgTargetType.DEPT;
    }

    @Override
    public List<MsgReceiver> getMsgReceivers(MsgTarget target) {
        UserPageReqVO reqVO = new UserPageReqVO();
        reqVO.setPageSize(1000000);
        reqVO.setDeptId(Long.valueOf(target.getTargetId()));
        return adminUserService.getUserPage(reqVO).getList().stream().map(user -> {
            MsgReceiver receiver = new MsgReceiver();
            receiver.setReceiverType(MsgReceiverType.USER);
            receiver.setReceiverId(String.valueOf(user.getId()));
            receiver.setReceiverName(user.getNickname());
            return receiver;
        }).toList();
    }
}
