package com.lingan.ucp.module.system.legacy.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lingan.ucp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_menu")
public class SysMenu extends BaseEntity {

    private String name;

    private String path;

    private String component;

    private String icon;

    private String parentId;

    private Integer sort;

    private Integer status;

    private Integer menuType;
}
