package com.lingan.ucp.config;

import com.lingan.ucp.module.system.api.dept.DeptApi;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.nocode.api.ObjectSettingsValidator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** 无代码业务复用现有系统模块的身份与组织规则，不复制用户/部门表或校验逻辑。 */
@Configuration
public class NocodePlatformConfiguration {
    /** 对象负责人和所属部门使用底座 ID；空值不触发校验，非空值依次校验用户、部门。 */
    @Bean
    public ObjectSettingsValidator nocodeObjectSettingsValidator(
            AdminUserApi users, DeptApi departments) {
        return (owner, organization) -> {
            if (owner != null && !owner.isBlank()) users.validateUser(Long.parseLong(owner));
            if (organization != null && !organization.isBlank())
                departments.validateDeptList(List.of(Long.parseLong(organization)));
        };
    }
}
