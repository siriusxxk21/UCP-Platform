package com.richuang.os.module.system.controller.admin.organization.vo;

import com.richuang.os.module.system.enums.organization.OrganizationStatusEnum;
import com.richuang.os.framework.common.validation.InEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 组织创建/修改 Request VO")
@Data
public class OrganizationSaveReqVO {

    @Schema(description = "组织编号", example = "1800000000000000001")
    private Long id;

    @Schema(description = "组织编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "HQ")
    @NotBlank(message = "组织编码不能为空")
    @Size(max = 50, message = "组织编码长度不能超过 50 个字符")
    private String orgCode;

    @Schema(description = "组织名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "总部")
    @NotBlank(message = "组织名称不能为空")
    @Size(max = 100, message = "组织名称长度不能超过 100 个字符")
    private String orgName;

    @Schema(description = "组织类型: 1-公司 2-分公司 3-部门", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "组织类型不能为空")
    private Integer orgType;

    @Schema(description = "父组织编号", example = "0")
    private Long parentId;

    @Schema(description = "负责人编号", example = "1024")
    private String leaderId;

    @Schema(description = "联系电话", example = "15601691000")
    @Size(max = 20, message = "联系电话长度不能超过 20 个字符")
    private String phone;

    @Schema(description = "邮箱", example = "os@example.com")
    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱长度不能超过 100 个字符")
    private String email;

    @Schema(description = "地址", example = "上海市")
    @Size(max = 255, message = "地址长度不能超过 255 个字符")
    private String address;

    @Schema(description = "排序", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "排序不能为空")
    private Integer sortOrder;

    @Schema(description = "组织状态：1 启用，0 禁用", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "状态不能为空")
    @InEnum(value = OrganizationStatusEnum.class, message = "状态必须是 {value}")
    private Integer status;

}
