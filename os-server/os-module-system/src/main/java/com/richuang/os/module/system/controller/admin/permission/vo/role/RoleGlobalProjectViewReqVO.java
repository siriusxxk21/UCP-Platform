package com.richuang.os.module.system.controller.admin.permission.vo.role;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 角色全局项目访问权开关 Request VO
 *
 * @author os
 */
@Schema(description = "管理后台 - 角色全局项目访问权开关 Request VO")
@Data
public class RoleGlobalProjectViewReqVO {

    @Schema(description = "角色编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "角色编号不能为空")
    private Long id;

    @Schema(description = "全局项目访问权（0=关闭 1=开启）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "全局项目访问权不能为空")
    private Integer globalProjectView;

}
