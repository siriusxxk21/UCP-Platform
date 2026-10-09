package com.lingan.ucp.nocode.report.service.dataset;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.FieldConversionCompatibility.Field;
import com.lingan.ucp.nocode.metadata.service.formula.Calculations;

import java.util.Objects;

/** 固定版本只能解释仍兼容的业务列；展示名称及无关字段变化不影响分析。 */
public final class ReportReadCompatibility {
    private ReportReadCompatibility() {}

    public static boolean field(Field before, Field after) {
        if (before == null || after == null || Calculations.live(after.options())) return false;
        Field old =
                new Field(
                        OrderedCalculations.queryField(before.definition(), before.options()),
                        before.options());
        Field next =
                new Field(
                        OrderedCalculations.queryField(after.definition(), after.options()),
                        after.options());
        return FieldConversionCompatibility.valueCompatible(old, next)
                && Objects.equals(column(before), column(after))
                && Objects.equals(before.options().nativeType(), after.options().nativeType())
                && OrderedCalculations.storageModeCompatible(before.options(), after.options());
    }

    public static boolean table(DataCenter.Definition before, DataCenter.Definition after) {
        TableBinding old = ObjectTables.main(before), next = ObjectTables.main(after);
        return Objects.equals(before.objectId(), after.objectId())
                && Objects.equals(before.tableName(), after.tableName())
                && Objects.equals(old.schemaName(), next.schemaName())
                && Objects.equals(old.keyColumn(), next.keyColumn());
    }

    private static String column(Field field) {
        return field.options().columnName() == null
                ? field.definition().code()
                : field.options().columnName();
    }
}
