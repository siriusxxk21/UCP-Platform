package com.lingan.ucp.nocode.metadata.service.object;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.dal.dataobject.ObjectDraftHeadDO;
import com.lingan.ucp.nocode.metadata.dal.mapper.DataCenterMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/** 设计字段保护、稳定标识映射、选项归一化及纳管字段边界。 */
@Component
public class ObjectDesignFields {
    @Resource private ObjectDesignCodec designCodec;

    @Resource
    private org.springframework.beans.factory.ObjectProvider<
                    com.lingan.ucp.nocode.api.SelectionTargetValidator>
            selectionTargets;

    @Resource private DataCenterMapper store;
    @Resource private DraftValidator validator;
    @Resource private ObjectMapper json;

    @PostConstruct
    void initialize() {
        this.json = json.copy().findAndRegisterModules();
    }

    void protectGenerated(Design previous, SaveObjectDraft input) {
        if (previous == null) return;
        for (FieldDefinition f : previous.draft().fields()) {
            if (!Boolean.TRUE.equals(
                    previous.fieldOptions()
                            .getOrDefault(f.id(), FieldOptions.defaults())
                            .generated())) continue;
            if (input.removedFieldIds() != null && input.removedFieldIds().contains(f.id()))
                throw invalid("关系生成字段请通过关系配置维护");
            for (FieldDefinition changed : input.fields()) {
                if (f.id().equals(changed.id())
                        && (!f.code().equals(changed.code()) || !f.type().equals(changed.type())))
                    throw invalid("关系生成字段的编码和类型由关系维护");
            }
        }
    }

    Map<String, String> remap(List<FieldDefinition> input, List<FieldDefinition> saved) {
        Map<String, String> ids = new HashMap<>();
        saved.forEach(f -> ids.put(f.id(), f.id()));
        input.forEach(
                f ->
                        saved.stream()
                                .filter(v -> v.code().equals(f.code()))
                                .findFirst()
                                .ifPresent(v -> ids.put(f.key(), v.id())));
        return ids;
    }

    void saveOptions(
            ObjectDraftHeadDO h,
            List<FieldDefinition> fields,
            Map<String, FieldOptions> requested,
            Map<String, String> keys,
            Map<String, FieldOptions> old,
            boolean adopted,
            Design previousDesign) {
        Map<String, FieldOptions> supplied = new HashMap<>();
        Map<String, FieldOptions> publishedOptions = new HashMap<>();
        Set<String> explicitReferences = new HashSet<>();
        if (previousDesign != null)
            explicitReferences.addAll(
                    explicitReferences(previousDesign.relations(), fields(previousDesign)));
        if (h.getCurrentPublishedVersionNo() != null) {
            Definition published =
                    designCodec.read(
                            store.versionSchema(h.getId(), h.getCurrentPublishedVersionNo()),
                            Definition.class);
            publishedOptions.putAll(published.fieldOptions());
            published.details().forEach(detail -> publishedOptions.putAll(detail.fieldOptions()));
            explicitReferences.addAll(
                    explicitReferences(
                            published.relations(), FieldConversionCompatibility.fields(published)));
        }
        if (requested != null)
            requested.forEach(
                    (key, value) -> {
                        if (!keys.containsKey(key)) throw invalid("扩展属性包含不属于当前表的字段");
                        supplied.put(keys.get(key), value);
                    });
        for (FieldDefinition f : fields) {
            DataCenter.FieldOptions previous = old.getOrDefault(f.id(), FieldOptions.defaults());
            DataCenter.FieldOptions o = supplied.getOrDefault(f.id(), previous);
            if (o == null) throw invalid("字段配置不能为空");
            boolean leavingReference =
                    !adopted
                            && explicitReferences.contains(f.id())
                            && !FieldTypeEnum.REFERENCE.matches(f.type());
            if (adopted
                    && (!Objects.equals(o.defaultValue(), previous.defaultValue())
                            || !Objects.equals(o.pattern(), previous.pattern())
                            || !Objects.equals(o.minimum(), previous.minimum())
                            || !Objects.equals(o.maximum(), previous.maximum())
                            || !Objects.equals(o.state(), previous.state())
                            || !Objects.equals(o.autoNumber(), previous.autoNumber())))
                throw invalid("纳管字段的存储和校验规则不可直接修改");
            String column = previous.columnName() == null ? f.code() : previous.columnName();
            if (h.getCurrentPublishedVersionNo() == null && !adopted) column = f.code();
            if (o.columnName() != null && !o.columnName().equals(column))
                throw invalid("物理列名由对象和纳管映射维护");
            if ((!Objects.equals(o.nativeType(), previous.nativeType())
                            && !(leavingReference && o.nativeType() == null))
                    || !Objects.equals(
                            Boolean.TRUE.equals(o.primaryKey()),
                            Boolean.TRUE.equals(previous.primaryKey()))
                    || !Objects.equals(
                            Boolean.TRUE.equals(o.generated()),
                            Boolean.TRUE.equals(previous.generated())))
                throw invalid("字段的数据库能力不能手工修改");
            validateOptions(f, o);
            DataCenter.FieldOptions publishedField = publishedOptions.get(f.id());
            if (publishedField != null
                    && (publishedField.autoNumber() == null) != (o.autoNumber() == null))
                throw invalid("已发布自动编号字段不能切换数据库自增与规则编号，请新增字段");
            DataCenter.FieldOptions normalized =
                    new FieldOptions(
                            column,
                            Objects.toString(
                                    o.classification(), DataClassificationEnum.NORMAL.getCode()),
                            adopted
                                    ? o.defaultValue()
                                    : FieldDefaultValidation.normalizeDefault(f, o, json),
                            o.description(),
                            o.pattern(),
                            o.minimum(),
                            o.maximum(),
                            Objects.toString(o.state(), MemberStateEnum.ACTIVE.getCode()),
                            o.options() == null ? List.of() : o.options(),
                            o.expression(),
                            o.resultType(),
                            leavingReference
                                    ? DisplayResolverEnum.NONE.getCode()
                                    : Objects.toString(
                                            o.resolver(), DisplayResolverEnum.NONE.getCode()),
                            leavingReference ? null : previous.nativeType(),
                            previous.primaryKey(),
                            previous.generated(),
                            o.selection(),
                            o.calculation(),
                            o.autoNumber(),
                            FieldRuleValidator.normalize(o.rules()));
            store.updateFieldOptions(
                    h.getVersionId(),
                    Long.parseLong(f.id()),
                    designCodec.write(normalized),
                    normalized.state(),
                    normalized.classification(),
                    column,
                    Boolean.TRUE.equals(previous.generated()));
        }
    }

    /** 草稿可经历多次切换；解除显式引用时同时检查当前草稿和已发布基线。 */
    private Map<String, FieldConversionCompatibility.Field> fields(Design design) {
        Map<String, FieldConversionCompatibility.Field> fields = new HashMap<>();
        for (FieldDefinition field : design.draft().fields())
            fields.put(
                    field.id(),
                    new FieldConversionCompatibility.Field(
                            field,
                            design.fieldOptions()
                                    .getOrDefault(field.id(), FieldOptions.defaults())));
        for (Detail detail : design.details())
            for (FieldDefinition field : detail.fields())
                fields.put(
                        field.id(),
                        new FieldConversionCompatibility.Field(
                                field,
                                detail.fieldOptions()
                                        .getOrDefault(field.id(), FieldOptions.defaults())));
        return fields;
    }

    /** 草稿和已发布定义统一识别人工配置的单值对象引用。 */
    private Set<String> explicitReferences(
            List<Relation> relations, Map<String, FieldConversionCompatibility.Field> fields) {
        Set<String> ids = new HashSet<>();
        fields.forEach(
                (id, value) -> {
                    if (FieldTypeEnum.REFERENCE.matches(value.definition().type())
                            && !Boolean.TRUE.equals(value.options().generated())
                            && relations.stream()
                                    .noneMatch(
                                            relation ->
                                                    id.equals(relation.fieldId())
                                                            && RelationTypeEnum.MASTER_DETAIL
                                                                    .matches(relation.kind())))
                        ids.add(id);
                });
        return ids;
    }

    void validateOptions(FieldDefinition f, FieldOptions o) {
        AutoNumberOptions.validate(f, o.autoNumber());
        FieldDefaultValidation.validate(f, o, json);
        // 跨对象、关系归属与依赖环在 ObjectDesignReferences 中按完整定义校验。
        FieldRuleValidator.validateShape(f, o);
        if (o.calculation() != null && !FieldTypeEnum.FORMULA.matches(f.type()))
            throw invalid("只有公式字段可配置计算来源");
        SelectionFields.validate(f, o);
        if (o.selection() != null) selectionTargets.ifAvailable(v -> v.validateDefinition(f, o));
        SelectionFields.Source selection = SelectionFields.source(f, o);
        boolean localOptions =
                selection == null || SelectionSourceEnum.LOCAL_OPTIONS.matches(selection.kind());
        if (!DataClassificationEnum.containsCode(
                Objects.toString(o.classification(), DataClassificationEnum.NORMAL.getCode())))
            throw invalid("字段数据分类无效");
        if (!MemberStateEnum.containsCode(
                Objects.toString(o.state(), MemberStateEnum.ACTIVE.getCode())))
            throw invalid("字段状态无效");
        if (o.description() != null && o.description().length() > 1000
                || o.defaultValue() != null && o.defaultValue().length() > 4000)
            throw invalid("字段说明或默认值过长");
        if (!DisplayResolverEnum.containsCode(
                Objects.toString(o.resolver(), DisplayResolverEnum.NONE.getCode())))
            throw invalid("全局对象不能绑定应用字典；请选择局部选项或目标记录标题");
        if (o.pattern() != null && !o.pattern().isBlank()) {
            if (o.pattern().length() > 200
                    || !Set.of(FieldTypeEnum.TEXT.getCode(), FieldTypeEnum.TEXTAREA.getCode())
                            .contains(f.type())) throw invalid("正则仅支持文本，最多 200 字符");
            try {
                Pattern.compile(o.pattern());
            } catch (RuntimeException ex) {
                throw invalid("正则表达式无效");
            }
        }
        try {
            if (o.minimum() != null) new java.math.BigDecimal(o.minimum());
            if (o.maximum() != null) new java.math.BigDecimal(o.maximum());
            if (o.minimum() != null
                    && o.maximum() != null
                    && new java.math.BigDecimal(o.minimum())
                                    .compareTo(new java.math.BigDecimal(o.maximum()))
                            > 0) throw invalid("最小值不能大于最大值");
        } catch (NumberFormatException ex) {
            throw invalid("数值范围必须为有效数字");
        }
        if ((o.minimum() != null || o.maximum() != null)
                && !FieldTypeEnum.fromCode(f.type()).isNumeric()) throw invalid("当前字段类型不支持数值范围");
        Set<String> codes = new HashSet<>();
        for (DataCenter.Option option : o.options() == null ? List.<Option>of() : o.options()) {
            if (option == null || !codes.add(validator.text(option.code(), "选项编码", 100)))
                throw invalid("选项编码不能为空或重复");
            validator.text(option.label(), "选项名称", 128);
        }
        if (codes.size() > 500) throw invalid("局部选项最多 500 项");
        if (Set.of(
                                FieldTypeEnum.SELECT.getCode(),
                                FieldTypeEnum.MULTI_SELECT.getCode(),
                                FieldTypeEnum.REGION.getCode(),
                                FieldTypeEnum.CASCADE.getCode())
                        .contains(f.type())
                && localOptions
                && codes.isEmpty()) throw invalid("选项字段至少配置一个局部选项");
        if (FieldTypeEnum.SELECT.matches(f.type())
                && localOptions
                && o.defaultValue() != null
                && !codes.contains(o.defaultValue())) throw invalid("默认选项编码不存在");
        if (FieldTypeEnum.fromCode(f.type()).isComputed()) {
            if ((o.calculation() == null
                            || CalculationModeEnum.LOCAL.matches(o.calculation().mode()))
                    && (o.expression() == null
                            || o.expression().isBlank()
                            || o.expression().length() > 1000)) throw invalid("计算字段必须配置受控表达式");
            if (!Set.of(
                            FieldTypeEnum.TEXT.getCode(),
                            FieldTypeEnum.DECIMAL.getCode(),
                            FieldTypeEnum.INTEGER.getCode(),
                            FieldTypeEnum.MONEY.getCode())
                    .contains(Objects.toString(o.resultType(), "")))
                throw invalid("计算结果类型只能是文本、整数、小数或金额");
        } else if (o.expression() != null && !o.expression().isBlank())
            throw invalid("普通字段不能携带计算表达式");
    }

    void validateAdoptedFields(
            List<FieldDefinition> fields, Map<String, FieldDefinition> previous) {
        if (fields.size() != previous.size()) throw invalid("纳管明细不能直接增删映射列，请通过物理差异同步维护");
        for (FieldDefinition f : fields) {
            FieldDefinition old = previous.get(f.id());
            if (old == null
                    || !old.code().equals(f.code())
                    || !old.type().equals(f.type())
                    || !Objects.equals(old.length(), f.length())
                    || !Objects.equals(old.precision(), f.precision())
                    || !Objects.equals(old.scale(), f.scale())
                    || !Objects.equals(old.required(), f.required())
                    || !Objects.equals(old.unique(), f.unique())) throw invalid("纳管明细的物理字段属性由原表决定");
        }
    }
}
