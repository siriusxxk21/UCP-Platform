package com.richuang.os.nocode.enums;

import java.util.Set;

/** 发布与纳管预检的稳定诊断编码。 编码显式固定，不依赖枚举名称或序号持久化。 */
public enum PublishCheckEnum implements NocodeCodeEnum {
    APPLICATION_CONTRACT("APPLICATION_CONTRACT"),
    /** 自动跟随：发布后将自动同步并生效的应用（提示） */
    APPLICATION_FOLLOW("APPLICATION_FOLLOW"),
    /** 自动跟随：开着跟随但有在途审批、暂时跟不上的应用（提示） */
    APPLICATION_FOLLOW_PENDING("APPLICATION_FOLLOW_PENDING"),
    BINDING_CHANGED("BINDING_CHANGED"),
    BINDING_INVALID("BINDING_INVALID"),
    UNLINKED_ROWS("UNLINKED_ROWS"),
    STRUCTURE_RETAINED("STRUCTURE_RETAINED"),

    ADOPTED_STRUCTURE("ADOPTED_STRUCTURE"),
    ADOPTION_CHANGED("ADOPTION_CHANGED"),
    BASE_FIELDS("BASE_FIELDS"),
    /** 业务文件：参与字段配置了文件默认值，禁止接入（阻断） */
    BUSINESS_FILE_DEFAULT("BUSINESS_FILE_DEFAULT"),
    /** 业务文件：存在未完成的业务上传会话（提示） */
    BUSINESS_FILE_SESSIONS("BUSINESS_FILE_SESSIONS"),
    /** 业务文件：规则变更或取消接入的影响范围（提示） */
    BUSINESS_FILE_SCOPE("BUSINESS_FILE_SCOPE"),
    CLAIMED("CLAIMED"),
    COLUMN_COUNT("COLUMN_COUNT"),
    COLUMN_IDENTITY("COLUMN_IDENTITY"),
    COLUMN_OCCUPIED("COLUMN_OCCUPIED"),
    COLUMN_READ_ONLY("COLUMN_READ_ONLY"),
    COLUMN_REMOVED("COLUMN_REMOVED"),
    DISABLED("DISABLED"),
    DRAFT_CHANGES("DRAFT_CHANGES"),
    DUPLICATES("DUPLICATES"),
    /** 自动跟随：本次新增字段会自动对哪些成员可见（提示） */
    FIELD_EXPOSURE("FIELD_EXPOSURE"),
    GENERATED_CHANGE("GENERATED_CHANGE"),
    /** 本对象还有在审批中的办理申请，对象发布后它们通过时将无法生效（现状提示） */
    HANDLING_IN_FLIGHT("HANDLING_IN_FLIGHT"),
    INCOMPATIBLE_COLUMN("INCOMPATIBLE_COLUMN"),
    INDEX_FIELDS("INDEX_FIELDS"),
    INVALID_FIELD("INVALID_FIELD"),
    NEW_COLUMN("NEW_COLUMN"),
    NULL_VALUES("NULL_VALUES"),
    PRIMARY_KEY("PRIMARY_KEY"),
    PROTECTED_COLUMN("PROTECTED_COLUMN"),
    READ_ONLY("READ_ONLY"),
    RELATION_TABLE("RELATION_TABLE"),
    REQUIRED_EXISTING("REQUIRED_EXISTING"),
    SELECT_PERMISSION("SELECT_PERMISSION"),
    STORAGE_CHANGE("STORAGE_CHANGE"),
    STRUCTURE_DRIFT("STRUCTURE_DRIFT"),
    TABLE_KIND("TABLE_KIND"),
    TABLE_MISSING("TABLE_MISSING"),
    TABLE_OCCUPIED("TABLE_OCCUPIED"),
    TARGET_KEY("TARGET_KEY"),
    TARGET_UNPUBLISHED("TARGET_UNPUBLISHED"),
    TYPE_NARROWING("TYPE_NARROWING"),
    UNMAPPED_COLUMN("UNMAPPED_COLUMN"),
    UNSUPPORTED_CHANGE("UNSUPPORTED_CHANGE");

    private final String code;
    private static final Set<String> CODES = NocodeCodeEnum.codes(PublishCheckEnum.class);

    PublishCheckEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static PublishCheckEnum fromCode(String code) {
        return NocodeCodeEnum.require(PublishCheckEnum.class, code);
    }

    public static boolean containsCode(String code) {
        return code != null && CODES.contains(code);
    }
}
