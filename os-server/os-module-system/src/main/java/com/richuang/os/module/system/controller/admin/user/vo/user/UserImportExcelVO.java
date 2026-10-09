package com.richuang.os.module.system.controller.admin.user.vo.user;

import cn.idev.excel.annotation.ExcelProperty;
import com.richuang.os.framework.excel.core.annotations.DictFormat;
import com.richuang.os.framework.excel.core.convert.DictConvert;
import com.richuang.os.module.system.enums.DictTypeConstants;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户 Excel 导入 VO
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserImportExcelVO {

    @ExcelProperty(value = "用户名", index = 0)
    private String username;

    @ExcelProperty(value = "昵称", index = 1)
    private String nickname;

    @ExcelProperty(value = "部门编码", index = 2)
    private String deptCode;

    @ExcelProperty(value = "用户邮箱", index = 3)
    private String email;

    @ExcelProperty(value = "手机号码", index = 4)
    private String mobile;

    @ExcelProperty(value = "用户性别", index = 5, converter = DictConvert.class)
    @DictFormat(DictTypeConstants.USER_SEX)
    private Integer sex;

    @ExcelProperty(value = "账号状态", index = 6, converter = UserStatusExcelConverter.class)
    private Integer status;

}
