package com.lingan.ucp.module.system.controller.admin.user.vo.user;

import com.lingan.ucp.framework.common.enums.CommonStatusEnum;
import com.lingan.ucp.framework.common.validation.InEnum;
import com.lingan.ucp.framework.dict.validation.InDict;
import com.lingan.ucp.module.system.enums.DictTypeConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - 用户更新状态 Request VO")
@Data
public class UserUpdateStatusReqVO {

    @Schema(description = "用户 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @NotNull(message = "用户 ID 不能为空")
    private Long id;

    @Schema(description = "状态，见 CommonStatusEnum 枚举", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "状态不能为空")
    @InEnum(value = CommonStatusEnum.class, message = "修改状态必须是 {value}")
    @InDict(type = DictTypeConstants.COMMON_STATUS)
    private Integer status;

}
