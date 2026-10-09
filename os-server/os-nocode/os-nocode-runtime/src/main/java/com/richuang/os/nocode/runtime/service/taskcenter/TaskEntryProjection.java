package com.richuang.os.nocode.runtime.service.taskcenter;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.nocode.api.*;

import java.util.*;

/** 任务入口的字段限制只能收窄应用能力；受限入口不暗中开放明细和关联。 */
final class TaskEntryProjection {
    private TaskEntryProjection() {}

    private static boolean restricted(TaskWorkEntries.Config c) {
        return c.readableFieldIds() != null
                || c.writableFieldIds() != null
                || c.dataMode() == TaskWorkEntries.DataMode.SOURCE_SHARED;
    }

    static void requireDetails(TaskWorkEntries.Config c) {
        if (restricted(c)) throw invalid("字段受限的共享入口不开放明细和关联操作");
    }

    static Map<String, Object> values(Map<String, Object> source, TaskWorkEntries.Config c) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source != null)
            source.forEach(
                    (k, v) -> {
                        if (c.readableFieldIds() == null || c.readableFieldIds().contains(k))
                            result.put(k, v);
                    });
        return result;
    }

    static ApplicationAuthorization.Capabilities caps(
            ApplicationAuthorization.Capabilities source, TaskWorkEntries.Config c) {
        if (source == null) return null;
        Set<String> read = new LinkedHashSet<>(source.readFields());
        if (c.readableFieldIds() != null) read.retainAll(c.readableFieldIds());
        Set<String> write = new LinkedHashSet<>(source.writeFields());
        write.retainAll(read);
        if (c.writableFieldIds() != null) write.retainAll(c.writableFieldIds());
        else if (c.dataMode() == TaskWorkEntries.DataMode.SOURCE_SHARED) write.clear();
        Set<String> actions = new LinkedHashSet<>(source.actions());
        if (write.isEmpty()) {
            actions.remove("CREATE");
            actions.remove("UPDATE");
            actions.remove("DELETE");
        }
        boolean restricted = restricted(c);
        return new ApplicationAuthorization.Capabilities(
                actions,
                read,
                write,
                restricted ? Set.of() : source.readDetails(),
                restricted ? Set.of() : source.writeDetails(),
                restricted ? Set.of() : source.readRelations(),
                restricted ? Set.of() : source.writeRelations());
    }

    static ApplicationRecords.Row row(ApplicationRecords.Row row, TaskWorkEntries.Config c) {
        if (row == null) return null;
        Map<String, String> display = new LinkedHashMap<>();
        if (row.displayValues() != null)
            row.displayValues()
                    .forEach(
                            (k, v) -> {
                                if (c.readableFieldIds() == null
                                        || c.readableFieldIds().contains(k)) display.put(k, v);
                            });
        return new ApplicationRecords.Row(
                row.id(),
                row.revision(),
                values(row.values(), c),
                caps(row.permissions(), c),
                display,
                row.clientRowKey(),
                row.parentId());
    }

    static ApplicationRecords.Aggregate aggregate(
            ApplicationRecords.Aggregate value, TaskWorkEntries.Config c) {
        if (value == null) return null;
        return new ApplicationRecords.Aggregate(
                row(value.record(), c),
                restricted(c) ? Map.of() : value.details(),
                value.processes(),
                restricted(c) ? Map.of() : value.relations());
    }

    static ApplicationRecords.Model model(
            ApplicationRecords.Model value, TaskWorkEntries.Config c) {
        return new ApplicationRecords.Model(
                value.object(),
                value.writable(),
                value.generatedKey(),
                value.keyFieldId(),
                value.keyType(),
                value.details(),
                caps(value.permissions(), c),
                value.managedFieldIds(),
                value.orderedStates());
    }

    static BusinessHandling.Result handling(
            BusinessHandling.Result value, TaskWorkEntries.Config c) {
        return value == null
                ? null
                : new BusinessHandling.Result(
                        value.outcome(), aggregate(value.result(), c), value.request());
    }

    static void validateWrite(ApplicationRecords.Save input, TaskWorkEntries.Config c) {
        List<String> allowed = c.writableFieldIds();
        if (allowed == null && c.dataMode() == TaskWorkEntries.DataMode.SOURCE_SHARED)
            allowed = List.of();
        if (allowed != null && allowed.isEmpty()) throw invalid("当前入口为只读，不允许提交业务修改");
        if (allowed != null
                && input.values() != null
                && !allowed.containsAll(input.values().keySet())) throw invalid("输入包含当前入口不允许修改的字段");
        if (c.readableFieldIds() != null
                && input.values() != null
                && !c.readableFieldIds().containsAll(input.values().keySet()))
            throw invalid("不能修改不可见字段");
        if (restricted(c)
                && (input.details() != null && !input.details().isEmpty()
                        || input.relations() != null && !input.relations().isEmpty()
                        || input.relatedRecords() != null && !input.relatedRecords().isEmpty()))
            throw invalid("共享入口只能修改明确开放的主表字段");
    }
}
