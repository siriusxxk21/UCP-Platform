package com.lingan.ucp.nocode.enums;

import java.util.Set;

/** 允许向数据中心登记依赖的业务资源类型。 编码显式固定，不依赖枚举名称或序号持久化。 */
public enum DependencyKindEnum implements NocodeCodeEnum {
    APP("APP"),
    PAGE("PAGE"),
    VIEW("VIEW"),
    FLOW("FLOW"),
    ACTION("ACTION"),
    IMPORT("IMPORT"),
    /** 独立报表数据集的草稿和不可变发布版本。 */
    DATASET("DATASET"),
    /** 对象字段规则（数据联动、引用筛选、挑取值）引用了其它对象的字段。 */
    OBJECT_RULE("OBJECT_RULE");

    private final String code;
    private static final Set<String> CODES = NocodeCodeEnum.codes(DependencyKindEnum.class);

    DependencyKindEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static DependencyKindEnum fromCode(String code) {
        return NocodeCodeEnum.require(DependencyKindEnum.class, code);
    }

    public static boolean containsCode(String code) {
        return code != null && CODES.contains(code);
    }
}
