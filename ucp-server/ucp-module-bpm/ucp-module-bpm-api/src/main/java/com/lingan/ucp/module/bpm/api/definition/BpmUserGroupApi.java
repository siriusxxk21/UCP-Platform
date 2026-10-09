package com.lingan.ucp.module.bpm.api.definition;

import java.util.Collection;

/** 跨模块复用底座用户组有效性校验，不暴露流程模块的持久化对象。 */
public interface BpmUserGroupApi {
    /** 选择器只取得稳定身份、名称和启停状态，不读取流程配置。 */
    record SelectionGroup(String id, String name, Integer status) {}

    java.util.List<SelectionGroup> getSelectionGroups();

    void validateUserGroups(Collection<Long> ids);
}
