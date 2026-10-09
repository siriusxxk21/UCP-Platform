package com.lingan.ucp.module.system.legacy.vo;

import lombok.Data;

import java.util.List;

@Data
public class RolePageVO {

    private Long total;

    private List<RoleVO> list;
}
