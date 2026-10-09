package com.richuang.os.module.system.legacy.common.context;

import com.richuang.os.module.system.legacy.vo.UserInfoVO;

/**
 * 用户上下文持有者
 * 用于在当前线程中存储和获取当前登录用户信息
 */
public class UserContext {

    private static final ThreadLocal<UserInfoVO> CURRENT_USER = new ThreadLocal<>();

    /**
     * 获取当前用户信息
     */
    public static UserInfoVO getCurrentUser() {
        return CURRENT_USER.get();
    }

    /**
     * 设置当前用户信息
     */
    public static void setCurrentUser(UserInfoVO user) {
        CURRENT_USER.set(user);
    }

    /**
     * 清除当前用户信息
     */
    public static void clear() {
        CURRENT_USER.remove();
    }

    /**
     * 判断是否有当前用户信息
     */
    public static boolean hasUser() {
        return CURRENT_USER.get() != null;
    }
}
