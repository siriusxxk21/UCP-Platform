package com.lingan.ucp.module.system.legacy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户DTO
 */
@Data
public class TenantDTO {

    private String id;
    @NotBlank(message = "租户编码不能为空", groups = {Create.class, Update.class})
    private String tenantCode;
    @NotBlank(message = "租户名称不能为空", groups = {Create.class, Update.class})
    private String tenantName;
    @NotNull(message = "租户类型不能为空", groups = {Create.class, Update.class})
    private Integer tenantType;
    private String contactName;
    private String contactPhone;
    private String contactEmail;
    private String logoUrl;
    private String domain;
    private String isolationStrategy;
    private Integer status;
    private LocalDateTime expireTime;
    private Integer maxUserCount;
    private Long maxStorageSize;
    /**
     * 管理员账号
     */
    @NotBlank(message = "管理员账号不能为空", groups = Create.class)
    private String adminUsername;
    /**
     * 管理员密码
     */
    @NotBlank(message = "管理员密码不能为空", groups = Create.class)
    private String adminPassword;

    // ========== 租户管理员信息（仅创建时需要） ==========
    /**
     * 管理员昵称
     */
    private String adminNickname;
    /**
     * 管理员手机
     */
    private String adminPhone;
    /**
     * 管理员邮箱
     */
    private String adminEmail;

    /**
     * 新增操作验证分组
     */
    public interface Create {
    }

    /**
     * 更新操作验证分组
     */
    public interface Update {
    }
}
