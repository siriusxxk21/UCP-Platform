package com.richuang.os.module.system.legacy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户部门关联实体类
 * 支持用户多部门兼职
 */
@Data
@TableName("sys_user_dept")
public class SysUserDept {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 部门ID
     */
    private String deptId;

    /**
     * 是否主部门: 0-否 1-是
     */
    private Integer isMain;

    /**
     * 岗位
     */
    private String post;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
