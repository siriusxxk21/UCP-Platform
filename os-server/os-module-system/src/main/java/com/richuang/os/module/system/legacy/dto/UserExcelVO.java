package com.richuang.os.module.system.legacy.dto;

import cn.idev.excel.annotation.ExcelProperty;
import cn.idev.excel.annotation.write.style.ColumnWidth;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户导出 Excel VO（FastExcelFactory 注解驱动）
 */
@Data
public class UserExcelVO {

    @ExcelProperty("用户名")
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

    @ExcelProperty("所属组织")
    @ColumnWidth(20)
    private String orgName;

    @ExcelProperty("所属部门")
    @ColumnWidth(20)
    private String deptName;

    @ExcelProperty("岗位")
    @ColumnWidth(15)
    private String post;

    @ExcelProperty(value = "用户类型")
    @ColumnWidth(12)
    private String userTypeLabel;

    @ExcelProperty(value = "数据权限")
    @ColumnWidth(12)
    private String dataScopeLabel;

    @ExcelProperty(value = "状态")
    @ColumnWidth(10)
    private String statusLabel;

    @ExcelProperty("创建时间")
    @ColumnWidth(20)
    private LocalDateTime createTime;
}
