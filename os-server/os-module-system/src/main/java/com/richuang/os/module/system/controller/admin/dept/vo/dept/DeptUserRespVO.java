package com.richuang.os.module.system.controller.admin.dept.vo.dept;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 部门用户 Response VO")
@Data
public class DeptUserRespVO {

    @Schema(description = "用户部门关联记录编号", example = "1024")
    private Long id;

    @Schema(description = "用户编号", example = "1024")
    private Long userId;

    @Schema(description = "用户账号", example = "os")
    private String username;

    @Schema(description = "用户昵称", example = "张三")
    private String nickname;

    @Schema(description = "手机号码", example = "15601691300")
    private String phone;

    @Schema(description = "邮箱", example = "os@example.com")
    private String email;

    @Schema(description = "用户状态", example = "1")
    private Integer userStatus;

    @Schema(description = "是否主部门：1 是，0 否", example = "1")
    private Integer isMain;

    @Schema(description = "岗位", example = "工程师")
    private String post;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
