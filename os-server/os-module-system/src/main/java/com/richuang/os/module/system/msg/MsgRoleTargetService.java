package com.richuang.os.module.system.msg;

import com.richuang.os.module.msg.api.*;
import com.richuang.os.module.system.controller.admin.user.vo.user.UserPageReqVO;
import com.richuang.os.module.system.service.user.AdminUserService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * <h3>功能说明</h3>
 * <p>指定角色消息目标解析策略。将 {@code ROLE} 类型的目标展开为角色下所有用户的接收者列表。</p>
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>支持 {@code MsgTargetType.ROLE} 类型</li>
 *   <li>查询角色下所有用户并转换为 {@code MsgReceiver} 列表</li>
 * </ul>
 *
 * <h3>归正修复</h3>
 * <ul>
 *   <li>P3：补充缺失的 {@code @Resource} 注解，修复 NPE 风险</li>
 * </ul>
 *
 * @author jun
 */
@Service
public class MsgRoleTargetService implements MsgTargetService {

    private final AdminUserService adminUserService;

    public MsgRoleTargetService(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @Override
    public MsgTargetType support() {
        return MsgTargetType.ROLE;
    }

    @Override
    public List<MsgReceiver> getMsgReceivers(MsgTarget target) {
        UserPageReqVO reqVO = new UserPageReqVO();
        reqVO.setPageSize(1000000);
        reqVO.setRoleId(Long.valueOf(target.getTargetId()));
        return adminUserService.getUserPage(reqVO).getList().stream().map(user -> {
            MsgReceiver receiver = new MsgReceiver();
            receiver.setReceiverType(MsgReceiverType.USER);
            receiver.setReceiverId(String.valueOf(user.getId()));
            receiver.setReceiverName(user.getNickname());
            return receiver;
        }).toList();
    }
}
