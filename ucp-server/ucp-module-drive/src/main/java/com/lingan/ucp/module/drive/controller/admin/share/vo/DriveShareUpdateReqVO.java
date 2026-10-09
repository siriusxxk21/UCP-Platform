package com.lingan.ucp.module.drive.controller.admin.share.vo;

import static com.lingan.ucp.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import lombok.Data;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 网盘分享修改 Request VO")
@Data
public class DriveShareUpdateReqVO {

    @Schema(description = "分享编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @NotNull(message = "分享编号不能为空")
    private Long id;

    @Schema(
            description = "分享授予的角色：VIEWER 可查看、EDITOR 可编辑",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "VIEWER")
    @NotEmpty(message = "分享角色不能为空")
    private String role;

    @Schema(description = "失效时间，不传表示长期有效")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime expireTime;
}
