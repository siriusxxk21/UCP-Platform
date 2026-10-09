package com.lingan.ucp.nocode.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/** 字段级对象规则：随对象版本冻结；只描述字段身份和常量，不含 SQL 或物理标识符。主表字段与内部明细字段共用。 */
public record FieldRules(
        @JsonInclude(JsonInclude.Include.NON_NULL) Reference reference,
        @JsonInclude(JsonInclude.Include.NON_NULL) Linkage linkage,
        @JsonInclude(JsonInclude.Include.NON_NULL) String defaultFormula,
        /** 仅 MONEY 目标且存在 linkage 或 defaultFormula 时允许；取值 HALF_UP / FLOOR / DOWN；null 表示 FLOOR。 */
        @JsonInclude(JsonInclude.Include.NON_NULL) String rounding,
        /** 只在运行模型投影时由服务端填写（全局字段 ID，可含主表字段）；存储快照中恒为 null。 */
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> dependsOn,
        /**
         * 只在运行模型投影时由服务端填写：字段有只读数据联动（linkage.readOnly 为 true 或 null）或配置了公式默认值时为 true，否则为
         * null；存储快照中恒为 null（与 dependsOn 同性质，2026-09-29 只读口径）。
         */
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean readOnly) {

    /** 存储形态的构造：不含只在运行模型投影时填写的 readOnly（为 null）。 */
    public FieldRules(
            Reference reference,
            Linkage linkage,
            String defaultFormula,
            String rounding,
            List<String> dependsOn) {
        this(reference, linkage, defaultFormula, rounding, dependsOn, null);
    }

    /**
     * 字段在运行期是否只读（2026-09-29 业务方裁定）：数据联动默认只读（readOnly 为 null 按 true，显式 false 仍可手改）；公式默认值一律只读。
     * 只看配置本身，不看求值结果。
     */
    public boolean effectiveReadOnly() {
        return readOnlyLinkage() || defaultFormula != null;
    }

    /** 对象赋值规则优先于旧表单带入；引用筛选本身不改变字段值。 */
    public static boolean hasValueRule(DataCenter.FieldOptions options) {
        return options != null
                && options.rules() != null
                && (options.rules().linkage() != null
                        || options.rules().defaultFormula() != null
                        || Boolean.TRUE.equals(options.rules().readOnly()));
    }

    /** 数据联动是否只读：readOnly 为 null 按只读，只有显式 false 可手改。 */
    public boolean readOnlyLinkage() {
        return linkage != null && linkage.readOnlyOrDefault();
    }

    public record Reference(String labelFieldId, List<Condition> filter) {}

    /**
     * 数据联动。autoUpdate、emptyValue 为 null 时不序列化：存量规则（没有这两个键）的序列化结果逐字节不变，
     * 审批生效路径按对象定义摘要比对，序列化一变在途审批全部失效。
     *
     * <p>有意不保留 5 个参数的构造形态：重建联动的代码（设计保存、对象复制、迁移工具）必须显式带上这两个值，否则会在每次保存时把它们静默丢掉。
     */
    public record Linkage(
            String sourceObjectId,
            List<Condition> conditions,
            String valueFieldId,
            String multiRow,
            Boolean readOnly,
            /** 来源变化时自动更新。只有 true 才开启；null 即关（存储里不出现 false，见 FieldRuleValidator.normalize）。 */
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean autoUpdate,
            /** 没有匹配记录时写入的值（字面量字符串，按目标字段类型解析）；null 表示写空。只在 autoUpdate 为 true 时允许。 */
            @JsonInclude(JsonInclude.Include.NON_NULL) String emptyValue) {
        /** 只读开关的生效值：null 按只读（2026-09-29 口径，原为按可编辑）；只有显式 false 可手改。 */
        public boolean readOnlyOrDefault() {
            return !Boolean.FALSE.equals(readOnly);
        }

        /** 是否开启「来源变化时自动更新」：只有显式 true 才算开，存量规则没有这个键即关。 */
        public boolean autoUpdateOn() {
            return Boolean.TRUE.equals(autoUpdate);
        }
    }

    /**
     * 条件之间只有 AND。fieldId 是来源对象字段的稳定 ID，或 "$record"。 valueSource=FORM_FIELD 时，formFieldId
     * 是本对象的全局稳定字段 ID： 主表字段的规则只能引用主表字段；明细字段的规则可以引用同一明细的本行字段或主表字段。作用域由 ID 推导，不另存。
     *
     * <p>valueSource=CURRENT_RECORD（只用于开启自动更新的数据联动）：fieldId 是来源对象主表上指向规则所在对象的单值引用字段，
     * 含义为「来源那一行的这个引用字段指向的就是当前这条记录」；算子只有 eq，value 与 formFieldId 都为 null。
     */
    public record Condition(
            String fieldId,
            String operator,
            String valueSource,
            Object value,
            String formFieldId) {}

    public static final String RECORD_KEY = "$record";
    public static final int MATCH_CAP = 5000;
    public static final int ROW_BATCH_CAP = 500;

    // ── 运行时 API 契约 ──
    /** values = 主表当前值；details 为空表示只求主表。 */
    public record EvaluateQuery(
            String applicationId,
            String objectId,
            String formId,
            String recordId,
            Boolean creating,
            Map<String, Object> values,
            List<String> changed,
            List<String> overridable,
            List<DetailRows> details) {}

    public record DetailRows(String detailId, List<RowInput> rows) {}

    /** rowKey 与保存命令的 clientRowKey 同一口径（RecordDetailWriter.java:89-93）；values 只含本行字段。 */
    public record RowInput(
            String rowKey,
            String detailRecordId,
            Boolean creating,
            Map<String, Object> values,
            List<String> changed,
            List<String> overridable) {}

    public record EvaluatePreview(
            EvaluateQuery query,
            List<ApplicationCenter.ObjectReference> objects,
            ApplicationUi.Form form) {}

    /** 主表结果的 detailId 和 rowKey 为 null。inScope 只在 kind=REFERENCE 时有值；当前值为空时为 null。 */
    public record Result(
            String fieldId,
            String kind,
            String state,
            Object value,
            int matchedRows,
            boolean readOnly,
            String message,
            List<String> pendingFields,
            String detailId,
            String rowKey,
            Boolean inScope) {}

    public record Evaluation(List<Result> results) {}
}
