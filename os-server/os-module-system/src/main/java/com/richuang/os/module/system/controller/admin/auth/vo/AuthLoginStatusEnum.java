package com.richuang.os.module.system.controller.admin.auth.vo;

/**
 * 登录阶段状态，用于区分正式登录与仅允许强制改密的预认证状态。
 */
public enum AuthLoginStatusEnum {

    SUCCESS,
    PASSWORD_CHANGE_REQUIRED

}
