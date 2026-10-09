package com.lingan.ucp.nocode.runtime.service.folder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.nocode.api.ApplicationRecords.Row;
import com.lingan.ucp.nocode.api.DataCenter;
import com.lingan.ucp.nocode.api.DataObjectApi;
import com.lingan.ucp.nocode.api.FieldDefinition;
import com.lingan.ucp.nocode.api.RecordFolders;
import com.lingan.ucp.nocode.api.RecordTitles;
import com.lingan.ucp.nocode.api.SelectionFields;
import com.lingan.ucp.nocode.enums.FieldTypeEnum;
import com.lingan.ucp.nocode.enums.RecordFolderNamePartEnum;
import com.lingan.ucp.nocode.runtime.dal.dataobject.RecordFolderSourceDO;
import com.lingan.ucp.nocode.runtime.service.record.RecordQueryAccess;
import com.lingan.ucp.nocode.runtime.service.selection.SelectionCatalog;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 记录子文件夹的命名器：按来源的命名模板给记录的子文件夹起名。任何情况下都返回一个合法的名字，绝不抛出。
 *
 * <p>不读写任何表、不建任何东西；重名不在这里处理（网盘侧建目录时加序号）。不调用附件归档的目录命名器——它的展示值解析要应用与操作者授权，
 * 后台线程里没有；这里按系统身份取值，只沿用它的清洗规则。
 */
@Component
public class RecordFolderNamer {
    /** 记录没有可见标题时标题渲染给出的占位文字；用它命名等于没有名字 */
    static final String NO_TITLE = "未提供可见标题";

    /** 子文件夹名称长度上限 */
    static final int MAX_LENGTH = 100;

    @Resource private ObjectMapper json;
    @Resource private SelectionCatalog selectionCatalog;
    @Resource private RecordQueryAccess records;
    @Resource private DataObjectApi objects;

    public String name(
            RecordFolderSourceDO source, DataCenter.Definition published, Row stored, long actor) {
        try {
            String raw = compose(source, published, stored, actor);
            return raw == null ? fallback(stored) : finish(raw, stored);
        } catch (RuntimeException failed) {
            // 模板损坏、选项目录或关联记录读取失败等任何情况都落到兜底名：命名在后台线程和补建的循环里，抛出去会让一页补建中断。
            return fallback(stored);
        }
    }

    /** 拼出清洗之前的名字；返回 null 表示用兜底名 */
    private String compose(
            RecordFolderSourceDO source, DataCenter.Definition published, Row stored, long actor) {
        RecordFolders.NameTemplate template = template(source);
        if (template == null) {
            String title = RecordTitles.render(published, stored.values());
            return title == null || title.isBlank() || NO_TITLE.equals(title) ? null : title;
        }
        List<String> values = new ArrayList<>();
        boolean anyField = false;
        for (RecordFolders.NamePart part : template.parts()) {
            if (RecordFolderNamePartEnum.TEXT.matches(part.kind())) {
                values.add(part.text() == null ? "" : part.text());
                continue;
            }
            String value = fieldValue(part.fieldId(), published, stored, actor);
            anyField = anyField || !value.isEmpty();
            values.add(value);
        }
        // 固定文字不算：所有字段段都取不到值时，名字里只剩固定文字，分不出是哪条记录
        if (!anyField) return null;
        String separator = template.separator() == null ? "" : template.separator();
        return String.join(separator, values.stream().filter(value -> !value.isBlank()).toList());
    }

    private RecordFolders.NameTemplate template(RecordFolderSourceDO source) {
        String text = source.getNameTemplate();
        if (text == null || text.isBlank()) return null;
        RecordFolders.NameTemplate template;
        try {
            template = json.readValue(text, RecordFolders.NameTemplate.class);
        } catch (java.io.IOException invalid) {
            throw new IllegalStateException("文件夹命名模板无法读取", invalid);
        }
        if (template == null || template.parts() == null || template.parts().isEmpty())
            throw new IllegalStateException("文件夹命名模板为空");
        return template;
    }

    /** 一个字段段的值；取不到一律为空串 */
    private String fieldValue(
            String fieldId, DataCenter.Definition published, Row stored, long actor) {
        FieldDefinition field =
                published.fields().stream()
                        .filter(item -> item.id().equals(fieldId))
                        .findFirst()
                        .orElse(null);
        if (field == null) return "";
        Object value = stored.values().get(fieldId);
        if (value == null || value.toString().isBlank()) return "";
        String text = value.toString().trim();
        DataCenter.Relation relation = RecordFolders.singleRelation(published, fieldId);
        if (relation != null) return referenceTitle(relation, text, actor);
        if (FieldTypeEnum.TEXT.matches(field.type())
                || FieldTypeEnum.AUTO_NUMBER.matches(field.type())
                || FieldTypeEnum.INTEGER.matches(field.type())) return text;
        if (FieldTypeEnum.DATE.matches(field.type())
                || FieldTypeEnum.DATETIME.matches(field.type()))
            return text.length() > 10 ? text.substring(0, 10) : text;
        if (FieldTypeEnum.SELECT.matches(field.type())) return optionLabel(field, published, text);
        return "";
    }

    /** 单选的选项名称；取不到或选项目录抛异常时用原值 */
    private String optionLabel(FieldDefinition field, DataCenter.Definition published, String raw) {
        try {
            DataCenter.FieldOptions options =
                    published
                            .fieldOptions()
                            .getOrDefault(field.id(), DataCenter.FieldOptions.defaults());
            for (SelectionFields.Option option :
                    selectionCatalog.selectedOptions(field, options, List.of(raw)))
                if (Objects.equals(option.value(), raw) && option.label() != null)
                    return option.label().trim();
            return raw;
        } catch (RuntimeException unavailable) {
            return raw;
        }
    }

    /** 关联的那条记录的标题：按系统身份读，不查操作者在对方对象上的权限；读不到或没有可见标题时为空 */
    private String referenceTitle(DataCenter.Relation relation, String id, long actor) {
        Row related = records.storedRow(relation.targetObjectId(), id, actor);
        if (related == null) return "";
        String title =
                RecordTitles.render(
                        objects.getPublished(relation.targetObjectId()), related.values());
        return title == null || NO_TITLE.equals(title) ? "" : title.trim();
    }

    /** 清洗、兜底与截断：斜杠、反斜杠、回车、换行、制表符各换成一个空格 */
    private static String finish(String raw, Row stored) {
        String name = clean(raw);
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) return fallback(stored);
        if (name.length() <= MAX_LENGTH) return name;
        // 不把一个代理对从中间截开
        int end =
                Character.isHighSurrogate(name.charAt(MAX_LENGTH - 1))
                        ? MAX_LENGTH - 1
                        : MAX_LENGTH;
        return name.substring(0, end).stripTrailing();
    }

    private static String clean(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            result.append(
                    value == '/' || value == '\\' || value == '\r' || value == '\n' || value == '\t'
                            ? ' '
                            : value);
        }
        return result.toString().trim();
    }

    /** 兜底名：「记录 」+ 记录编号末 8 位（不足 8 位取全部）；编号里不能用作名称的字符同样清洗掉 */
    static String fallback(Row stored) {
        String id = stored == null || stored.id() == null ? "" : stored.id();
        String tail = clean(id.length() > 8 ? id.substring(id.length() - 8) : id);
        return ("记录 " + tail).trim();
    }
}
