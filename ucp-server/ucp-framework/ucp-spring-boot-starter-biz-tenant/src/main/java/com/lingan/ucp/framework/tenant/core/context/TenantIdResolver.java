package com.lingan.ucp.framework.tenant.core.context;

import cn.hutool.core.lang.Assert;
import com.lingan.ucp.framework.security.core.util.SecurityFrameworkUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 解析当前业务租户编号，兼容启用多租户和关闭多租户的部署模式。
 *
 * <p>单租户模式统一使用配置的默认租户编号；多租户模式严格使用请求上下文或登录令牌。</p>
 */
@Component
public class TenantIdResolver {

    private final boolean tenantEnabled;
    private final long defaultTenantId;

    public TenantIdResolver(@Value("${os.tenant.enable:true}") boolean tenantEnabled,
                            @Value("${os.tenant.default-tenant-id:1}") long defaultTenantId) {
        this.tenantEnabled = tenantEnabled;
        this.defaultTenantId = defaultTenantId;
    }

    public Long getRequiredTenantId() {
        if (!tenantEnabled) {
            Assert.isTrue(defaultTenantId > 0, "单租户默认租户编号必须大于0");
            return defaultTenantId;
        }
        Long tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            tenantId = SecurityFrameworkUtils.getLoginUserTenantId();
        }
        Assert.notNull(tenantId, "无法确定当前租户，请重新登录");
        return tenantId;
    }

    public boolean isTenantEnabled() {
        return tenantEnabled;
    }
}
