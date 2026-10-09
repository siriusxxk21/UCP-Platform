package com.lingan.ucp.nocode.api;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import java.util.*;
import java.util.regex.Pattern;

/** 标题模板按字段编码取值，仅使用调用方已经过权限裁剪的记录。 */
public final class RecordTitles {
    private static final Pattern TOKEN =
            Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}}");

    private RecordTitles() {}

    public static void validate(String template, List<FieldDefinition> fields) {
        if (template == null || template.isBlank()) return;
        java.util.regex.Matcher matcher = TOKEN.matcher(template);
        while (matcher.find()) {
            String code = matcher.group(1);
            if (fields.stream()
                    .noneMatch(
                            f ->
                                    f != null
                                            && Objects.equals(f.code(), code)
                                            && BusinessFields.linkable(f)))
                throw invalid("标题模板字段不存在或不支持：" + code);
        }
        String remainder = TOKEN.matcher(template).replaceAll("");
        if (remainder.contains("{{") || remainder.contains("}}")) throw invalid("标题模板请使用 {{字段编码}}");
    }

    /** 渲染记录名称要读哪些字段：没有标题模板时是标题字段，有模板时是模板里用到的字段（按编码找到的那些）。 */
    public static Set<String> fieldIds(DataCenter.Definition d) {
        String template = d.settings() == null ? null : d.settings().titleTemplate();
        Set<String> result = new LinkedHashSet<>();
        if (template == null || template.isBlank()) {
            if (d.titleFieldId() != null) result.add(d.titleFieldId());
            return result;
        }
        var matcher = TOKEN.matcher(template);
        while (matcher.find())
            for (FieldDefinition field : d.fields())
                if (field.code().equals(matcher.group(1))) result.add(field.id());
        return result;
    }

    public static String render(DataCenter.Definition d, Map<String, Object> values) {
        return render(d, values, d.settings() == null ? null : d.settings().titleTemplate());
    }

    /** 按指定模板渲染，例如引用字段的显示名字段 {{code}}；模板为空时回落标题字段。 */
    public static String render(
            DataCenter.Definition d, Map<String, Object> values, String template) {
        if (template == null || template.isBlank())
            return Objects.toString(values.get(d.titleFieldId()), "未提供可见标题");
        java.util.regex.Matcher matcher = TOKEN.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            FieldDefinition field =
                    d.fields().stream()
                            .filter(f -> f.code().equals(matcher.group(1)))
                            .findFirst()
                            .orElse(null);
            if (field == null || !values.containsKey(field.id())) return "未提供可见标题";
            matcher.appendReplacement(
                    result,
                    java.util.regex.Matcher.quoteReplacement(
                            Objects.toString(values.get(field.id()), "")));
        }
        matcher.appendTail(result);
        return result.toString().isBlank() ? "未提供可见标题" : result.toString();
    }
}
