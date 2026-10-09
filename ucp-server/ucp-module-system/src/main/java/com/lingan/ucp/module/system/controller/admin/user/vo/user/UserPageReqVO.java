package com.lingan.ucp.module.system.controller.admin.user.vo.user;

import com.lingan.ucp.framework.common.pojo.PageParam;
import com.lingan.ucp.common.dto.DynamicConditionDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.util.List;

import static com.lingan.ucp.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description = "管理后台 - 用户分页 Request VO")
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class UserPageReqVO extends PageParam {

    @Schema(description = "用户名，模糊匹配", example = "os")
    private String username;

    @Schema(description = "手机号码，模糊匹配", example = "os")
    private String mobile;

    @Schema(description = "展示状态，参见 CommonStatusEnum 枚举类", example = "1")
    private Integer status;

    @Schema(description = "创建时间", example = "[2022-07-01 00:00:00, 2022-07-01 23:59:59]")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] createTime;

    @Schema(description = "部门编号，同时筛选子部门", example = "1024")
    private Long deptId;

    @Schema(description = "组织编号，同时筛选子组织", example = "1024")
    private Long orgId;

    @Schema(description = "角色编号", example = "1024")
    private Long roleId;

    @Schema(description = "关键词，模糊匹配")
    private String keyword;

    @Schema(description = "高级检索动态条件")
    private DynamicConditionDTO conditions;

    @Schema(description = "导出时指定的用户 ID 列表", example = "[1, 2]")
    private List<Long> ids;

}
