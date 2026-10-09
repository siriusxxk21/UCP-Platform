package com.richuang.os.module.system.legacy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richuang.os.module.system.legacy.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

// @Mapper -- 已废弃（v2.0 统一认证迁移）
public interface SysRoleMapper extends BaseMapper<SysRole> {

    @Select("SELECT r.* FROM sys_role r " +
            "INNER JOIN sys_user_role ur ON r.id = ur.role_id " +
            "WHERE ur.user_id = #{userId} AND r.deleted = 0")
    List<SysRole> selectRolesByUserId(@Param("userId") String userId);
}
