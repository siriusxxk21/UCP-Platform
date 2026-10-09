package com.richuang.os.module.drive.controller.admin.space.vo;

import com.richuang.os.framework.common.enums.CommonStatusEnum;
import com.richuang.os.framework.common.validation.InEnum;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import lombok.Data;

@Schema(description = "管理后台 - 网盘团队空间修改 Request VO")
@Data
public class DriveSpaceUpdateReqVO {

    @Schema(description = "空间编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "空间编号不能为空")
    private Long id;

    @Schema(description = "空间名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "产品部空间")
    @NotEmpty(message = "空间名称不能为空")
    @Size(max = 255, message = "空间名称长度不能超过255个字符")
    private String name;

    @Schema(description = "归属主体用户编号", example = "1024")
    private Long ownerId;

    @Schema(description = "归属部门编号", example = "100")
    private Long ownerDeptId;

    @Schema(description = "容量配额（字节），0 表示不限制", example = "0")
    @PositiveOrZero(message = "容量配额不能小于0")
    private Long quotaBytes;

    @Schema(description = "空间状态：0 开启、1 关闭", example = "0")
    @InEnum(value = CommonStatusEnum.class, message = "空间状态必须是 {value}")
    private Integer status;
}
