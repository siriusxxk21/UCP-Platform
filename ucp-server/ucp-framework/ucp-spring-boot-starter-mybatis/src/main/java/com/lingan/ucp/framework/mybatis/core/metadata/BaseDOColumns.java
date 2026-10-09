package com.lingan.ucp.framework.mybatis.core.metadata;

import java.util.List;
import java.util.Set;

/** 动态业务表对应 BaseDO 的列契约，与现有 PostgreSQL 系统表保持一致。 不包含租户字段：只有使用 TenantBaseDO 的业务才需要 tenant_id。 */
public final class BaseDOColumns {
    private BaseDOColumns() {}

    public record Definition(
            String name, String type, boolean required, String defaultValue, String comment) {}

    public static final List<Definition> FIELDS =
            List.of(
                    new Definition("creator", "varchar(64)", false, "", "创建者（底座用户 ID）"),
                    new Definition(
                            "create_time", "timestamp without time zone", true, null, "创建时间"),
                    new Definition("updater", "varchar(64)", false, "", "更新者（底座用户 ID）"),
                    new Definition(
                            "update_time", "timestamp without time zone", true, null, "更新时间"),
                    new Definition("deleted", "smallint", true, "0", "是否删除：0 否，1 是"));

    public static final Set<String> NAMES =
            Set.of("creator", "create_time", "updater", "update_time", "deleted");

    /** 纳管仅报告差异，不擅自修改已有表。 */
    public static List<String> differences(DatabaseMetadata.Table table) {
        return FIELDS.stream()
                .filter(
                        expected ->
                                table.columns().stream()
                                        .noneMatch(
                                                actual ->
                                                        expected.name().equals(actual.name())
                                                                && normalize(expected.type())
                                                                        .equals(
                                                                                normalize(
                                                                                        actual
                                                                                                .nativeType()))
                                                                && (!expected.required()
                                                                        || !actual.nullable())))
                .map(Definition::name)
                .toList();
    }

    private static String normalize(String type) {
        return type.replace("character varying", "varchar").replace("timestamp(6)", "timestamp");
    }
}
