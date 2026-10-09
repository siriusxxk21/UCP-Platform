package com.richuang.os.nocode.api;

import com.richuang.os.nocode.enums.NocodeCodeEnum;

import java.util.Map;

/** 仅有序 ON_SAVE 的校准状态；合法业务空值与未回填状态分开。 */
public final class OrderedCalculations {
    private OrderedCalculations() {}

    /** 有序落库的查询值使用既有结果类型，字段身份与存储定义不变。 */
    public static FieldDefinition queryField(
            FieldDefinition field, DataCenter.FieldOptions options) {
        CalculationOptions calculation = options == null ? null : options.calculation();
        if (!com.richuang.os.nocode.enums.FieldTypeEnum.FORMULA.matches(field.type())
                || calculation == null
                || !com.richuang.os.nocode.enums.CalculationUpdateEnum.ON_SAVE.matches(
                        calculation.updateMode())
                || !(com.richuang.os.nocode.enums.CalculationModeEnum.RUNNING_TOTAL.matches(
                                calculation.mode())
                        || com.richuang.os.nocode.enums.CalculationModeEnum.SEQUENCE.matches(
                                calculation.mode()))) return field;
        return new FieldDefinition(
                field.key(),
                field.id(),
                field.code(),
                field.name(),
                options.resultType(),
                field.length(),
                38,
                10,
                field.required(),
                field.unique(),
                field.sort());
    }

    public enum Readiness implements NocodeCodeEnum {
        PENDING("PENDING"),
        BACKFILLING("BACKFILLING"),
        READY("READY"),
        FAILED("FAILED");
        private final String code;

        Readiness(String code) {
            this.code = code;
        }

        public String getCode() {
            return code;
        }

        public static Readiness fromCode(String code) {
            return NocodeCodeEnum.require(Readiness.class, code);
        }
    }

    /** API 层只判断更新语义，算法依赖摘要由 metadata 检查。 */
    public static boolean stored(DataCenter.FieldOptions options) {
        return options != null
                && options.calculation() != null
                && com.richuang.os.nocode.enums.CalculationUpdateEnum.ON_SAVE.matches(
                        options.calculation().updateMode())
                && (com.richuang.os.nocode.enums.CalculationModeEnum.RUNNING_TOTAL.matches(
                                options.calculation().mode())
                        || com.richuang.os.nocode.enums.CalculationModeEnum.SEQUENCE.matches(
                                options.calculation().mode()));
    }

    public static boolean storageModeCompatible(
            DataCenter.FieldOptions before, DataCenter.FieldOptions after) {
        return !stored(before) && !stored(after)
                || stored(before)
                        && stored(after)
                        && java.util.Objects.equals(before.calculation(), after.calculation());
    }

    /** cursor 只保存分批位置与完成组信息，不接受表达式或 SQL。 */
    public record State(
            String objectId,
            String fieldId,
            String signature,
            String state,
            Map<String, Object> cursor,
            long totalRows,
            long updatedRows,
            long completedGroups,
            String error,
            long revision) {}
}
