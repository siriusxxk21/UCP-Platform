package com.lingan.ucp.nocode.metadata.service.object;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.nocode.api.DataCenter.BusinessFileGroup;
import com.lingan.ucp.nocode.api.DataCenter.BusinessFilePolicy;
import com.lingan.ucp.nocode.api.DataCenter.Definition;
import com.lingan.ucp.nocode.api.DataCenter.Detail;
import com.lingan.ucp.nocode.api.DataCenter.FieldOptions;
import com.lingan.ucp.nocode.api.DataCenter.Settings;
import com.lingan.ucp.nocode.api.FieldDefinition;
import com.lingan.ucp.nocode.enums.FieldTypeEnum;
import com.lingan.ucp.nocode.enums.MemberStateEnum;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 业务文件接入规则的结构校验与字段身份重映射。
 *
 * <p>校验随设计保存执行（挂载点与整单规则一致）：规则非空即视为启用，必须完整且内部一致； 关闭接入以规则为 null 表达。文件默认值接入的阻断在发布预检执行，保存阶段不拦截草稿配置。
 */
public final class BusinessFilePolicies {

    private static final int MAX_SPACE_NAME = 64;
    private static final int MAX_FIXED_LEVELS = 5;
    private static final int MAX_GROUPS = 2;
    private static final int MAX_LABEL_FIELDS = 5;
    private static final int MAX_PARTICIPATING = 20;
    private static final int MAX_NAME_LENGTH = 100;

    /** 记录目录名称允许的字段类型：可稳定展示的标量字段，排除文件、集合与实时计算字段 */
    private static final Set<FieldTypeEnum> LABEL_TYPES =
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
                    FieldTypeEnum.URL,
                    FieldTypeEnum.AUTO_NUMBER,
                    FieldTypeEnum.UUID,
                    FieldTypeEnum.REFERENCE);

    private BusinessFilePolicies() {}

    /** 设计保存前的结构校验：目录模板、分组来源与参与字段必须完整、类型正确且引用有效。 */
    public static void validate(Definition d) {
        Settings settings = d.settings();
        BusinessFilePolicy policy = settings == null ? null : settings.businessFilePolicy();
        if (policy == null) return;
        text(policy.spaceName(), "业务空间名称", MAX_SPACE_NAME);
        if (policy.fixedPath() != null) {
            if (policy.fixedPath().size() > MAX_FIXED_LEVELS)
                throw invalid("固定目录最多 " + MAX_FIXED_LEVELS + " 层");
            for (String level : policy.fixedPath()) name(level, "固定目录名称");
        }
        Map<String, FieldDefinition> main = activeMainFields(d);
        if (policy.groups() != null) {
            if (policy.groups().size() > MAX_GROUPS) throw invalid("业务分组最多 " + MAX_GROUPS + " 层");
            Set<String> groupFields = new HashSet<>();
            for (BusinessFileGroup group : policy.groups()) {
                if (group == null) throw invalid("业务分组配置无效");
                FieldDefinition field = main.get(group.fieldId());
                if (field == null) throw invalid("业务分组必须引用主表有效字段");
                FieldTypeEnum type = FieldTypeEnum.fromCode(field.type());
                boolean date = type == FieldTypeEnum.DATE || type == FieldTypeEnum.DATETIME;
                if (type != FieldTypeEnum.TEXT
                        && type != FieldTypeEnum.SELECT
                        && type != FieldTypeEnum.REFERENCE
                        && !date) throw invalid("业务分组仅支持主表文本、单选、日期或单值关联字段：" + field.name());
                String format = group.format();
                if (format != null && !format.isBlank()) {
                    if (!"YEAR".equals(format) && !"MONTH".equals(format))
                        throw invalid("业务分组格式仅支持年份或年月");
                    if (!date) throw invalid("年份/年月格式仅适用于日期字段：" + field.name());
                }
                if (!groupFields.add(group.fieldId())) throw invalid("业务分组字段重复");
            }
        }
        if (policy.recordLabelFields() != null) {
            if (policy.recordLabelFields().size() > MAX_LABEL_FIELDS)
                throw invalid("记录目录名称最多引用 " + MAX_LABEL_FIELDS + " 个字段");
            for (String fieldId : policy.recordLabelFields()) {
                FieldDefinition field = main.get(fieldId);
                if (field == null) throw invalid("记录目录名称必须引用主表有效字段");
                if (!LABEL_TYPES.contains(FieldTypeEnum.fromCode(field.type())))
                    throw invalid("记录目录名称不支持此字段类型：" + field.name());
            }
        }
        if (policy.fieldIds() == null || policy.fieldIds().isEmpty())
            throw invalid("接入业务网盘时至少选择一个附件或图片字段");
        if (policy.fieldIds().size() > MAX_PARTICIPATING)
            throw invalid("接入字段最多 " + MAX_PARTICIPATING + " 个");
        Set<String> participating = new HashSet<>();
        for (String fieldId : policy.fieldIds()) {
            if (!participating.add(fieldId)) throw invalid("接入字段重复");
            FieldDefinition field = main.get(fieldId);
            if (field == null) field = activeDetailField(d, fieldId);
            if (field == null) throw invalid("接入字段不存在或已停用");
            if (!FieldTypeEnum.ATTACHMENT.matches(field.type())
                    && !FieldTypeEnum.IMAGE.matches(field.type()))
                throw invalid("业务文件仅支持附件或图片字段：" + field.name());
        }
    }

    private static Map<String, FieldDefinition> activeMainFields(Definition d) {
        return d.fields().stream()
                .filter(f -> active(d.fieldOptions(), f.id()))
                .collect(Collectors.toMap(FieldDefinition::id, f -> f));
    }

    private static FieldDefinition activeDetailField(Definition d, String fieldId) {
        for (Detail detail : d.details()) {
            if (!MemberStateEnum.ACTIVE.matches(detail.state())) continue;
            FieldDefinition field =
                    detail.fields().stream()
                            .filter(f -> f.id().equals(fieldId))
                            .filter(f -> active(detail.fieldOptions(), f.id()))
                            .findFirst()
                            .orElse(null);
            if (field != null) return field;
        }
        return null;
    }

    private static boolean active(Map<String, FieldOptions> options, String fieldId) {
        return !MemberStateEnum.INACTIVE.matches(
                options.getOrDefault(fieldId, FieldOptions.defaults()).state());
    }

    private static void text(String value, String label, int limit) {
        if (value == null || value.isBlank() || value.length() > limit)
            throw invalid(label + "不能为空或超过 " + limit + " 字");
    }

    private static void name(String value, String label) {
        if (value == null || value.isBlank() || value.length() > MAX_NAME_LENGTH)
            throw invalid(label + "不能为空或超过 " + MAX_NAME_LENGTH + " 字");
        if (value.contains("/") || value.contains("\\") || value.trim().startsWith("."))
            throw invalid(label + "不能包含路径分隔符或以点开头");
    }

    /** 字段身份变化（新建草稿补齐正式 ID、明细编码重映射）后同步规则内引用，避免保存设计时引用悬空。 */
    public static BusinessFilePolicy remap(BusinessFilePolicy policy, Map<String, String> ids) {
        if (policy == null) return null;
        Function<String, String> id = v -> v == null ? null : ids.getOrDefault(v, v);
        // fixedPath 是固定目录名而非字段引用，保持原样
        return new BusinessFilePolicy(
                policy.spaceId(),
                policy.spaceName(),
                policy.fixedPath(),
                policy.groups() == null
                        ? null
                        : policy.groups().stream()
                                .map(g -> new BusinessFileGroup(id.apply(g.fieldId()), g.format()))
                                .toList(),
                remapList(policy.recordLabelFields(), id),
                remapList(policy.fieldIds(), id));
    }

    private static List<String> remapList(List<String> values, Function<String, String> id) {
        return values == null ? null : values.stream().map(id).toList();
    }

    /** 空值容错读取，供发布预检等场景复用 */
    public static BusinessFilePolicy policyOf(Definition d) {
        return d == null || d.settings() == null ? null : d.settings().businessFilePolicy();
    }
}
