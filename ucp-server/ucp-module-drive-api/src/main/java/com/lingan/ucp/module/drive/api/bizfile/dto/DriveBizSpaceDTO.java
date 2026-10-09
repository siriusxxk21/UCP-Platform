package com.lingan.ucp.module.drive.api.bizfile.dto;

/** 业务空间配置选项；稳定编号用于对象发布规则，名称只用于展示。 */
public record DriveBizSpaceDTO(Long id, String name, Integer status) {}
