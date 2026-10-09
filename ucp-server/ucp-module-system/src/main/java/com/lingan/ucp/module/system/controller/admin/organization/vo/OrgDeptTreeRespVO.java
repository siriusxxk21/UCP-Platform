package com.lingan.ucp.module.system.controller.admin.organization.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Schema(description = "管理后台 - 组织部门树 Response VO")
@Data
public class OrgDeptTreeRespVO {

    @Schema(description = "节点编号，带类型前缀", example = "org_1800000000000000001")
    private String id;

    @Schema(description = "原始编号", example = "1800000000000000001")
    private String rawId;

    @Schema(description = "节点名称", example = "总部")
    private String name;

    @Schema(description = "节点类型：org、dept", example = "org")
    private String nodeType;

    @Schema(description = "所属组织编号")
    private String orgId;

    @Schema(description = "子节点")
    private List<OrgDeptTreeRespVO> children = new ArrayList<>();

}
