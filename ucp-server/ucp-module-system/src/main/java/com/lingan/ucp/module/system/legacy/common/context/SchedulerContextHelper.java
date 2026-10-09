package com.lingan.ucp.module.system.legacy.common.context;

import com.lingan.ucp.common.tenant.TenantContext;
import com.lingan.ucp.module.system.legacy.vo.UserInfoVO;
import lombok.extern.slf4j.Slf4j;

/**
 * 定时任务上下文辅助工具（已废弃 - v2.0 统一认证迁移）
 * <p>
 * 新版 Job 体系通过 TenantJob/TenantIgnore 注解 + TenantContextHolder 处理租户上下文，
 * 不再需要手动设置 UserContext 和 TenantContext。
 *
 * <p>原使用方式（已废弃）：
 * <pre>
 * SchedulerContextHelper.runWithTenantContext(tenantId, () -> {
 *     // 业务逻辑，此时 TenantContext / UserContext 已就绪
 * });
 * </pre>
 */
@Slf4j
@Deprecated
public class SchedulerContextHelper {

    /**
     * 系统用户 ID，用于标识由定时任务/系统自动产生的操作
     */
    public static final String SYSTEM_USER_ID = "-1";

    /**
     * 系统用户名
     */
    public static final String SYSTEM_USERNAME = "system";

    private SchedulerContextHelper() {
    }

    /**
     * 在指定租户上下文中执行逻辑，执行完毕后自动清理
     *
     * @param tenantId 租户ID
     * @param action   要执行的逻辑
     */
    public static void runWithTenantContext(String tenantId, Runnable action) {
        TenantContext.setTenantId(tenantId);
        UserContext.setCurrentUser(buildSystemUser(tenantId));
        try {
            action.run();
        } finally {
            UserContext.clear();
            TenantContext.clear();
        }
    }

    /**
     * 构建系统用户对象
     *
     * @param tenantId 租户ID
     * @return 系统用户 UserInfoVO
     */
    private static UserInfoVO buildSystemUser(String tenantId) {
        UserInfoVO user = new UserInfoVO();
        user.setId(SYSTEM_USER_ID);
        user.setUsername(SYSTEM_USERNAME);
        user.setNickname("系统");
        user.setTenantId(tenantId);
        user.setUserType(3); // 系统管理员
        return user;
    }
}
