package com.richuang.os.nocode.enums;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

/** 汇总 DSL 的封闭操作集合，同时限制运行期聚合函数。 */
public enum SummaryOperationEnum {
    COUNT,
    SUM,
    AVG,
    MIN,
    MAX;

    public static SummaryOperationEnum fromCode(String value) {
        for (var operation : values())
            if (operation.name().equalsIgnoreCase(value)) return operation;
        throw invalid("不支持的汇总操作");
    }

    public boolean zeroWhenEmpty() {
        return this == COUNT || this == SUM;
    }
}
