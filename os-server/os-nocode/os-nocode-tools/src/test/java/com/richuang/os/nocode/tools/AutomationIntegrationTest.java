package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.richuang.os.module.system.api.user.AdminUserApi;
import com.richuang.os.module.system.api.user.dto.AdminUserRespDTO;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationRecords.*;
import com.richuang.os.nocode.api.TaskCenter.Action;
import com.richuang.os.nocode.api.TaskCenter.AssignmentMode;
import com.richuang.os.nocode.api.TaskCenter.Binding;
import com.richuang.os.nocode.api.TaskCenter.Create;
import com.richuang.os.nocode.api.TaskCenter.DataAccessMode;
import com.richuang.os.nocode.api.TaskCenter.DataPolicy;
import com.richuang.os.nocode.api.TaskCenter.Detail;
import com.richuang.os.nocode.api.TaskCenter.NodeInput;
import com.richuang.os.nocode.api.TaskCenter.Schedule;
import com.richuang.os.nocode.api.TaskCenter.TimeMode;
import com.richuang.os.nocode.api.TaskCenter.Transition;
import com.richuang.os.nocode.application.service.application.ApplicationService;
import com.richuang.os.nocode.application.service.authorization.ApplicationAuthorizationService;
import com.richuang.os.nocode.runtime.service.record.RecordService;
import com.richuang.os.nocode.runtime.service.task.TaskEntryRuntimeScope;
import com.richuang.os.nocode.runtime.service.taskcenter.TaskCenterService;
import com.richuang.os.nocode.runtime.service.taskcenter.TaskWorkEntryService;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;

/** 当前开发库真实自动化事务回归；仅创建、删除随机前缀自有对象和应用，不触碰体验数据。 */
class AutomationIntegrationTest {
    private NocodeIntegrationSupport fixture;
    private ApplicationService apps;
    private RecordService runtime;
    private DataObjectApi objects;
    private DataCenter.Definition source, target;
    private String app, relation;
    private int serial;
    private final List<String> taskRoots = new ArrayList<>();

    @BeforeAll
    static void open() throws Exception {
        connect();
    }

    @AfterAll
    static void shutdown() {
        close();
    }

    @BeforeEach
    void setup() {
        fixture = new NocodeIntegrationSupport();
        fixture.name();
        apps = servicesContext.getBean(ApplicationService.class);
        runtime = servicesContext.getBean(RecordService.class);
        objects = servicesContext.getBean(DataObjectApi.class);
    }

    @AfterEach
    void cleanup() {
        for (String root : taskRoots) {
            jdbc.update(
                    "DELETE FROM public.nocode_task_entry_record WHERE task_id IN (SELECT id FROM"
                            + " public.nocode_task_instance WHERE root_id=?)",
                    root);
            jdbc.update(
                    "DELETE FROM public.nocode_task_entry_binding WHERE task_id IN (SELECT id FROM"
                            + " public.nocode_task_instance WHERE root_id=?)",
                    root);
            jdbc.update("DELETE FROM public.nocode_task_event WHERE root_id=?", root);
            jdbc.update("DELETE FROM public.nocode_task_instance WHERE root_id=?", root);
        }
        for (Long id :
                jdbc.queryForList(
                        "SELECT id FROM public.nocode_application WHERE app_code LIKE ?",
                        Long.class,
                        fixture.prefix + "%")) {
            jdbc.update(
                    "DELETE FROM public.nocode_object_application_grant_log WHERE application_id=?",
                    id);
            jdbc.update(
                    "DELETE FROM public.nocode_object_application_grant WHERE application_id=?",
                    id);
            jdbc.update("DELETE FROM public.nocode_application_access WHERE application_id=?", id);
            jdbc.update("DELETE FROM public.nocode_application_version WHERE application_id=?", id);
            jdbc.update("DELETE FROM public.nocode_application WHERE id=?", id);
        }
        fixture.clean();
    }

    private DataCenter.Definition object(
            String name, List<FieldDefinition> fields, List<DataCenter.Relation> relations) {
        return object(name, fields, relations, DataCenter.Settings.defaults(), Map.of());
    }

    private DataCenter.Definition object(
            String name,
            List<FieldDefinition> fields,
            List<DataCenter.Relation> relations,
            DataCenter.Settings settings,
            Map<String, DataCenter.FieldOptions> options) {
        var request = fixture.createRequest("object" + serial++);
        var design =
                designs.save(
                        new DataCenter.SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        request.objectCode(),
                                        name,
                                        null,
                                        request.tableName(),
                                        "name",
                                        fields,
                                        List.of()),
                                settings,
                                options,
                                relations,
                                List.of(),
                                List.of()),
                        10001);
        var plan =
                publisher.plan(
                        new DataCenter.Revision(
                                design.draft().id(), design.draft().lockVersion(), null),
                        10001);
        assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "自动化集成测试"), 10001)
                                .state())
                .isEqualTo("SUCCEEDED");
        return objects.getPublished(design.draft().id());
    }

    private FieldDefinition f(String code, String type) {
        return fixture.field(code, code, type, serial++);
    }

    private String field(DataCenter.Definition d, String code) {
        return d.fields().stream()
                .filter(f -> f.code().equals(code))
                .findFirst()
                .orElseThrow()
                .id();
    }

    private ApplicationCenter.ObjectReference ref(DataCenter.Definition d) {
        var v = objects.getVersion(d.objectId(), null);
        return new ApplicationCenter.ObjectReference(v.objectId(), v.versionNo(), v.checksum());
    }

    private ApplicationCenter.Resource rule(
            String id,
            String mode,
            Set<String> events,
            DataScope conditions,
            String direction,
            List<ApplicationAutomations.Assignment> assignments) {
        var c =
                new ApplicationAutomations.Config(
                        source.objectId(),
                        target.objectId(),
                        true,
                        mode,
                        events,
                        conditions,
                        new ApplicationAutomations.Binding(relation, direction),
                        assignments);
        return new ApplicationCenter.Resource(
                id,
                "AUTOMATION",
                id,
                id,
                mapper.convertValue(c, new TypeReference<Map<String, Object>>() {}));
    }

    private ApplicationAutomations.Assignment assignment(
            String to, String kind, String from, Object value, Object empty) {
        return new ApplicationAutomations.Assignment(
                field(target, to), kind, from == null ? null : field(source, from), value, empty);
    }

    private String application(List<ApplicationCenter.Resource> rules) {
        var a =
                apps.save(
                        new ApplicationCenter.Save(
                                null,
                                null,
                                fixture.prefix + "app" + serial++,
                                "自动化测试",
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        List.of(ref(source), ref(target)), rules)),
                        10001);
        grantApplicationObjects(a.application().id());
        apps.publish(
                new ApplicationCenter.Revision(
                        a.application().id(), a.application().revision(), "测试"),
                10001);
        return a.application().id();
    }

    private void scenario() {
        target =
                object(
                        "订单",
                        List.of(
                                f("name", "TEXT"),
                                new FieldDefinition(
                                        "status", null, "status", "登记状态", "TEXT", null, null, null,
                                        true, false, 10),
                                f("count", "INTEGER"),
                                f("total", "DECIMAL"),
                                f("latest", "TEXT")),
                        List.of());
        source =
                object(
                        "收款",
                        List.of(f("name", "TEXT"), f("amount", "DECIMAL"), f("valid", "BOOLEAN")),
                        List.of(
                                new DataCenter.Relation(
                                        null,
                                        "purchase",
                                        "关联订单",
                                        "REFERENCE",
                                        target.objectId(),
                                        null,
                                        null,
                                        false,
                                        "SET_NULL",
                                        null)));
        relation = source.relations().getFirst().id();
        var condition =
                new DataScope(
                        "AND",
                        List.of(new DataScope.Condition(field(source, "valid"), "eq", true)),
                        List.of());
        app =
                application(
                        List.of(
                                rule(
                                        "maintain",
                                        "MAINTAIN",
                                        Set.of("CREATE", "UPDATE", "DELETE"),
                                        condition,
                                        "OUTGOING",
                                        List.of(
                                                assignment("status", "EXISTS", null, "已登记", "未登记"),
                                                assignment("count", "COUNT", null, null, null),
                                                assignment("total", "SUM", "amount", null, null))),
                                rule(
                                        "event",
                                        "EVENT",
                                        Set.of("CREATE", "UPDATE"),
                                        condition,
                                        "OUTGOING",
                                        List.of(
                                                assignment(
                                                        "latest", "FIELD", "name", null, null)))));
    }

    private Map<String, Object> data(DataCenter.Definition d, Object... entries) {
        var out = new LinkedHashMap<String, Object>();
        for (int i = 0; i < entries.length; i += 2)
            out.put(field(d, entries[i].toString()), entries[i + 1]);
        return out;
    }

    private Row createTarget(String name) {
        return runtime.save(
                        new Save(
                                app,
                                target.objectId(),
                                null,
                                null,
                                data(target, "name", name),
                                null),
                        10001)
                .record();
    }

    private Row createSource(
            String application,
            String parent,
            String name,
            String amount,
            boolean valid,
            long actor) {
        var values = data(source, "name", name, "amount", amount, "valid", valid);
        values.put(source.relations().getFirst().fieldId(), parent);
        return runtime.save(
                        new Save(application, source.objectId(), null, null, values, null), actor)
                .record();
    }

    private Row update(DataCenter.Definition d, Row row, Map<String, Object> values) {
        return runtime.save(
                        new Save(app, d.objectId(), row.id(), row.revision(), values, null), 10001)
                .record();
    }

    private Row target(String id) {
        return runtime.get(app, target.objectId(), id, 10001).record();
    }

    private void state(String id, String status, int count, String total) {
        var row = target(id);
        assertThat(row.values().get(field(target, "status"))).isEqualTo(status);
        assertThat(row.values().get(field(target, "count")).toString())
                .isEqualTo(Integer.toString(count));
        assertThat(new java.math.BigDecimal(row.values().get(field(target, "total")).toString()))
                .isEqualByComparingTo(total);
    }

    private void delete(Row row) {
        runtime.delete(new Delete(app, source.objectId(), row.id(), row.revision()), 10001);
    }

    /** 真任务入口、真实记录事务；员工没有应用成员身份，规则来源也不要求配置成任务卡片。 */
    private String delegatedTask(boolean receipts, DataAccessMode mode) {
        scenario();
        ApplicationCenter.Detail existing = apps.get(app);
        List<ApplicationCenter.Resource> resources = new ArrayList<>(existing.draft().resources());
        for (DataCenter.Definition object : List.of(target, source)) {
            String formId = object == target ? "order_form" : "receipt_form";
            resources.add(
                    new ApplicationCenter.Resource(
                            formId,
                            "FORM",
                            formId,
                            formId,
                            Map.of(
                                    "objectId",
                                    object.objectId(),
                                    "detailIds",
                                    List.of(),
                                    "nodes",
                                    object.fields().stream()
                                            .map(
                                                    f ->
                                                            Map.<String, Object>of(
                                                                    "id",
                                                                    "node_" + f.id(),
                                                                    "type",
                                                                    "FIELD",
                                                                    "fieldId",
                                                                    f.id(),
                                                                    "children",
                                                                    List.of()))
                                            .toList())));
        }
        ApplicationCenter.Detail updated =
                apps.save(
                        new ApplicationCenter.Save(
                                app,
                                existing.application().revision(),
                                existing.application().code(),
                                existing.application().name(),
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        existing.draft().objects(), resources)),
                        10001);
        apps.publish(
                new ApplicationCenter.Revision(app, updated.application().revision(), "任务办理规则回归"),
                10001);
        when(servicesContext.getBean(AdminUserApi.class).getUser(anyLong()))
                .thenAnswer(
                        invocation -> {
                            AdminUserRespDTO user = new AdminUserRespDTO();
                            user.setId(invocation.getArgument(0));
                            user.setStatus(0);
                            user.setNickname("自动授权测试成员");
                            return user;
                        });
        List<TaskWorkEntries.Config> entries =
                receipts
                        ? List.of(
                                new TaskWorkEntries.Config(
                                        "receipts",
                                        "到货登记",
                                        new Binding(app, "receipt_form", null),
                                        TaskWorkEntries.DataMode.ROOT_SHARED,
                                        null,
                                        null,
                                        null,
                                        null,
                                        false,
                                        false))
                        : List.of();
        NodeInput node =
                new NodeInput(
                        null,
                        null,
                        "任务自动业务能力回归",
                        null,
                        20002L,
                        null,
                        null,
                        new Schedule(TimeMode.UNSCHEDULED, null, 0, 0),
                        List.of(),
                        new Binding(app, "order_form", null),
                        null,
                        entries,
                        AssignmentMode.ASSIGNED,
                        List.of(),
                        new DataPolicy(1, mode, mode));
        TaskCenterService tasks = servicesContext.getBean(TaskCenterService.class);
        Detail created =
                tasks.create(
                        new Create(
                                node,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                UUID.randomUUID().toString()),
                        10001);
        taskRoots.add(created.task().id());
        tasks.transition(
                new Transition(
                        created.task().id(),
                        created.task().revision(),
                        Action.START,
                        null,
                        UUID.randomUUID().toString()),
                20002);
        return created.task().id();
    }

    private Row taskSave(String task, boolean receipt, Row previous, Map<String, Object> values) {
        DataCenter.Definition object = receipt ? source : target;
        String form = receipt ? "receipt_form" : "order_form";
        TaskWorkEntries.Saved saved =
                servicesContext
                        .getBean(TaskWorkEntryService.class)
                        .save(
                                new TaskWorkEntries.Save(
                                        task,
                                        receipt ? "receipts" : "__business",
                                        null,
                                        new Save(
                                                app,
                                                object.objectId(),
                                                previous == null ? null : previous.id(),
                                                previous == null ? null : previous.revision(),
                                                values,
                                                null,
                                                null,
                                                null,
                                                form,
                                                UUID.randomUUID().toString(),
                                                null)),
                                20002);
        return saved.handling().result().record();
    }

    @Test
    void taskNonMemberCanInitializeOrderWithoutSourceEntryOrOrdinaryApplicationAccess() {
        String task = delegatedTask(false, DataAccessMode.GROUP);
        Row order = taskSave(task, false, null, data(target, "name", "本组采购"));
        state(order.id(), "未登记", 0, "0");
        assertThatThrownBy(() -> runtime.get(app, target.objectId(), order.id(), 20002))
                .hasMessageContaining("权限");
        assertThat(servicesContext.getBean(TaskEntryRuntimeScope.class).current()).isNull();
        assertThat(com.richuang.os.nocode.runtime.service.record.AutomationWriteScope.current())
                .isNull();
    }

    @Test
    void taskAutomationMaintainsCompleteTotalsWithoutExposingExternalSourceRows() {
        String task = delegatedTask(true, DataAccessMode.GROUP);
        Row order = taskSave(task, false, null, data(target, "name", "本组采购"));
        Row externalReceipt = createSource(app, order.id(), "后台登记", "5", true, 10001);
        Map<String, Object> values = data(source, "name", "员工登记", "amount", "10", "valid", true);
        values.put(source.relations().getFirst().fieldId(), order.id());
        Row receipt = taskSave(task, true, null, values);
        state(order.id(), "已登记", 2, "15");
        receipt = taskSave(task, true, receipt, data(source, "amount", "12"));
        state(order.id(), "已登记", 2, "17");
        assertThatThrownBy(
                        () ->
                                servicesContext
                                        .getBean(TaskWorkEntryService.class)
                                        .form(
                                                new TaskWorkEntries.Form(
                                                        task,
                                                        "receipts",
                                                        externalReceipt.id(),
                                                        null),
                                                20002))
                .hasMessageContaining("范围");
        servicesContext
                .getBean(TaskWorkEntryService.class)
                .delete(
                        new TaskWorkEntries.Delete(
                                task,
                                "receipts",
                                receipt.id(),
                                receipt.revision(),
                                UUID.randomUUID().toString()),
                        20002);
        state(order.id(), "已登记", 1, "5");
        assertThatThrownBy(
                        () ->
                                taskSave(
                                        task,
                                        false,
                                        target(order.id()),
                                        data(target, "total", "999")))
                .hasMessageContaining("不能手工修改");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_record_history WHERE"
                                        + " object_id=? AND creator='20002' AND"
                                        + " source_json->>'kind'='AUTOMATION'",
                                Long.class,
                                Long.valueOf(target.objectId())))
                .isPositive();
    }

    @Test
    void taskAutomationRejectsOutOfGroupTargetAndRollsBackSourceCreation() {
        String task = delegatedTask(true, DataAccessMode.GROUP);
        Row foreign = createTarget("其他任务采购");
        Map<String, Object> values = data(source, "name", "越界回写不得保存", "amount", "8", "valid", true);
        values.put(source.relations().getFirst().fieldId(), foreign.id());
        assertThatThrownBy(() -> taskSave(task, true, null, values)).hasMessageContaining("范围");
        state(foreign.id(), "未登记", 0, "0");
        assertThat(
                        runtime.page(
                                        new Query(
                                                app,
                                                source.objectId(),
                                                1,
                                                20,
                                                null,
                                                Map.of(),
                                                null,
                                                false),
                                        10001)
                                .getTotal())
                .isZero();
        assertThat(servicesContext.getBean(TaskEntryRuntimeScope.class).current()).isNull();
        assertThat(com.richuang.os.nocode.runtime.service.record.AutomationWriteScope.current())
                .isNull();
    }

    @Test
    void initializesMaintainsMovesQualifiesDeletesAndProtectsActualFields() {
        scenario();
        var a = createTarget("流水 A");
        var b = createTarget("流水 B");
        state(a.id(), "未登记", 0, "0");
        assertThat(runtime.model(app, target.objectId(), 10001).managedFieldIds())
                .contains(field(target, "status"), field(target, "total"));
        var one = createSource(app, a.id(), "凭证一", "10.25", true, 10001);
        var two = createSource(app, a.id(), "凭证二", "20", true, 10001);
        state(a.id(), "已登记", 2, "30.25");
        assertThat(target(a.id()).values().get(field(target, "latest"))).isEqualTo("凭证二");
        var third = createSource(app, a.id(), "凭证三", "4", true, 10001);
        delete(third);
        state(a.id(), "已登记", 2, "30.25");
        two = update(source, two, Map.of(source.relations().getFirst().fieldId(), b.id()));
        state(a.id(), "已登记", 1, "10.25");
        state(b.id(), "已登记", 1, "20");
        two = update(source, two, data(source, "valid", false));
        state(b.id(), "未登记", 0, "0");
        one = update(source, one, data(source, "amount", "14.30"));
        state(a.id(), "已登记", 1, "14.30");
        var current = target(a.id());
        assertThatThrownBy(() -> update(target, current, data(target, "status", "未登记")))
                .hasMessageContaining("不能手工修改");
        delete(one);
        state(a.id(), "未登记", 0, "0");
        delete(two);
        state(b.id(), "未登记", 0, "0");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_record_history WHERE"
                                        + " object_id=? AND source_json->>'kind'='AUTOMATION'",
                                Long.class,
                                Long.valueOf(target.objectId())))
                .isPositive();
    }

    @Test
    void repeatedSaveUsesReceiptAndConcurrentChangesNeverLoseAggregation() throws Exception {
        scenario();
        var a = createTarget("并发目标");
        var values = data(source, "name", "幂等", "amount", "3", "valid", true);
        values.put(source.relations().getFirst().fieldId(), a.id());
        var save =
                new Save(
                        app,
                        source.objectId(),
                        null,
                        null,
                        values,
                        null,
                        null,
                        null,
                        null,
                        UUID.randomUUID().toString(),
                        null);
        var first = runtime.save(save, 10001);
        assertThat(runtime.save(save, 10001).record().id()).isEqualTo(first.record().id());
        state(a.id(), "已登记", 1, "3");
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Future<Row>> futures = new ArrayList<>();
            var gate = new CountDownLatch(1);
            for (int i = 0; i < 4; i++) {
                int n = i;
                futures.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    return createSource(app, a.id(), "并发" + n, "2", true, 10001);
                                }));
            }
            gate.countDown();
            List<Row> created = new ArrayList<>();
            for (var future : futures) created.add(future.get(45, TimeUnit.SECONDS));
            state(a.id(), "已登记", 5, "11");
            List<Future<?>> deletes = new ArrayList<>();
            for (var row : created) deletes.add(pool.submit(() -> delete(row)));
            for (var future : deletes) future.get(45, TimeUnit.SECONDS);
            state(a.id(), "已登记", 1, "3");
        }
    }

    private ApplicationAuthorization.ObjectGrant grant(
            DataCenter.Definition d,
            String scope,
            Set<String> actions,
            Map<String, DataScope> scopes) {
        var fields =
                d.fields().stream()
                        .map(FieldDefinition::id)
                        .collect(java.util.stream.Collectors.toSet());
        return new ApplicationAuthorization.ObjectGrant(
                d.objectId(),
                actions,
                scope,
                fields,
                fields,
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                scopes,
                Set.of());
    }

    private void authorize(String application, List<ApplicationAuthorization.ObjectGrant> grants) {
        var auth = servicesContext.getBean(ApplicationAuthorizationService.class);
        auth.save(
                new ApplicationAuthorization.Save(
                        application,
                        auth.get(application).revision(),
                        List.of(new ApplicationAuthorization.Member("USER", "20002", grants))),
                10001);
    }

    private long countSource() {
        return runtime.page(
                        new Query(app, source.objectId(), 1, 20, null, Map.of(), null, false),
                        10001)
                .getTotal();
    }

    @Test
    void readsAllSourcesAcrossApplicationsAndRollsBackInsufficientOrConditionalPermissions() {
        scenario();
        var a = createTarget("权限目标");
        var second = application(List.of());
        createSource(second, a.id(), "从另一个应用新增", "7.5", true, 10001);
        state(a.id(), "已登记", 1, "7.5");
        authorize(
                app,
                List.of(
                        grant(
                                source,
                                "OWN",
                                Set.of("READ", "CREATE", "UPDATE", "DELETE"),
                                Map.of()),
                        grant(target, "ALL", Set.of("READ", "UPDATE"), Map.of())));
        assertThatThrownBy(() -> createSource(app, a.id(), "拒绝部分统计", "10", true, 20002))
                .hasMessageContaining("完整读取");
        assertThat(countSource()).isEqualTo(1);
        state(a.id(), "已登记", 1, "7.5");
        authorize(
                app,
                List.of(
                        grant(
                                source,
                                "ALL",
                                Set.of("READ", "CREATE", "UPDATE", "DELETE"),
                                Map.of()),
                        grant(target, "ALL", Set.of("READ"), Map.of())));
        assertThatThrownBy(() -> createSource(app, a.id(), "拒绝无权回写", "10", true, 20002))
                .hasMessageContaining("权限");
        assertThat(countSource()).isEqualTo(1);
        var onlyUnregistered =
                new DataScope(
                        "AND",
                        List.of(new DataScope.Condition(field(target, "status"), "eq", "未登记")),
                        List.of());
        authorize(
                app,
                List.of(
                        grant(
                                source,
                                "ALL",
                                Set.of("READ", "CREATE", "UPDATE", "DELETE"),
                                Map.of()),
                        grant(
                                target,
                                "ALL",
                                Set.of("READ", "UPDATE"),
                                Map.of("UPDATE", onlyUnregistered))));
        var b = createTarget("只能改未登记");
        assertThatThrownBy(() -> createSource(app, b.id(), "改后越出范围", "10", true, 20002))
                .hasMessageContaining("权限");
        state(b.id(), "未登记", 0, "0");
        assertThat(countSource()).isEqualTo(1);
    }

    @Test
    void incomingRulesInitializeNewTargetsAndUseDeleteSnapshotWithoutFinancialAssumptions() {
        source = object("客户", List.of(f("name", "TEXT")), List.of());
        target =
                object(
                        "服务工单",
                        List.of(
                                f("name", "TEXT"),
                                f("customer_name", "TEXT"),
                                f("has_customer", "BOOLEAN")),
                        List.of(
                                new DataCenter.Relation(
                                        null,
                                        "customer",
                                        "客户",
                                        "REFERENCE",
                                        source.objectId(),
                                        null,
                                        null,
                                        false,
                                        "SET_NULL",
                                        null)));
        relation = target.relations().getFirst().id();
        app =
                application(
                        List.of(
                                rule(
                                        "customer_exists",
                                        "MAINTAIN",
                                        Set.of("CREATE", "UPDATE", "DELETE"),
                                        null,
                                        "INCOMING",
                                        List.of(
                                                assignment(
                                                        "has_customer",
                                                        "EXISTS",
                                                        null,
                                                        true,
                                                        false))),
                                rule(
                                        "customer_name",
                                        "EVENT",
                                        Set.of("UPDATE", "DELETE"),
                                        null,
                                        "INCOMING",
                                        List.of(
                                                assignment(
                                                        "customer_name",
                                                        "FIELD",
                                                        "name",
                                                        null,
                                                        null)))));
        var customer =
                runtime.save(
                                new Save(
                                        app,
                                        source.objectId(),
                                        null,
                                        null,
                                        data(source, "name", "客户甲"),
                                        null),
                                10001)
                        .record();
        var ticketData = data(target, "name", "工单甲");
        ticketData.put(target.relations().getFirst().fieldId(), customer.id());
        var ticket =
                runtime.save(new Save(app, target.objectId(), null, null, ticketData, null), 10001)
                        .record();
        assertThat(target(ticket.id()).values().get(field(target, "has_customer"))).isEqualTo(true);
        customer = update(source, customer, data(source, "name", "客户改名"));
        assertThat(target(ticket.id()).values().get(field(target, "customer_name")))
                .isEqualTo("客户改名");
        update(target, target(ticket.id()), data(target, "customer_name", "人工备注"));
        delete(customer);
        var after = target(ticket.id());
        assertThat(after.values().get(field(target, "has_customer"))).isEqualTo(false);
        assertThat(after.values().get(field(target, "customer_name"))).isEqualTo("客户改名");
        assertThat(after.values().get(target.relations().getFirst().fieldId())).isNull();
    }

    @Test
    void allowsDisjointEventRulesToWriteSameTargetField() {
        scenario();
        application(
                List.of(
                        rule(
                                "mark_deleted",
                                "EVENT",
                                Set.of("DELETE"),
                                null,
                                "OUTGOING",
                                List.of(assignment("latest", "VALUE", null, "已删除", null)))));
        Row order = createTarget("事件拆分订单");
        Row receipt = createSource(app, order.id(), "新增收款", "10", true, 10001);
        assertThat(target(order.id()).values().get(field(target, "latest"))).isEqualTo("新增收款");

        delete(receipt);

        assertThat(target(order.id()).values().get(field(target, "latest"))).isEqualTo("已删除");
    }

    @Test
    void rejectsDuplicateOwnershipAndGlobalObjectCyclesBeforePublish() {
        scenario();
        // 一次性赋值（事件赋值、按日期）可以多条写同一字段；持续维护独占目标字段，不能与已有的事件赋值共写。
        assertThatThrownBy(
                        () ->
                                application(
                                        List.of(
                                                rule(
                                                        "duplicate",
                                                        "MAINTAIN",
                                                        Set.of("CREATE", "UPDATE", "DELETE"),
                                                        null,
                                                        "OUTGOING",
                                                        List.of(
                                                                assignment(
                                                                        "latest", "EXISTS", null,
                                                                        "有", "无"))))))
                .hasMessageContaining("同一目标字段");
        var originalSource = source;
        source = target;
        target = originalSource;
        assertThatThrownBy(
                        () ->
                                application(
                                        List.of(
                                                rule(
                                                        "cycle",
                                                        "EVENT",
                                                        Set.of("UPDATE"),
                                                        null,
                                                        "INCOMING",
                                                        List.of(
                                                                assignment(
                                                                        "name", "VALUE", null, "循环",
                                                                        null))))))
                .hasMessageContaining("循环");
    }

    @Test
    void oldApplicationProjectionDoesNotClearNewIncomingLinkAndCannotEscapeRuleApplicationScope() {
        source = object("客户旧版", List.of(f("name", "TEXT")), List.of());
        target =
                object("工单旧版", List.of(f("name", "TEXT"), f("has_customer", "BOOLEAN")), List.of());
        var oldApp = application(List.of());
        var editable =
                designs.editPublished(
                        new DataCenter.Revision(
                                target.objectId(),
                                designs.get(target.objectId()).draft().lockVersion(),
                                null),
                        10001);
        var changed =
                designs.save(
                        new DataCenter.SaveDesign(
                                fixture.edit(
                                        editable.draft(),
                                        editable.draft().fields(),
                                        List.of(),
                                        editable.draft().titleFieldId()),
                                editable.settings(),
                                editable.fieldOptions(),
                                List.of(
                                        new DataCenter.Relation(
                                                null,
                                                "customer",
                                                "客户",
                                                "REFERENCE",
                                                source.objectId(),
                                                null,
                                                null,
                                                false,
                                                "SET_NULL",
                                                null)),
                                editable.indexes(),
                                editable.details(),
                                editable.mainBinding()),
                        10001);
        var plan =
                publisher.plan(
                        new DataCenter.Revision(
                                changed.draft().id(), changed.draft().lockVersion(), null),
                        10001);
        assertThat(publisher.execute(new DataCenter.ExecutePlan(plan.id(), "增加引用"), 10001).state())
                .isEqualTo("SUCCEEDED");
        target = objects.getPublished(target.objectId());
        relation = target.relations().getFirst().id();
        app =
                application(
                        List.of(
                                rule(
                                        "incoming_maintain",
                                        "MAINTAIN",
                                        Set.of("CREATE", "UPDATE", "DELETE"),
                                        null,
                                        "INCOMING",
                                        List.of(
                                                assignment(
                                                        "has_customer",
                                                        "EXISTS",
                                                        null,
                                                        true,
                                                        false)))));
        var customer =
                runtime.save(
                                new Save(
                                        app,
                                        source.objectId(),
                                        null,
                                        null,
                                        data(source, "name", "真实客户"),
                                        null),
                                10001)
                        .record();
        var values = data(target, "name", "已有引用");
        values.put(target.relations().getFirst().fieldId(), customer.id());
        var row =
                runtime.save(new Save(app, target.objectId(), null, null, values, null), 10001)
                        .record();
        var oldModel = runtime.model(oldApp, target.objectId(), 10001);
        assertThat(oldModel.object().fields())
                .noneMatch(f -> f.id().equals(target.relations().getFirst().fieldId()));
        runtime.save(
                new Save(
                        oldApp,
                        target.objectId(),
                        row.id(),
                        row.revision(),
                        data(target, "name", "旧应用只改标题"),
                        null),
                10001);
        assertThat(target(row.id()).values().get(field(target, "has_customer"))).isEqualTo(true);
        var second = application(List.of());
        var empty =
                runtime.save(
                                new Save(
                                        app,
                                        target.objectId(),
                                        null,
                                        null,
                                        data(target, "name", "待关联"),
                                        null),
                                10001)
                        .record();
        var onlyEmpty =
                new DataScope(
                        "AND",
                        List.of(
                                new DataScope.Condition(
                                        field(target, "has_customer"), "eq", false)),
                        List.of());
        authorize(
                app,
                List.of(
                        grant(source, "ALL", Set.of("READ"), Map.of()),
                        grant(
                                target,
                                "ALL",
                                Set.of("READ", "UPDATE"),
                                Map.of("UPDATE", onlyEmpty))));
        authorize(
                second,
                List.of(
                        grant(source, "ALL", Set.of("READ"), Map.of()),
                        grant(target, "ALL", Set.of("READ", "UPDATE"), Map.of())));
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new Save(
                                                second,
                                                target.objectId(),
                                                empty.id(),
                                                empty.revision(),
                                                Map.of(
                                                        target.relations().getFirst().fieldId(),
                                                        customer.id()),
                                                null),
                                        20002))
                .hasMessageContaining("权限");
        assertThat(target(empty.id()).values().get(field(target, "has_customer"))).isEqualTo(false);
        assertThat(target(empty.id()).values().get(target.relations().getFirst().fieldId()))
                .isNull();
    }

    @Test
    void cascadingDeleteSkipsExactlyTheTargetsAlreadyDeletedInTheSameTransaction() {
        source = object("父记录", List.of(f("name", "TEXT")), List.of());
        target =
                object(
                        "级联子记录",
                        List.of(f("name", "TEXT"), f("active", "BOOLEAN")),
                        List.of(
                                new DataCenter.Relation(
                                        null,
                                        "owner",
                                        "主从归属",
                                        "MASTER_DETAIL",
                                        source.objectId(),
                                        null,
                                        null,
                                        false,
                                        "CASCADE",
                                        null),
                                new DataCenter.Relation(
                                        null,
                                        "lookup",
                                        "业务引用",
                                        "REFERENCE",
                                        source.objectId(),
                                        null,
                                        null,
                                        false,
                                        "SET_NULL",
                                        null)));
        relation =
                target.relations().stream()
                        .filter(r -> r.code().equals("lookup"))
                        .findFirst()
                        .orElseThrow()
                        .id();
        app =
                application(
                        List.of(
                                rule(
                                        "cascade_maintain",
                                        "MAINTAIN",
                                        Set.of("CREATE", "UPDATE", "DELETE"),
                                        null,
                                        "INCOMING",
                                        List.of(
                                                assignment(
                                                        "active", "EXISTS", null, true, false)))));
        var parent =
                runtime.save(
                                new Save(
                                        app,
                                        source.objectId(),
                                        null,
                                        null,
                                        data(source, "name", "主记录"),
                                        null),
                                10001)
                        .record();
        var input = data(target, "name", "子记录");
        target.relations().forEach(r -> input.put(r.fieldId(), parent.id()));
        runtime.save(new Save(app, target.objectId(), null, null, input, null), 10001);
        delete(parent);
        assertThat(
                        runtime.page(
                                        new Query(
                                                app,
                                                target.objectId(),
                                                1,
                                                20,
                                                null,
                                                Map.of(),
                                                null,
                                                false),
                                        10001)
                                .getTotal())
                .isZero();
    }

    @Test
    void importedValuesMaintainMinMaxAndNeverPersistTruncatedAggregates() {
        target =
                object(
                        "统计主表",
                        List.of(
                                f("name", "TEXT"),
                                f("minimum", "DECIMAL"),
                                f("maximum", "DECIMAL")),
                        List.of());
        source =
                object(
                        "统计来源",
                        List.of(f("name", "TEXT"), f("amount", "DECIMAL")),
                        List.of(
                                new DataCenter.Relation(
                                        null,
                                        "summary_root",
                                        "统计主表",
                                        "REFERENCE",
                                        target.objectId(),
                                        null,
                                        null,
                                        false,
                                        "SET_NULL",
                                        null)));
        relation = source.relations().getFirst().id();
        app =
                application(
                        List.of(
                                rule(
                                        "extrema",
                                        "MAINTAIN",
                                        Set.of("CREATE", "UPDATE", "DELETE"),
                                        null,
                                        "OUTGOING",
                                        List.of(
                                                assignment("minimum", "MIN", "amount", null, null),
                                                assignment(
                                                        "maximum", "MAX", "amount", null, null)))));
        var parent = createTarget("统计目标");
        var one = data(source, "name", "导入一", "amount", "12.5");
        one.put(source.relations().getFirst().fieldId(), parent.id());
        var two = data(source, "name", "导入二", "amount", "2.5");
        two.put(source.relations().getFirst().fieldId(), parent.id());
        assertThat(runtime.importRecords(app, source.objectId(), List.of(one, two), 10001))
                .isEqualTo(2);
        assertThat(
                        new java.math.BigDecimal(
                                target(parent.id())
                                        .values()
                                        .get(field(target, "minimum"))
                                        .toString()))
                .isEqualByComparingTo("2.5");
        assertThat(
                        new java.math.BigDecimal(
                                target(parent.id())
                                        .values()
                                        .get(field(target, "maximum"))
                                        .toString()))
                .isEqualByComparingTo("12.5");
        var invalid = new LinkedHashMap<>(two);
        invalid.put(field(source, "amount"), "不是数字");
        assertThatThrownBy(
                        () ->
                                runtime.importRecords(
                                        app, source.objectId(), List.of(one, invalid), 10001))
                .hasMessageContaining("本批未写入");
        assertThat(countSource()).isEqualTo(2);
        var refField = DataScope.field(source, source.relations().getFirst().fieldId());
        var refColumn =
                Objects.toString(
                        source.fieldOptions().get(refField.id()).columnName(), refField.code());
        // 只造本例所属物理表的大量边界夹具，不通过线上路径伪造正常业务结果。
        jdbc.update(
                "INSERT INTO public.\""
                        + source.tableName()
                        + "\" (name,amount,\""
                        + refColumn
                        + "\",creator,updater) SELECT '容量夹具' || g, 1, ?, '10001','10001' FROM"
                        + " generate_series(1,10001) g",
                Long.valueOf(parent.id()));
        var before = target(parent.id());
        assertThatThrownBy(() -> update(target, before, data(target, "name", "超限保存")))
                .hasMessageContaining("10000");
        assertThat(target(parent.id()).revision()).isEqualTo(before.revision());
    }

    @Test
    void serverFormDefaultsDoNotOverrideOrBlockMaintainedInitialValues() {
        var options =
                new DataCenter.FieldOptions(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "ACTIVE",
                        List.of(
                                new DataCenter.Option("none", "无记录", false),
                                new DataCenter.Option("some", "有记录", false)),
                        null,
                        null,
                        null,
                        null,
                        false,
                        false);
        target =
                object(
                        "默认值目标",
                        List.of(f("name", "TEXT"), f("state", "SELECT")),
                        List.of(),
                        DataCenter.Settings.defaults(),
                        Map.of("state", options));
        source =
                object(
                        "默认值来源",
                        List.of(f("name", "TEXT")),
                        List.of(
                                new DataCenter.Relation(
                                        null,
                                        "parent_ref",
                                        "目标",
                                        "REFERENCE",
                                        target.objectId(),
                                        null,
                                        null,
                                        false,
                                        "SET_NULL",
                                        null)));
        relation = source.relations().getFirst().id();
        var form =
                new ApplicationUi.Form(
                        target.objectId(),
                        List.of(
                                new ApplicationUi.Node(
                                        "name_node",
                                        "FIELD",
                                        field(target, "name"),
                                        null,
                                        null,
                                        24,
                                        List.of()),
                                new ApplicationUi.Node(
                                        "state_node",
                                        "FIELD",
                                        field(target, "state"),
                                        null,
                                        null,
                                        24,
                                        List.of(),
                                        null,
                                        new ApplicationUi.FieldPresentation(
                                                null,
                                                null,
                                                null,
                                                true,
                                                new SelectionFields.Presentation(
                                                        "SELECT", List.of(), false, null, null,
                                                        null)))),
                        List.of());
        var formResource =
                new ApplicationCenter.Resource(
                        "target_form",
                        "FORM",
                        "target_form",
                        "含旧默认值的表单",
                        mapper.convertValue(form, new TypeReference<Map<String, Object>>() {}));
        app =
                application(
                        List.of(
                                formResource,
                                rule(
                                        "maintained_state",
                                        "MAINTAIN",
                                        Set.of("CREATE", "UPDATE", "DELETE"),
                                        null,
                                        "OUTGOING",
                                        List.of(
                                                assignment(
                                                        "state", "EXISTS", null, "some",
                                                        "none")))));
        // 选项类字段现在不允许设表单默认值；「含旧默认值的表单」只能是旧版本发布留下的快照，这里直接改写已发布快照来构造。
        rewritePublishedSnapshot(
                app,
                tree -> {
                    for (var resource : tree.path("definition").path("resources"))
                        if ("target_form".equals(resource.path("code").asText()))
                            ((com.fasterxml.jackson.databind.node.ObjectNode)
                                            ObjectRuleMigrationForms.fieldNodes(
                                                            resource.path("config"))
                                                    .get("main:state_node")
                                                    .path("presentation")
                                                    .path("selection"))
                                    .put("defaultValue", "some");
                });
        var saved =
                runtime.save(
                        new Save(
                                app,
                                target.objectId(),
                                null,
                                null,
                                data(target, "name", "从表单新建"),
                                null,
                                null,
                                null,
                                "target_form",
                                UUID.randomUUID().toString(),
                                null),
                        10001);
        assertThat(saved.record().values().get(field(target, "state"))).isEqualTo("none");
    }

    @Test
    void numericLookingTextAndChoiceCodesKeepTheirExactIdentity() {
        var options =
                new DataCenter.FieldOptions(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "ACTIVE",
                        List.of(
                                new DataCenter.Option("1", "无记录", false),
                                new DataCenter.Option("001", "有记录", false)),
                        null,
                        null,
                        null,
                        null,
                        false,
                        false);
        target =
                object(
                        "编码目标",
                        List.of(f("name", "TEXT"), f("state", "SELECT"), f("latest", "TEXT")),
                        List.of(),
                        DataCenter.Settings.defaults(),
                        Map.of("state", options));
        source =
                object(
                        "编码来源",
                        List.of(f("name", "TEXT")),
                        List.of(
                                new DataCenter.Relation(
                                        null,
                                        "code_target",
                                        "目标",
                                        "REFERENCE",
                                        target.objectId(),
                                        null,
                                        null,
                                        false,
                                        "SET_NULL",
                                        null)));
        relation = source.relations().getFirst().id();
        app =
                application(
                        List.of(
                                rule(
                                        "numeric_choice",
                                        "MAINTAIN",
                                        Set.of("CREATE", "UPDATE", "DELETE"),
                                        null,
                                        "OUTGOING",
                                        List.of(assignment("state", "EXISTS", null, "001", "1"))),
                                rule(
                                        "numeric_text",
                                        "EVENT",
                                        Set.of("CREATE", "UPDATE"),
                                        null,
                                        "OUTGOING",
                                        List.of(
                                                assignment(
                                                        "latest", "FIELD", "name", null, null)))));
        var parent = createTarget("编码测试");
        assertThat(target(parent.id()).values().get(field(target, "state"))).isEqualTo("1");
        var input = data(source, "name", "001");
        input.put(source.relations().getFirst().fieldId(), parent.id());
        var row =
                runtime.save(new Save(app, source.objectId(), null, null, input, null), 10001)
                        .record();
        assertThat(target(parent.id()).values().get(field(target, "state"))).isEqualTo("001");
        assertThat(target(parent.id()).values().get(field(target, "latest"))).isEqualTo("001");
        row = update(source, row, data(source, "name", "1"));
        assertThat(target(parent.id()).values().get(field(target, "latest"))).isEqualTo("1");
        delete(row);
        assertThat(target(parent.id()).values().get(field(target, "state"))).isEqualTo("1");
    }

    @Test
    void controlledLifecycleStateCannotBeAnAutomationTarget() {
        var options =
                new DataCenter.FieldOptions(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "ACTIVE",
                        List.of(
                                new DataCenter.Option("draft", "草稿", false),
                                new DataCenter.Option("done", "完成", false)),
                        null,
                        null,
                        null,
                        null,
                        false,
                        false);
        var lifecycle =
                new DocumentPolicy.Lifecycle(
                        "state",
                        "draft",
                        List.of(
                                new DocumentPolicy.State("draft", "草稿", List.of(), List.of(), true),
                                new DocumentPolicy.State("done", "完成", List.of(), List.of(), true)),
                        List.of(
                                new DocumentPolicy.Action(
                                        "complete", "完成", List.of("draft"), "done", "UPDATE")));
        target =
                object(
                        "生命周期单据",
                        List.of(f("name", "TEXT"), f("state", "SELECT")),
                        List.of(),
                        new DataCenter.Settings(
                                null, null, null, null, new DocumentPolicy(List.of(), lifecycle)),
                        Map.of("state", options));
        source =
                object(
                        "事件来源",
                        List.of(f("name", "TEXT")),
                        List.of(
                                new DataCenter.Relation(
                                        null,
                                        "document_ref",
                                        "单据",
                                        "REFERENCE",
                                        target.objectId(),
                                        null,
                                        null,
                                        false,
                                        "SET_NULL",
                                        null)));
        relation = source.relations().getFirst().id();
        assertThatThrownBy(
                        () ->
                                application(
                                        List.of(
                                                rule(
                                                        "state_write",
                                                        "EVENT",
                                                        Set.of("CREATE"),
                                                        null,
                                                        "OUTGOING",
                                                        List.of(
                                                                assignment(
                                                                        "state", "VALUE", null,
                                                                        "done", null))))))
                .hasMessageContaining("生命周期状态字段");
    }
}
