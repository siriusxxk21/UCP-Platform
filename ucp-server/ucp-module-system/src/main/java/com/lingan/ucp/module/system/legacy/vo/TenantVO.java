package com.lingan.ucp.module.system.legacy.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户VO
 */
@Data
public class TenantVO {

    private String id;

    private String tenantCode;

    private String tenantName;

    private Integer tenantType;

    private String tenantTypeName;

    private String contactName;

    private String contactPhone;

    private String contactEmail;

    private String logoUrl;

    private String domain;

    private String isolationStrategy;

    private Integer status;

    private String statusName;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime expireTime;

    private Integer maxUserCount;

    private Long maxStorageSize;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updateTime;
}
