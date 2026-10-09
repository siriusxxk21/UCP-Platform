package com.lingan.ucp.nocode.schema.service.selection;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.dal.mapper.SelectionMigrationMapper;

import jakarta.annotation.Resource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.*;

/** 发布锁内复核全量旧值映射，再与对象版本切换一起提交。失败由发布事务完整回滚。 */
@Service
public class SelectionMigrationService {
    @Resource private SelectionMigrationMapper mapper;
    @Resource private ObjectMapper json;
    @Resource private ObjectProvider<SelectionTargetValidator> targetValidator;

    public record Change(
            String fieldId,
            String fieldName,
            List<String> existingValues,
            Map<String, List<String>> mapping,
            long conflicts,
            String error,
            SelectionMigrationMapper.Statement statement) {}

    public List<Change> preview(DataCenter.Definition current, DataCenter.Definition previous) {
        return preview(current, previous, Set.of());
    }

    /** 原地清空转换的字段由发布计划统一处理，不能再次按旧选择结构读取。 */
    public List<Change> preview(
            DataCenter.Definition current,
            DataCenter.Definition previous,
            Set<String> conversionFields) {
        if (previous == null) return List.of();
        var result = new ArrayList<Change>();
        collect(
                current.schemaName(),
                current.tableName(),
                current.fields(),
                current.fieldOptions(),
                previous.fields(),
                previous.fieldOptions(),
                current.mainBinding(),
                result,
                conversionFields);
        for (var detail : current.details())
            previous.details().stream()
                    .filter(d -> d.id().equals(detail.id()))
                    .findFirst()
                    .ifPresent(
                            old ->
                                    collect(
                                            current.schemaName(),
                                            detail.tableName(),
                                            detail.fields(),
                                            detail.fieldOptions(),
                                            old.fields(),
                                            old.fieldOptions(),
                                            detail.binding(),
                                            result,
                                            conversionFields));
        return result;
    }

    private void collect(
            String schema,
            String table,
            List<FieldDefinition> fields,
            Map<String, DataCenter.FieldOptions> options,
            List<FieldDefinition> oldFields,
            Map<String, DataCenter.FieldOptions> oldOptions,
            TableBinding binding,
            List<Change> result,
            Set<String> conversionFields) {
        for (var field : fields) {
            if (conversionFields.contains(field.id())) continue;
            var old =
                    oldFields.stream()
                            .filter(f -> f.id().equals(field.id()))
                            .findFirst()
                            .orElse(null);
            if (old == null) continue;
            var before = oldOptions.getOrDefault(old.id(), DataCenter.FieldOptions.defaults());
            var after = options.getOrDefault(field.id(), DataCenter.FieldOptions.defaults());
            if (SelectionFields.identity(field, after)
                    .equals(SelectionFields.identity(old, before))) continue;
            if (SelectionFields.source(field, after) == null
                    || SelectionFields.source(old, before) == null) {
                result.add(
                        new Change(
                                field.id(),
                                field.name(),
                                List.of(),
                                Map.of(),
                                0,
                                "选择字段与非选择字段转换需另建字段并明确迁移",
                                null));
                continue;
            }
            var source = SelectionFields.source(field, after);
            var mapping =
                    source.migrationMap() == null
                            ? Map.<String, List<String>>of()
                            : source.migrationMap();
            try {
                var sql =
                        new SelectionMigrationMapper.Statement(
                                schema,
                                table,
                                Objects.requireNonNullElse(before.columnName(), old.code()),
                                FieldStorage.sqlType(field, after),
                                SelectionFields.multiple(old),
                                SelectionFields.multiple(field),
                                Boolean.TRUE.equals(field.required()),
                                json.writeValueAsString(mapping),
                                "0",
                                true);
                var existing = mapper.values(sql);
                String error = null;
                long conflicts = 0;
                if (binding != null
                        && (Boolean.TRUE.equals(binding.readOnly())
                                || ObjectSourceEnum.ADOPTED.matches(binding.source())))
                    error = "纳管或只读表的选择来源转换需要先处理外部写入与结构管理边界";
                else if (existing.size() > 1000) error = "旧值超过 1000 种，请分批处理数据后迁移";
                else if (!mapping.keySet().containsAll(existing)) error = "请为全部旧值配置目标 ID／编码映射";
                else {
                    var targets =
                            mapping.values().stream()
                                    .flatMap(Collection::stream)
                                    .distinct()
                                    .toList();
                    targetValidator.getObject().validateTargets(field, after, targets);
                    conflicts = mapper.conflicts(sql);
                    if (conflicts > 0) error = "存在无法转换到目标选择数量的记录";
                }
                result.add(
                        new Change(
                                field.id(),
                                field.name(),
                                existing,
                                mapping,
                                conflicts,
                                error,
                                sql));
            } catch (Exception e) {
                result.add(
                        new Change(
                                field.id(),
                                field.name(),
                                List.of(),
                                mapping,
                                0,
                                e
                                                instanceof
                                                com.lingan.ucp.framework.common.exception
                                                        .ServiceException
                                        ? e.getMessage()
                                        : "无法检查选择来源转换，请检查物理结构与字段配置",
                                null));
            }
        }
    }

    public void apply(List<Change> changes, long actor) {
        for (var change : changes) {
            if (change.error() != null) throw invalid(change.fieldName() + "：" + change.error());
            var old = change.statement();
            var statement =
                    new SelectionMigrationMapper.Statement(
                            old.schema(),
                            old.table(),
                            old.column(),
                            old.type(),
                            old.oldMultiple(),
                            old.newMultiple(),
                            old.required(),
                            old.mapping(),
                            Long.toString(actor),
                            old.audit());
            mapper.toText(statement);
            // 空数组没有可展开的历史 ID，也必须映射为单选 NULL。
            mapper.mapValues(statement);
            mapper.toTarget(statement);
        }
    }
}
