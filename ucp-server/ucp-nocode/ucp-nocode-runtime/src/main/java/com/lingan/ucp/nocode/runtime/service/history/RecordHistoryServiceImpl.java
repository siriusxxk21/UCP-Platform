package com.lingan.ucp.nocode.runtime.service.history;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.*;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.RecordHistory.*;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.enums.ApplicationActionEnum;
import com.lingan.ucp.nocode.enums.RecordChangeOperationEnum;
import com.lingan.ucp.nocode.runtime.dal.mapper.RecordHistoryMapper;
import com.lingan.ucp.nocode.runtime.service.access.ApplicationRuntimePolicy;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;
import java.util.function.*;

/** 汇总只扫描范围内事件；全表按批授权、按页返回；行过程按需加载。 */
@Service
public class RecordHistoryServiceImpl implements RecordHistoryService {
    @Resource private ApplicationService applications;
    @Resource private DataObjectApi objects;
    @Resource private ApplicationRuntimePolicy policy;
    @Resource private RecordHistoryTracker tracker;
    @Resource private RecordHistoryMapper history;
    @Resource private ObjectMapper json;
    @Resource private AdminUserApi users;
    @Resource private PlatformTransactionManager manager;
    @Resource private HistoryDetailProjection detailProjection;

    private record Entry(
            DataCenter.Definition definition,
            List<String> apps,
            List<String> names,
            List<ApplicationRuntimePolicy.Access> access) {}

    private record Window(String start, String end, String visibility) {}

    private record Projection(
            boolean denied,
            Map<String, Object> before,
            Map<String, Object> after,
            Set<String> fields,
            List<DetailChange> details) {
        boolean changed() {
            return !denied && (!Objects.equals(before, after) || !details.isEmpty());
        }
    }

    /** 每张表只保留记录和人员计数，不在汇总内存中保留前后值或全量事件。 */
    private static final class Counter {
        long records, create, update, delete;
        final Set<String> people = new HashSet<>();

        void event(String operation, String person) {
            switch (RecordChangeOperationEnum.valueOf(operation)) {
                case CREATE -> create++;
                case UPDATE -> update++;
                case DELETE -> delete++;
                default -> throw invalid("无效历史操作");
            }
            records = 1;
            people.add(person);
        }

        void add(Counter source) {
            records += source.records;
            create += source.create;
            update += source.update;
            delete += source.delete;
            people.addAll(source.people);
        }

        Counts result() {
            return new Counts(
                    records, create + update + delete, create, update, delete, people.size());
        }
    }

    private static final class Stat {
        boolean denied;
        final Counter total = new Counter();
        final Map<String, Counter> employees = new LinkedHashMap<>();

        boolean matches(String person) {
            return !denied && (person == null ? total.records > 0 : employees.containsKey(person));
        }
    }

    @Override
    public Summary query(Query query, long actor) {
        validate(query, actor);
        var entries = entries(query, actor, null);
        // 已留存的表不获取写锁。首次基线使用单表独立短事务，完成后再开始只读查询。
        for (var entry : entries.values()) ensureCoverage(entry, actor);
        return read(
                () -> {
                    var view = decode(history.view());
                    String end = query.end() == null ? view.path("end").asText() : query.end();
                    var window =
                            window(
                                    new Query(
                                            query.start(),
                                            end,
                                            query.applicationId(),
                                            query.employeeId()),
                                    view.path("visibility").asText(),
                                    actor);
                    List<TableSummary> tables = new ArrayList<>();
                    Map<String, String> names = new HashMap<>();
                    for (var entry : entries.values()) {
                        var stats = statistics(entry, window);
                        Counter total = new Counter();
                        Map<String, Counter> people = new LinkedHashMap<>();
                        for (var stat : stats.values()) {
                            if (stat.denied) continue;
                            var selected =
                                    query.employeeId() == null
                                            ? stat.total
                                            : stat.employees.get(query.employeeId());
                            if (selected != null) total.add(selected);
                            stat.employees.forEach(
                                    (id, count) ->
                                            people.computeIfAbsent(id, k -> new Counter())
                                                    .add(count));
                        }
                        String covered = coverage(entry);
                        loadNames(people.keySet(), names);
                        tables.add(
                                new TableSummary(
                                        entry.definition().objectId(),
                                        entry.definition().objectName(),
                                        entry.apps(),
                                        entry.names(),
                                        covered,
                                        !time(window.start()).isBefore(time(covered)),
                                        total.result(),
                                        people.entrySet().stream()
                                                .map(
                                                        e ->
                                                                new Employee(
                                                                        e.getKey(),
                                                                        names.computeIfAbsent(
                                                                                e.getKey(),
                                                                                this::employeeName),
                                                                        e.getValue().result()))
                                                .toList()));
                    }
                    return new Summary(window.start(), window.end(), window.visibility(), tables);
                });
    }

    @Override
    public Page page(PageQuery request, long actor) {
        if (request == null
                || request.pageNo() < 1
                || request.pageNo() > 1000000
                || request.pageSize() < 1
                || request.pageSize() > 100) throw invalid("分页参数无效");
        var window = window(request.query(), request.visibility(), actor);
        var entry = entry(request.query(), actor, request.objectId());
        return read(
                () -> {
                    var stats = statistics(entry, window);
                    List<String> selected = new ArrayList<>();
                    long total = 0, offset = (long) (request.pageNo() - 1) * request.pageSize();
                    if (request.changesOnly()) {
                        var ids =
                                stats.entrySet().stream()
                                        .filter(
                                                e ->
                                                        e.getValue()
                                                                .matches(
                                                                        request.query()
                                                                                .employeeId()))
                                        .map(Map.Entry::getKey)
                                        .sorted()
                                        .toList();
                        total = ids.size();
                        selected.addAll(
                                ids.subList(
                                        (int) Math.min(offset, total),
                                        (int) Math.min(offset + request.pageSize(), total)));
                    } else {
                        String cursor = "";
                        while (true) {
                            var batch =
                                    history.snapshotBatch(
                                            request.objectId(),
                                            window.end(),
                                            window.visibility(),
                                            cursor);
                            for (String raw : batch) {
                                var snapshot = decode(raw);
                                cursor = snapshot.path("recordId").asText();
                                var stat = stats.get(cursor);
                                var row = snapshot.get("row");
                                if (absent(row)
                                        || (stat != null && stat.denied)
                                        || !readable(row, entry)) continue;
                                if (total >= offset && selected.size() < request.pageSize())
                                    selected.add(cursor);
                                total++;
                            }
                            if (batch.size() < 1000) break;
                        }
                    }
                    return new Page(
                            rows(entry, window, selected, request.query().employeeId(), false),
                            total,
                            request.pageNo(),
                            request.pageSize());
                });
    }

    @Override
    public Detail detail(DetailQuery request, long actor) {
        if (request == null || request.recordId() == null || request.recordId().isBlank())
            throw invalid("请选择记录");
        var window = window(request.query(), request.visibility(), actor);
        var entry = entry(request.query(), actor, request.objectId());
        return read(
                () -> {
                    // 员工筛选不裁剪修改过程；行详情包含范围内所有有权查看的操作人。
                    var table = rows(entry, window, List.of(request.recordId()), null, true);
                    if (table.rows().isEmpty()) throw invalid("记录不存在或当前无权查看，请重新查询");
                    return new Detail(table.rows().getFirst(), table.fields());
                });
    }

    private Map<String, Entry> entries(Query query, long actor, String object) {
        Map<String, Entry> result = new TreeMap<>();
        Map<String, DataCenter.Definition> definitions = new HashMap<>();
        for (String app : applications.runnableIds()) {
            if (query.applicationId() != null && !app.equals(query.applicationId())) continue;
            var release = applications.published(app);
            for (var ref : release.definition().objects()) {
                if (object != null && !object.equals(ref.objectId())) continue;
                if (!policy.canRead(app, ref.objectId(), actor)) continue;
                var d =
                        definitions.computeIfAbsent(
                                ref.objectId() + ":" + ref.versionNo(),
                                k ->
                                        objects.getVersion(ref.objectId(), ref.versionNo())
                                                .definition());
                var entry =
                        result.computeIfAbsent(
                                d.objectId(),
                                k ->
                                        new Entry(
                                                d,
                                                new ArrayList<>(),
                                                new ArrayList<>(),
                                                new ArrayList<>()));
                entry.apps().add(app);
                entry.names().add(release.application().name());
                entry.access().add(policy.access(app, d, actor));
            }
        }
        return result;
    }

    private Entry entry(Query query, long actor, String object) {
        if (object == null || object.isBlank()) throw invalid("请选择表格");
        var entry = entries(query, actor, object).get(object);
        if (entry == null) throw invalid("表格不存在或当前无权查看，请重新查询");
        return entry;
    }

    private void ensureCoverage(Entry entry, long actor) {
        if (history.coverage(entry.definition().objectId()) != null) return;
        var tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(10);
        tx.executeWithoutResult(status -> tracker.begin(entry.definition(), actor));
    }

    private <T> T read(Supplier<T> work) {
        var tx = new TransactionTemplate(manager);
        tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setTimeout(10);
        return tx.execute(status -> work.get());
    }

    private Window window(Query query, String visibility, long actor) {
        validate(query, actor);
        // 可见性仅是数据过滤条件，不能替代授权；参数绑定且限制格式/长度。
        if (query.end() == null
                || visibility == null
                || visibility.length() > 65536
                || !visibility.matches("[0-9]+:[0-9]+:([0-9]+(,[0-9]+)*)?"))
            throw invalid("查询上下文无效，请重新查询");
        Instant end = time(query.end());
        if (end.isAfter(Instant.now())) end = Instant.now();
        if (time(query.start()).isAfter(end)) throw invalid("开始时间不能晚于结束时间");
        return new Window(time(query.start()).toString(), end.toString(), visibility);
    }

    private void validate(Query query, long actor) {
        if (actor <= 0 || query == null) throw invalid("请先登录并选择检索范围");
        Instant end = query.end() == null ? Instant.now() : time(query.end());
        if (time(query.start()).isAfter(end) || time(query.start()).isAfter(Instant.now()))
            throw invalid("开始时间不能晚于结束时间或当前时间");
    }

    private Map<String, Stat> statistics(Entry entry, Window window) {
        Map<String, Stat> stats = new LinkedHashMap<>();
        events(
                entry,
                window,
                null,
                false,
                event -> {
                    var stat =
                            stats.computeIfAbsent(event.path("recordId").asText(), k -> new Stat());
                    if (stat.denied) return;
                    var projection = project(event, entry);
                    if (projection.denied()) {
                        stat.denied = true;
                        return;
                    }
                    if (!projection.changed()
                            && !event.path("source").path("relatedUpdate").asBoolean(false))
                        return; // 隐藏字段独有的修改不计入可见统计；联合更新只有不含子内容的说明。
                    String person = event.path("actor").asText(),
                            operation = event.path("operation").asText();
                    stat.total.event(operation, person);
                    stat.employees
                            .computeIfAbsent(person, k -> new Counter())
                            .event(operation, person);
                });
        return stats;
    }

    private void events(
            Entry entry, Window window, List<String> ids, boolean fields, Consumer<JsonNode> sink) {
        String cursor = window.start();
        long cursorId = 0;
        while (true) {
            var batch =
                    history.events(
                            entry.definition().objectId(),
                            window.start(),
                            window.end(),
                            window.visibility(),
                            cursor,
                            cursorId,
                            ids,
                            fields);
            for (String raw : batch) {
                var event = decode(raw);
                sink.accept(event);
                cursor = event.path("time").asText();
                cursorId = event.path("id").asLong();
            }
            if (batch.size() < 1000) return;
        }
    }

    private static final class RowState {
        JsonNode snapshot;
        boolean found, denied, touched;
        boolean detailTouched;
        long count;
        Map<String, Object> initial, lastBefore;
        final Set<String> changed = new LinkedHashSet<>();
        final List<Change> changes = new ArrayList<>();
        final Map<String, Field> fields = new LinkedHashMap<>();
    }

    private Table rows(
            Entry entry, Window window, List<String> ids, String person, boolean detail) {
        Map<String, RowState> states = new LinkedHashMap<>();
        ids.forEach(id -> states.put(id, new RowState()));
        Map<String, Field> fields = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            for (String raw :
                    history.snapshots(
                            entry.definition().objectId(),
                            window.end(),
                            window.visibility(),
                            ids)) {
                var snapshot = decode(raw);
                var state = states.get(snapshot.path("recordId").asText());
                state.found = true;
                state.snapshot = snapshot.get("row");
                var allowed = allowed(state.snapshot, entry);
                state.denied = !absent(state.snapshot) && !readable(state.snapshot, entry);
                collectFields(snapshot.get("fields"), allowed, state.fields);
            }
            Map<String, String> names = new HashMap<>();
            events(
                    entry,
                    window,
                    ids,
                    true,
                    event -> {
                        var state = states.get(event.path("recordId").asText());
                        if (state.denied) return;
                        var projection = project(event, entry);
                        if (projection.denied()) {
                            state.denied = true;
                            return;
                        }
                        if (!projection.changed()
                                && !event.path("source").path("relatedUpdate").asBoolean(false))
                            return;
                        if (!state.touched) state.initial = projection.before();
                        state.touched = true;
                        state.detailTouched |= !projection.details().isEmpty();
                        state.lastBefore = projection.before();
                        String employee = event.path("actor").asText();
                        if (person == null || person.equals(employee)) {
                            state.count++;
                            state.changed.addAll(changed(projection.before(), projection.after()));
                        }
                        Map<String, Field> eventFields = new LinkedHashMap<>();
                        collectFields(event.get("fields"), projection.fields(), eventFields);
                        state.fields.putAll(eventFields);
                        if (detail)
                            state.changes.add(
                                    new Change(
                                            event.path("id").asText(),
                                            event.path("operation").asText(),
                                            event.path("time").asText(),
                                            employee,
                                            names.computeIfAbsent(employee, this::employeeName),
                                            projection.before(),
                                            projection.after(),
                                            List.copyOf(eventFields.values()),
                                            event.path("source").isObject()
                                                    ? json.convertValue(
                                                            event.path("source"), Map.class)
                                                    : null,
                                            projection.details()));
                    });
        }
        List<Row> rows = new ArrayList<>();
        for (var item : states.entrySet()) {
            var s = item.getValue();
            if (s.denied || !s.found || (absent(s.snapshot) && !s.touched)) continue;
            fields.putAll(s.fields);
            var end = values(s.snapshot, allowed(s.snapshot, entry));
            var initial = s.touched ? s.initial : end;
            var shown = end == null ? s.lastBefore : end;
            rows.add(
                    new Row(
                            item.getKey(),
                            detail ? initial : null,
                            detail ? end : null,
                            shown == null ? Map.of() : shown,
                            List.copyOf(s.changed),
                            s.changes,
                            s.count,
                            end == null,
                            s.touched && initial == null,
                            s.touched
                                    && initial != null
                                    && Objects.equals(initial, end)
                                    && !s.detailTouched));
        }
        String covered = coverage(entry);
        return new Table(
                entry.definition().objectId(),
                entry.definition().objectName(),
                entry.apps(),
                entry.names(),
                covered,
                !time(window.start()).isBefore(time(covered)),
                List.copyOf(fields.values()),
                rows);
    }

    private Projection project(JsonNode event, Entry entry) {
        var before = event.get("before");
        var after = event.get("after");
        var b = allowed(before, entry);
        var a = allowed(after, entry);
        if ((!absent(before) && !readable(before, entry))
                || (!absent(after) && !readable(after, entry)))
            return new Projection(true, null, null, Set.of(), List.of());
        var fields = absent(before) ? a : b;
        if (!absent(after)) fields.retainAll(a);
        return new Projection(
                false,
                values(before, fields),
                values(after, fields),
                fields,
                detailProjection.changes(event, entry.access()));
    }

    private Set<String> changed(Map<String, Object> before, Map<String, Object> after) {
        Set<String> keys = new LinkedHashSet<>();
        if (before != null) keys.addAll(before.keySet());
        if (after != null) keys.addAll(after.keySet());
        keys.removeIf(
                k ->
                        before != null
                                && after != null
                                && Objects.equals(before.get(k), after.get(k)));
        return keys;
    }

    private String coverage(Entry entry) {
        String covered = history.coverage(entry.definition().objectId());
        if (covered == null) throw invalid("历史尚未开始留存，请重新查询");
        return time(covered.replace(' ', 'T').replaceAll("([+-][0-9]{2})$", "$1:00")).toString();
    }

    private Instant time(String value) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (Exception e) {
            throw invalid("请选择有效的起止时间");
        }
    }

    private JsonNode decode(String raw) {
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            throw invalid("历史数据无法读取");
        }
    }

    private boolean absent(JsonNode row) {
        return row == null || row.isNull();
    }

    private Set<String> allowed(JsonNode row, Entry entry) {
        Set<String> fields = new LinkedHashSet<>();
        if (absent(row)) return fields;
        Map<String, Object> values = json.convertValue(row.path("values"), Map.class);
        for (var access : entry.access()) {
            var caps = access.forRow(row.path("recordCreator").asText(), values);
            if (caps.actions().contains(ApplicationActionEnum.READ.getCode()))
                fields.addAll(caps.readFields());
        }
        return fields;
    }

    private boolean readable(JsonNode row, Entry entry) {
        return !allowed(row, entry).isEmpty() || detailProjection.readable(row, entry.access());
    }

    private Map<String, Object> values(JsonNode row, Set<String> allowed) {
        if (absent(row)) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        row.path("values")
                .fields()
                .forEachRemaining(
                        e -> {
                            if (allowed.contains(e.getKey()))
                                result.put(
                                        e.getKey(), json.convertValue(e.getValue(), Object.class));
                        });
        return result;
    }

    private void collectFields(JsonNode source, Set<String> allowed, Map<String, Field> target) {
        if (source == null) return;
        for (var field : source)
            if (allowed.contains(field.path("id").asText()))
                target.put(
                        field.path("id").asText(),
                        new Field(field.path("id").asText(), field.path("name").asText()));
    }

    private void loadNames(Collection<String> ids, Map<String, String> names) {
        List<Long> pending = new ArrayList<>();
        for (String id : ids)
            if (!names.containsKey(id)) {
                try {
                    pending.add(Long.parseLong(id));
                    names.put(id, "用户 " + id);
                } catch (NumberFormatException e) {
                    names.put(id, "系统");
                }
            }
        if (!pending.isEmpty())
            for (var user : users.getUserList(pending))
                names.put(user.getId().toString(), user.getNickname());
    }

    private String employeeName(String id) {
        try {
            var user = users.getUser(Long.parseLong(id));
            return user == null ? "用户 " + id : user.getNickname();
        } catch (NumberFormatException e) {
            return "系统";
        }
    }
}
