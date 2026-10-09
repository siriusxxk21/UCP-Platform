package com.lingan.ucp.module.system.controller.admin.user.vo.user;

import cn.idev.excel.converters.Converter;
import cn.idev.excel.enums.CellDataTypeEnum;
import cn.idev.excel.metadata.GlobalConfiguration;
import cn.idev.excel.metadata.data.ReadCellData;
import cn.idev.excel.metadata.data.WriteCellData;
import cn.idev.excel.metadata.property.ExcelContentProperty;
import com.lingan.ucp.framework.common.enums.CommonStatusEnum;

/**
 * 用户管理 Excel 账号状态转换器。
 *
 * <p>用户页面、导入模板与导出文件统一使用“启用 / 禁用”，不依赖通用字典的“开启 / 关闭”文案。</p>
 */
public class UserStatusExcelConverter implements Converter<Integer> {

    @Override
    public Class<?> supportJavaTypeKey() {
        throw new UnsupportedOperationException("无需指定 Java 类型");
    }

    @Override
    public CellDataTypeEnum supportExcelTypeKey() {
        throw new UnsupportedOperationException("无需指定 Excel 类型");
    }

    @Override
    public Integer convertToJavaData(ReadCellData readCellData, ExcelContentProperty contentProperty,
                                     GlobalConfiguration globalConfiguration) {
        return switch (readCellData.getStringValue()) {
            case "启用" -> CommonStatusEnum.ENABLE.getStatus();
            case "禁用" -> CommonStatusEnum.DISABLE.getStatus();
            default -> throw new IllegalArgumentException("账号状态仅支持“启用”或“禁用”");
        };
    }

    @Override
    public WriteCellData<String> convertToExcelData(Integer value, ExcelContentProperty contentProperty,
                                                     GlobalConfiguration globalConfiguration) {
        return new WriteCellData<>(Integer.valueOf(CommonStatusEnum.ENABLE.getStatus()).equals(value) ? "启用" : "禁用");
    }
}
