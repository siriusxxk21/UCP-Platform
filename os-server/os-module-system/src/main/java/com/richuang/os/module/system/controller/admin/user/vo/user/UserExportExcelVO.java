package com.richuang.os.module.system.controller.admin.user.vo.user;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import com.richuang.os.framework.excel.core.annotations.DictFormat;
import com.richuang.os.framework.excel.core.convert.DictConvert;
import com.richuang.os.module.system.enums.DictTypeConstants;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户列表导出专用数据结构。
 *
 * <p>导出列与接口响应解耦，避免为了 Excel 文案调整影响用户详情接口。</p>
 */
@Data
@ExcelIgnoreUnannotated
public class UserExportExcelVO {

    @ExcelProperty("用户名")
    private String username;

    @ExcelProperty("昵称")
    private String nickname;

    @ExcelProperty("部门名称")
    private String deptName;

    @ExcelProperty("用户邮箱")
    private String email;

    @ExcelProperty("手机号码")
    private String mobile;

    @ExcelProperty(value = "用户性别", converter = DictConvert.class)
    @DictFormat(DictTypeConstants.USER_SEX)
    private Integer sex;

    @ExcelProperty(value = "账号状态", converter = UserStatusExcelConverter.class)
    private Integer status;

    @ExcelProperty("最后登录 IP")
    private String loginIp;

    @ExcelProperty("最后登录时间")
    private LocalDateTime loginDate;

}
