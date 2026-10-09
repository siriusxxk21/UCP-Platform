package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.richuang.os.framework.common.biz.system.permission.PermissionCommonApi;
import com.richuang.os.nocode.api.TaskEfficiency.*;
import com.richuang.os.nocode.runtime.dal.mapper.TaskEfficiencyMapper;
import com.richuang.os.nocode.runtime.service.taskcenter.TaskEfficiencyServiceImpl;

import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.*;

/** 真实开发库SQL统计回归：只清理本类UUID任务，覆盖标准规则、实例调整、范围及分页。 */
class TaskEfficiencyIntegrationTest {
    private static final long BOSS = 901101L;
    private static final long OTHER_BOSS = 901102L;
    private static final long EMPLOYEE = 901103L;
    private static final long COLLEAGUE = 901104L;
    private TaskEfficiencyServiceImpl service;
    private PermissionCommonApi permissions;
    private final List<String> roots = new ArrayList<>();
    private final List<String> approvals = new ArrayList<>();

    @BeforeAll
    static void open() throws Exception {
        connect();
    }

    @AfterAll
    static void closeContext() {
        close();
    }

    @BeforeEach
    void setup() {
        service = new TaskEfficiencyServiceImpl();
        permissions = mock(PermissionCommonApi.class);
        when(permissions.hasAnyPermissions(BOSS, "nocode:task:create")).thenReturn(true);
        when(permissions.hasAnyPermissions(OTHER_BOSS, "nocode:task:create")).thenReturn(true);
        ReflectionTestUtils.setField(
                service, "store", session.getMapper(TaskEfficiencyMapper.class));
        ReflectionTestUtils.setField(service, "permissions", permissions);
    }

    @AfterEach
    void cleanup() {
        for (String root : roots) {
            jdbc.update(
                    "DELETE FROM public.nocode_task_entry_record WHERE task_id IN (SELECT id FROM"
                            + " public.nocode_task_instance WHERE root_id=?)",
                    root);
            jdbc.update(
                    "DELETE FROM public.nocode_task_entry_binding WHERE task_id IN (SELECT id FROM"
                            + " public.nocode_task_instance WHERE root_id=?)",
                    root);
            jdbc.update("DELETE FROM public.nocode_task_instance WHERE root_id=?", root);
        }
        for (String id : approvals) {
            jdbc.update("DELETE FROM public.nocode_handling_request WHERE id=CAST(? AS uuid)", id);
            jdbc.update("DELETE FROM public.nocode_work_submission WHERE id=?", id);
            jdbc.update("DELETE FROM public.nocode_work_draft WHERE id=?", id);
        }
    }

    private String task(String root, long owner, long employee) {
        String id = UUID.randomUUID().toString();
        if (root == null) roots.add(id);
        jdbc.update(
                "INSERT INTO"
                    + " public.nocode_task_instance(id,root_id,parent_id,title,assignee_id,status,config_json,t0,creator,updater,create_time)"
                    + " VALUES(?,?,?,?,?,'RUNNING',?,timestamp '2026-10-01 09:00',?,?,timestamp"
                    + " '2026-10-01 09:00')",
                id,
                root == null ? id : root,
                root,
                "能效统计专属夹具",
                employee,
                "{\"effectiveWorkMinutes\":120}",
                Long.toString(owner),
                Long.toString(owner));
        return id;
    }

    private void binding(String task, String key, String dataset, String rule) {
        jdbc.update(
                "INSERT INTO"
                    + " public.nocode_task_entry_binding(id,task_id,entry_key,dataset_id,config_json,business_json,creator,updater)"
                    + " VALUES(?,?,?,?,?,'{}',?,?)",
                UUID.randomUUID().toString(),
                task,
                key,
                dataset,
                "{\"name\":\"房间办理\",\"workRule\":" + rule + "}",
                Long.toString(BOSS),
                Long.toString(BOSS));
    }

    private String fact(
            String task,
            String key,
            String dataset,
            long employee,
            String record,
            String operation,
            String values,
            String time) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO"
                    + " public.nocode_task_entry_record(id,task_id,entry_key,dataset_id,business_json,operation,snapshot_json,request_key,request_hash,creator,updater,create_time)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,CAST(? AS timestamp))",
                id,
                task,
                key,
                dataset,
                "{\"recordId\":\"" + record + "\"}",
                operation,
                "{\"record\":{\"values\":" + values + "}}",
                id,
                "fixture",
                Long.toString(employee),
                Long.toString(employee),
                time);
        return id;
    }

    private Query query(String root) {
        return new Query(
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 7),
                null,
                null,
                root,
                1,
                20,
                null,
                null,
                null);
    }

    @Test
    void deniedEmployeeCannotDiscoverOtherPeopleOrFilters() {
        assertThatThrownBy(() -> service.overview(query(null), EMPLOYEE))
                .hasMessageContaining("权限");
        assertThatThrownBy(() -> service.records(query(null), EMPLOYEE)).hasMessageContaining("权限");
        assertThatThrownBy(() -> service.options(query(null), EMPLOYEE)).hasMessageContaining("权限");
    }

    private void pricedFact(
            String root, String record, String mode, int rate, String values, int day) {
        String id =
                fact(
                        root,
                        "room",
                        root,
                        EMPLOYEE,
                        record,
                        "UPDATED",
                        values,
                        "2026-10-0" + day + " 10:00");
        jdbc.update(
                "UPDATE public.nocode_task_entry_record SET work_rule_json=? WHERE id=?",
                "{\"mode\":\""
                        + mode
                        + "\",\"minutes\":"
                        + rate
                        + ",\"quantityFieldId\":\"q\",\"conditionFieldId\":\"state\",\"conditionValue\":\"done\"}",
                id);
    }

    @Test
    void mixedQuantityRatesUseSegmentsAndReturnRealQuantityWithoutInventingSingleRate() {
        String root = task(null, BOSS, EMPLOYEE);
        binding(
                root,
                "room",
                root,
                "{\"mode\":\"QUANTITY\",\"minutes\":99,\"quantityFieldId\":\"q\"}");
        pricedFact(root, "r1", "QUANTITY", 10, "{\"q\":5}", 2);
        pricedFact(root, "r1", "QUANTITY", 20, "{\"q\":8}", 3);
        pricedFact(root, "r1", "QUANTITY", 99, "{\"q\":6}", 4);
        pricedFact(root, "r1", "QUANTITY", 30, "{\"q\":7}", 5);
        assertThat(service.overview(query(root), BOSS).standardMinutes())
                .isEqualByComparingTo("100");
        assertThat(service.records(query(root), BOSS).getList())
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.quantity()).isEqualByComparingTo("7");
                            assertThat(row.unitMinutes()).isNull();
                            assertThat(row.standardMinutes()).isEqualByComparingTo("100");
                        });
        fact(root, "room", root, COLLEAGUE, "r1", "DELETED", "{}", "2026-10-06 10:00");
        assertThat(service.overview(query(root), BOSS).standardMinutes()).isZero();
    }

    @Test
    void firstEffectiveRateSurvivesLaterMoreExpensiveSavesAndCurrentRuleChange() {
        for (String mode : List.of("RECORD_ONCE", "CONDITION")) {
            String root = task(null, BOSS, EMPLOYEE);
            binding(
                    root,
                    "room",
                    root,
                    "{\"mode\":\""
                            + mode
                            + "\",\"minutes\":99,\"conditionFieldId\":\"state\",\"conditionValue\":\"done\"}");
            pricedFact(root, "r1", mode, 20, "{\"state\":\"done\"}", 2);
            pricedFact(root, "r1", mode, 60, "{\"state\":\"done\"}", 3);
            pricedFact(root, "r2", mode, 60, "{\"state\":\"done\"}", 4);
            assertThat(service.overview(query(root), BOSS).standardMinutes())
                    .isEqualByComparingTo("80");
        }
    }

    @Test
    void stoppingCountingKeepsHistoricalCreditsAndQuantityBaselineWithoutBackfill() {
        for (String mode : List.of("RECORD_ONCE", "CONDITION", "QUANTITY")) {
            String root = task(null, BOSS, EMPLOYEE);
            binding(
                    root,
                    "room",
                    root,
                    "{\"mode\":\""
                            + mode
                            + "\",\"minutes\":0,\"quantityFieldId\":\"q\",\"conditionFieldId\":\"state\",\"conditionValue\":\"done\"}");
            pricedFact(root, "r1", mode, 10, "{\"q\":5,\"state\":\"done\"}", 2);
            pricedFact(root, "r1", mode, 0, "{\"q\":8,\"state\":\"done\"}", 3);
            assertThat(service.overview(query(root), BOSS).standardMinutes())
                    .isEqualByComparingTo(mode.equals("QUANTITY") ? "50" : "10");
            pricedFact(root, "r1", mode, 20, "{\"q\":9,\"state\":\"done\"}", 4);
            assertThat(service.overview(query(root), BOSS).standardMinutes())
                    .isEqualByComparingTo(mode.equals("QUANTITY") ? "70" : "10");
            if (mode.equals("QUANTITY"))
                assertThat(service.records(query(root), BOSS).getList())
                        .singleElement()
                        .satisfies(row -> assertThat(row.quantity()).isEqualByComparingTo("6"));
        }
    }

    @Test
    void adjustedMinutesDeduplicateByNodeEntryPersonAndRecordWithoutLeakingOtherOwners() {
        String root = task(null, BOSS, EMPLOYEE),
                child = task(root, BOSS, EMPLOYEE),
                hidden = task(null, OTHER_BOSS, EMPLOYEE);
        String rule = "{\"mode\":\"RECORD_ONCE\",\"minutes\":15,\"adjustmentMinutes\":5}";
        binding(root, "room", root, rule);
        binding(child, "room", root, rule);
        binding(hidden, "room", hidden, rule);
        fact(root, "room", root, EMPLOYEE, "r1", "CREATED", "{}", "2026-10-02 10:00");
        fact(root, "room", root, EMPLOYEE, "r1", "UPDATED", "{}", "2026-10-03 10:00");
        fact(root, "room", root, COLLEAGUE, "r1", "UPDATED", "{}", "2026-10-03 10:00");
        fact(child, "room", root, EMPLOYEE, "r1", "UPDATED", "{}", "2026-10-04 10:00");
        fact(hidden, "room", hidden, EMPLOYEE, "secret", "CREATED", "{}", "2026-10-02 10:00");
        Overview result = service.overview(query(root), BOSS);
        assertThat(result.standardMinutes()).isEqualByComparingTo("60");
        assertThat(result.recordCount()).isEqualTo(3);
        assertThat(result.employeeCount()).isEqualTo(2);
        assertThat(result.trend()).hasSize(7);
        assertThat(result.trend().get(1).standardMinutes()).isEqualByComparingTo("20");
        assertThat(service.records(query(root), BOSS).getTotal()).isEqualTo(3);
        assertThat(service.records(query(root), BOSS).getList())
                .allSatisfy(row -> assertThat(row.unitMinutes()).isEqualByComparingTo("20"));
        assertThat(service.overview(query(hidden), BOSS).standardMinutes()).isZero();
        assertThat(service.tasks(query(root), BOSS).getList())
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.referenceMinutes()).isEqualTo(120);
                            assertThat(row.totalNodeCount()).isEqualTo(1);
                            assertThat(row.standardMinutes()).isEqualByComparingTo("60");
                        });
        when(permissions.hasAnyPermissions(BOSS, "nocode:task:manage-all")).thenReturn(true);
        assertThat(service.overview(query(hidden), BOSS).standardMinutes())
                .isEqualByComparingTo("20");
    }

    @Test
    void quantityUsesLatestValueConditionCountsOnceAndDeletionReversesAllSharedContributions() {
        String root = task(null, BOSS, EMPLOYEE);
        binding(
                root,
                "qty",
                root + "q",
                "{\"mode\":\"QUANTITY\",\"minutes\":5,\"quantityFieldId\":\"qty\"}");
        binding(
                root,
                "condition",
                root + "c",
                "{\"mode\":\"CONDITION\",\"minutes\":30,\"conditionFieldId\":\"state\",\"conditionValue\":\"done\"}");
        fact(root, "qty", root + "q", EMPLOYEE, "q1", "CREATED", "{\"qty\":7}", "2026-10-01 10:00");
        fact(root, "qty", root + "q", EMPLOYEE, "q1", "UPDATED", "{\"qty\":2}", "2026-10-03 10:00");
        fact(
                root,
                "qty",
                root + "q",
                COLLEAGUE,
                "q1",
                "UPDATED",
                "{\"qty\":1}",
                "2026-10-04 10:00");
        fact(
                root,
                "condition",
                root + "c",
                EMPLOYEE,
                "c1",
                "CREATED",
                "{\"state\":\"pending\"}",
                "2026-10-01 10:00");
        fact(
                root,
                "condition",
                root + "c",
                EMPLOYEE,
                "c1",
                "UPDATED",
                "{\"state\":\"done\"}",
                "2026-10-02 10:00");
        fact(
                root,
                "condition",
                root + "c",
                EMPLOYEE,
                "c1",
                "UPDATED",
                "{\"state\":\"later\"}",
                "2026-10-03 10:00");
        assertThat(service.overview(query(root), BOSS).standardMinutes())
                .isEqualByComparingTo("45");
        assertThat(service.records(query(root), BOSS).getList())
                .filteredOn(row -> row.recordId().equals("q1") && row.employeeId() == EMPLOYEE)
                .singleElement()
                .satisfies(row -> assertThat(row.quantity()).isEqualByComparingTo("2"));
        fact(root, "qty", root + "q", COLLEAGUE, "q1", "DELETED", "{}", "2026-10-05 10:00");
        assertThat(service.overview(query(root), BOSS).standardMinutes())
                .isEqualByComparingTo("30");
    }

    @Test
    void periodBelongsToFirstEffectiveMeasureNotRepeatSaveAndIgnoresNonContributions() {
        String root = task(null, BOSS, EMPLOYEE);
        binding(root, "room", root, "{\"mode\":\"RECORD_ONCE\",\"minutes\":15}");
        fact(root, "room", root, EMPLOYEE, "r1", "CREATED", "{}", "2026-10-01 10:00");
        fact(root, "room", root, EMPLOYEE, "r1", "UPDATED", "{}", "2026-10-03 10:00");
        fact(root, "room", root, EMPLOYEE, "linked", "LINKED", "{}", "2026-10-03 10:00");
        fact(root, "room", root, EMPLOYEE, "unchanged", "UNCHANGED", "{}", "2026-10-03 10:00");
        String superseded =
                fact(
                        root,
                        "room",
                        root,
                        EMPLOYEE,
                        "superseded",
                        "CREATED",
                        "{}",
                        "2026-10-03 10:00");
        jdbc.update(
                "UPDATE public.nocode_task_entry_record SET superseded_by='next' WHERE id=?",
                superseded);
        String approval =
                fact(
                        root,
                        "room",
                        root,
                        EMPLOYEE,
                        "unapproved",
                        "CREATED",
                        "{}",
                        "2026-10-03 10:00");
        jdbc.update(
                "UPDATE public.nocode_task_entry_record SET business_json=? WHERE id=?",
                "{\"recordId\":\"unapproved\",\"requestId\":\"missing\"}",
                approval);
        Query third =
                new Query(
                        LocalDate.of(2026, 10, 3),
                        LocalDate.of(2026, 10, 3),
                        null,
                        null,
                        root,
                        1,
                        20,
                        null,
                        null,
                        null);
        assertThat(service.overview(third, BOSS).standardMinutes()).isZero();
        assertThat(service.overview(query(root), BOSS).standardMinutes())
                .isEqualByComparingTo("15");
    }

    @Test
    void employeesAndTaskProgressUseLeafNodesAndPaginationStaysServerSide() {
        String root = task(null, BOSS, EMPLOYEE), child = task(root, BOSS, COLLEAGUE);
        binding(child, "room", root, "{\"mode\":\"RECORD_ONCE\",\"minutes\":15}");
        fact(child, "room", root, COLLEAGUE, "r1", "CREATED", "{}", "2026-10-02 10:00");
        jdbc.update(
                "UPDATE public.nocode_task_instance SET expected_end=timestamp '2026-01-01 10:00'"
                        + " WHERE id=?",
                child);
        assertThat(service.overview(query(root), BOSS).activeNodeCount()).isEqualTo(1);
        assertThat(service.overview(query(root), BOSS).overdueNodeCount()).isEqualTo(1);
        assertThat(service.employees(query(root), BOSS).getList())
                .singleElement()
                .satisfies(row -> assertThat(row.employeeId()).isEqualTo(COLLEAGUE));
        jdbc.update(
                "UPDATE public.nocode_task_instance SET status='COMPLETED',actual_start=timestamp"
                    + " '2026-10-02 09:00',actual_end=timestamp '2026-10-03 10:00' WHERE root_id=?",
                root);
        assertThat(service.overview(query(root), BOSS).completedNodeCount()).isEqualTo(1);
        assertThat(service.tasks(query(root), BOSS).getList())
                .singleElement()
                .satisfies(row -> assertThat(row.elapsedMinutes()).isEqualByComparingTo("1500"));
        Query second =
                new Query(
                        query(root).from(),
                        query(root).to(),
                        null,
                        null,
                        root,
                        2,
                        1,
                        null,
                        "recordCount",
                        true);
        assertThat(service.records(second, BOSS).getTotal()).isEqualTo(1);
        assertThat(service.records(second, BOSS).getList()).isEmpty();
        assertThat(service.options(query(root), BOSS).employees())
                .extracting(EmployeeOption::id)
                .containsExactlyInAnyOrder(EMPLOYEE, COLLEAGUE);
    }

    @Test
    void approvedFactsBackfillTheirOriginalEffectiveSubmissionDayAndRespectIdentity() {
        String root = task(null, BOSS, EMPLOYEE);
        binding(root, "room", root, "{\"mode\":\"RECORD_ONCE\",\"minutes\":15}");
        String request = UUID.randomUUID().toString();
        approvals.add(request);
        jdbc.update(
                "INSERT INTO"
                    + " public.nocode_work_draft(id,source_type,source_id,resource_json,object_id,values_json,creator,updater)"
                    + " VALUES(?,'TASK_ENTRY',?,'{}','902','{}',?,?)",
                request,
                root,
                Long.toString(EMPLOYEE),
                Long.toString(EMPLOYEE));
        jdbc.update(
                "INSERT INTO"
                    + " public.nocode_work_submission(id,draft_id,idempotency_key,request_digest,material_json,creator,updater)"
                    + " VALUES(?,?,?,'fixture','{}',?,?)",
                request,
                request,
                request,
                Long.toString(EMPLOYEE),
                Long.toString(EMPLOYEE));
        jdbc.update(
                "INSERT INTO"
                    + " public.nocode_handling_request(id,application_id,application_name,application_version,object_id,object_name,record_id,operation,name,request_key,request_digest,definition_checksum,definition_json,submission_id,process_definition_id,process_definition_key,status,creator,updater)"
                    + " VALUES(CAST(? AS"
                    + " uuid),901,'fixture',1,902,'fixture','approved-record','CREATE','fixture',?,'fixture','fixture','{}',?,'fixture','fixture','PENDING',?,?)",
                request,
                request,
                request,
                Long.toString(EMPLOYEE),
                Long.toString(EMPLOYEE));
        String contribution =
                fact(
                        root,
                        "room",
                        root,
                        EMPLOYEE,
                        "approved-record",
                        "CREATED",
                        "{}",
                        "2026-10-01 10:00");
        jdbc.update(
                "UPDATE public.nocode_task_entry_record SET business_json=? WHERE id=?",
                "{\"requestId\":\""
                        + request
                        + "\",\"resource\":{\"applicationId\":\"901\",\"applicationVersion\":1},\"object\":{\"objectId\":\"902\"}}",
                contribution);
        assertThat(service.overview(query(root), BOSS).standardMinutes()).isZero();
        jdbc.update(
                "UPDATE public.nocode_handling_request SET status='APPROVED',update_time=timestamp"
                        + " '2026-10-03 10:00' WHERE id=CAST(? AS uuid)",
                request);
        assertThat(service.overview(query(root), BOSS).standardMinutes())
                .isEqualByComparingTo("15");
        assertThat(service.records(query(root), BOSS).getList())
                .singleElement()
                .satisfies(
                        row ->
                                assertThat(row.firstCountedAt().toLocalDate())
                                        .isEqualTo(LocalDate.of(2026, 10, 1)));
        jdbc.update(
                "UPDATE public.nocode_handling_request SET creator=? WHERE id=CAST(? AS uuid)",
                Long.toString(COLLEAGUE),
                request);
        assertThat(service.overview(query(root), BOSS).standardMinutes()).isZero();
    }

    @Test
    void employeeAndTemplateFiltersApplyToFiguresAndDetails() {
        String root = task(null, BOSS, EMPLOYEE);
        String template = UUID.randomUUID().toString();
        jdbc.update(
                "UPDATE public.nocode_task_instance SET template_id=?,template_version=1 WHERE"
                        + " id=?",
                template,
                root);
        binding(root, "room", root, "{\"mode\":\"RECORD_ONCE\",\"minutes\":15}");
        fact(root, "room", root, EMPLOYEE, "r1", "CREATED", "{}", "2026-10-02 10:00");
        fact(root, "room", root, COLLEAGUE, "r1", "UPDATED", "{}", "2026-10-03 10:00");
        Query filtered =
                new Query(
                        query(root).from(),
                        query(root).to(),
                        COLLEAGUE,
                        template,
                        root,
                        1,
                        20,
                        null,
                        null,
                        null);
        assertThat(service.overview(filtered, BOSS).standardMinutes()).isEqualByComparingTo("15");
        assertThat(service.records(filtered, BOSS).getList())
                .singleElement()
                .satisfies(row -> assertThat(row.employeeId()).isEqualTo(COLLEAGUE));
        assertThat(service.tasks(filtered, BOSS).getList())
                .singleElement()
                .satisfies(row -> assertThat(row.templateId()).isEqualTo(template));
        Query hidden =
                new Query(
                        query(root).from(),
                        query(root).to(),
                        COLLEAGUE,
                        "missing",
                        root,
                        1,
                        20,
                        null,
                        null,
                        null);
        assertThat(service.overview(hidden, BOSS).standardMinutes()).isZero();
        assertThat(service.tasks(hidden, BOSS).getTotal()).isZero();
    }

    @Test
    void employeeFilterKeepsWholeGroupProgressButDoesNotIncludeUnrelatedGroups() {
        String root = task(null, BOSS, EMPLOYEE);
        String completed = task(root, BOSS, EMPLOYEE);
        String overdue = task(root, BOSS, COLLEAGUE);
        jdbc.update(
                "UPDATE public.nocode_task_instance SET status='COMPLETED',actual_end=timestamp"
                        + " '2026-10-03 10:00' WHERE id=?",
                completed);
        jdbc.update(
                "UPDATE public.nocode_task_instance SET expected_end=timestamp '2026-01-01 10:00'"
                        + " WHERE id=?",
                overdue);
        Query employee =
                new Query(
                        query(root).from(),
                        query(root).to(),
                        EMPLOYEE,
                        null,
                        root,
                        1,
                        20,
                        null,
                        null,
                        null);
        assertThat(service.overview(employee, BOSS).completedNodeCount()).isEqualTo(1);
        assertThat(service.overview(employee, BOSS).activeNodeCount()).isZero();
        assertThat(service.tasks(employee, BOSS).getList())
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.totalNodeCount()).isEqualTo(2);
                            assertThat(row.completedNodeCount()).isEqualTo(1);
                            assertThat(row.overdueNodeCount()).isEqualTo(1);
                        });
        Query unrelated =
                new Query(
                        query(root).from(),
                        query(root).to(),
                        OTHER_BOSS,
                        null,
                        root,
                        1,
                        20,
                        null,
                        null,
                        null);
        assertThat(service.tasks(unrelated, BOSS).getList()).isEmpty();
        assertThat(service.tasks(unrelated, BOSS).getTotal()).isZero();
    }

    @Test
    void selectedTemplateDoesNotHideOtherManageableTemplateOptions() {
        String first = task(null, BOSS, EMPLOYEE);
        String second = task(null, BOSS, COLLEAGUE);
        String hidden = task(null, OTHER_BOSS, COLLEAGUE);
        String firstTemplate = UUID.randomUUID().toString();
        String secondTemplate = UUID.randomUUID().toString();
        String hiddenTemplate = UUID.randomUUID().toString();
        jdbc.update(
                "UPDATE public.nocode_task_instance SET template_id=? WHERE id=?",
                firstTemplate,
                first);
        jdbc.update(
                "UPDATE public.nocode_task_instance SET template_id=? WHERE id=?",
                secondTemplate,
                second);
        jdbc.update(
                "UPDATE public.nocode_task_instance SET template_id=? WHERE id=?",
                hiddenTemplate,
                hidden);
        Query selected =
                new Query(
                        query(first).from(),
                        query(first).to(),
                        null,
                        firstTemplate,
                        null,
                        1,
                        20,
                        null,
                        null,
                        null);
        Options options = service.options(selected, BOSS);
        assertThat(options.templates())
                .extracting(TemplateOption::id)
                .contains(firstTemplate, secondTemplate)
                .doesNotContain(hiddenTemplate);
        assertThat(options.employees()).extracting(EmployeeOption::id).containsExactly(EMPLOYEE);
    }

    @Test
    void cancelledLeafNodesStaySeparateFromNormalCompletionAfterGroupDelivery() {
        String root = task(null, BOSS, EMPLOYEE);
        String completed = task(root, BOSS, EMPLOYEE);
        String cancelled = task(root, BOSS, COLLEAGUE);
        jdbc.update(
                "UPDATE public.nocode_task_instance SET status='COMPLETED',actual_end=timestamp"
                        + " '2026-10-03 10:00' WHERE id IN (?,?)",
                root,
                completed);
        jdbc.update(
                "UPDATE public.nocode_task_instance SET status='CANCELLED',actual_end=timestamp"
                        + " '2026-10-02 10:00' WHERE id=?",
                cancelled);
        assertThat(service.tasks(query(root), BOSS).getList())
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.status()).isEqualTo("COMPLETED");
                            assertThat(row.totalNodeCount()).isEqualTo(2);
                            assertThat(row.completedNodeCount()).isEqualTo(1);
                            assertThat(row.cancelledNodeCount()).isEqualTo(1);
                        });
    }

    @Test
    void recordSearchIncludesEmployeeAndRootTitleWithMatchingPageTotal() {
        String root = task(null, BOSS, EMPLOYEE);
        String child = task(root, BOSS, EMPLOYEE);
        String rootTitle = "总任务专用搜索词-" + UUID.randomUUID();
        jdbc.update("UPDATE public.nocode_task_instance SET title=? WHERE id=?", rootTitle, root);
        jdbc.update("UPDATE public.nocode_task_instance SET title='独立子项标题' WHERE id=?", child);
        binding(child, "room", root, "{\"mode\":\"RECORD_ONCE\",\"minutes\":15}");
        fact(child, "room", root, EMPLOYEE, "r1", "CREATED", "{}", "2026-10-02 10:00");
        fact(child, "room", root, EMPLOYEE, "r2", "CREATED", "{}", "2026-10-03 10:00");
        fact(child, "room", root, COLLEAGUE, "r3", "CREATED", "{}", "2026-10-03 10:00");
        String employeeName =
                service.options(query(root), BOSS).employees().stream()
                        .filter(option -> option.id() == EMPLOYEE)
                        .findFirst()
                        .orElseThrow()
                        .name();
        Query employeeSearch =
                new Query(
                        query(root).from(),
                        query(root).to(),
                        null,
                        null,
                        root,
                        1,
                        1,
                        employeeName,
                        null,
                        null);
        assertThat(service.records(employeeSearch, BOSS).getTotal()).isEqualTo(2);
        assertThat(service.records(employeeSearch, BOSS).getList())
                .singleElement()
                .satisfies(row -> assertThat(row.employeeId()).isEqualTo(EMPLOYEE));
        Query rootSearch =
                new Query(
                        query(root).from(),
                        query(root).to(),
                        null,
                        null,
                        root,
                        1,
                        20,
                        rootTitle,
                        null,
                        null);
        assertThat(service.records(rootSearch, BOSS).getTotal()).isEqualTo(3);
        assertThat(service.records(rootSearch, BOSS).getList()).hasSize(3);
    }

    @Test
    void rejectsUnboundedAndUntrustedQueries() {
        assertThatThrownBy(
                        () ->
                                service.overview(
                                        new Query(
                                                LocalDate.of(2024, 1, 1),
                                                LocalDate.of(2026, 1, 1),
                                                null,
                                                null,
                                                null,
                                                1,
                                                20,
                                                null,
                                                null,
                                                null),
                                        BOSS))
                .hasMessageContaining("366");
        assertThatThrownBy(
                        () ->
                                service.tasks(
                                        new Query(
                                                null, null, null, null, null, 1, 1000, null, null,
                                                null),
                                        BOSS))
                .hasMessageContaining("分页");
        assertThatThrownBy(
                        () ->
                                service.employees(
                                        new Query(
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                1,
                                                20,
                                                null,
                                                "standardMinutes;drop",
                                                true),
                                        BOSS))
                .hasMessageContaining("排序");
    }
}
