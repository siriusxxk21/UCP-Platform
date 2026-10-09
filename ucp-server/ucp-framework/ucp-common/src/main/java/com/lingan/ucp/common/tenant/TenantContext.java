package com.lingan.ucp.common.tenant;

/**
 * 租户上下文持有者
 * 用于在当前线程中存储和获取租户ID
 */
public class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    /**
     * 获取当前租户ID
     */
    public static String getTenantId() {
        return CURRENT_TENANT.get();
    }

    /**
     * 设置当前租户ID
     */
    public static void setTenantId(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    /**
     * 清除租户ID
     */
    public static void clear() {
        CURRENT_TENANT.remove();
    }

    /**
     * 判断当前是否有租户上下文
     */
    public static boolean hasTenant() {
        return CURRENT_TENANT.get() != null && !CURRENT_TENANT.get().isEmpty();
    }
}
