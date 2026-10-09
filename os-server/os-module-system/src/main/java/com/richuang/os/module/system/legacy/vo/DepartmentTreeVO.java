package com.richuang.os.module.system.legacy.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 部门树VO
 */
@Data
public class DepartmentTreeVO {

    private String id;

    private String orgId;

    private String orgName;

    private String deptCode;

    private String deptName;

    private String parentId;

    private String parentIds;

    private Integer level;

    private String leaderId;

    private String leaderName;

    private String phone;

    private String email;

    private Integer sortOrder;

    private Integer status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;

    /**
     * 子部门列表
     */
    private List<DepartmentTreeVO> children;
}
