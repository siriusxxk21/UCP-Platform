package com.lingan.ucp.module.system.controller.admin.permission.vo.permission;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Collections;
import java.util.Set;

/**
 * 角色用户成员覆盖分配请求。
 *
 * <p>角色管理页面一次提交全部已选用户，避免逐用户调用角色分配接口。</p>
 */
@Schema(description = "管理后台 - 覆盖分配角色用户 Request VO")
@Data
public class PermissionAssignRoleUsersReqVO {

    @Schema(description = "角色编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "角色编号不能为空")
    private Long roleId;

    @Schema(description = "用户编号列表", example = "[1, 2, 3]")
    private Set<Long> userIds = Collections.emptySet();

}
