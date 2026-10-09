package com.richuang.os.nocode.metadata.service.object;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.DataCenter.*;
import com.richuang.os.nocode.enums.MemberStateEnum;

import java.util.*;

/** 在内存中合并操作前草稿；不分配数据库 ID，不保存临时定义或接受客户端物理表为查询来源。 */
final class ObjectOperationDrafts {
    private ObjectOperationDrafts() {}

    static Definition proposed(Definition current, SaveDesign input, int revision) {
        if (input == null) return current;
        if (input.draft() == null
                || !current.objectId().equals(input.draft().id())
                || !Objects.equals(revision, input.draft().expectedLockVersion()))
            throw invalid("预检草稿的对象身份或修订号不匹配");
        SaveObjectDraft draft = input.draft();
        List<FieldDefinition> fields =
                merge(current.fields(), draft.fields(), draft.removedFieldIds());
        Settings settings = input.settings() == null ? current.settings() : input.settings();
        Map<String, FieldOptions> options = new LinkedHashMap<>(current.fieldOptions());
        if (input.fieldOptions() != null) options.putAll(input.fieldOptions());
        return new Definition(
                current.objectId(),
                current.objectCode(),
                current.objectName(),
                current.description(),
                current.schemaName(),
                current.tableName(),
                current.source(),
                current.readOnly(),
                draft.titleFieldKey() == null ? current.titleFieldId() : draft.titleFieldKey(),
                settings,
                fields,
                options,
                input.relations() == null ? current.relations() : input.relations(),
                input.indexes() == null ? current.indexes() : input.indexes(),
                input.details() == null ? current.details() : normalizeDetails(input.details()),
                current.mainBinding());
    }

    private static List<FieldDefinition> merge(
            List<FieldDefinition> previous, List<FieldDefinition> supplied, List<String> removed) {
        Map<String, FieldDefinition> fields = new LinkedHashMap<>();
        previous.forEach(field -> fields.put(field.id(), field));
        if (supplied != null)
            supplied.forEach(
                    field -> {
                        if (field == null || field.key() == null) throw invalid("预检字段及字段身份不能为空");
                        fields.put(field.id() == null ? field.key() : field.id(), stable(field));
                    });
        if (removed != null) removed.forEach(fields::remove);
        return List.copyOf(fields.values());
    }

    private static FieldDefinition stable(FieldDefinition field) {
        return field.id() != null
                ? field
                : new FieldDefinition(
                        field.key(),
                        field.key(),
                        field.code(),
                        field.name(),
                        field.type(),
                        field.length(),
                        field.precision(),
                        field.scale(),
                        field.required(),
                        field.unique(),
                        field.sort());
    }

    private static List<Detail> normalizeDetails(List<Detail> details) {
        return details.stream()
                .map(
                        detail -> {
                            if (detail == null) throw invalid("预检明细不能为空");
                            return new Detail(
                                    detail.id() == null
                                            ? "preview_detail_" + detail.code()
                                            : detail.id(),
                                    detail.code(),
                                    detail.name(),
                                    detail.tableName(),
                                    detail.state() == null
                                            ? MemberStateEnum.ACTIVE.getCode()
                                            : detail.state(),
                                    detail.fields().stream()
                                            .map(ObjectOperationDrafts::stable)
                                            .toList(),
                                    detail.fieldOptions(),
                                    detail.indexes(),
                                    detail.binding());
                        })
                .toList();
    }

    static Definition apply(
            Definition source,
            String detailId,
            FieldDefinition field,
            FieldOptions option,
            boolean restore) {
        List<FieldDefinition> main = source.fields();
        Map<String, FieldOptions> options = source.fieldOptions();
        List<Detail> details = new ArrayList<>();
        if (detailId == null) {
            main = changed(main, field, restore);
            options = new LinkedHashMap<>(options);
            if (restore) options.put(field.id(), option);
        }
        for (Detail detail : source.details()) {
            if (!Objects.equals(detailId, detail.id())) {
                details.add(detail);
                continue;
            }
            Map<String, FieldOptions> nextOptions = new LinkedHashMap<>(detail.fieldOptions());
            if (restore) nextOptions.put(field.id(), option);
            details.add(
                    new Detail(
                            detail.id(),
                            detail.code(),
                            detail.name(),
                            detail.tableName(),
                            detail.state(),
                            changed(detail.fields(), field, restore),
                            nextOptions,
                            detail.indexes(),
                            detail.binding()));
        }
        return new Definition(
                source.objectId(),
                source.objectCode(),
                source.objectName(),
                source.description(),
                source.schemaName(),
                source.tableName(),
                source.source(),
                source.readOnly(),
                source.titleFieldId(),
                source.settings(),
                main,
                options,
                source.relations(),
                source.indexes(),
                details,
                source.mainBinding());
    }

    private static List<FieldDefinition> changed(
            List<FieldDefinition> original, FieldDefinition field, boolean restore) {
        List<FieldDefinition> fields =
                new ArrayList<>(
                        original.stream()
                                .filter(item -> !Objects.equals(item.id(), field.id()))
                                .toList());
        if (restore) fields.add(field);
        return List.copyOf(fields);
    }
}
