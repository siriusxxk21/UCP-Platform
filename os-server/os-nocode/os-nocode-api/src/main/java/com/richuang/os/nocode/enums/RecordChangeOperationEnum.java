package com.richuang.os.nocode.enums;

/** 表格变更统计只计算实际数据写入，读取和初始快照不属于 CRUD 变更。 */
public enum RecordChangeOperationEnum implements NocodeCodeEnum {
    BASELINE,
    CREATE,
    UPDATE,
    DELETE;

    @Override
    public String getCode() {
        return name();
    }
}
