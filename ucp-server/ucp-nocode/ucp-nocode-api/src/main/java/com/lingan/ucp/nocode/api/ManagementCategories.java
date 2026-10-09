package com.lingan.ucp.nocode.api;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

/** 自由录入的管理分类，不进入发布定义；空字符串表示未分类。 */
public final class ManagementCategories {
    private ManagementCategories() {}

    /** 保存和过滤共用规范化规则。 */
    public static String normalize(String value) {
        String category = value == null ? "" : value.strip();
        if (category.length() > 100) throw invalid("分类名称最多 100 字符");
        return category;
    }

    /** 缺省查询全部，显式空值查询未分类。 */
    public static String filter(String value) {
        return value == null ? null : normalize(value);
    }
}
