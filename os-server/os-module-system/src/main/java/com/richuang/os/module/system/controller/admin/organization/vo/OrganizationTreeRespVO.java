package com.richuang.os.module.system.controller.admin.organization.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

@Schema(description = "管理后台 - 组织树 Response VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class OrganizationTreeRespVO extends OrganizationRespVO {

    @Schema(description = "子组织")
    private List<OrganizationTreeRespVO> children = new ArrayList<>();

}
