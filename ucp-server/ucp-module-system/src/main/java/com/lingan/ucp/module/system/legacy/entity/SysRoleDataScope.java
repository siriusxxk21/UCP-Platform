package com.lingan.ucp.module.system.legacy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色数据权限实体类
 */
@Data
@TableName("sys_role_data_scope")
public class SysRoleDataScope {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 角色ID
     */
    private String roleId;

    /**
     * 数据权限范围: 1-全部 2-本部门 3-本部门及以下 4-自定义
     */
    private Integer dataScope;

    /**
     * 自定义部门ID列表(逗号分隔)
     */
    private String customDeptIds;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
