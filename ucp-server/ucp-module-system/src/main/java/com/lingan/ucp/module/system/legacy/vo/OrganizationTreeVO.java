package com.lingan.ucp.module.system.legacy.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 组织树VO
 */
@Data
public class OrganizationTreeVO {

    private String id;

    private String orgCode;

    private String orgName;

    private Integer orgType;

    private String orgTypeName;

    private String parentId;

    private String parentIds;

    private Integer level;

    private String leaderId;

    private String leaderName;

    private String phone;

    private String email;

    private String address;

    private Integer sortOrder;

    private Integer status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;

    /**
     * 子组织列表
     */
    private List<OrganizationTreeVO> children;
}
