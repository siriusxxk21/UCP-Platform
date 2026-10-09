package com.richuang.os.nocode.api;

/** 数据对象负责人/组织的底座接入点，正式启动模块委托现有用户与部门 API 校验。 */
public interface ObjectSettingsValidator {
    void validate(String ownerId, String organizationId);
}
