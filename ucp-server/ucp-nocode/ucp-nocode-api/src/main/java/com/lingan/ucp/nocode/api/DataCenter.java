package com.lingan.ucp.nocode.api;

import com.lingan.ucp.nocode.enums.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 数据中心的结构化契约。只描述对象、版本与治理，不接受 SQL 或数据库连接配置。 */
public final class DataCenter {
    private DataCenter() {}

    /** 仅试算用户填写的样例值，不读取或保存业务记录。 */
    public record FormulaPreview(
            String expression,
            List<String> fieldCodes,
            Map<String, String> values,
            List<Map<String, String>> rows,
            CalculationOptions.Sequence sequence,
            List<String> groupFields,
            Map<String, String> fieldTypes) {
        public FormulaPreview(
                String expression, List<String> fieldCodes, Map<String, String> values) {
            this(expression, fieldCodes, values, null, null, null, null);
        }
    }

    public record FormulaPreviewResult(
            String value,
            String resultType,
            List<String> referencedFields,
            List<FormulaPreviewRow> rows) {
        public FormulaPreviewResult(
                String value, String resultType, List<String> referencedFields) {
            this(value, resultType, referencedFields, List.of());
        }
    }

    /** index 与 adjacentIndex 均指向输入样例的零起始下标，排序不会丢失输入行身份。 */
    public record FormulaPreviewRow(
            Integer index, String value, String contribution, Integer adjacentIndex) {}

    /**
     * 字段扩展属性与基础类型分开；列名由服务端维护，不随已发布字段改名变化。
     *
     * <p>复制已有配置时必须保留 selection 与 rules：使用 {@link #copyOf} 只覆盖需要改变的组件。
     */
    public record FieldOptions(
            String columnName,
            String classification,
            String defaultValue,
            String description,
            String pattern,
            String minimum,
            String maximum,
            String state,
            List<Option> options,
            String expression,
            String resultType,
            String resolver,
            String nativeType,
            Boolean primaryKey,
            Boolean generated,
            SelectionFields.Source selection,
            CalculationOptions calculation,
            AutoNumberOptions autoNumber,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    FieldRules rules) {
        /** 兼容未配置对象规则的既有契约与快照。 */
        public FieldOptions(
                String columnName,
                String classification,
                String defaultValue,
                String description,
                String pattern,
                String minimum,
                String maximum,
                String state,
                List<Option> options,
                String expression,
                String resultType,
                String resolver,
                String nativeType,
                Boolean primaryKey,
                Boolean generated,
                SelectionFields.Source selection,
                CalculationOptions calculation,
                AutoNumberOptions autoNumber) {
            this(
                    columnName,
                    classification,
                    defaultValue,
                    description,
                    pattern,
                    minimum,
                    maximum,
                    state,
                    options,
                    expression,
                    resultType,
                    resolver,
                    nativeType,
                    primaryKey,
                    generated,
                    selection,
                    calculation,
                    autoNumber,
                    null);
        }

        /** 兼容旧字段扩展契约；未配置规则的自动编号保持数据库身份列。 */
        public FieldOptions(
                String columnName,
                String classification,
                String defaultValue,
                String description,
                String pattern,
                String minimum,
                String maximum,
                String state,
                List<Option> options,
                String expression,
                String resultType,
                String resolver,
                String nativeType,
                Boolean primaryKey,
                Boolean generated,
                SelectionFields.Source selection,
                CalculationOptions calculation) {
            this(
                    columnName,
                    classification,
                    defaultValue,
                    description,
                    pattern,
                    minimum,
                    maximum,
                    state,
                    options,
                    expression,
                    resultType,
                    resolver,
                    nativeType,
                    primaryKey,
                    generated,
                    selection,
                    calculation,
                    null);
        }

        public FieldOptions withAutoNumber(AutoNumberOptions value) {
            return new FieldOptions(
                    columnName,
                    classification,
                    defaultValue,
                    description,
                    pattern,
                    minimum,
                    maximum,
                    state,
                    options,
                    expression,
                    resultType,
                    resolver,
                    nativeType,
                    primaryKey,
                    generated,
                    selection,
                    calculation,
                    value,
                    rules);
        }

        public FieldOptions(
                String columnName,
                String classification,
                String defaultValue,
                String description,
                String pattern,
                String minimum,
                String maximum,
                String state,
                List<Option> options,
                String expression,
                String resultType,
                String resolver,
                String nativeType,
                Boolean primaryKey,
                Boolean generated,
                SelectionFields.Source selection) {
            this(
                    columnName,
                    classification,
                    defaultValue,
                    description,
                    pattern,
                    minimum,
                    maximum,
                    state,
                    options,
                    expression,
                    resultType,
                    resolver,
                    nativeType,
                    primaryKey,
                    generated,
                    selection,
                    null);
        }

        public FieldOptions withCalculation(CalculationOptions value) {
            return new FieldOptions(
                    columnName,
                    classification,
                    defaultValue,
                    description,
                    pattern,
                    minimum,
                    maximum,
                    state,
                    options,
                    expression,
                    resultType,
                    resolver,
                    nativeType,
                    primaryKey,
                    generated,
                    selection,
                    value,
                    autoNumber,
                    rules);
        }

        /** 兼容旧快照和既有构造器；复制已有配置时必须保留 selection。 */
        public FieldOptions(
                String columnName,
                String classification,
                String defaultValue,
                String description,
                String pattern,
                String minimum,
                String maximum,
                String state,
                List<Option> options,
                String expression,
                String resultType,
                String resolver,
                String nativeType,
                Boolean primaryKey,
                Boolean generated) {
            this(
                    columnName,
                    classification,
                    defaultValue,
                    description,
                    pattern,
                    minimum,
                    maximum,
                    state,
                    options,
                    expression,
                    resultType,
                    resolver,
                    nativeType,
                    primaryKey,
                    generated,
                    null);
        }

        public FieldOptions withDefaultValue(String value) {
            return new FieldOptions(
                    columnName,
                    classification,
                    value,
                    description,
                    pattern,
                    minimum,
                    maximum,
                    state,
                    options,
                    expression,
                    resultType,
                    resolver,
                    nativeType,
                    primaryKey,
                    generated,
                    selection,
                    calculation,
                    autoNumber,
                    rules);
        }

        public FieldOptions withSelection(SelectionFields.Source source) {
            return new FieldOptions(
                    columnName,
                    classification,
                    defaultValue,
                    description,
                    pattern,
                    minimum,
                    maximum,
                    state,
                    options,
                    expression,
                    resultType,
                    resolver,
                    nativeType,
                    primaryKey,
                    generated,
                    source,
                    calculation,
                    autoNumber,
                    rules);
        }

        /** 替换字段对象规则，其余配置原样保留；null 表示清除规则。 */
        public FieldOptions withRules(FieldRules value) {
            return copyOf(this).rules(value).build();
        }

        /** 以已有配置为底复制，默认保留全部组件（含 selection 与 rules），只覆盖显式设置的组件。 */
        public static Builder copyOf(FieldOptions source) {
            return new Builder(source == null ? defaults() : source);
        }

        /** 复制专用的组件覆盖器；组件与记录一一对应，新增组件时须同时补充。 */
        public static final class Builder {
            private String columnName;
            private String classification;
            private String defaultValue;
            private String description;
            private String pattern;
            private String minimum;
            private String maximum;
            private String state;
            private List<Option> options;
            private String expression;
            private String resultType;
            private String resolver;
            private String nativeType;
            private Boolean primaryKey;
            private Boolean generated;
            private SelectionFields.Source selection;
            private CalculationOptions calculation;
            private AutoNumberOptions autoNumber;
            private FieldRules rules;

            private Builder(FieldOptions o) {
                columnName = o.columnName();
                classification = o.classification();
                defaultValue = o.defaultValue();
                description = o.description();
                pattern = o.pattern();
                minimum = o.minimum();
                maximum = o.maximum();
                state = o.state();
                options = o.options();
                expression = o.expression();
                resultType = o.resultType();
                resolver = o.resolver();
                nativeType = o.nativeType();
                primaryKey = o.primaryKey();
                generated = o.generated();
                selection = o.selection();
                calculation = o.calculation();
                autoNumber = o.autoNumber();
                rules = o.rules();
            }

            public Builder columnName(String value) {
                columnName = value;
                return this;
            }

            public Builder classification(String value) {
                classification = value;
                return this;
            }

            public Builder defaultValue(String value) {
                defaultValue = value;
                return this;
            }

            public Builder description(String value) {
                description = value;
                return this;
            }

            public Builder pattern(String value) {
                pattern = value;
                return this;
            }

            public Builder minimum(String value) {
                minimum = value;
                return this;
            }

            public Builder maximum(String value) {
                maximum = value;
                return this;
            }

            public Builder state(String value) {
                state = value;
                return this;
            }

            public Builder options(List<Option> value) {
                options = value;
                return this;
            }

            public Builder expression(String value) {
                expression = value;
                return this;
            }

            public Builder resultType(String value) {
                resultType = value;
                return this;
            }

            public Builder resolver(String value) {
                resolver = value;
                return this;
            }

            public Builder nativeType(String value) {
                nativeType = value;
                return this;
            }

            public Builder primaryKey(Boolean value) {
                primaryKey = value;
                return this;
            }

            public Builder generated(Boolean value) {
                generated = value;
                return this;
            }

            public Builder selection(SelectionFields.Source value) {
                selection = value;
                return this;
            }

            public Builder calculation(CalculationOptions value) {
                calculation = value;
                return this;
            }

            public Builder autoNumber(AutoNumberOptions value) {
                autoNumber = value;
                return this;
            }

            public Builder rules(FieldRules value) {
                rules = value;
                return this;
            }

            public FieldOptions build() {
                return new FieldOptions(
                        columnName,
                        classification,
                        defaultValue,
                        description,
                        pattern,
                        minimum,
                        maximum,
                        state,
                        options,
                        expression,
                        resultType,
                        resolver,
                        nativeType,
                        primaryKey,
                        generated,
                        selection,
                        calculation,
                        autoNumber,
                        rules);
            }
        }

        public static FieldOptions defaults() {
            return new FieldOptions(
                    null,
                    DataClassificationEnum.NORMAL.getCode(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    MemberStateEnum.ACTIVE.getCode(),
                    List.of(),
                    null,
                    null,
                    DisplayResolverEnum.NONE.getCode(),
                    null,
                    false,
                    false,
                    null,
                    null,
                    null,
                    null);
        }
    }

    /** 选项只保存稳定编码和值；业务行不保存名称副本。 */
    public record Option(String code, String label, Boolean disabled) {}

    public record Settings(
            String icon,
            String ownerId,
            String organizationId,
            String titleTemplate,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    DocumentPolicy documentPolicy,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    BusinessFilePolicy businessFilePolicy) {
        public Settings(String icon, String ownerId, String organizationId, String titleTemplate) {
            this(icon, ownerId, organizationId, titleTemplate, null, null);
        }

        public Settings(
                String icon,
                String ownerId,
                String organizationId,
                String titleTemplate,
                DocumentPolicy documentPolicy) {
            this(icon, ownerId, organizationId, titleTemplate, documentPolicy, null);
        }

        public static Settings defaults() {
            return new Settings(null, null, null, null);
        }
    }

    /**
     * 对象业务文件接入规则：随对象版本冻结，运行期按发布版本读取。
     *
     * <p>spaceId 为目标业务空间的稳定身份；spaceName 保留为展示名与历史规则兼容定位。fixedPath 为固定目录层级；groups 为业务分组层（首期最多 2
     * 层）；recordLabelFields 组成记录目录名称的主表字段；fieldIds 为接入的附件/图片字段（含内部明细字段）。
     */
    public record BusinessFilePolicy(
            Long spaceId,
            String spaceName,
            List<String> fixedPath,
            List<BusinessFileGroup> groups,
            List<String> recordLabelFields,
            List<String> fieldIds) {

        /** 兼容未携带稳定空间编号的历史调用与对象版本。 */
        public BusinessFilePolicy(
                String spaceName,
                List<String> fixedPath,
                List<BusinessFileGroup> groups,
                List<String> recordLabelFields,
                List<String> fieldIds) {
            this(null, spaceName, fixedPath, groups, recordLabelFields, fieldIds);
        }

        /** 空值容错：历史版本未配置业务文件时按无规则处理 */
        public static boolean enabled(BusinessFilePolicy policy) {
            return policy != null
                    && policy.spaceName() != null
                    && !policy.spaceName().isBlank()
                    && policy.fieldIds() != null
                    && !policy.fieldIds().isEmpty();
        }
    }

    /** 业务分组层：fieldId 为主表字段；format 取 TEXT/YEAR/MONTH，为空时按字段类型取值 （文本/单选用显示值、单值关联用目标记录标题、日期用原文）。 */
    public record BusinessFileGroup(String fieldId, String format) {}

    /** 关系属于发出引用的对象；sourceDetailId 为空表示主表，否则引用列属于指定内部明细。 */
    public record Relation(
            String id,
            String code,
            String name,
            String kind,
            String targetObjectId,
            String fieldId,
            String targetFieldId,
            Boolean required,
            String onDelete,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    String sourceDetailId) {
        public Relation {
            // 下拉控件以空字符串代表主表，入口统一后再进行明细归属校验。
            sourceDetailId =
                    sourceDetailId == null || sourceDetailId.isBlank()
                            ? null
                            : sourceDetailId.trim();
        }

        public Relation(
                String id,
                String code,
                String name,
                String kind,
                String targetObjectId,
                String fieldId,
                String targetFieldId,
                Boolean required,
                String onDelete) {
            this(
                    id,
                    code,
                    name,
                    kind,
                    targetObjectId,
                    fieldId,
                    targetFieldId,
                    required,
                    onDelete,
                    null);
        }
    }

    public record Index(
            String id,
            String code,
            String name,
            Boolean unique,
            List<String> fieldIds,
            Boolean parentScoped) {
        public Index(String id, String code, String name, Boolean unique, List<String> fieldIds) {
            this(id, code, name, unique, fieldIds, false);
        }
    }

    /** 内部明细属于同一个对象版本，不在全局对象列表创建另一对象。 */
    public record Detail(
            String id,
            String code,
            String name,
            String tableName,
            String state,
            List<FieldDefinition> fields,
            Map<String, FieldOptions> fieldOptions,
            List<Index> indexes,
            TableBinding binding) {
        public Detail(
                String id,
                String code,
                String name,
                String tableName,
                String state,
                List<FieldDefinition> fields,
                Map<String, FieldOptions> fieldOptions,
                List<Index> indexes) {
            this(id, code, name, tableName, state, fields, fieldOptions, indexes, null);
        }
    }

    /** 冻结在版本内的结构；不包含修订时间、权限和依赖等易变查询结果。 */
    public record Definition(
            String objectId,
            String objectCode,
            String objectName,
            String description,
            String schemaName,
            String tableName,
            String source,
            boolean readOnly,
            String titleFieldId,
            Settings settings,
            List<FieldDefinition> fields,
            Map<String, FieldOptions> fieldOptions,
            List<Relation> relations,
            List<Index> indexes,
            List<Detail> details,
            TableBinding mainBinding) {
        public Definition(
                String objectId,
                String objectCode,
                String objectName,
                String description,
                String schemaName,
                String tableName,
                String source,
                boolean readOnly,
                String titleFieldId,
                Settings settings,
                List<FieldDefinition> fields,
                Map<String, FieldOptions> fieldOptions,
                List<Relation> relations,
                List<Index> indexes,
                List<Detail> details) {
            this(
                    objectId,
                    objectCode,
                    objectName,
                    description,
                    schemaName,
                    tableName,
                    source,
                    readOnly,
                    titleFieldId,
                    settings,
                    fields,
                    fieldOptions,
                    relations,
                    indexes,
                    details,
                    null);
        }
    }

    /**
     * 保存完整对象设计。基础字段保存后，扩展属性中的临时 key 同样映射成服务端稳定 ID。
     *
     * @param draft 主表基础草稿，携带对象身份与预期修订
     * @param settings 对象呈现、负责人、组织和整单规则配置
     * @param fieldOptions 按字段 key/稳定 ID 索引的扩展配置
     * @param relations 对象关系定义，目标对象仍独立维护
     * @param indexes 对象索引定义
     * @param details 一级内部明细定义，与主表设计共同保存
     * @param mainBinding 主表生成或纳管配置，必须满足当前物理绑定约束
     * @param restoredFieldIds 显式恢复的原稳定字段 ID，必须同时放回对应主表或内部明细字段列表
     */
    public record SaveDesign(
            SaveObjectDraft draft,
            Settings settings,
            Map<String, FieldOptions> fieldOptions,
            List<Relation> relations,
            List<Index> indexes,
            List<Detail> details,
            TableBinding mainBinding,
            List<String> restoredFieldIds) {
        /** 显式恢复与本次设计保存共同提交，稳定 ID 同时覆盖主表和内部明细。 */
        public SaveDesign(
                SaveObjectDraft draft,
                Settings settings,
                Map<String, FieldOptions> fieldOptions,
                List<Relation> relations,
                List<Index> indexes,
                List<Detail> details,
                TableBinding mainBinding) {
            this(draft, settings, fieldOptions, relations, indexes, details, mainBinding, null);
        }

        public SaveDesign(
                SaveObjectDraft draft,
                Settings settings,
                Map<String, FieldOptions> fieldOptions,
                List<Relation> relations,
                List<Index> indexes,
                List<Detail> details) {
            this(draft, settings, fieldOptions, relations, indexes, details, null);
        }
    }

    /** 停用字段保留原定义和配置；受关系或纳管保护的字段只展示维护原因。 */
    public record InactiveField(
            FieldDefinition field,
            FieldOptions options,
            boolean restorable,
            String blockedReason) {}

    public record Design(
            ObjectDraft draft,
            Settings settings,
            Map<String, FieldOptions> fieldOptions,
            List<Relation> relations,
            List<Index> indexes,
            String source,
            String schemaName,
            Integer publishedVersion,
            Boolean readOnly,
            List<Version> versions,
            List<Dependency> dependencies,
            List<Detail> details,
            String status,
            TableBinding mainBinding) {
        public Design(
                ObjectDraft draft,
                Settings settings,
                Map<String, FieldOptions> fieldOptions,
                List<Relation> relations,
                List<Index> indexes,
                String source,
                String schemaName,
                Integer publishedVersion,
                Boolean readOnly,
                List<Version> versions,
                List<Dependency> dependencies,
                List<Detail> details,
                String status) {
            this(
                    draft,
                    settings,
                    fieldOptions,
                    relations,
                    indexes,
                    source,
                    schemaName,
                    publishedVersion,
                    readOnly,
                    versions,
                    dependencies,
                    details,
                    status,
                    null);
        }
    }

    public record Version(
            int versionNo,
            String state,
            String checksum,
            OffsetDateTime createdAt,
            OffsetDateTime publishedAt,
            String publishedBy) {}

    public record Dependency(
            String sourceKind,
            String sourceKey,
            String sourceName,
            String targetObjectId,
            List<String> fieldIds) {}

    public record Revision(String id, Integer expectedLockVersion, String reason) {}

    public record Copy(String id, String objectCode, String objectName, String tableName) {}

    public record Adoption(
            String schemaName,
            String tableName,
            String objectCode,
            String objectName,
            String titleColumn,
            String fingerprint) {}

    public record ImportColumn(
            String code,
            String name,
            String type,
            Integer length,
            Integer precision,
            Integer scale,
            Boolean required,
            Boolean unique) {}

    public record ImportDesign(
            String objectCode,
            String objectName,
            String tableName,
            String titleColumn,
            List<ImportColumn> columns,
            String category) {
        /** 兼容省略分类的既有调用。 */
        public ImportDesign(
                String objectCode,
                String objectName,
                String tableName,
                String titleColumn,
                List<ImportColumn> columns) {
            this(objectCode, objectName, tableName, titleColumn, columns, null);
        }
    }

    public record Check(String code, String message, boolean blocking) {}

    public record Step(String kind, String message) {}

    /** 客户端只看到可读的结构变化；执行时根据持久化计划及当前状态重新编译。 */
    public record PublishPlan(
            String id,
            String objectId,
            int revision,
            int versionNo,
            String state,
            List<Step> changes,
            List<Check> checks,
            List<Dependency> dependencies,
            OffsetDateTime createdAt,
            List<FieldConversions.Change> conversions,
            List<ObjectApplicationUpgrade.Impact> applicationUpgrades) {
        public PublishPlan {
            applicationUpgrades =
                    applicationUpgrades == null ? List.of() : List.copyOf(applicationUpgrades);
        }

        public PublishPlan(
                String id,
                String objectId,
                int revision,
                int versionNo,
                String state,
                List<Step> changes,
                List<Check> checks,
                List<Dependency> dependencies,
                OffsetDateTime createdAt,
                List<FieldConversions.Change> conversions) {
            this(
                    id,
                    objectId,
                    revision,
                    versionNo,
                    state,
                    changes,
                    checks,
                    dependencies,
                    createdAt,
                    conversions,
                    List.of());
        }

        public PublishPlan(
                String id,
                String objectId,
                int revision,
                int versionNo,
                String state,
                List<Step> changes,
                List<Check> checks,
                List<Dependency> dependencies,
                OffsetDateTime createdAt) {
            this(
                    id,
                    objectId,
                    revision,
                    versionNo,
                    state,
                    changes,
                    checks,
                    dependencies,
                    createdAt,
                    List.of());
        }
    }

    /** 清空名单必须与计划中的非空列精确一致；旧客户端不确认时不能执行有损转换。 */
    public record ExecutePlan(
            String planId,
            String reason,
            List<String> clearFieldIds,
            List<String> suspendApplicationIds) {
        public ExecutePlan(String planId, String reason, List<String> clearFieldIds) {
            this(planId, reason, clearFieldIds, List.of());
        }

        public ExecutePlan(String planId, String reason) {
            this(planId, reason, List.of());
        }

        public ExecutePlan {
            clearFieldIds = clearFieldIds == null ? List.of() : List.copyOf(clearFieldIds);
            suspendApplicationIds =
                    suspendApplicationIds == null ? List.of() : List.copyOf(suspendApplicationIds);
        }
    }

    public record Execution(
            String id,
            String objectId,
            int versionNo,
            String state,
            String reason,
            String error,
            OffsetDateTime createdAt,
            OffsetDateTime executedAt) {}

    public record ObjectRow(
            String id,
            String objectCode,
            String objectName,
            String tableName,
            String schemaName,
            String source,
            String status,
            Integer publishedVersion,
            int fieldCount,
            int detailCount,
            int relationCount,
            int lockVersion,
            Settings settings,
            OffsetDateTime updatedAt,
            String category) {
        /** 兼容省略分类的既有调用。 */
        public ObjectRow(
                String id,
                String objectCode,
                String objectName,
                String tableName,
                String schemaName,
                String source,
                String status,
                Integer publishedVersion,
                int fieldCount,
                int detailCount,
                int relationCount,
                int lockVersion,
                Settings settings,
                OffsetDateTime updatedAt) {
            this(
                    id,
                    objectCode,
                    objectName,
                    tableName,
                    schemaName,
                    source,
                    status,
                    publishedVersion,
                    fieldCount,
                    detailCount,
                    relationCount,
                    lockVersion,
                    settings,
                    updatedAt,
                    null);
        }
    }
}
