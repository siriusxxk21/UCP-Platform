package com.richuang.os.module.system.legacy.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class MenuVO {

    private String id;

    private String name;

    private String path;

    private String component;

    private String icon;

    private String parentId;

    private Integer sort;

    private Integer status;

    private Integer menuType;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private List<MenuVO> children;
}
