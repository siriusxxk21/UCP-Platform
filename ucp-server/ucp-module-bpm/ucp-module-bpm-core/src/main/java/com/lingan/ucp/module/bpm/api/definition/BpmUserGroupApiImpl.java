package com.lingan.ucp.module.bpm.api.definition;

import com.lingan.ucp.module.bpm.service.definition.BpmUserGroupService;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.util.Collection;

/** 直接委托现有用户组服务，沿用启停状态和统一业务错误。 */
@Service
public class BpmUserGroupApiImpl implements BpmUserGroupApi {
    @Resource private BpmUserGroupService userGroups;

    @Override
    public java.util.List<SelectionGroup> getSelectionGroups() {
        return userGroups.getUserGroupListByStatus(null).stream()
                .map(g -> new SelectionGroup(g.getId().toString(), g.getName(), g.getStatus()))
                .toList();
    }

    @Override
    public void validateUserGroups(Collection<Long> ids) {
        userGroups.validUserGroups(ids);
    }
}
