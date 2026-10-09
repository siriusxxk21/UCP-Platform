package com.lingan.ucp.module.system.legacy.dto;

import lombok.Data;

/**
 * 租户查询DTO
 */
@Data
public class TenantQueryDTO {

    private String tenantCode;

    private String tenantName;

    private Integer tenantType;

    private Integer status;

    private Integer pageNum = 1;

    private Integer pageSize = 10;
}
