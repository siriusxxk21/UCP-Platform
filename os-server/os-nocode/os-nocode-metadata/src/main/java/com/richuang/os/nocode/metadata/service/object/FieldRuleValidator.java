package com.richuang.os.nocode.metadata.service.object;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.DataCenter.*;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.metadata.service.form.DocumentPolicies;
import com.richuang.os.nocode.metadata.service.formula.Calculations;
import com.richuang.os.nocode.metadata.service.formula.FieldExpressions;
import com.richuang.os.nocode.metadata.service.formula.FormulaDates;
import com.richuang.os.nocode.metadata.service.formula.MoneyRounding;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 字段对象规则（引用筛选、数据联动、公式默认值、挑取值、取整方式）的保存与发布校验。 报错点名「字段 · 规则 · 原因」；来源对象只从调用方给出的固定定义解析，
 * 本类不读取数据库，也不接受请求中的配置（引用字段固定值是否存在由调用方给出的 {@link ReferenceRecordLookup} 回答）。
 */
public final class FieldRuleValidator {
    static final int MAX_CONDITIONS = 20;
    private static final int MAX_CONSTANT_ITEMS = 100;

    /** 对象规则里日期字段能与相对日期组合的比较方式（日期字段的规则词表只有早于 / 晚于 / 在范围内）。 */
    private static final Set<String> RELATIVE_OPERATORS = Set.of("lt", "gt", "between");

    private static final Map<String, String> OPERATOR_LABELS =
            Map.ofEntries(
                    Map.entry("eq", "等于"),
                    Map.entry("neq", "不等于"),
                    Map.entry("gt", "大于"),
                    Map.entry("gte", "大于等于"),
                    Map.entry("lt", "小于"),
                    Map.entry("lte", "小于等于"),
                    Map.entry("like", "包含"),
                    Map.entry("notLike", "不包含"),
                    Map.entry("between", "在范围内"),
                    Map.entry("containsAny", "包含任一"),
                    Map.entry("isNull", "为空"),
                    Map.entry("notNull", "不为空"));

    /**
     * 文本目标可接收的来源值类型（业务方 2026-10-01 裁定）：单行文本收文本、自动编号、链接；多行文本另收多行文本。
     *
     * <p>多行内容不放进单行文本，故单行文本不收多行文本。计算字段先折成结果类型再查表；前端 textLinkageSources 是同一张表。
     */
    private static final Map<FieldTypeEnum, Set<FieldTypeEnum>> TEXT_SOURCES =
            Map.of(
                    FieldTypeEnum.TEXT,
                    Set.of(FieldTypeEnum.TEXT, FieldTypeEnum.AUTO_NUMBER, FieldTypeEnum.URL),
                    FieldTypeEnum.TEXTAREA,
                    Set.of(
                            FieldTypeEnum.TEXT,
                            FieldTypeEnum.TEXTAREA,
                            FieldTypeEnum.AUTO_NUMBER,
                            FieldTypeEnum.URL));

    /**
     * 一期可以开启「来源变化时自动更新」的目标字段类型。关联、目录（人员、部门等）、富文本、地区、级联、链接、附件不在其内：
     * 这些类型的写入要过操作者的引用可见性或目录校验，系统身份下的行为一期不接。
     */
    private static final Set<FieldTypeEnum> AUTO_UPDATE_TARGETS =
            Set.of(
                    FieldTypeEnum.TEXT,
                    FieldTypeEnum.TEXTAREA,
                    FieldTypeEnum.INTEGER,
                    FieldTypeEnum.DECIMAL,
                    FieldTypeEnum.MONEY,
                    FieldTypeEnum.PERCENT,
                    FieldTypeEnum.BOOLEAN,
                    FieldTypeEnum.DATE,
                    FieldTypeEnum.DATETIME,
                    FieldTypeEnum.TIME,
                    FieldTypeEnum.SELECT,
                    FieldTypeEnum.MULTI_SELECT);

    private FieldRuleValidator() {}

    /** 存量转换工具回滚时置位：要把引用字段的固定值恢复成转换前的存量值，这一项校验必须让路。只在本线程、只在 action 执行期间有效。 */
    private static final ThreadLocal<Boolean> RESTORING_STORED_CONSTANTS = new ThreadLocal<>();

    /** 在 action 执行期间不核引用字段的条件固定值（其余校验照常）。只给存量转换工具的回滚用：回滚的目的就是写回转换前的存量值（多半是名称文本）， 而对象设计保存现在会拒绝它。 */
    public static <T> T restoringStoredConstants(java.util.function.Supplier<T> action) {
        RESTORING_STORED_CONSTANTS.set(Boolean.TRUE);
        try {
            return action.get();
        } finally {
            RESTORING_STORED_CONSTANTS.remove();
        }
    }

    /** 规则所在字段及其表；detail 为空表示主表字段。 */
    private record Scope(
            Definition object,
            Detail detail,
            FieldDefinition field,
            FieldOptions options,
            Relation relation) {
        String label() {
            return detail == null
                    ? "字段「" + field.name() + "」"
                    : "明细「" + detail.name() + "」字段「" + field.name() + "」";
        }

        String tableName() {
            return detail == null ? "主表" : detail.name();
        }
    }

    /**
     * 存储前清除只在运行模型投影中由服务端填写的 dependsOn 与 readOnly，并把数据联动的两个可选键归一：autoUpdate 只有 true 才保留（false 归一为
     * null，存储里只出现「键不存在」或 true），空白的 emptyValue 归一为 null。绝不把 null 补成 true：存量联动没有这个键即关。
     */
    public static FieldRules normalize(FieldRules rules) {
        if (rules == null) return null;
        var linkage = normalize(rules.linkage());
        if ((rules.dependsOn() == null || rules.dependsOn().isEmpty())
                && rules.readOnly() == null
                && linkage == rules.linkage()) return rules;
        return new FieldRules(
                rules.reference(), linkage, rules.defaultFormula(), rules.rounding(), null, null);
    }

    private static FieldRules.Linkage normalize(FieldRules.Linkage l) {
        if (l == null) return null;
        Boolean autoUpdate = l.autoUpdateOn() ? Boolean.TRUE : null;
        String emptyValue =
                l.emptyValue() == null || l.emptyValue().isBlank() ? null : l.emptyValue();
        if (Objects.equals(autoUpdate, l.autoUpdate())
                && Objects.equals(emptyValue, l.emptyValue())) return l;
        return new FieldRules.Linkage(
                l.sourceObjectId(),
                l.conditions(),
                l.valueFieldId(),
                l.multiRow(),
                l.readOnly(),
                autoUpdate,
                emptyValue);
    }

    /** 与关系和其它对象无关的静态形状，设计保存时逐字段执行。 */
    public static void validateShape(FieldDefinition field, FieldOptions options) {
        shape("字段「" + field.name() + "」", field, options);
    }

    /**
     * 对象设计保存与发布快照前的完整校验。
     *
     * @param root 当前对象（草稿或固定版本）定义
     * @param definitions 其它对象的定义；返回 null 表示未发布、已停用或不可用
     * @param absent 对象不可用时的原因短语，例如「资金流水」未发布或已停用
     */
    public static void validate(
            Definition root,
            Function<String, Definition> definitions,
            Function<String, String> absent) {
        validate(root, definitions, absent, FieldRuleValidator::localChoices);
    }

    /** 同上；choices 给出来源字段实际生效的候选项（局部选项或公共字典项，不读业务行），用于判断挑取值来源字段是否有选项。 */
    public static void validate(
            Definition root,
            Function<String, Definition> definitions,
            Function<String, String> absent,
            BiFunction<FieldDefinition, FieldOptions, List<SelectionFields.Option>> choices) {
        validateAll(root, definitions, absent, choices, null);
    }

    /**
     * 同上；records 回答引用字段的条件固定值是不是目标对象里的记录，只在对象设计保存与发布时给出。为 null 时（应用发布、自动跟随）不核引用字段的固定值：
     * 自动跟随一次只同步一个对象，应用里另一个对象若还带着存量坏值，在这里挡住会让跟随永远发不出去；坏值由对象设计端阻断、运行时报明白的错。
     */
    public static void validate(
            Definition root,
            Function<String, Definition> definitions,
            Function<String, String> absent,
            BiFunction<FieldDefinition, FieldOptions, List<SelectionFields.Option>> choices,
            ReferenceRecordLookup records) {
        validateAll(root, definitions, absent, choices, records);
    }

    /** 未接入选择目录时只认局部选项；公共字典等动态来源视为没有可核实的选项。 */
    static List<SelectionFields.Option> localChoices(FieldDefinition field, FieldOptions options) {
        var source = SelectionFields.source(field, options);
        if (source != null && !SelectionSourceEnum.LOCAL_OPTIONS.matches(source.kind()))
            return List.of();
        return (options.options() == null ? List.<Option>of() : options.options())
                .stream()
                        .map(
                                o ->
                                        new SelectionFields.Option(
                                                o.code(),
                                                o.label(),
                                                o.code(),
                                                null,
                                                o.label(),
                                                Boolean.TRUE.equals(o.disabled()),
                                                false))
                        .toList();
    }

    /** 应用发布，挑取值来源只认局部选项；可解析公共字典时用带 choices 的重载。 */
    public static void validatePinned(
            Map<String, Definition> pinned, Function<String, String> absent) {
        validatePinned(pinned, absent, FieldRuleValidator::localChoices);
    }

    /** 应用发布：数据联动与挑取值的来源对象必须在本应用固定的对象版本中，字段、类型与生效选项都按固定版本重新校验。 */
    public static void validatePinned(
            Map<String, Definition> pinned,
            Function<String, String> absent,
            BiFunction<FieldDefinition, FieldOptions, List<SelectionFields.Option>> choices) {
        for (var d : pinned.values())
            validate(d, id -> id.equals(d.objectId()) ? d : pinned.get(id), absent, choices);
    }

    /** 规则引用到的其它对象字段，用于登记 OBJECT_RULE 依赖；键为其它对象 ID。 */
    public static Map<String, Set<String>> dependencies(Definition d) {
        Map<String, Set<String>> result = new TreeMap<>();
        for (var scope : scopes(d)) {
            var o = scope.options();
            var r = o.rules();
            if (r != null && r.linkage() != null && other(d, r.linkage().sourceObjectId())) {
                var fields =
                        result.computeIfAbsent(r.linkage().sourceObjectId(), k -> new TreeSet<>());
                if (r.linkage().valueFieldId() != null) fields.add(r.linkage().valueFieldId());
                conditionFields(r.linkage().conditions(), fields);
            }
            if (r != null
                    && r.reference() != null
                    && scope.relation() != null
                    && other(d, scope.relation().targetObjectId())) {
                var fields =
                        result.computeIfAbsent(
                                scope.relation().targetObjectId(), k -> new TreeSet<>());
                if (r.reference().labelFieldId() != null) fields.add(r.reference().labelFieldId());
                conditionFields(r.reference().filter(), fields);
            }
            var s = o.selection();
            if (s != null
                    && SelectionSourceEnum.OBJECT_FIELD_OPTIONS.matches(s.kind())
                    && other(d, s.sourceObjectId())
                    && s.sourceFieldId() != null)
                result.computeIfAbsent(s.sourceObjectId(), k -> new TreeSet<>())
                        .add(s.sourceFieldId());
        }
        result.values().removeIf(Set::isEmpty);
        return result;
    }

    /**
     * 某个字段被对象规则用到的位置；location 形如「主表字段 / 金额 / 数据联动」，reason 说明为何受类型变化影响。 pick 为真表示这是
     * 「挑取值」对来源字段选项集的引用：来源字段只要仍是单选或多选（哪怕选项来源从局部选项换成公共字典），挑取值照常取它实际生效的选项。
     */
    public record Usage(String location, String reason, boolean pick) {
        public Usage(String location, String reason) {
            this(location, reason, false);
        }
    }

    /**
     * owner 中有哪些字段的对象规则用到了 objectId 对象的 fieldId 字段：数据联动（取值字段、条件）、引用（显示名字段、筛选条件）、
     * 公式默认值与挑取值来源。供字段类型转换的依赖检查使用；被转换字段自身的规则不算在内（它随字段一起按新类型校验）。
     */
    public static List<Usage> usages(Definition owner, String objectId, String fieldId) {
        List<Usage> result = new ArrayList<>();
        boolean same = owner.objectId().equals(objectId);
        var scopes = scopes(owner);
        for (var scope : scopes) {
            if (fieldId.equals(scope.field().id())) continue;
            var o = scope.options();
            var r = o.rules();
            String at =
                    (scope.detail() == null ? "主表字段" : "内部明细 / " + scope.detail().name())
                            + " / "
                            + scope.field().name();
            if (r != null && r.linkage() != null) {
                var l = r.linkage();
                boolean source = objectId.equals(l.sourceObjectId());
                if (source && fieldId.equals(l.valueFieldId()))
                    result.add(new Usage(at + " / 数据联动", "数据联动仍按原类型取该字段的值，请先调整或移除该联动"));
                if (conditionUses(l.conditions(), source ? fieldId : null, same ? fieldId : null))
                    result.add(new Usage(at + " / 数据联动条件", "数据联动条件仍按原类型比较该字段，请先调整该条件"));
            }
            if (r != null && r.reference() != null && scope.relation() != null) {
                boolean target = objectId.equals(scope.relation().targetObjectId());
                if (target && fieldId.equals(r.reference().labelFieldId()))
                    result.add(new Usage(at + " / 显示名字段", "引用的显示名字段仍使用该字段，请先调整显示名字段"));
                if (conditionUses(
                        r.reference().filter(), target ? fieldId : null, same ? fieldId : null))
                    result.add(new Usage(at + " / 引用筛选", "引用筛选条件仍按原类型比较该字段，请先调整该条件"));
            }
            if (same
                    && r != null
                    && r.defaultFormula() != null
                    && formulaUses(scopes, scope, fieldId))
                result.add(new Usage(at + " / 公式默认值", "公式默认值使用该字段的原值类型，请先调整或删除该公式"));
            var s = o.selection();
            if (s != null
                    && SelectionSourceEnum.OBJECT_FIELD_OPTIONS.matches(s.kind())
                    && objectId.equals(s.sourceObjectId())
                    && fieldId.equals(s.sourceFieldId()))
                result.add(new Usage(at + " / 挑取值", "挑取值仍以该字段的选项为来源，请先调整挑取值来源", true));
        }
        return result;
    }

    /** sourceFieldId 比对条件左侧的来源对象字段，formFieldId 比对右侧的当前表单字段；传 null 表示该侧不属于被检查的对象。 */
    private static boolean conditionUses(
            List<FieldRules.Condition> conditions, String sourceFieldId, String formFieldId) {
        if (conditions == null) return false;
        for (var c : conditions) {
            if (c == null) continue;
            if (sourceFieldId != null && sourceFieldId.equals(c.fieldId())) return true;
            if (formFieldId != null
                    && RuleValueSourceEnum.FORM_FIELD.matches(c.valueSource())
                    && formFieldId.equals(c.formFieldId())) return true;
        }
        return false;
    }

    /** 公式按编码引用字段：主表字段的公式只看主表，明细字段的公式先看本行、再看主表（与 validateFormula 的解析范围一致）。 */
    private static boolean formulaUses(List<Scope> scopes, Scope scope, String fieldId) {
        Map<String, String> columns = new HashMap<>();
        Map<String, String> ids = new HashMap<>();
        for (var s : scopes)
            if (s.detail() == null && !FieldTypeEnum.fromCode(s.field().type()).isComputed()) {
                columns.put(s.field().code(), s.field().code());
                ids.put(s.field().code(), s.field().id());
            }
        if (scope.detail() != null)
            for (var s : scopes)
                if (s.detail() != null
                        && s.detail().id().equals(scope.detail().id())
                        && !FieldTypeEnum.fromCode(s.field().type()).isComputed()) {
                    columns.put(s.field().code(), s.field().code());
                    ids.put(s.field().code(), s.field().id());
                }
        try {
            return FieldExpressions.parse(scope.options().rules().defaultFormula(), columns)
                    .references()
                    .stream()
                    .map(ids::get)
                    .anyMatch(fieldId::equals);
        } catch (ServiceException unparsable) {
            // 解析不了的公式由保存校验点名报错；被转换字段此时可能已不满足公式要求，按编码出现与否保守判断。
            String code =
                    scopes.stream()
                            .filter(s -> fieldId.equals(s.field().id()))
                            .map(s -> s.field().code())
                            .findFirst()
                            .orElse(null);
            return code != null
                    && java.util.regex.Pattern.compile(
                                    "(?<![A-Za-z0-9_])" + code + "(?![A-Za-z0-9_])")
                            .matcher(scope.options().rules().defaultFormula())
                            .find();
        }
    }

    private static void validateAll(
            Definition root,
            Function<String, Definition> definitions,
            Function<String, String> absent,
            BiFunction<FieldDefinition, FieldOptions, List<SelectionFields.Option>> choices,
            ReferenceRecordLookup records) {
        var scopes = scopes(root);
        Map<String, Scope> byId = new HashMap<>();
        scopes.forEach(s -> byId.put(s.field().id(), s));
        Map<String, Definition> cache = new HashMap<>();
        Function<String, Definition> resolve =
                id -> {
                    if (id == null) return null;
                    if (id.equals(root.objectId())) return root;
                    return cache.computeIfAbsent(
                            id,
                            key -> {
                                try {
                                    return definitions.apply(key);
                                } catch (ServiceException unavailable) {
                                    return null;
                                }
                            });
                };
        var context = new Context(root, byId, resolve, absent, choices, records);
        for (var scope : scopes) {
            var o = scope.options();
            var r = o.rules();
            var selection = o.selection();
            boolean pick =
                    selection != null
                            && SelectionSourceEnum.OBJECT_FIELD_OPTIONS.matches(selection.kind());
            if (r == null && !pick) continue;
            shape(scope.label(), scope.field(), o);
            if (pick) validatePick(context, scope, selection);
            if (r == null) continue;
            String type = scope.field().type();
            String relationKind = scope.relation() == null ? null : scope.relation().kind();
            if (r.reference() != null) {
                if (!FieldRuleMatrix.allows(
                        type, relationKind, FieldRuleMatrix.MODE_REFERENCE_LABEL))
                    throw invalid(scope.label() + "不是关联字段，不能设置显示名字段或引用筛选");
                if (r.reference().filter() != null
                        && !r.reference().filter().isEmpty()
                        && !FieldRuleMatrix.allows(
                                type, relationKind, FieldRuleMatrix.MODE_REFERENCE_FILTER))
                    throw invalid(scope.label() + "是主从关系的父键，只能设置显示名字段，不能设置引用筛选");
                validateReference(context, scope, r.reference());
            }
            if (r.linkage() != null) {
                if (!FieldRuleMatrix.allows(type, relationKind, FieldRuleMatrix.MODE_LINKAGE))
                    throw invalid(scope.label() + "不支持数据联动");
                validateLinkage(context, scope, r.linkage());
            }
            if (r.defaultFormula() != null) {
                if (!FieldRuleMatrix.allows(type, relationKind, FieldRuleMatrix.MODE_FORMULA))
                    throw invalid(scope.label() + "不支持公式默认值");
                validateFormula(scopes, scope, r);
            }
        }
        var cycle = FieldRuleGraph.of(root).cycle();
        if (!cycle.isEmpty())
            throw invalid(
                    "数据联动 / 公式默认值存在循环依赖："
                            + String.join(
                                    " → ",
                                    cycle.stream()
                                            .map(
                                                    id -> {
                                                        var s = byId.get(id);
                                                        return s == null
                                                                ? id
                                                                : s.tableName()
                                                                        + " · "
                                                                        + s.field().name();
                                                    })
                                            .toList()));
    }

    private record Context(
            Definition root,
            Map<String, Scope> fields,
            Function<String, Definition> definitions,
            Function<String, String> absent,
            BiFunction<FieldDefinition, FieldOptions, List<SelectionFields.Option>> choices,
            ReferenceRecordLookup records) {}

    private static void shape(String label, FieldDefinition field, FieldOptions o) {
        var r = o.rules();
        boolean option = FieldRuleMatrix.optionType(field);
        if (!option) {
            List<String> configured = new ArrayList<>();
            if (o.defaultValue() != null) configured.add("自定义默认值");
            if (r != null && r.linkage() != null) configured.add("数据联动");
            if (r != null && r.defaultFormula() != null) configured.add("公式默认值");
            if (configured.size() > 1)
                throw invalid("「" + field.name() + "」同时配置了" + joinAnd(configured) + "，只能保留一种");
        }
        if (r == null) return;
        if (r.linkage() != null) {
            var l = r.linkage();
            conditionShape(label + "的数据联动", l.conditions(), l);
            autoUpdateShape(label + "的数据联动", field, l);
            if (l.multiRow() != null && !LinkageMultiRowEnum.containsCode(l.multiRow()))
                throw invalid(label + "的数据联动：多行匹配档位「" + l.multiRow() + "」无效");
            if (FieldTypeEnum.MULTI_SELECT.matches(field.type())
                    && (l.multiRow() == null || LinkageMultiRowEnum.CONCAT.matches(l.multiRow())))
                throw invalid(label + "的数据联动：多选字段不能使用「拼接成一行」，请选择「取第一行」或「报错」");
        }
        if (r.reference() != null) conditionShape(label + "的引用筛选", r.reference().filter(), null);
        if (r.defaultFormula() != null) {
            if (option) throw invalid("「" + field.name() + "」是选项类字段，不能设置公式默认值");
            if (r.defaultFormula().isBlank()) throw invalid(label + "的公式默认值不能为空");
        }
        if (r.rounding() != null) {
            if (!FieldTypeEnum.MONEY.matches(field.type()))
                throw invalid(label + "不是金额字段，不能设置取整方式（非金额数值保持小数）");
            if (r.linkage() == null && r.defaultFormula() == null)
                throw invalid(label + "的取整方式只用于数据联动或公式默认值");
            if (!MoneyRoundingEnum.containsCode(r.rounding()))
                throw invalid(label + "的取整方式「" + r.rounding() + "」无效，可选：四舍五入 / 向下取整 / 去掉小数");
        }
    }

    /** 「来源变化时自动更新」与「没有匹配记录时填入」的形状：与其它对象无关，设计保存时就拦。 */
    private static void autoUpdateShape(
            String prefix, FieldDefinition field, FieldRules.Linkage l) {
        if (l.autoUpdateOn() && !l.readOnlyOrDefault())
            throw invalid(prefix + "：开启「来源变化时自动更新」的字段必须只读（可手改的联动不跟随来源变化），请先打开「当前字段只读」");
        String emptyValue = l.emptyValue();
        if (emptyValue == null || emptyValue.isBlank()) return;
        if (!l.autoUpdateOn()) throw invalid(prefix + "：「没有匹配记录时填入」只在开启「来源变化时自动更新」后可用");
        var problem = LinkageEmptyValues.check(field, emptyValue);
        if (problem == null) return;
        throw invalid(
                prefix
                        + switch (problem) {
                            case UNSUPPORTED_TYPE -> "：这种类型的字段暂不支持「没有匹配记录时填入」";
                            case MONEY_NOT_INTEGER -> "：金额按日元整数保存，「没有匹配记录时填入」请填写整数";
                            case TEXT_TOO_LONG ->
                                    "：「没有匹配记录时填入」的值超过字段长度上限 " + LinkageEmptyValues.textLimit(field);
                            case MALFORMED -> "：「没有匹配记录时填入」的值「" + emptyValue + "」不符合字段类型";
                        });
    }

    /**
     * 条件结构只有一层且之间只有“且”；值为标量或受控列表，不接受对象（即不能夹带分组或嵌套条件）。
     *
     * @param linkage 条件所属的数据联动；引用筛选传 null（「等于当前记录」只在开启自动更新的数据联动上有效）
     */
    private static void conditionShape(
            String prefix, List<FieldRules.Condition> conditions, FieldRules.Linkage linkage) {
        if (conditions == null) return;
        if (conditions.size() > MAX_CONDITIONS) throw invalid(prefix + "：条件最多 20 条");
        int currentRecords = 0;
        for (var c : conditions) {
            if (c == null || c.fieldId() == null || c.fieldId().isBlank())
                throw invalid(prefix + "：条件缺少来源字段");
            if (!FieldRuleMatrix.OPERATORS.contains(c.operator()))
                throw invalid(prefix + "：条件算子「" + c.operator() + "」无效");
            if (!RuleValueSourceEnum.containsCode(c.valueSource())
                    || linkage == null
                            && RuleValueSourceEnum.CURRENT_RECORD.matches(c.valueSource()))
                throw invalid(prefix + "：条件值来源「" + c.valueSource() + "」无效");
            if (RuleValueSourceEnum.CURRENT_RECORD.matches(c.valueSource())) {
                if (!linkage.autoUpdateOn())
                    throw invalid(prefix + "：「等于当前记录」只用于开启了「来源变化时自动更新」的联动");
                if (FieldRules.RECORD_KEY.equals(c.fieldId()))
                    throw invalid(prefix + "：「按记录匹配」不能与「当前记录」组合，请改选来源对象上的关联字段");
                if (!"eq".equals(c.operator()) || c.value() != null || c.formFieldId() != null)
                    throw invalid(prefix + "：「等于当前记录」只能使用「等于」，且不需要比较值");
                if (++currentRecords > 1) throw invalid(prefix + "：「等于当前记录」的条件只能有一条");
                continue;
            }
            boolean form = RuleValueSourceEnum.FORM_FIELD.matches(c.valueSource());
            boolean valueless = FieldRuleMatrix.valueless(c.operator());
            if (valueless) {
                // 为空 / 不为空只看来源字段本身：带了固定值或当前字段都说明配置与意图不符，拒绝而不是悄悄忽略。
                if (form || c.formFieldId() != null || c.value() != null)
                    throw invalid(
                            prefix
                                    + "：「"
                                    + OPERATOR_LABELS.get(c.operator())
                                    + "」不需要比较值，不能填写固定值或当前字段");
            } else if (form) {
                if (c.formFieldId() == null || c.formFieldId().isBlank())
                    throw invalid(prefix + "：条件缺少当前字段");
                if (c.value() != null) throw invalid(prefix + "：当前字段条件不能同时填写固定值");
            } else {
                if (c.formFieldId() != null) throw invalid(prefix + "：固定值条件不能同时指定当前字段");
                if (c.value() == null) throw invalid(prefix + "：条件缺少固定值");
            }
            if (FieldRules.RECORD_KEY.equals(c.fieldId()) && !"eq".equals(c.operator()))
                throw invalid(prefix + "：「按记录匹配」只能使用「等于」");
            if (valueless) continue;
            // 相对日期（今天、本周、过去 N 天……）：只在引用筛选里认——候选每次现查、按当天换算；数据联动取到的值写进字段，过了零点不会自己变。
            if (!form && RelativeDates.isRelative(c.value())) {
                if (linkage != null)
                    throw invalid(
                            prefix
                                    + "：数据联动不支持相对日期（今天、本月等）。联动取到的值会写进字段，过了零点不会自己变；"
                                    + "请改用具体日期，或在视图、统计视图的条件里使用相对日期");
                if (!RELATIVE_OPERATORS.contains(c.operator()))
                    throw invalid(prefix + "：相对日期只能与「早于」「晚于」「在范围内」组合");
                try {
                    RelativeDates.parse(c.value());
                } catch (ServiceException e) {
                    throw invalid(prefix + "：" + e.getMessage());
                }
                continue;
            }
            if ("between".equals(c.operator())) {
                if (form
                        || !(c.value() instanceof List<?> range)
                        || range.size() != 2
                        || range.stream().anyMatch(v -> !scalar(v)))
                    throw invalid(prefix + "：「在范围内」只能使用固定值，并填写起止两个值");
            } else if (!form) {
                boolean valid =
                        scalar(c.value())
                                || "containsAny".equals(c.operator())
                                        && c.value() instanceof List<?> items
                                        && !items.isEmpty()
                                        && items.size() <= MAX_CONSTANT_ITEMS
                                        && items.stream().allMatch(FieldRuleValidator::scalar);
                if (!valid) throw invalid(prefix + "：条件值格式无效（条件之间只有“且”，不支持分组或嵌套）");
            }
        }
    }

    private static boolean scalar(Object value) {
        return value instanceof String text && text.length() <= 4000
                || value instanceof Number
                || value instanceof Boolean;
    }

    private static void validateReference(
            Context context, Scope scope, FieldRules.Reference reference) {
        String prefix = scope.label() + "的引用筛选";
        var relation = scope.relation();
        var target = context.definitions().apply(relation.targetObjectId());
        if (target == null)
            throw invalid(prefix + "：目标对象" + context.absent().apply(relation.targetObjectId()));
        if (reference.labelFieldId() != null) {
            var label = activeField(target, reference.labelFieldId());
            if (label == null)
                throw invalid(
                        scope.label()
                                + "的显示名字段：目标字段「"
                                + fieldName(target, reference.labelFieldId())
                                + "」已不存在或已停用");
            if (!BusinessFields.linkable(label))
                throw invalid(scope.label() + "的显示名字段：显示名字段「" + label.name() + "」不可用作显示名");
        }
        conditions(context, scope, prefix, reference.filter(), target, false);
    }

    private static void validateLinkage(Context context, Scope scope, FieldRules.Linkage l) {
        String prefix = scope.label() + "的数据联动";
        if (l.sourceObjectId() == null || l.sourceObjectId().isBlank())
            throw invalid(prefix + "：请选择来源对象");
        if (l.valueFieldId() == null || l.valueFieldId().isBlank())
            throw invalid(prefix + "：请选择带入的来源字段");
        boolean auto = l.autoUpdateOn();
        if (l.emptyValue() != null && scope.relation() != null)
            throw invalid(prefix + "：这种类型的字段暂不支持「没有匹配记录时填入」");
        if (auto) autoUpdateTarget(context, scope, prefix, l);
        var source = context.definitions().apply(l.sourceObjectId());
        if (source == null)
            throw invalid(prefix + "：来源对象" + context.absent().apply(l.sourceObjectId()));
        var value = activeField(source, l.valueFieldId());
        if (value == null)
            throw invalid(prefix + "：来源字段「" + fieldName(source, l.valueFieldId()) + "」已不存在或已停用");
        if (auto && FieldTypeEnum.fromCode(value.type()).isComputed())
            throw invalid(prefix + "：开启自动更新时，带入的来源字段「" + value.name() + "」不能是计算字段");
        var mode =
                l.multiRow() == null
                        ? LinkageMultiRowEnum.CONCAT
                        : LinkageMultiRowEnum.fromCode(l.multiRow());
        if (mode == LinkageMultiRowEnum.SUM && !numeric(source, value))
            throw invalid(prefix + "：「求和」只适用于数值或金额来源");
        transferable(scope, prefix, source, value);
        conditions(context, scope, prefix, l.conditions(), source, auto);
        if (!auto) return;
        if (anchor(l) == null)
            throw invalid(
                    prefix
                            + "：自动更新需要一条按记录匹配的条件——「来源对象的关联字段 等于 当前记录」，或「按记录匹配 等于"
                            + " 当前字段（指向来源对象的关联字段）」；否则无法确定哪些记录要更新");
        if (l.emptyValue() != null && FieldTypeEnum.SELECT.matches(scope.field().type())) {
            var codes = choiceCodes(context, scope.field(), scope.options(), true);
            if (!codes.isEmpty() && !codes.contains(l.emptyValue()))
                throw invalid(
                        prefix
                                + "：「没有匹配记录时填入」的值「"
                                + l.emptyValue()
                                + "」不是字段「"
                                + scope.field().name()
                                + "」的有效选项，请从选项中选择");
        }
    }

    /** 开启自动更新对目标字段本身的一期限制：主表字段、来源不是本对象、类型在白名单内、不受单据状态控制。 */
    private static void autoUpdateTarget(
            Context context, Scope scope, String prefix, FieldRules.Linkage l) {
        if (scope.detail() != null) throw invalid(prefix + "：明细字段的数据联动暂不支持自动更新");
        if (l.sourceObjectId().equals(context.root().objectId()))
            throw invalid(prefix + "：来源对象是本对象的联动暂不支持自动更新");
        if (scope.relation() != null
                || BusinessFields.relation(context.root(), scope.field().id()) != null
                || !AUTO_UPDATE_TARGETS.contains(FieldTypeEnum.fromCode(scope.field().type())))
            throw invalid(
                    prefix
                            + "：「"
                            + scope.field().name()
                            + "」这种类型的字段暂不支持自动更新（一期支持：文本、数值、金额、百分比、布尔、日期时间、单选、多选）");
        var policy = DocumentPolicies.policy(context.root());
        var lifecycle = policy == null ? null : policy.lifecycle();
        if (lifecycle == null) return;
        if (scope.field().id().equals(lifecycle.fieldId()))
            throw invalid(prefix + "：由状态动作维护的状态字段不能开启自动更新");
        for (var state : lifecycle.states())
            if (state.lockedFields().contains(scope.field().id()))
                throw invalid(
                        prefix + "：字段在单据状态「" + state.name() + "」下被锁定，不能开启自动更新；请先把它从该状态的锁定字段中移除");
    }

    /**
     * 自动更新的锚点（按记录匹配的那条条件）：「来源对象的关联字段 等于 当前记录」优先（不查库就知道牵动谁），其次是 「按记录匹配 等于 当前字段」。没有锚点时无法确定哪些记录要更新，返回
     * null。
     */
    public static FieldRules.Condition anchor(FieldRules.Linkage l) {
        if (l == null || l.conditions() == null) return null;
        FieldRules.Condition recordKey = null;
        for (var c : l.conditions()) {
            if (c == null) continue;
            if (RuleValueSourceEnum.CURRENT_RECORD.matches(c.valueSource())) return c;
            if (recordKey == null
                    && FieldRules.RECORD_KEY.equals(c.fieldId())
                    && "eq".equals(c.operator())
                    && RuleValueSourceEnum.FORM_FIELD.matches(c.valueSource())
                    && c.formFieldId() != null) recordKey = c;
        }
        return recordKey;
    }

    /**
     * 来源值能否写入目标：引用对引用（同一目标对象）；选项对同一选择身份，或一方的挑取值正好指向另一方（目标挑来源、来源挑目标均可）。
     *
     * <p>文本目标查 TEXT_SOURCES 表；其余标量同类型或数值对数值。
     */
    private static void transferable(
            Scope scope, String prefix, Definition source, FieldDefinition value) {
        var target = scope.field();
        var sourceOptions = options(source, value.id());
        boolean ok;
        if (scope.relation() != null) {
            var sourceRelation = BusinessFields.relation(source, value.id());
            ok =
                    sourceRelation != null
                            && !BusinessFields.multiple(sourceRelation)
                            && Objects.equals(
                                    sourceRelation.targetObjectId(),
                                    scope.relation().targetObjectId());
        } else if (FieldRuleMatrix.optionType(target)) {
            var selection = scope.options().selection();
            boolean pickSame =
                    selection != null
                            && SelectionSourceEnum.OBJECT_FIELD_OPTIONS.matches(selection.kind())
                            && Objects.equals(selection.sourceObjectId(), source.objectId())
                            && Objects.equals(selection.sourceFieldId(), value.id());
            // 反方向（2026-10-01）：来源字段的挑取值指向当前字段，二者共用当前字段的选项集。
            var picked = sourceOptions.selection();
            boolean pickedFromTarget =
                    picked != null
                            && SelectionSourceEnum.OBJECT_FIELD_OPTIONS.matches(picked.kind())
                            && Objects.equals(picked.sourceObjectId(), scope.object().objectId())
                            && Objects.equals(picked.sourceFieldId(), target.id());
            ok =
                    FieldRuleMatrix.optionType(value)
                            && SelectionFields.multiple(value) == SelectionFields.multiple(target)
                            && (pickSame
                                    || pickedFromTarget
                                    || sameSelection(
                                            value, sourceOptions, target, scope.options()));
        } else {
            var targetType = FieldTypeEnum.fromCode(target.type());
            String sourceType = valueType(value, sourceOptions);
            if (SelectionFields.DIRECTORIES.contains(targetType))
                ok = targetType.matches(sourceType);
            else if (targetType == FieldTypeEnum.RICH_TEXT)
                ok =
                        FieldTypeEnum.RICH_TEXT.matches(sourceType)
                                || FieldTypeEnum.TEXTAREA.matches(sourceType);
            else {
                var known =
                        FieldTypeEnum.containsCode(sourceType)
                                ? FieldTypeEnum.fromCode(sourceType)
                                : null;
                var textSources = TEXT_SOURCES.get(targetType);
                ok =
                        BusinessFields.relation(source, value.id()) == null
                                && !FieldRuleMatrix.optionType(value)
                                && (textSources != null
                                        ? known != null && textSources.contains(known)
                                        : targetType.matches(sourceType)
                                                || targetType.isNumeric()
                                                        && known != null
                                                        && known.isNumeric());
            }
        }
        if (!ok)
            throw invalid(
                    prefix
                            + "：来源「"
                            + value.name()
                            + "」（"
                            + typeLabel(source, value)
                            + "）不能带入到「"
                            + target.name()
                            + "」（"
                            + typeLabel(scope.object(), target)
                            + "）");
    }

    /**
     * 来源与目标是否同一套选择：选择身份一致（同一公共字典、同一系统目录等）。两边都是局部选项时，身份串相同只说明「都是局部选项」，
     * 互不相干的两套局部选项不能互相带入——要求编码→标签逐项一致，与表单带入沿用的 SelectionCompatibility 同口径。
     */
    private static boolean sameSelection(
            FieldDefinition value,
            FieldOptions sourceOptions,
            FieldDefinition target,
            FieldOptions targetOptions) {
        if (!SelectionFields.identity(value, sourceOptions)
                .equals(SelectionFields.identity(target, targetOptions))) return false;
        var from = SelectionFields.source(value, sourceOptions);
        var to = SelectionFields.source(target, targetOptions);
        boolean local =
                from != null
                        && to != null
                        && SelectionSourceEnum.LOCAL_OPTIONS.matches(from.kind())
                        && SelectionSourceEnum.LOCAL_OPTIONS.matches(to.kind());
        return !local
                || SelectionCompatibility.sameLocalOptions(
                        sourceOptions.options(), targetOptions.options());
    }

    /**
     * @param auto 条件所属的数据联动开启了自动更新（引用筛选恒为 false）：此时认「等于当前记录」，且条件字段不能是有序落库计算字段
     */
    private static void conditions(
            Context context,
            Scope scope,
            String prefix,
            List<FieldRules.Condition> conditions,
            Definition subject,
            boolean auto) {
        if (conditions == null) return;
        for (var c : conditions) {
            if (RuleValueSourceEnum.CURRENT_RECORD.matches(c.valueSource())) {
                var field = activeField(subject, c.fieldId());
                if (field == null)
                    throw invalid(
                            prefix + "：条件字段「" + fieldName(subject, c.fieldId()) + "」已不存在或已停用");
                var relation = singleRelation(subject, field.id());
                if (relation == null
                        || relation.sourceDetailId() != null
                        || !Objects.equals(relation.targetObjectId(), context.root().objectId()))
                    throw invalid(
                            prefix
                                    + "：「等于当前记录」只能用于来源对象上指向「"
                                    + context.root().objectName()
                                    + "」的单选关联字段");
                continue;
            }
            boolean form = RuleValueSourceEnum.FORM_FIELD.matches(c.valueSource());
            Scope current = form ? current(context, scope, prefix, c.formFieldId()) : null;
            if (FieldRules.RECORD_KEY.equals(c.fieldId())) {
                if (form) {
                    var relation = current.relation();
                    if (relation == null
                            || BusinessFields.multiple(relation)
                            || !Objects.equals(relation.targetObjectId(), subject.objectId()))
                        throw invalid(
                                prefix + "：「按记录匹配」的当前字段必须是指向「" + subject.objectName() + "」的关联字段");
                }
                continue;
            }
            var field = activeField(subject, c.fieldId());
            if (field == null)
                throw invalid(prefix + "：条件字段「" + fieldName(subject, c.fieldId()) + "」已不存在或已停用");
            var options = options(subject, field.id());
            if (auto && Calculations.orderedStored(options))
                throw invalid(prefix + "：开启自动更新时，条件字段「" + field.name() + "」不能是有序计算字段");
            var reference = singleRelation(subject, field.id());
            boolean relation = reference != null;
            var operators =
                    Calculations.live(options)
                            ? List.<String>of()
                            : FieldRuleMatrix.operators(
                                    field.type(), options.resultType(), relation);
            if (operators.isEmpty()) throw invalid(prefix + "：条件字段「" + field.name() + "」不支持筛选");
            if (!operators.contains(c.operator()))
                throw invalid(
                        prefix
                                + "：「"
                                + field.name()
                                + "」不能用「"
                                + OPERATOR_LABELS.getOrDefault(c.operator(), c.operator())
                                + "」");
            if (!form) {
                if (RelativeDates.isRelative(c.value())) {
                    if (!FieldTypeEnum.containsCode(field.type())
                            || !RelativeDates.supports(FieldTypeEnum.fromCode(field.type())))
                        throw invalid(prefix + "：「" + field.name() + "」不是日期或日期时间字段，不能使用相对日期");
                } else if (!FieldRuleMatrix.valueless(c.operator())) {
                    constantChoice(context, prefix, field, options, c.value());
                    if (reference != null)
                        constantReference(
                                context,
                                prefix,
                                field,
                                options,
                                reference,
                                c.operator(),
                                c.value());
                }
                continue;
            }
            var formField = current.field();
            if (FieldTypeEnum.fromCode(formField.type()).isComputed())
                throw invalid(prefix + "：当前字段「" + formField.name() + "」是计算字段，不能作为条件值");
            String left = kind(subject, field, options);
            String right = kind(current.object(), formField, current.options());
            if (!left.equals(right))
                throw invalid(
                        prefix
                                + "：条件「"
                                + field.name()
                                + " "
                                + OPERATOR_LABELS.getOrDefault(c.operator(), c.operator())
                                + " 当前字段·"
                                + formField.name()
                                + "」两侧类型不一致（"
                                + versus(kindLabel(context, left), kindLabel(context, right))
                                + "）");
        }
    }

    /**
     * 选项类条件字段（单选、多选）的固定值必须是该字段选项的编码（2026-10-01）。库里存的是编码：填了选项名称（如「已录入」而编码是 ylr）永远比不中， 候选恒为空，却没有任何报错。
     *
     * <p>停用的选项仍算存在（已有记录里可能还是这个值，按它筛选仍有意义）。选项集解析不出来或为空时不拦：无从判断，不能因此挡住整个对象的保存。
     */
    private static void constantChoice(
            Context context,
            String prefix,
            FieldDefinition field,
            FieldOptions options,
            Object value) {
        var type = FieldTypeEnum.fromCode(field.type());
        if (type != FieldTypeEnum.SELECT && type != FieldTypeEnum.MULTI_SELECT) return;
        var codes = choiceCodes(context, field, options, false);
        if (codes.isEmpty()) return;
        for (Object v : value instanceof List<?> items ? items : List.of(value))
            if (!codes.contains(String.valueOf(v)))
                throw invalid(
                        prefix + "：条件字段「" + field.name() + "」的固定值「" + v + "」不是该字段的有效选项，请从选项中选择");
    }

    /**
     * 引用字段（单值关系）的固定值必须是目标对象里的记录 ID（业务方 2026-10-04）。条件行原先给的是自由文本框，填了名称「民宿管理」而库里存的是记录 ID，
     * 运行时按字段类型转换失败，候选恒为空、保存被拦。只在对象设计保存 / 发布时核（records 不为 null）：先核格式（与运行时同一个转换函数）， 再由 records
     * 核是否存在；目标对象取不到或 records 答不上来时只核格式。
     */
    private static void constantReference(
            Context context,
            String prefix,
            FieldDefinition field,
            FieldOptions options,
            Relation relation,
            String operator,
            Object value) {
        if (context.records() == null || Boolean.TRUE.equals(RESTORING_STORED_CONSTANTS.get()))
            return;
        var target = context.definitions().apply(relation.targetObjectId());
        String targetName = target == null ? field.name() : target.objectName();
        List<String> ids = new ArrayList<>();
        for (Object v : value instanceof List<?> items ? items : List.of(value)) {
            try {
                RecordConditionValues.value(
                        field, options, v, RecordQueryOperatorEnum.fromCode(operator));
            } catch (ServiceException malformed) {
                throw notRecord(prefix, field, v, targetName);
            }
            ids.add(String.valueOf(v));
        }
        if (target == null) return;
        var existing = context.records().existing(relation.targetObjectId(), ids);
        if (existing == null) return;
        for (String id : ids)
            if (!existing.contains(id)) throw notRecord(prefix, field, id, targetName);
    }

    private static ServiceException notRecord(
            String prefix, FieldDefinition field, Object value, String targetName) {
        return invalid(
                prefix
                        + "：条件「"
                        + field.name()
                        + "」的固定值「"
                        + value
                        + "」不是「"
                        + targetName
                        + "」里的记录，请重新选择");
    }

    /**
     * 字段实际生效的选项编码：局部选项、公共字典；挑取值取它指向的来源字段的选项（来源对象按调用方给出的固定定义解析）。空集表示无从核对。
     *
     * @param enabledOnly 为真时去掉停用项（写入新值的场合：停用的选项不能再被选中）
     */
    private static Set<String> choiceCodes(
            Context context, FieldDefinition field, FieldOptions options, boolean enabledOnly) {
        var target = field;
        var targetOptions = options;
        var source = SelectionFields.source(field, options);
        if (source != null && SelectionSourceEnum.OBJECT_FIELD_OPTIONS.matches(source.kind())) {
            var origin =
                    source.sourceObjectId() == null
                            ? null
                            : context.definitions().apply(source.sourceObjectId());
            target =
                    origin == null || source.sourceFieldId() == null
                            ? null
                            : activeField(origin, source.sourceFieldId());
            if (target == null) return Set.of();
            targetOptions = options(origin, target.id());
        }
        var effective = SelectionFields.source(target, targetOptions);
        boolean local =
                effective == null || SelectionSourceEnum.LOCAL_OPTIONS.matches(effective.kind());
        // 只核对「选项」：局部选项与公共字典。多选字段配成组织目录时值是目录 ID，不在此列（目录可达上万项，也不是选项编码）。
        if (!local && !SelectionSourceEnum.SYSTEM_DICTIONARY.matches(effective.kind()))
            return Set.of();
        List<SelectionFields.Option> available;
        try {
            // 局部选项就在定义里，不必经选择目录；公共字典由调用方给出的 choices 解析。
            available =
                    local
                            ? localChoices(target, targetOptions)
                            : context.choices().apply(target, targetOptions);
        } catch (ServiceException unavailable) {
            return Set.of();
        }
        Set<String> codes = new HashSet<>();
        if (available != null)
            for (var o : available)
                if (o != null && o.value() != null && !(enabledOnly && o.disabled()))
                    codes.add(o.value());
        return codes;
    }

    /** 当前字段的作用域：主表规则只能引用主表字段；明细规则可引用本行字段或主表字段。 */
    private static Scope current(Context context, Scope scope, String prefix, String formFieldId) {
        var found = context.fields().get(formFieldId);
        if (found == null) throw invalid(prefix + "：条件引用的当前字段「" + formFieldId + "」不存在或已停用");
        if (scope.detail() == null && found.detail() != null)
            throw invalid(
                    "主表字段「"
                            + scope.field().name()
                            + "」的规则不能引用明细字段「"
                            + found.field().name()
                            + "」（一对多，无法确定取哪一行）");
        if (scope.detail() != null
                && found.detail() != null
                && !found.detail().id().equals(scope.detail().id()))
            throw invalid(
                    "明细「"
                            + scope.detail().name()
                            + "」字段「"
                            + scope.field().name()
                            + "」的规则不能引用明细「"
                            + found.detail().name()
                            + "」的字段「"
                            + found.field().name()
                            + "」");
        return found;
    }

    private static void validateFormula(List<Scope> scopes, Scope scope, FieldRules rules) {
        String prefix = scope.label() + "的公式默认值";
        Map<String, String> columns = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (var s : scopes)
            if (s.detail() == null && !FieldTypeEnum.fromCode(s.field().type()).isComputed())
                columns.put(s.field().code(), s.field().code());
        if (scope.detail() != null)
            for (var s : scopes)
                if (s.detail() != null
                        && s.detail().id().equals(scope.detail().id())
                        && !FieldTypeEnum.fromCode(s.field().type()).isComputed()) {
                    if (columns.containsKey(s.field().code())) ambiguous.add(s.field().code());
                    columns.put(s.field().code(), s.field().code());
                }
        FieldExpressions.Parsed parsed;
        try {
            parsed = FieldExpressions.parse(rules.defaultFormula(), columns);
        } catch (ServiceException ex) {
            throw invalid(prefix + "：" + ex.getMessage());
        }
        for (String code : parsed.references())
            if (ambiguous.contains(code))
                throw invalid(
                        "明细「"
                                + scope.detail().name()
                                + "」字段「"
                                + scope.field().name()
                                + "」的公式引用「"
                                + code
                                + "」在主表和明细中重名，无法确定来源");
        // 日期函数的类型检查：键是字段编码，明细字段的公式本行优先、其次主表（与上面 columns 的范围一致）。
        Map<String, FormulaDates.Kind> kinds = new HashMap<>();
        Map<String, String> labels = new HashMap<>();
        for (boolean row : new boolean[] {false, true})
            for (var s : scopes) {
                boolean own =
                        row
                                ? scope.detail() != null
                                        && s.detail() != null
                                        && s.detail().id().equals(scope.detail().id())
                                : s.detail() == null;
                if (!own || FieldTypeEnum.fromCode(s.field().type()).isComputed()) continue;
                kinds.put(s.field().code(), FormulaDates.kind(s.field(), s.options()));
                labels.put(s.field().code(), s.field().name());
            }
        FormulaDates.checkDefault(
                prefix,
                scope.field().type(),
                parsed.expression(),
                new FormulaDates.Context(kinds, labels, true, false));
        if (FieldTypeEnum.MONEY.matches(scope.field().type())
                && MoneyRounding.usesRound(parsed.expression()))
            throw invalid(
                    scope.label()
                            + "是金额字段，公式里不能写 round()：取整统一由「取整方式」决定（当前："
                            + MoneyRoundingEnum.of(rules.rounding()).getLabel()
                            + "）");
    }

    private static void validatePick(Context context, Scope scope, SelectionFields.Source s) {
        String prefix = scope.label() + "的挑取值";
        var source = context.definitions().apply(s.sourceObjectId());
        if (source == null)
            throw invalid(prefix + "：来源对象" + context.absent().apply(s.sourceObjectId()));
        var field = activeField(source, s.sourceFieldId());
        if (field == null)
            throw invalid(prefix + "：来源字段「" + fieldName(source, s.sourceFieldId()) + "」已不存在或已停用");
        if (!FieldTypeEnum.SELECT.matches(field.type())
                && !FieldTypeEnum.MULTI_SELECT.matches(field.type()))
            throw invalid(prefix + "：来源字段「" + field.name() + "」不是单选或多选字段");
        // 候选 = 来源字段实际生效的选项集（局部选项或公共字典项），停用项不计；不读取业务记录。
        List<SelectionFields.Option> available;
        try {
            available = context.choices().apply(field, options(source, field.id()));
        } catch (ServiceException unavailable) {
            available = List.of();
        }
        if (available == null || available.stream().allMatch(SelectionFields.Option::disabled))
            throw invalid(scope.label() + "的「挑取值」来源字段「" + field.name() + "」没有配置选项");
    }

    private static List<Scope> scopes(Definition d) {
        List<Scope> result = new ArrayList<>();
        for (var f : d.fields()) {
            var o = options(d, f.id());
            if (MemberStateEnum.INACTIVE.matches(o.state())) continue;
            result.add(new Scope(d, null, f, o, singleRelation(d, f.id())));
        }
        for (var detail : d.details() == null ? List.<Detail>of() : d.details()) {
            if (MemberStateEnum.INACTIVE.matches(detail.state())) continue;
            for (var f : detail.fields()) {
                var o =
                        detail.fieldOptions() == null
                                ? FieldOptions.defaults()
                                : detail.fieldOptions()
                                        .getOrDefault(f.id(), FieldOptions.defaults());
                if (MemberStateEnum.INACTIVE.matches(o.state())) continue;
                result.add(new Scope(d, detail, f, o, singleRelation(d, f.id())));
            }
        }
        return result;
    }

    private static Relation singleRelation(Definition d, String fieldId) {
        var relation = BusinessFields.relation(d, fieldId);
        return relation == null || BusinessFields.multiple(relation) ? null : relation;
    }

    private static FieldOptions options(Definition d, String fieldId) {
        return d.fieldOptions().getOrDefault(fieldId, FieldOptions.defaults());
    }

    /** 来源与目标对象的主表有效字段；来源对象的内部明细字段不作为联动来源。 */
    private static FieldDefinition activeField(Definition d, String fieldId) {
        return d.fields().stream()
                .filter(
                        f ->
                                f.id().equals(fieldId)
                                        && !MemberStateEnum.INACTIVE.matches(
                                                options(d, f.id()).state()))
                .findFirst()
                .orElse(null);
    }

    private static String fieldName(Definition d, String fieldId) {
        return d.fields().stream()
                .filter(f -> f.id().equals(fieldId))
                .map(FieldDefinition::name)
                .findFirst()
                .orElse(fieldId);
    }

    private static boolean numeric(Definition d, FieldDefinition f) {
        String type = valueType(f, options(d, f.id()));
        return FieldTypeEnum.containsCode(type) && FieldTypeEnum.fromCode(type).isNumeric();
    }

    /** 计算字段按结果类型参与类型判断。 */
    private static String valueType(FieldDefinition f, FieldOptions o) {
        return FieldTypeEnum.fromCode(f.type()).isComputed() ? o.resultType() : f.type();
    }

    /** 条件两侧的比较类别：引用按目标对象，目录按目录类型，其余按值的形态。 */
    private static String kind(Definition d, FieldDefinition f, FieldOptions o) {
        var relation = singleRelation(d, f.id());
        if (relation != null) return "REF:" + relation.targetObjectId();
        String type = valueType(f, o);
        if (!FieldTypeEnum.containsCode(type)) return "UNKNOWN";
        return switch (FieldTypeEnum.fromCode(type)) {
            case TEXT, TEXTAREA, URL, AUTO_NUMBER -> "TEXT";
            case INTEGER, DECIMAL, MONEY, PERCENT -> "NUMBER";
            case SELECT, MULTI_SELECT, REGION, CASCADE -> "OPTION";
            case USER, DEPARTMENT, ORGANIZATION, POST, USER_GROUP -> "DIRECTORY:" + type;
            case REFERENCE -> "REF:";
            default -> type;
        };
    }

    private static String kindLabel(Context context, String kind) {
        if (kind.startsWith("REF:")) {
            String target = kind.substring(4);
            var d = target.isEmpty() ? null : context.definitions().apply(target);
            return d == null ? "引用" : "引用「" + d.objectName() + "」";
        }
        if (kind.startsWith("DIRECTORY:")) return typeName(kind.substring(10));
        return switch (kind) {
            case "TEXT" -> "文本";
            case "NUMBER" -> "数值";
            case "OPTION" -> "选项";
            case "UNKNOWN" -> "未知类型";
            default -> typeName(kind);
        };
    }

    /** 「引用「公司」对 文本」：左侧以书名号收尾时不再加空格，与设计稿文案一致。 */
    private static String versus(String left, String right) {
        return left + (left.endsWith("」") ? "" : " ") + "对 " + right;
    }

    private static String typeLabel(Definition d, FieldDefinition f) {
        return singleRelation(d, f.id()) != null ? "关联" : typeName(f.type());
    }

    private static String typeName(String type) {
        if (!FieldTypeEnum.containsCode(type)) return type;
        return switch (FieldTypeEnum.fromCode(type)) {
            case TEXT -> "文本";
            case TEXTAREA -> "多行文本";
            case INTEGER -> "整数";
            case DECIMAL -> "小数";
            case BOOLEAN -> "布尔";
            case DATE -> "日期";
            case DATETIME -> "日期时间";
            case RICH_TEXT -> "富文本";
            case URL -> "链接";
            case MONEY -> "金额";
            case PERCENT -> "百分比";
            case TIME -> "时间";
            case SELECT -> "单选";
            case MULTI_SELECT -> "多选";
            case ORGANIZATION -> "组织";
            case USER -> "人员";
            case DEPARTMENT -> "部门";
            case POST -> "岗位";
            case USER_GROUP -> "用户组";
            case IMAGE -> "图片";
            case ATTACHMENT -> "附件";
            case REGION -> "地区";
            case CASCADE -> "级联";
            case AUTO_NUMBER -> "自动编号";
            case FORMULA -> "公式";
            case SUMMARY -> "汇总";
            case REFERENCE -> "引用";
            case UUID -> "UUID";
        };
    }

    private static boolean other(Definition d, String objectId) {
        return objectId != null && !objectId.isBlank() && !objectId.equals(d.objectId());
    }

    private static void conditionFields(List<FieldRules.Condition> conditions, Set<String> fields) {
        if (conditions == null) return;
        for (var c : conditions)
            if (c != null && c.fieldId() != null && !FieldRules.RECORD_KEY.equals(c.fieldId()))
                fields.add(c.fieldId());
    }

    private static String joinAnd(List<String> items) {
        if (items.size() <= 2) return String.join("和", items);
        return String.join("、", items.subList(0, items.size() - 1)) + "和" + items.getLast();
    }
}
