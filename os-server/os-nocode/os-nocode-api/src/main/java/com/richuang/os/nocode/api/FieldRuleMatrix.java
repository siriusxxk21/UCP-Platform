package com.richuang.os.nocode.api;

import com.richuang.os.nocode.enums.FieldTypeEnum;
import com.richuang.os.nocode.enums.RelationTypeEnum;

import java.util.*;

/**
 * 字段值来源能力矩阵与条件算子词表；前端 valueSourceModesFor 与本类读同一份期望表
 * （contract-baseline/field-rule-matrix.json）。抽屉只出现一块：选项类与关系字段为 OPTIONS，其余用户填值字段为 DEFAULT，
 * 不由用户填值的字段为 NONE。
 */
public final class FieldRuleMatrix {
    private FieldRuleMatrix() {}

    /** 选项▾：候选来源与数据联动（不出现默认值块）。 */
    public static final String BLOCK_OPTIONS = "OPTIONS";

    /** 默认值▾：自定义、数据联动与公式。 */
    public static final String BLOCK_DEFAULT = "DEFAULT";

    /** 整块不显示：不由用户填值的字段。 */
    public static final String BLOCK_NONE = "NONE";

    /** 自定义：选项类为局部选项或公共字典，其它为常量默认值（留空即没有默认值）。 */
    public static final String MODE_CUSTOM = "CUSTOM";

    /** 关联其它表单数据·挑取值（selection.kind=OBJECT_FIELD_OPTIONS）。 */
    public static final String MODE_OBJECT_FIELD_OPTIONS = "OBJECT_FIELD_OPTIONS";

    /** 关联其它表单数据·挑对象·显示名字段（rules.reference.labelFieldId）。 */
    public static final String MODE_REFERENCE_LABEL = "REFERENCE_LABEL";

    /** 关联其它表单数据·挑对象·引用筛选（rules.reference.filter）。 */
    public static final String MODE_REFERENCE_FILTER = "REFERENCE_FILTER";

    /** 数据联动（rules.linkage）；选项类与关系字段上叠加在候选来源之上。 */
    public static final String MODE_LINKAGE = "LINKAGE";

    /** 公式编辑（rules.defaultFormula）。 */
    public static final String MODE_FORMULA = "FORMULA";

    /** 选项类字段：未作答与“选中默认项”无法区分，不得有默认值。 */
    public static final Set<FieldTypeEnum> OPTION_TYPES =
            Set.of(
                    FieldTypeEnum.SELECT,
                    FieldTypeEnum.MULTI_SELECT,
                    FieldTypeEnum.REGION,
                    FieldTypeEnum.CASCADE);

    /** 条件算子，编码与前端词表一致；in 只在运行时由多值当前字段改写产生，不可配置。 */
    public static final List<String> OPERATORS =
            List.of(
                    "eq",
                    "neq",
                    "gt",
                    "gte",
                    "lt",
                    "lte",
                    "like",
                    "notLike",
                    "between",
                    "containsAny",
                    "isNull",
                    "notNull");

    /** 「为空」「不为空」：只看来源字段本身，不带比较值（值来源固定为 CONSTANT，value 与 formFieldId 均为 null）。 */
    public static final List<String> EMPTINESS = List.of("isNull", "notNull");

    public static boolean valueless(String operator) {
        return operator != null && EMPTINESS.contains(operator);
    }

    public static boolean optionType(FieldDefinition field) {
        return field != null
                && FieldTypeEnum.containsCode(field.type())
                && OPTION_TYPES.contains(FieldTypeEnum.fromCode(field.type()));
    }

    /** 字段抽屉出现的块。relationKind 为该字段作为引用列所属关系的类型，非引用列传 null。 */
    public static String block(String type, String relationKind) {
        if (modes(type, relationKind).isEmpty()) return BLOCK_NONE;
        return relationKind != null || OPTION_TYPES.contains(FieldTypeEnum.fromCode(type))
                ? BLOCK_OPTIONS
                : BLOCK_DEFAULT;
    }

    /** 档位按固定顺序返回；空列表表示不开放任何值来源配置。 */
    public static List<String> modes(String type, String relationKind) {
        if (relationKind != null) {
            if (RelationTypeEnum.MANY_TO_MANY.matches(relationKind)) return List.of();
            // 主从关系的父键只允许显示名字段，不开放引用筛选与联动。
            if (RelationTypeEnum.MASTER_DETAIL.matches(relationKind))
                return List.of(MODE_REFERENCE_LABEL);
            return List.of(MODE_REFERENCE_LABEL, MODE_REFERENCE_FILTER, MODE_LINKAGE);
        }
        return switch (FieldTypeEnum.fromCode(type)) {
            case SELECT, MULTI_SELECT ->
                    List.of(MODE_CUSTOM, MODE_OBJECT_FIELD_OPTIONS, MODE_LINKAGE);
            case REGION, CASCADE -> List.of(MODE_CUSTOM, MODE_LINKAGE);
            case TEXT, TEXTAREA, URL, INTEGER, DECIMAL, MONEY, PERCENT ->
                    List.of(MODE_CUSTOM, MODE_LINKAGE, MODE_FORMULA);
            case DATE,
                            DATETIME,
                            TIME,
                            BOOLEAN,
                            USER,
                            DEPARTMENT,
                            ORGANIZATION,
                            POST,
                            USER_GROUP,
                            RICH_TEXT ->
                    List.of(MODE_CUSTOM, MODE_LINKAGE);
            case IMAGE, ATTACHMENT -> List.of(MODE_CUSTOM);
            case AUTO_NUMBER, FORMULA, SUMMARY, UUID, REFERENCE -> List.of();
        };
    }

    public static boolean allows(String type, String relationKind, String mode) {
        return modes(type, relationKind).contains(mode);
    }

    /**
     * 条件字段可用的算子。relation 表示该字段是单值引用列；resultType 只对计算字段有意义。空列表表示字段不可作为条件：附件、图片、富文本、
     * 地区、级联、汇总，以及运行端查询不支持的链接和 UUID（以 RecordQueryOperatorEnum.supports 为准）。
     *
     * <p>2026-10-01 起：凡可作条件的字段都提供「为空 / 不为空」；选项、关联、目录、布尔与文本另提供「不等于」。「不等于」对空值安全（来源字段为空的
     * 记录算不等于，会被选中）；文本与选项的「为空」把 NULL 与空串都算空，多选把空列表也算空。
     */
    public static List<String> operators(String type, String resultType, boolean relation) {
        if (relation) return EQUALITY;
        var t = FieldTypeEnum.fromCode(type);
        if (t == FieldTypeEnum.FORMULA)
            return resultType == null || !FieldTypeEnum.containsCode(resultType)
                    ? List.of()
                    : operators(resultType, null, false);
        return switch (t) {
            case TEXT, TEXTAREA, AUTO_NUMBER ->
                    List.of("eq", "neq", "like", "notLike", "isNull", "notNull");
            case DATE, DATETIME, TIME -> List.of("lt", "gt", "between", "isNull", "notNull");
            case INTEGER, DECIMAL, MONEY, PERCENT ->
                    List.of("eq", "neq", "gt", "gte", "lt", "lte", "isNull", "notNull");
            case SELECT, REFERENCE, USER, DEPARTMENT, ORGANIZATION, POST, USER_GROUP, BOOLEAN ->
                    EQUALITY;
            case MULTI_SELECT -> List.of("containsAny", "isNull", "notNull");
            default -> List.of();
        };
    }

    /** 只比相等与否的字段（选项、关联、目录、布尔）的词表。 */
    private static final List<String> EQUALITY = List.of("eq", "neq", "isNull", "notNull");

    /** 按记录匹配（$record）只能使用等于。 */
    public static List<String> recordKeyOperators() {
        return List.of("eq");
    }
}
