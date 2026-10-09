package com.lingan.ucp.module.system.controller.admin.dict.vo.type;

import static com.lingan.ucp.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

import com.lingan.ucp.framework.common.enums.CommonStatusEnum;
import com.lingan.ucp.framework.common.pojo.PageParam;
import com.lingan.ucp.framework.common.validation.InEnum;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.EqualsAndHashCode;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 字典类型分页列表 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class DictTypePageReqVO extends PageParam {

    @Schema(description = "字典类型名称，模糊匹配", example = "芋道")
    private String name;

    @Schema(description = "字典类型，模糊匹配", example = "sys_common_sex")
    @Size(max = 100, message = "字典类型类型长度不能超过100个字符")
    private String type;

    @Schema(description = "展示状态，参见 CommonStatusEnum 枚举类", example = "1")
    @InEnum(value = CommonStatusEnum.class, message = "状态必须是 {value}")
    private Integer status;

    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    @Schema(description = "创建时间")
    private LocalDateTime[] createTime;
}
