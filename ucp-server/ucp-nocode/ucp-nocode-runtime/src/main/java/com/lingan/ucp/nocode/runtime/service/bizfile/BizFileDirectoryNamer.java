package com.lingan.ucp.nocode.runtime.service.bizfile;

import cn.hutool.core.util.StrUtil;

import com.lingan.ucp.nocode.api.BusinessFields;
import com.lingan.ucp.nocode.api.DataCenter;
import com.lingan.ucp.nocode.api.FieldDefinition;
import com.lingan.ucp.nocode.api.RecordTitles;
import com.lingan.ucp.nocode.api.SelectionFields;
import com.lingan.ucp.nocode.enums.BusinessFileLabelStatusEnum;
import com.lingan.ucp.nocode.enums.FieldTypeEnum;
import com.lingan.ucp.nocode.enums.MemberStateEnum;
import com.lingan.ucp.nocode.runtime.service.record.RecordSelectionSupport;
import com.lingan.ucp.nocode.runtime.service.selection.SelectionCatalog;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 业务目录命名器
 *
 * <p>按对象业务文件规则把主表值解析为目录展示名：固定目录 → 业务分组 → 记录目录。 内部身份始终绑定字段/记录稳定 ID，目录名只影响展示；分组值经当前操作者授权解析，
 * 无权或失效时退化为受限名称，不泄露未授权内容。
 */
@Component
public class BizFileDirectoryNamer {

    /** 空分组值的统一目录名 */
    private static final String EMPTY_GROUP_LABEL = "未分类";

    /** 分组名称来源不可读时的占位标签 */
    private static final String RESTRICTED_GROUP_LABEL = "受限分组";

    /** 记录名全部不可读时的占位前缀 */
    private static final String RESTRICTED_RECORD_PREFIX = "记录 ";

    /** 单段目录名长度上限，防御性截断，避免超出 drive_entry.name 上限 */
    private static final int MAX_SEGMENT_LENGTH = 100;

    /** 分组值内的层级分隔符替换字符：绑定表以“|”拼接层级，来源值不得破坏解析 */
    private static final String LEVEL_SEPARATOR = "|";

    /** 受限标签区分标识长度：取摘要前缀，仅用于区分，不承载业务含义 */
    private static final int TOKEN_LENGTH = 8;

    @Resource private SelectionCatalog selectionCatalog;
    @Resource private RecordSelectionSupport selections;

    /**
     * 浏览期安全标签
     *
     * <p>restricted 为真表示至少一个名称来源字段或关联当前不可读；只显示可读组合片段或安全占位。目录授权按记录本身判定，不因标签降级放开或关闭任何文件。
     */
    public record Label(String text, boolean restricted, BusinessFileLabelStatusEnum status) {
        public Label(String text, boolean restricted) {
            this(
                    text,
                    restricted,
                    restricted
                            ? BusinessFileLabelStatusEnum.RESTRICTED
                            : BusinessFileLabelStatusEnum.NORMAL);
        }
    }

    private static final Pattern TITLE_TOKEN =
            Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}}");

    /** 默认标题沿用对象标题模板；显式组合始终优先，旧绑定不改写规则。 */
    public List<String> recordFields(
            DataCenter.Definition definition, DataCenter.BusinessFilePolicy policy) {
        if (customLabel(policy)) return policy.recordLabelFields();
        String template =
                definition.settings() == null ? null : definition.settings().titleTemplate();
        if (StrUtil.isBlank(template))
            return definition.titleFieldId() == null
                    ? List.of()
                    : List.of(definition.titleFieldId());
        List<String> ids = new ArrayList<>();
        Matcher matcher = TITLE_TOKEN.matcher(template);
        while (matcher.find()) {
            String code = matcher.group(1);
            ids.add(
                    definition.fields().stream()
                            .filter(f -> f.code().equals(code))
                            .map(FieldDefinition::id)
                            .findFirst()
                            .orElse("missing:" + code));
        }
        return ids;
    }

    private boolean customLabel(DataCenter.BusinessFilePolicy policy) {
        return policy != null
                && policy.recordLabelFields() != null
                && !policy.recordLabelFields().isEmpty();
    }

    /**
     * 计算记录目录层级（固定目录 + 业务分组 + 记录目录名）
     *
     * @param values 主表字段值（字段 ID 键）
     * @param recordId 记录编号，用于无可用标题时的安全短标识
     */
    public List<String> recordPath(
            DataCenter.Definition definition,
            DataCenter.BusinessFilePolicy policy,
            Map<String, Object> values,
            String recordId,
            String appId,
            long actor) {
        List<String> path = new ArrayList<>();
        if (policy.fixedPath() != null) {
            for (String segment : policy.fixedPath()) {
                path.add(sanitize(segment));
            }
        }
        List<String> groupLabels = new ArrayList<>();
        List<String> groupKeys = new ArrayList<>();
        if (policy.groups() != null) {
            for (DataCenter.BusinessFileGroup group : policy.groups()) {
                GroupValue resolved = groupValue(definition, group, values, appId, actor);
                groupLabels.add(resolved.label());
                groupKeys.add(resolved.key());
            }
        }
        path.addAll(groupLabels);
        path.add(recordLabel(definition, policy, values, recordId, appId, actor));
        return path;
    }

    /** 业务分组稳定键串：按分组来源值（引用以目标记录 ID）拼接，用于判断目录是否需要调整 */
    public String groupKeys(
            DataCenter.Definition definition,
            DataCenter.BusinessFilePolicy policy,
            Map<String, Object> values) {
        List<String> keys = new ArrayList<>();
        if (policy.groups() != null) {
            for (DataCenter.BusinessFileGroup group : policy.groups()) {
                keys.add(groupKey(group, values));
            }
        }
        return String.join(LEVEL_SEPARATOR, keys);
    }

    /**
     * 浏览期分组标签
     *
     * <p>key 来自目录绑定：名称来源字段可读时为原始值，不可读时为服务端摘要令牌； 受限标签只含占位文案与摘要前缀，不能还原目录名称，也不改变记录授权。
     */
    public Label groupView(
            DataCenter.Definition definition,
            DataCenter.BusinessFileGroup group,
            String key,
            boolean hashed,
            String appId,
            long actor) {
        String value = StrUtil.trimToEmpty(key);
        if (value.isEmpty()) {
            return new Label(EMPTY_GROUP_LABEL, false);
        }
        if (hashed) {
            return new Label(RESTRICTED_GROUP_LABEL + " · " + shortToken(value), true);
        }
        return resolveGroup(definition, group, value, appId, actor);
    }

    /**
     * 浏览期记录目录标签
     *
     * <p>values 只包含当前访问者可读的标签来源字段值（可读空值保留键）；未配置组合时复用对象标题。不可读、空值与失效配置分别返回状态，不返回保存时的目录名称。
     */
    public Label recordView(
            DataCenter.Definition definition,
            DataCenter.BusinessFilePolicy policy,
            Map<String, Object> values,
            String recordId,
            String appId,
            long actor) {
        List<String> parts = new ArrayList<>();
        Map<String, Object> display = new LinkedHashMap<>();
        boolean restricted = false;
        boolean invalid = false;
        for (String fieldId : recordFields(definition, policy)) {
            FieldDefinition field =
                    definition.fields().stream()
                            .filter(f -> f.id().equals(fieldId))
                            .findFirst()
                            .orElse(null);
            if (field == null
                    || !BusinessFields.linkable(field)
                    || MemberStateEnum.INACTIVE.matches(
                            definition
                                    .fieldOptions()
                                    .getOrDefault(fieldId, DataCenter.FieldOptions.defaults())
                                    .state())) {
                invalid = true;
                continue;
            }
            if (!values.containsKey(fieldId)) {
                restricted = true;
                continue;
            }
            Object raw = values.get(fieldId);
            if (raw == null || StrUtil.isBlank(raw.toString())) {
                display.put(fieldId, "");
                continue;
            }
            String value = displayValue(definition, fieldId, raw.toString(), appId, actor);
            if (value == null) {
                restricted = true;
                continue;
            }
            display.put(fieldId, value);
            if (!StrUtil.isBlank(value)) parts.add(sanitize(value));
        }
        BusinessFileLabelStatusEnum status =
                restricted
                        ? BusinessFileLabelStatusEnum.RESTRICTED
                        : invalid
                                ? BusinessFileLabelStatusEnum.INVALID
                                : BusinessFileLabelStatusEnum.NORMAL;
        String fallback = RESTRICTED_RECORD_PREFIX + shortId(recordId);
        if (!customLabel(policy)) {
            // 模板任何字段不可见都不拼接残缺标题；仅使用已裁剪且解析过的值。
            if (restricted || invalid) return new Label(fallback, restricted, status);
            String title = RecordTitles.render(definition, display);
            if ((!parts.isEmpty() || recordFields(definition, policy).isEmpty())
                    && !StrUtil.isBlank(title)
                    && !"未提供可见标题".equals(title)) {
                return new Label(sanitize(title), false);
            }
        } else if (!parts.isEmpty()) {
            return new Label(String.join(" · ", parts), restricted, status);
        }
        if (restricted || invalid) return new Label(fallback, restricted, status);
        return new Label(
                "未填写标题 · ID " + shortId(recordId), false, BusinessFileLabelStatusEnum.EMPTY);
    }

    /** 明细行目录名：绑定持久行 ID，排序变化不改变目录身份 */
    public String rowLabel(String rowId) {
        return "行 " + shortId(rowId);
    }

    private record GroupValue(String label, String key) {}

    private GroupValue groupValue(
            DataCenter.Definition definition,
            DataCenter.BusinessFileGroup group,
            Map<String, Object> values,
            String appId,
            long actor) {
        String raw = groupKey(group, values);
        if (raw.isEmpty()) {
            return new GroupValue(EMPTY_GROUP_LABEL, "");
        }
        return new GroupValue(resolveGroup(definition, group, raw, appId, actor).text(), raw);
    }

    /** 分组展示名按格式与字段类型解析；浏览与保存共用同一规则，目录名和导航标签一致 */
    private Label resolveGroup(
            DataCenter.Definition definition,
            DataCenter.BusinessFileGroup group,
            String raw,
            String appId,
            long actor) {
        String format = StrUtil.trimToEmpty(group.format());
        if ("YEAR".equals(format) && raw.length() >= 4) {
            return new Label(sanitize(raw.substring(0, 4)), false);
        }
        if ("MONTH".equals(format) && raw.length() >= 7) {
            return new Label(sanitize(raw.substring(0, 7)), false);
        }
        String displayed = displayValue(definition, group.fieldId(), raw, appId, actor);
        return displayed == null
                ? new Label(
                        RESTRICTED_GROUP_LABEL
                                + " · "
                                + shortToken(cn.hutool.crypto.digest.DigestUtil.md5Hex(raw)),
                        true)
                : new Label(sanitize(displayed), false);
    }

    /** 分组键持久化前替换层级分隔符：来源值不得破坏“|”拼接的层级解析 */
    private String groupKey(DataCenter.BusinessFileGroup group, Map<String, Object> values) {
        Object value = values.get(group.fieldId());
        return value == null
                ? ""
                : StrUtil.trimToEmpty(value.toString()).replace(LEVEL_SEPARATOR, "_");
    }

    /** 记录目录名：编号/标题等可显示字段组合；全空时退化为“记录 + 安全短标识” */
    private String recordLabel(
            DataCenter.Definition definition,
            DataCenter.BusinessFilePolicy policy,
            Map<String, Object> values,
            String recordId,
            String appId,
            long actor) {
        return recordView(definition, policy, values, recordId, appId, actor).text();
    }

    /** 按字段类型解析展示值：单选用选项标签、单值引用用目标记录标题，其余取原值。 关联解析失败或无权时返回空标识，由调用方生成安全占位，不返回原始关联 ID。 */
    private String displayValue(
            DataCenter.Definition definition,
            String fieldId,
            String raw,
            String appId,
            long actor) {
        com.lingan.ucp.nocode.api.FieldDefinition field =
                definition.fields().stream()
                        .filter(f -> f.id().equals(fieldId))
                        .findFirst()
                        .orElse(null);
        if (field == null) {
            return raw;
        }
        DataCenter.FieldOptions options =
                definition.fieldOptions().getOrDefault(fieldId, DataCenter.FieldOptions.defaults());
        if (FieldTypeEnum.SELECT.matches(field.type())) {
            List<SelectionFields.Option> candidates =
                    selectionCatalog.selectedOptions(field, options, List.of(raw));
            for (SelectionFields.Option option : candidates) {
                if (Objects.equals(option.value(), raw)) {
                    return option.label();
                }
            }
            return raw;
        }
        if (FieldTypeEnum.REFERENCE.matches(field.type())) {
            DataCenter.Relation relation = BusinessFields.relation(definition, fieldId);
            if (relation != null && !BusinessFields.multiple(relation)) {
                try {
                    Map<String, SelectionFields.Option> resolved =
                            selections.referenceOptions(
                                    appId, relation.targetObjectId(), List.of(raw), actor);
                    SelectionFields.Option option = resolved.get(raw);
                    if (option != null && !"未提供可见标题".equals(option.label())) {
                        return option.label();
                    }
                } catch (RuntimeException ignored) {
                    // 目标对象不可见或已停用时仅使用安全占位，不阻断保存
                }
            }
            return null;
        }
        return raw;
    }

    /** 记录短标识：编号末尾片段，仅用于区分，不承载业务含义 */
    private String shortId(String recordId) {
        return recordId.length() <= TOKEN_LENGTH
                ? recordId
                : recordId.substring(recordId.length() - TOKEN_LENGTH);
    }

    /** 受限区分标识：服务端摘要前缀，无法还原原始名称 */
    private String shortToken(String token) {
        return token.length() <= TOKEN_LENGTH ? token : token.substring(0, TOKEN_LENGTH);
    }

    /** 目录名规范化：去除路径分隔与控制字符，限制长度；空值退化为“未分类” */
    public String sanitize(String name) {
        String cleaned =
                StrUtil.trimToEmpty(name)
                        .replace("/", " ")
                        .replace("\\", " ")
                        .replaceAll("[\\r\\n\\t]", " ")
                        .trim();
        if (cleaned.equals(".") || cleaned.equals("..")) {
            cleaned = "未分类";
        }
        if (cleaned.length() > MAX_SEGMENT_LENGTH) {
            cleaned = cleaned.substring(0, MAX_SEGMENT_LENGTH).trim();
        }
        return cleaned.isEmpty() ? EMPTY_GROUP_LABEL : cleaned;
    }
}
