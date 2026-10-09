package com.richuang.os.module.system.legacy.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sys_role_menu")
public class SysRoleMenu {

    private String roleId;

    private String menuId;
}
