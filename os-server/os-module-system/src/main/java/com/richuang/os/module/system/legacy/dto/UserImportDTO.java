package com.richuang.os.module.system.legacy.dto;

import cn.idev.excel.annotation.ExcelProperty;
import cn.idev.excel.annotation.write.style.ColumnWidth;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 用户导入 Excel DTO（FastExcelFactory 注解驱动）
 * 仅含导入所需字段，其余由系统自动填充
 */
@Data
public class UserImportDTO {

    @ExcelProperty("用户名")
    @NotBlank(message = "用户名不能为空")
    @ColumnWidth(20)
    private String username;

    @ExcelProperty("昵称")
    @ColumnWidth(20)
    private String nickname;

    @ExcelProperty("手机号")
    @ColumnWidth(15)
    private String phone;

    @ExcelProperty("邮箱")
    @ColumnWidth(25)
    private String email;

    @ExcelProperty("岗位")
    @ColumnWidth(15)
    private String post;

    @ExcelProperty(value = "用户类型")
    @ColumnWidth(12)
    private String userTypeLabel;

    @ExcelProperty(value = "状态")
    @ColumnWidth(10)
    private String statusLabel;
}
