package com.richuang.os.common.enums;

import lombok.Getter;

/**
 * 数据权限范围枚举
 */
@Getter
public enum DataScopeType {

    ALL(1, "全部数据"),
    DEPT_ONLY(2, "本部门数据"),
    DEPT_AND_CHILD(3, "本部门及以下数据"),
    SELF_ONLY(4, "仅本人数据"),
    CUSTOM(5, "自定义数据");

    private final Integer code;
    private final String desc;

    DataScopeType(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static DataScopeType getByCode(Integer code) {
        for (DataScopeType type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return ALL;
    }
}
