package com.richuang.os.nocode.api;

import com.richuang.os.nocode.enums.ObjectSourceEnum;
import com.richuang.os.nocode.enums.StructureModeEnum;

/** 一张表的版本化绑定。读取/写入能力还需叠加实际目录、底座权限与应用权限，客户端声明不能解除限制。 */
public record TableBinding(
        String source,
        String schemaName,
        String keyColumn,
        String parentColumn,
        String structureMode,
        Boolean readOnly,
        Boolean repairBaseFields,
        String fingerprint) {
    public static TableBinding generated(String schema, boolean detail) {
        return new TableBinding(
                ObjectSourceEnum.GENERATED.getCode(),
                schema,
                "id",
                detail ? "parent_id" : null,
                StructureModeEnum.MANAGED.getCode(),
                false,
                false,
                null);
    }

    public boolean adopted() {
        return ObjectSourceEnum.ADOPTED.matches(source);
    }

    public boolean managed() {
        return StructureModeEnum.MANAGED.matches(structureMode);
    }
}
