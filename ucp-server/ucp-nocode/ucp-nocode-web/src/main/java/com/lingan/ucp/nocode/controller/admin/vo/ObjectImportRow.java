package com.lingan.ucp.nocode.controller.admin.vo;

import cn.idev.excel.annotation.ExcelProperty;

import lombok.Data;

/** 全部按文本读取，在预检阶段报告具体行，防止类型转换异常掩盖错误位置。 */
@Data
public class ObjectImportRow {
    @ExcelProperty(value = "字段编码", index = 0)
    private String code;

    @ExcelProperty(value = "字段名称", index = 1)
    private String name;

    @ExcelProperty(value = "字段类型", index = 2)
    private String type;

    @ExcelProperty(value = "长度", index = 3)
    private String length;

    @ExcelProperty(value = "精度", index = 4)
    private String precision;

    @ExcelProperty(value = "小数位数", index = 5)
    private String scale;

    @ExcelProperty(value = "必填", index = 6)
    private String required;

    @ExcelProperty(value = "唯一", index = 7)
    private String unique;
}
