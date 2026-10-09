package com.lingan.ucp.nocode.enums;

import java.util.Set;

/** 结构变化摘要类型，供发布计划展示。 编码显式固定，不依赖枚举名称或序号持久化。 */
public enum SchemaChangeEnum implements NocodeCodeEnum {
    BIND_TABLE("BIND_TABLE"),
    LINK_PARENT("LINK_PARENT"),
    REPAIR_BASE_FIELDS("REPAIR_BASE_FIELDS"),

    ADD_COLUMN("ADD_COLUMN"),
    ALTER_COLUMN_TYPE("ALTER_COLUMN_TYPE"),
    ADOPT("ADOPT"),
    CREATE_TABLE("CREATE_TABLE"),
    DEACTIVATE_FIELD("DEACTIVATE_FIELD"),
    RESTORE_FIELD("RESTORE_FIELD"),
    DEACTIVATE_RELATION("DEACTIVATE_RELATION"),
    DEFAULT("DEFAULT"),
    DROP_INDEX("DROP_INDEX"),
    IMPORT_COLUMN("IMPORT_COLUMN"),
    INDEX("INDEX"),
    MANY_TO_MANY("MANY_TO_MANY"),
    METADATA("METADATA"),
    RECONCILE("RECONCILE"),
    RELATION("RELATION"),
    REQUIRED("REQUIRED"),
    SUMMARY("SUMMARY"),
    SYNC_COLUMN("SYNC_COLUMN"),
    UNIQUE("UNIQUE"),
    VALIDATION("VALIDATION"),
    WIDEN_COLUMN("WIDEN_COLUMN");

    private final String code;
    private static final Set<String> CODES = NocodeCodeEnum.codes(SchemaChangeEnum.class);

    SchemaChangeEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static SchemaChangeEnum fromCode(String code) {
        return NocodeCodeEnum.require(SchemaChangeEnum.class, code);
    }

    public static boolean containsCode(String code) {
        return code != null && CODES.contains(code);
    }
}
