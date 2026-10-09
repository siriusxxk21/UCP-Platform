package com.richuang.os.nocode.api;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.nocode.enums.*;

import java.util.*;

/** 选择字段的共同协议。来源绑定属于对象版本，候选值来自实时目录或目标对象。 */
public final class SelectionFields {
    private SelectionFields() {}

    /** sourceObjectId、sourceFieldId 只用于挑取值（OBJECT_FIELD_OPTIONS），其它来源必须为空。 */
    public record Source(
            String kind,
            String directory,
            String dictionaryType,
            List<String> rootIds,
            Boolean includeDescendants,
            List<Integer> organizationTypes,
            String defaultMode,
            Map<String, List<String>> migrationMap,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    String sourceObjectId,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    String sourceFieldId) {
        /** 兼容未使用挑取值的既有来源配置与快照。 */
        public Source(
                String kind,
                String directory,
                String dictionaryType,
                List<String> rootIds,
                Boolean includeDescendants,
                List<Integer> organizationTypes,
                String defaultMode,
                Map<String, List<String>> migrationMap) {
            this(
                    kind,
                    directory,
                    dictionaryType,
                    rootIds,
                    includeDescendants,
                    organizationTypes,
                    defaultMode,
                    migrationMap,
                    null,
                    null);
        }

        public Source(
                String kind,
                String directory,
                String dictionaryType,
                List<String> rootIds,
                Boolean includeDescendants,
                List<Integer> organizationTypes,
                String defaultMode) {
            this(
                    kind,
                    directory,
                    dictionaryType,
                    rootIds,
                    includeDescendants,
                    organizationTypes,
                    defaultMode,
                    null);
        }
    }

    /** 来源身份变化会改变已有业务值含义；范围与名称变化不改变身份。挑取值换了来源对象或字段即换了值的含义， 只在配置了挑取值来源时追加这一段，既有来源的身份保持原文。 */
    public static String identity(FieldDefinition f, DataCenter.FieldOptions options) {
        var source = source(f, options);
        if (source == null) return "";
        String identity =
                source.kind()
                        + ":"
                        + Objects.toString(source.directory(), "")
                        + ":"
                        + Objects.toString(source.dictionaryType(), "")
                        + ":"
                        + multiple(f);
        return source.sourceObjectId() == null && source.sourceFieldId() == null
                ? identity
                : identity
                        + ":"
                        + Objects.toString(source.sourceObjectId(), "")
                        + ":"
                        + Objects.toString(source.sourceFieldId(), "");
    }

    public record Option(
            String value,
            String label,
            String code,
            String parentValue,
            String path,
            boolean disabled,
            boolean unavailable) {}

    /** 请求只定位字段，不能指定另一份来源配置。selected 仅用于授权后的已有值回显。 */
    public record Query(
            String applicationId,
            String objectId,
            String detailId,
            String fieldId,
            String search,
            int pageNo,
            int pageSize,
            List<String> selected,
            String recordId,
            String formId,
            Map<String, Object> formValues,
            Boolean creating,
            String detailRecordId) {
        public Query(
                String applicationId,
                String objectId,
                String detailId,
                String fieldId,
                String search,
                int pageNo,
                int pageSize,
                List<String> selected,
                String recordId,
                String formId,
                Map<String, Object> formValues) {
            this(
                    applicationId,
                    objectId,
                    detailId,
                    fieldId,
                    search,
                    pageNo,
                    pageSize,
                    selected,
                    recordId,
                    formId,
                    formValues,
                    null,
                    null);
        }

        public Query(
                String applicationId,
                String objectId,
                String detailId,
                String fieldId,
                String search,
                int pageNo,
                int pageSize,
                List<String> selected,
                String recordId) {
            this(
                    applicationId,
                    objectId,
                    detailId,
                    fieldId,
                    search,
                    pageNo,
                    pageSize,
                    selected,
                    recordId,
                    null,
                    null);
        }
    }

    /** 场景只可收紧对象来源；联动字段引用同一表单，关联目标字段引用已有对象关系。 */
    public record PreviewQuery(
            Query query,
            List<ApplicationCenter.ObjectReference> objects,
            ApplicationUi.Form form,
            boolean validate) {}

    /** 场景只可收紧对象来源；联动引用同一表单，限定视图只贡献目标对象的固定范围。 */
    public record Presentation(
            String appearance,
            List<String> rootIds,
            Boolean includeDescendants,
            String linkFieldId,
            String linkTargetFieldId,
            Object defaultValue,
            String viewId) {
        /** 兼容未配置限定视图的既有表单快照。 */
        public Presentation(
                String appearance,
                List<String> rootIds,
                Boolean includeDescendants,
                String linkFieldId,
                String linkTargetFieldId,
                Object defaultValue) {
            this(
                    appearance,
                    rootIds,
                    includeDescendants,
                    linkFieldId,
                    linkTargetFieldId,
                    defaultValue,
                    null);
        }
    }

    /** ruleState、ruleMessage、pendingFields 描述对象引用筛选或挑取值的求值状态；未配置规则时为空。 */
    public record Result(
            List<Option> options,
            long total,
            List<Option> selected,
            boolean tree,
            Object defaultValue,
            String defaultWarning,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    String ruleState,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    String ruleMessage,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    List<String> pendingFields) {
        /** 兼容未携带规则状态的既有候选结果。 */
        public Result(
                List<Option> options,
                long total,
                List<Option> selected,
                boolean tree,
                Object defaultValue,
                String defaultWarning) {
            this(options, total, selected, tree, defaultValue, defaultWarning, null, null, null);
        }

        public Result(
                List<Option> options,
                long total,
                List<Option> selected,
                boolean tree,
                Object defaultValue) {
            this(options, total, selected, tree, defaultValue, null);
        }
    }

    public static final Set<FieldTypeEnum> DIRECTORIES =
            Set.of(
                    FieldTypeEnum.ORGANIZATION,
                    FieldTypeEnum.DEPARTMENT,
                    FieldTypeEnum.USER,
                    FieldTypeEnum.POST,
                    FieldTypeEnum.USER_GROUP);

    public static boolean multiple(FieldDefinition f) {
        return Set.of(FieldTypeEnum.MULTI_SELECT, FieldTypeEnum.REGION, FieldTypeEnum.CASCADE)
                .contains(FieldTypeEnum.fromCode(f.type()));
    }

    /** 旧快照缺少来源时按已发布字段类型解释，不改写历史版本。 */
    public static Source source(FieldDefinition f, DataCenter.FieldOptions o) {
        if (o.selection() != null) return o.selection();
        FieldTypeEnum type = FieldTypeEnum.fromCode(f.type());
        if (DIRECTORIES.contains(type))
            return new Source(
                    SelectionSourceEnum.DIRECTORY.getCode(),
                    type.getCode(),
                    null,
                    List.of(),
                    false,
                    List.of(),
                    SelectionDefaultEnum.NONE.getCode());
        if (Set.of(
                        FieldTypeEnum.SELECT,
                        FieldTypeEnum.MULTI_SELECT,
                        FieldTypeEnum.REGION,
                        FieldTypeEnum.CASCADE)
                .contains(type))
            return new Source(
                    SelectionSourceEnum.LOCAL_OPTIONS.getCode(),
                    null,
                    null,
                    List.of(),
                    false,
                    List.of(),
                    SelectionDefaultEnum.NONE.getCode());
        return null;
    }

    public static Map<String, ApplicationUi.FieldPresentation> presentations(
            List<ApplicationUi.Node> nodes) {
        Map<String, ApplicationUi.FieldPresentation> result = new LinkedHashMap<>();
        if (nodes != null)
            for (ApplicationUi.Node node : nodes) {
                if (node.fieldId() != null) result.put(node.fieldId(), node.presentation());
                result.putAll(presentations(node.children()));
            }
        return result;
    }

    public static void validatePresentations(
            ApplicationUi.Form form,
            DataCenter.Definition d,
            Map<String, DataCenter.Definition> definitions) {
        validatePresentations(form, d, definitions, "表单");
    }

    public static void validatePresentations(
            ApplicationUi.Form form,
            DataCenter.Definition d,
            Map<String, DataCenter.Definition> definitions,
            String location) {
        Map<String, ApplicationUi.FieldPresentation> nodes = presentations(form.nodes());
        for (Map.Entry<String, ApplicationUi.FieldPresentation> entry : nodes.entrySet()) {
            SelectionFields.Presentation p =
                    entry.getValue() == null ? null : entry.getValue().selection();
            if (p == null) continue;
            FieldDefinition f =
                    BusinessFields.fields(d).stream()
                            .filter(v -> v.id().equals(entry.getKey()))
                            .findFirst()
                            .orElseThrow(
                                    () -> invalid(location + "，字段（ID：" + entry.getKey() + "）不存在"));
            String fieldLocation = location + "，字段“" + f.name() + "”（ID：" + f.id() + "）：";
            SelectionFields.Source source =
                    source(
                            f,
                            d.fieldOptions()
                                    .getOrDefault(f.id(), DataCenter.FieldOptions.defaults()));
            DataCenter.Relation relation = BusinessFields.relation(d, f.id());
            if (source == null && relation == null) throw invalid(fieldLocation + "当前字段不支持选择器呈现");
            SelectionAppearanceEnum appearance =
                    SelectionAppearanceEnum.fromCode(
                            Objects.requireNonNullElse(
                                    p.appearance(), SelectionAppearanceEnum.AUTO.getCode()));
            boolean tree =
                    source != null
                            && SelectionSourceEnum.DIRECTORY.matches(source.kind())
                            && Set.of(
                                            FieldTypeEnum.ORGANIZATION.getCode(),
                                            FieldTypeEnum.DEPARTMENT.getCode())
                                    .contains(Objects.toString(source.directory(), ""));
            if (appearance == SelectionAppearanceEnum.TREE && !tree)
                throw invalid(fieldLocation + "树选择器仅支持组织和部门");
            if (p.rootIds() != null
                    && (!tree && !p.rootIds().isEmpty()
                            || p.rootIds().size() > 100
                            || p.rootIds().stream()
                                    .anyMatch(id -> id == null || !id.matches("[1-9][0-9]{0,18}"))))
                throw invalid(fieldLocation + "表单选择范围无效");
            String linkLocation =
                    fieldLocation
                            + "联动来源字段（ID："
                            + p.linkFieldId()
                            + "），目标筛选字段（ID："
                            + p.linkTargetFieldId()
                            + "）：";
            if (p.linkFieldId() != null) {
                if (!nodes.containsKey(p.linkFieldId()) || p.linkFieldId().equals(f.id()))
                    throw invalid(linkLocation + "联动来源必须为表单中的其他字段");
                FieldDefinition from =
                        BusinessFields.fields(d).stream()
                                .filter(v -> v.id().equals(p.linkFieldId()))
                                .findFirst()
                                .orElseThrow(() -> invalid(linkLocation + "联动来源字段不存在"));
                if (!BusinessFields.linkable(from)) throw invalid(linkLocation + "联动上游必须为可筛选的单值字段");
                if (relation == null && !tree) throw invalid(linkLocation + "此来源不支持层级联动");
                if (tree) {
                    SelectionFields.Source upstream =
                            source(
                                    from,
                                    d.fieldOptions()
                                            .getOrDefault(
                                                    from.id(), DataCenter.FieldOptions.defaults()));
                    if (upstream == null
                            || !Objects.equals(upstream.directory(), source.directory()))
                        throw invalid(linkLocation + "层级联动字段必须引用同一目录");
                }
                if (relation != null) {
                    DataCenter.Definition target = definitions.get(relation.targetObjectId());
                    FieldDefinition targetField =
                            target == null
                                    ? null
                                    : target.fields().stream()
                                            .filter(
                                                    v ->
                                                            v.id().equals(p.linkTargetFieldId())
                                                                    && BusinessFields.linkable(v))
                                            .findFirst()
                                            .orElse(null);
                    if (targetField == null) throw invalid(linkLocation + "请选择关联目标对象中的单值筛选字段");
                    if (!linkCompatible(d, from, target, targetField))
                        throw invalid(
                                linkLocation
                                        + "联动来源与目标筛选字段类型或引用对象不匹配："
                                        + from.name()
                                        + " → "
                                        + targetField.name());
                }
            } else if (p.linkTargetFieldId() != null) throw invalid(linkLocation + "目标筛选字段缺少联动来源");
            // 选项类未作答与“选中默认项”无法区分，会污染统计；表单层同样不接受默认值。
            if (p.defaultValue() != null && relation == null && FieldRuleMatrix.optionType(f))
                throw invalid("「" + f.name() + "」是选项类字段，不能设置表单默认值（未作答与选中无法区分，影响统计）");
            if (p.defaultValue() != null
                    && (multiple(f) != (p.defaultValue() instanceof Collection<?>)
                            || p.defaultValue() instanceof Map<?, ?>
                            || p.defaultValue().toString().length() > 10000))
                throw invalid(fieldLocation + "表单默认值与选择数量不一致");
        }
        // 依赖不能成环，否则无法确定候选范围和默认值的求值顺序。
        for (String id : nodes.keySet()) {
            Set<String> seen = new HashSet<>();
            String next = id;
            while (next != null) {
                if (!seen.add(next))
                    throw invalid(
                            location + "，字段（ID：" + id + "），循环字段（ID：" + next + "）：选择字段联动不能循环依赖");
                ApplicationUi.FieldPresentation p = nodes.get(next);
                next = p == null || p.selection() == null ? null : p.selection().linkFieldId();
            }
        }
    }

    /** 数字 ID 只在相同引用对象内可比较，不能因底层同为整数而混用。 */
    public static boolean linkCompatible(
            DataCenter.Definition fromObject,
            FieldDefinition from,
            DataCenter.Definition targetObject,
            FieldDefinition target) {
        DataCenter.Relation a = BusinessFields.relation(fromObject, from.id());
        DataCenter.Relation b = BusinessFields.relation(targetObject, target.id());
        if (a != null || b != null)
            return a != null
                    && b != null
                    && !BusinessFields.multiple(a)
                    && !BusinessFields.multiple(b)
                    && Objects.equals(a.targetObjectId(), b.targetObjectId());
        DataCenter.FieldOptions fromOptions =
                fromObject
                        .fieldOptions()
                        .getOrDefault(from.id(), DataCenter.FieldOptions.defaults());
        DataCenter.FieldOptions targetOptions =
                targetObject
                        .fieldOptions()
                        .getOrDefault(target.id(), DataCenter.FieldOptions.defaults());
        FieldTypeEnum fromType = FieldTypeEnum.fromCode(from.type());
        FieldTypeEnum targetType = FieldTypeEnum.fromCode(target.type());
        if (fromType.isComputed()) fromType = FieldTypeEnum.fromCode(fromOptions.resultType());
        if (targetType.isComputed())
            targetType = FieldTypeEnum.fromCode(targetOptions.resultType());
        if (fromType == FieldTypeEnum.SELECT || targetType == FieldTypeEnum.SELECT)
            return SelectionCompatibility.compatible(from, fromOptions, target, targetOptions);
        return fromType == targetType || fromType.isNumeric() && targetType.isNumeric();
    }

    public static void validate(FieldDefinition f, DataCenter.FieldOptions o) {
        SelectionFields.Source s = source(f, o);
        if (s == null) return;
        SelectionSourceEnum kind = SelectionSourceEnum.fromCode(s.kind());
        if (s.migrationMap() != null) {
            if (s.migrationMap().size() > 1000) throw invalid("一次迁移最多映射 1000 个旧值");
            for (Map.Entry<String, List<String>> entry : s.migrationMap().entrySet())
                if (entry.getKey() == null
                        || entry.getKey().length() > 500
                        || entry.getValue() == null
                        || entry.getValue().isEmpty()
                        || entry.getValue().size() > 100
                        || entry.getValue().stream().anyMatch(v -> v == null || v.length() > 100))
                    throw invalid("选择值迁移映射无效");
        }
        FieldTypeEnum type = FieldTypeEnum.fromCode(f.type());
        if (kind == SelectionSourceEnum.OBJECT_RELATION) throw invalid("业务对象来源必须通过对象关系配置，不能手工填写来源");
        if ((!Objects.requireNonNullElse(s.rootIds(), List.of()).isEmpty()
                        || Boolean.TRUE.equals(s.includeDescendants()))
                && (kind != SelectionSourceEnum.DIRECTORY
                        || !Set.of(
                                        FieldTypeEnum.ORGANIZATION.getCode(),
                                        FieldTypeEnum.DEPARTMENT.getCode())
                                .contains(Objects.toString(s.directory(), ""))))
            throw invalid("仅组织和部门支持层级范围");
        if (!Objects.requireNonNullElse(s.organizationTypes(), List.of()).isEmpty()
                && !FieldTypeEnum.ORGANIZATION.matches(s.directory()))
            throw invalid("组织类型范围仅适用于组织目录");
        if (!DIRECTORIES.contains(type)
                && !Set.of(
                                FieldTypeEnum.SELECT,
                                FieldTypeEnum.MULTI_SELECT,
                                FieldTypeEnum.REGION,
                                FieldTypeEnum.CASCADE,
                                FieldTypeEnum.REFERENCE)
                        .contains(type)) throw invalid("当前字段类型不能配置选择来源");
        if (kind == SelectionSourceEnum.DIRECTORY) {
            FieldTypeEnum directory = FieldTypeEnum.fromCode(s.directory());
            if (!DIRECTORIES.contains(directory)) throw invalid("不支持的系统目录");
            if (DIRECTORIES.contains(type) && type != directory) throw invalid("目录与字段类型不一致");
        } else if (s.directory() != null || DIRECTORIES.contains(type)) throw invalid("目录配置与来源不一致");
        if (kind == SelectionSourceEnum.SYSTEM_DICTIONARY) {
            if (s.dictionaryType() == null || !s.dictionaryType().matches("[A-Za-z0-9_:-]{1,100}"))
                throw invalid("请选择平台公共字典");
        } else if (s.dictionaryType() != null) throw invalid("当前来源不能携带字典绑定");
        if (s.rootIds() != null
                && (s.rootIds().size() > 100
                        || s.rootIds().stream()
                                .anyMatch(id -> id == null || !id.matches("[1-9][0-9]{0,18}"))))
            throw invalid("目录范围无效");
        if (s.organizationTypes() != null
                && (s.organizationTypes().size() > 3
                        || s.organizationTypes().stream()
                                .anyMatch(t -> t == null || t < 1 || t > 3)))
            throw invalid("组织类型范围无效");
        SelectionDefaultEnum mode =
                SelectionDefaultEnum.fromCode(
                        Objects.requireNonNullElse(
                                s.defaultMode(), SelectionDefaultEnum.NONE.getCode()));
        if (mode == SelectionDefaultEnum.CURRENT_USER_ORGANIZATION
                && (kind != SelectionSourceEnum.DIRECTORY
                        || !FieldTypeEnum.ORGANIZATION.matches(s.directory())))
            throw invalid("当前用户所属组织默认值仅用于组织来源");
        if (mode == SelectionDefaultEnum.CURRENT_USER_ORGANIZATION && o.defaultValue() != null)
            throw invalid("动态默认值不能同时设置固定值");
        if (kind != SelectionSourceEnum.LOCAL_OPTIONS
                && o.options() != null
                && !o.options().isEmpty()) throw invalid("动态来源不能同时维护局部选项");
        if (kind == SelectionSourceEnum.OBJECT_FIELD_OPTIONS) {
            // 跨对象部分（来源字段是否存在、类型、是否有选项）在发布校验中按来源对象版本检查。
            if (type != FieldTypeEnum.SELECT && type != FieldTypeEnum.MULTI_SELECT)
                throw invalid("挑取值只适用于单选和多选字段");
            if (s.sourceObjectId() == null
                    || !s.sourceObjectId().matches("[1-9][0-9]{0,18}")
                    || s.sourceFieldId() == null
                    || !s.sourceFieldId().matches("[1-9][0-9]{0,18}"))
                throw invalid("挑取值须选择来源对象和来源字段");
            if (mode != SelectionDefaultEnum.NONE) throw invalid("挑取值不能设置默认值");
        } else if (s.sourceObjectId() != null || s.sourceFieldId() != null)
            throw invalid("只有挑取值来源可以指定来源对象字段");
    }
}
