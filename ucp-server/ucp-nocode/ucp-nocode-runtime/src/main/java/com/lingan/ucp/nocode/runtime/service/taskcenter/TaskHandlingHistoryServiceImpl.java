package com.lingan.ucp.nocode.runtime.service.taskcenter;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.module.system.api.user.dto.AdminUserRespDTO;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.TaskWorkEntries.*;
import com.lingan.ucp.nocode.application.service.published.ApplicationPublishedService;
import com.lingan.ucp.nocode.enums.ApplicationActionEnum;
import com.lingan.ucp.nocode.enums.MemberStateEnum;
import com.lingan.ucp.nocode.runtime.dal.dataobject.TaskWorkRecordDO;
import com.lingan.ucp.nocode.runtime.dal.mapper.RecordHistoryMapper;
import com.lingan.ucp.nocode.runtime.dal.mapper.TaskWorkEntryMapper;
import com.lingan.ucp.nocode.runtime.service.access.ApplicationRuntimePolicy;
import com.lingan.ucp.nocode.runtime.service.history.HistoryDetailProjection;
import com.lingan.ucp.nocode.runtime.service.record.RecordService;
import com.lingan.ucp.nocode.runtime.service.task.TaskEntryRuntimeScope;
import com.lingan.ucp.nocode.runtime.service.task.TaskEntryRuntimeService;
import com.lingan.ucp.nocode.runtime.service.task.TaskGroupRuntime;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Supplier;

/** 先验证任务和入口，再按当前行字段能力投影历史；删除不会抹去成功事实，也不会恢复已撤销权限。 */
@Service
public class TaskHandlingHistoryServiceImpl implements TaskHandlingHistoryService {
    @Resource private TaskCenterService tasks;
    @Resource private TaskWorkEntryService entries;
    @Resource private TaskWorkEntryMapper contributions;
    @Resource private RecordHistoryMapper history;
    @Resource private TaskGroupRuntime groups;
    @Resource private TaskEntryRuntimeScope scope;
    @Resource private TaskEntryRuntimeService entryRuntime;
    @Resource private ApplicationPublishedService published;
    @Resource private ApplicationRuntimePolicy policy;
    @Resource private HistoryDetailProjection detailProjection;
    @Resource private DataObjectApi objects;
    @Resource private AdminUserApi users;
    @Resource private ObjectMapper json;

    @Resource(name = "nocodeRecordService")
    private RecordService records;

    private record Context(TaskCenter.Row task, Entry entry) {}

    private record Fact(JsonNode event, boolean known, boolean beforeKnown, boolean afterKnown) {}

    @Override
    public PageResult<HandlingRow> page(HistoryQuery query, long actor) {
        if (query == null
                || query.taskId() == null
                || query.pageNo() < 1
                || query.pageNo() > 1000000
                || query.pageSize() < 1
                || query.pageSize() > 100) throw invalid("请选择任务及有效分页参数");
        String search = TaskGraph.text(query.search(), "搜索", 200, false);
        List<HandlingRow> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, List<TaskWorkRecordDO>> datasets = new HashMap<>();
        for (Context context :
                contexts(query.taskId(), query.entryKey(), query.onlyCurrentTask(), actor)) {
            for (TaskWorkRecordDO row :
                    datasets.computeIfAbsent(context.entry().datasetId(), contributions::records)) {
                if (!context.task().id().equals(row.getTaskId())
                        || !context.entry().config().key().equals(row.getEntryKey())
                        || !seen.add(row.getId())
                        || Operation.UNCHANGED.name().equals(row.getOperation())) continue;
                if (query.operation() != null
                        && !query.operation().name().equals(row.getOperation())) continue;
                try {
                    TaskCenter.BusinessRef ref = reference(row);
                    if (ref == null
                            || query.recordId() != null && !query.recordId().equals(ref.recordId()))
                        continue;
                    HandlingDetail detail = read(context, row, ref, actor);
                    if (detail == null || !matches(detail.row(), search)) continue;
                    result.add(detail.row());
                } catch (ServiceException unavailable) {
                    // 失权贡献不计入总数；不能通过搜索、分页或历史身份探测隐藏记录。
                }
            }
        }
        result.sort(
                Comparator.comparing(HandlingRow::time).thenComparing(HandlingRow::id).reversed());
        int start = (int) Math.min(result.size(), (long) (query.pageNo() - 1) * query.pageSize());
        return new PageResult<>(
                result.subList(start, Math.min(result.size(), start + query.pageSize())),
                (long) result.size());
    }

    @Override
    public HandlingDetail detail(HistoryRef query, long actor) {
        if (query == null || query.taskId() == null || query.contributionId() == null)
            throw invalid("请选择办理记录");
        TaskWorkRecordDO row = contributions.record(query.contributionId());
        if (row == null || Operation.UNCHANGED.name().equals(row.getOperation()))
            throw invalid("办理记录不存在或无权查看");
        for (Context context : contexts(query.taskId(), query.entryKey(), false, actor)) {
            if (!context.task().id().equals(row.getTaskId())
                    || !context.entry().config().key().equals(row.getEntryKey())
                    || !context.entry().datasetId().equals(row.getDatasetId())) continue;
            TaskCenter.BusinessRef ref = reference(row);
            HandlingDetail detail = ref == null ? null : read(context, row, ref, actor);
            if (detail != null) return detail;
        }
        throw invalid("办理记录不存在或无权查看");
    }

    private List<Context> contexts(String taskId, String key, boolean onlyCurrent, long actor) {
        TaskCenter.Detail detail = tasks.detail(taskId, actor);
        Map<String, TaskCenter.Row> visible = new LinkedHashMap<>();
        detail.nodes().forEach(node -> visible.put(node.id(), node));
        List<Context> result = new ArrayList<>();
        for (TaskCenter.Row node : visible.values()) {
            if (onlyCurrent && !node.id().equals(taskId) || !descendant(node, taskId, visible))
                continue;
            try {
                for (Entry entry : entries.entries(node.id(), actor))
                    if (key == null || key.equals(entry.config().key()))
                        result.add(new Context(node, entry));
            } catch (ServiceException unavailable) {
                // 可见任务摘要不授予业务资料权限；仍以原入口校验为准。
            }
        }
        return result;
    }

    private boolean descendant(
            TaskCenter.Row row, String ancestor, Map<String, TaskCenter.Row> nodes) {
        Set<String> seen = new HashSet<>();
        while (row != null && seen.add(row.id())) {
            if (ancestor.equals(row.id())) return true;
            row = nodes.get(row.parentId());
        }
        return false;
    }

    private TaskCenter.BusinessRef reference(TaskWorkRecordDO row) {
        TaskCenter.BusinessRef ref = decode(row.getBusinessJson(), TaskCenter.BusinessRef.class);
        if (ref.requestId() != null) {
            String approved = contributions.approvedRecord(row.getId());
            if (approved == null) return null; // 未生效或失败申请不是实际业务办理成功。
            ref = new TaskCenter.BusinessRef(ref.resource(), ref.object(), approved, null);
        }
        return ref.recordId() == null ? null : ref;
    }

    private HandlingDetail read(
            Context c, TaskWorkRecordDO row, TaskCenter.BusinessRef ref, long actor) {
        if (!Objects.equals(c.entry().binding().resource(), ref.resource())
                || !Objects.equals(c.entry().binding().object(), ref.object())) return null;
        Supplier<HandlingDetail> action =
                () -> published.withVersion(ref.resource(), () -> project(c, row, ref, actor));
        if (groups.enabled(c.task().id()))
            return groups.execute(c.task().id(), c.entry().config().key(), actor, false, action);
        if (c.entry().config().binding().entryId() != null)
            return published.withVersion(
                    ref.resource(),
                    () ->
                            entryRuntime.readHistoryScope(
                                    new TaskEntries.Locator(
                                            ref.resource().applicationId(),
                                            c.entry().config().binding().entryId(),
                                            ref.resource().applicationVersion()),
                                    ref.resource().resourceId(),
                                    ref.recordId(),
                                    actor,
                                    action));
        return action.get();
    }

    private HandlingDetail project(
            Context c, TaskWorkRecordDO row, TaskCenter.BusinessRef ref, long actor) {
        scope.requireRecord(ref.object().objectId(), ref.recordId());
        ApplicationRecords.Model model =
                records.model(ref.resource().applicationId(), ref.object().objectId(), actor);
        DataCenter.Definition current = objects.getPublished(ref.object().objectId());
        ApplicationRuntimePolicy.Access access =
                policy.access(ref.resource().applicationId(), model.object(), actor);
        // 当前存在的记录仍须满足当前行条件；删除后使用当次历史行值判断，不能因缺记录一概丢失历史。
        ApplicationRecords.Aggregate live =
                records.getIfPresent(
                        ref.resource().applicationId(),
                        ref.object().objectId(),
                        ref.recordId(),
                        actor);
        Fact fact = fact(row, ref);
        if (fact == null) return null;
        JsonNode before = fact.event().path("before"), after = fact.event().path("after");
        Set<String> allowed = allowed(absent(before) ? after : before, access, c.entry().config());
        if (!absent(before) && !absent(after))
            allowed.retainAll(allowed(after, access, c.entry().config()));
        Set<String> active = new HashSet<>();
        current.fields().stream()
                .filter(
                        field ->
                                !MemberStateEnum.INACTIVE.matches(
                                        current.fieldOptions()
                                                .getOrDefault(
                                                        field.id(),
                                                        DataCenter.FieldOptions.defaults())
                                                .state()))
                .forEach(field -> active.add(field.id()));
        allowed.retainAll(active);
        if (live != null) allowed.retainAll(live.record().permissions().readFields());
        List<RecordHistory.DetailChange> details =
                c.entry().config().readableFieldIds() != null
                                || c.entry().config().writableFieldIds() != null
                                || c.entry().config().dataMode() == DataMode.SOURCE_SHARED
                        ? List.of()
                        : safeDetails(
                                detailProjection.changes(fact.event(), List.of(access)),
                                current,
                                live,
                                fact);
        if (allowed.isEmpty() && details.isEmpty()) return null;
        Map<String, Object> oldValues = values(before, allowed), newValues = values(after, allowed);
        if (fact.known()
                && Operation.UPDATED.name().equals(row.getOperation())
                && Objects.equals(oldValues, newValues)
                && details.isEmpty()
                && !fact.event().path("source").path("relatedUpdate").asBoolean(false)) return null;
        List<RecordHistory.Field> fields = new ArrayList<>();
        for (JsonNode field : fact.event().path("fields")) {
            String id = field.path("id").asText();
            if (allowed.contains(id))
                fields.add(new RecordHistory.Field(id, field.path("name").asText(id)));
        }
        if (fields.isEmpty())
            model.object().fields().stream()
                    .filter(field -> allowed.contains(field.id()))
                    .forEach(
                            field -> fields.add(new RecordHistory.Field(field.id(), field.name())));
        Map<String, Object> display = newValues == null ? oldValues : newValues;
        String label = label(ref.recordId(), display);
        long author = Long.parseLong(row.getCreator());
        AdminUserRespDTO user = users.getUser(author);
        LocalDateTime time = row.getCreateTime();
        if (fact.known() && !fact.event().path("time").asText().isBlank())
            time =
                    OffsetDateTime.parse(fact.event().path("time").asText())
                            .atZoneSameInstant(java.time.ZoneId.systemDefault())
                            .toLocalDateTime();
        HandlingRow summary =
                new HandlingRow(
                        row.getId(),
                        c.task().id(),
                        c.task().title(),
                        row.getEntryKey(),
                        c.entry().config().name(),
                        c.entry().category(),
                        ref.recordId(),
                        label,
                        Operation.valueOf(row.getOperation()),
                        author,
                        user == null ? row.getCreator() : user.getNickname(),
                        time,
                        true,
                        fact.known());
        return new HandlingDetail(
                summary,
                fields,
                oldValues,
                newValues,
                details,
                fact.beforeKnown(),
                fact.afterKnown(),
                fact.event().path("source").path("relatedUpdate").asBoolean(false));
    }

    private Fact fact(TaskWorkRecordDO row, TaskCenter.BusinessRef ref) {
        List<String> exact =
                history.contributionEvents(
                        row.getId(),
                        ref.resource().applicationId(),
                        ref.object().objectId(),
                        ref.recordId());
        if (!exact.isEmpty()) {
            ObjectNode first = (ObjectNode) parse(exact.getFirst());
            JsonNode last = parse(exact.getLast());
            first.set("after", last.path("after"));
            first.set("fields", last.path("fields"));
            first.set("detailFields", last.path("detailFields"));
            if (exact.stream()
                    .map(this::parse)
                    .anyMatch(event -> event.path("source").path("relatedUpdate").asBoolean(false)))
                first.withObject("source").put("relatedUpdate", true);
            return new Fact(first, true, true, true);
        }
        List<String> byRevision =
                row.getRecordRevision() == null
                        ? List.of()
                        : history.revisionEvents(
                                ref.resource().applicationId(),
                                ref.object().objectId(),
                                ref.recordId(),
                                row.getRecordRevision());
        if (byRevision.size() == 1) {
            ObjectNode event = (ObjectNode) parse(byRevision.getFirst());
            if (Operation.LINKED.name().equals(row.getOperation())) {
                event.putNull("before");
                event.remove("time");
                return new Fact(event, true, false, true);
            }
            // 旧收据没有事件标识。即使人员、任务、版本相同，也可能只是重复保存；不能据此认领早先修改。
        }
        if (row.getSnapshotJson() == null) return null;
        ApplicationRecords.Aggregate saved =
                decode(row.getSnapshotJson(), ApplicationRecords.Aggregate.class);
        ObjectNode snapshot = json.createObjectNode();
        snapshot.set("values", json.valueToTree(saved.record().values()));
        ObjectNode details = snapshot.putObject("details"),
                order = snapshot.putObject("detailOrder");
        saved.details()
                .forEach(
                        (id, lines) -> {
                            ObjectNode items = details.putObject(id);
                            lines.forEach(
                                    line -> items.set(line.id(), json.valueToTree(line.values())));
                            order.set(
                                    id,
                                    json.valueToTree(
                                            lines.stream()
                                                    .map(ApplicationRecords.Row::id)
                                                    .toList()));
                        });
        ObjectNode event = json.createObjectNode();
        boolean deletion = Operation.DELETED.name().equals(row.getOperation());
        event.set(deletion ? "before" : "after", snapshot);
        return new Fact(event, false, false, !deletion);
    }

    private List<RecordHistory.DetailChange> safeDetails(
            List<RecordHistory.DetailChange> source,
            DataCenter.Definition current,
            ApplicationRecords.Aggregate live,
            Fact fact) {
        List<RecordHistory.DetailChange> result = new ArrayList<>();
        for (RecordHistory.DetailChange change : source) {
            DataCenter.Detail definition =
                    current.details().stream()
                            .filter(item -> item.id().equals(change.id()))
                            .findFirst()
                            .orElse(null);
            if (definition == null
                    || !MemberStateEnum.ACTIVE.matches(definition.state())
                    || live != null
                            && !live.record().permissions().readDetails().contains(change.id()))
                continue;
            Set<String> active = new HashSet<>();
            definition.fields().stream()
                    .filter(
                            field ->
                                    !MemberStateEnum.INACTIVE.matches(
                                            definition
                                                    .fieldOptions()
                                                    .getOrDefault(
                                                            field.id(),
                                                            DataCenter.FieldOptions.defaults())
                                                    .state()))
                    .forEach(field -> active.add(field.id()));
            List<RecordHistory.Field> fields =
                    change.fields().stream().filter(field -> active.contains(field.id())).toList();
            if (fields.isEmpty()) continue;
            Set<String> visible = new HashSet<>();
            fields.forEach(field -> visible.add(field.id()));
            Map<String, Map<String, Object>> before = detailValues(change.before(), visible);
            Map<String, Map<String, Object>> after = detailValues(change.after(), visible);
            boolean beforeKnown = change.beforeKnown() && fact.beforeKnown();
            boolean afterKnown = change.afterKnown() && fact.afterKnown();
            if (beforeKnown
                    && afterKnown
                    && before.equals(after)
                    && change.beforeOrder().equals(change.afterOrder())) continue;
            result.add(
                    new RecordHistory.DetailChange(
                            change.id(),
                            change.name(),
                            fields,
                            before,
                            after,
                            change.beforeOrder(),
                            change.afterOrder(),
                            beforeKnown,
                            afterKnown));
        }
        return result;
    }

    private Map<String, Map<String, Object>> detailValues(
            Map<String, Map<String, Object>> rows, Set<String> fields) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        rows.forEach(
                (id, values) -> {
                    Map<String, Object> safe = new LinkedHashMap<>();
                    values.forEach(
                            (field, value) -> {
                                if (fields.contains(field)) safe.put(field, value);
                            });
                    result.put(id, safe);
                });
        return result;
    }

    private Set<String> allowed(
            JsonNode row, ApplicationRuntimePolicy.Access access, Config config) {
        if (absent(row)) return new HashSet<>();
        Map<String, Object> values =
                json.convertValue(
                        row.path("values"),
                        new com.fasterxml.jackson.core.type.TypeReference<
                                Map<String, Object>>() {});
        ApplicationAuthorization.Capabilities caps =
                access.forRow(row.path("recordCreator").asText(null), values);
        if (!caps.actions().contains(ApplicationActionEnum.READ.getCode()))
            throw invalid("当前无权查看该办理记录");
        Set<String> fields = new LinkedHashSet<>(caps.readFields());
        if (config.readableFieldIds() != null) fields.retainAll(config.readableFieldIds());
        return fields;
    }

    private Map<String, Object> values(JsonNode row, Set<String> allowed) {
        if (absent(row)) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        row.path("values")
                .fields()
                .forEachRemaining(
                        field -> {
                            if (allowed.contains(field.getKey()))
                                result.put(
                                        field.getKey(),
                                        json.convertValue(field.getValue(), Object.class));
                        });
        return result;
    }

    private String label(String id, Map<String, Object> values) {
        if (values != null)
            for (Object value : values.values())
                if (value instanceof String text && !text.isBlank())
                    return text.substring(0, Math.min(200, text.length()));
        return "记录 " + id;
    }

    private boolean matches(HandlingRow row, String search) {
        return search == null
                || search.isBlank()
                || (row.taskTitle()
                                + " "
                                + row.entryName()
                                + " "
                                + row.recordLabel()
                                + " "
                                + row.actorName())
                        .toLowerCase(Locale.ROOT)
                        .contains(search.toLowerCase(Locale.ROOT));
    }

    private boolean absent(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }

    private JsonNode parse(String raw) {
        try {
            return json.readTree(raw);
        } catch (Exception failure) {
            throw invalid("办理历史无法读取");
        }
    }

    private <T> T decode(String raw, Class<T> type) {
        try {
            return json.readValue(raw, type);
        } catch (Exception failure) {
            throw invalid("办理历史无法读取");
        }
    }
}
