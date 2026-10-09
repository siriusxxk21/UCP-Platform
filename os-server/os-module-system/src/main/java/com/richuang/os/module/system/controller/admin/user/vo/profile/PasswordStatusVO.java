package com.richuang.os.module.system.controller.admin.user.vo.profile;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "管理后台 - 用户密码状态 VO")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PasswordStatusVO {

    @Schema(description = "是否必须修改密码（初始密码或已过期）", requiredMode = Schema.RequiredMode.REQUIRED, example = "true")
    private Boolean mustChange;

    @Schema(description = "密码剩余有效天数，null 表示永不过期", example = "15")
    private Integer remainDays;

    @Schema(description = "临期提醒天数（剩余天数小于等于该值时提醒）", example = "7")
    private Integer remindDays;

}
