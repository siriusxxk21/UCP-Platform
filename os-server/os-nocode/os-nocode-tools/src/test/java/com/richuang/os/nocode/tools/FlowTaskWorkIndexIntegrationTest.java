package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.module.bpm.api.task.BpmBusinessTaskBinding;
import com.richuang.os.nocode.api.ApplicationAuthorization;
import com.richuang.os.nocode.api.work.*;
import com.richuang.os.nocode.api.workflow.*;
import com.richuang.os.nocode.application.service.authorization.ApplicationAuthorizationService;
import com.richuang.os.nocode.workflow.service.task.*;

import org.flowable.task.api.Task;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.*;

/** 复用真实发布／Flowable 夹具但不继承测试，独立验证索引授权、分页及无写入契约。 */
class FlowTaskWorkIndexIntegrationTest {
    static AnnotationConfigApplicationContext queryContext;
    static FlowTaskWorkQueryService index;
    FlowTaskIntegrationTest fixture;
    WorkDraftIntegrationTest business;

    @BeforeAll
    static void open() throws Exception {
        FlowTaskIntegrationTest.open();
        queryContext = new AnnotationConfigApplicationContext();
        queryContext.setParent(FlowTaskIntegrationTest.flowContext);
        queryContext.register(FlowTaskWorkQueryServiceImpl.class);
        queryContext.refresh();
        index = queryContext.getBean(FlowTaskWorkQueryService.class);
    }

    @AfterAll
    static void shutdown() {
        if (queryContext != null) queryContext.close();
        FlowTaskIntegrationTest.shutdown();
    }

    @BeforeEach
    void setup() {
        fixture = new FlowTaskIntegrationTest();
        fixture.setup();
        business = fixture.business;
    }

    @AfterEach
    void cleanup() {
        fixture.clean();
    }

    @Test
    void listsOnlyOwnedFlowSummariesWithoutCreatingDraftsBindingsOrBusinessRows() throws Exception {
        var task = start(10001);
        var draft = draft(task, 10001, "不可输出的业务输入");
        var unopened = start(10001);
        var personal = personal("个人表单草稿");
        var before = storedCounts();

        var items = items("DRAFT", 10001);
        assertThat(items).hasSize(1);
        var item = items.getFirst();
        assertThat(item.draftId()).isEqualTo(draft.id());
        assertThat(item.taskId()).isEqualTo(task.getId());
        assertThat(item.processInstanceId()).isEqualTo(task.getProcessInstanceId());
        assertThat(item.nodeId()).isEqualTo("work");
        assertThat(item.state()).isEqualTo("DRAFT");
        assertThat(item.formName()).isEqualTo("工作表单");
        assertThat(item.objectName()).isEqualTo(business.object.objectName());
        assertThat(item.applicationVersion()).isEqualTo(1);
        assertThat(item.writable()).isTrue();
        assertThat(item.submissionId()).isNull();
        assertThat(item.blockedReason()).isNull();
        assertThat(item.updatedAt()).isNotNull();
        assertThat(mapper.writeValueAsString(items))
                .doesNotContain("不可输出的业务输入", "values", personal.id(), unopened.getId());
        assertThat(items("DRAFT", 20002)).isEmpty();
        assertThat(storedCounts()).isEqualTo(before);
        assertThat(business.recordCount()).isZero();
    }

    @Test
    void rechecksTransferredCancelledAndSuspendedTasksAndRestoresAfterResume() throws Exception {
        business.grantMember(Set.of("READ", "CREATE"), Set.of(business.nameField));
        var moved = start(10001);
        draft(moved, 10001, "转办前输入");
        var cancelled = start(10001);
        draft(cancelled, 10001, "取消前输入");
        var suspended = start(10001);
        var suspendedDraft = draft(suspended, 10001, "挂起前输入");
        assertThat(items("DRAFT", 10001)).hasSize(3);

        var engine = FlowTaskIntegrationTest.engine;
        engine.getTaskService().setAssignee(moved.getId(), "20002");
        engine.getRuntimeService()
                .deleteProcessInstance(cancelled.getProcessInstanceId(), "索引回归取消");
        engine.getRuntimeService().suspendProcessInstanceById(suspended.getProcessInstanceId());
        assertThat(items("DRAFT", 10001)).isEmpty();
        assertThat(items("DRAFT", 20002)).isEmpty();
        var replacement = draft(moved, 20002, "接手人的独立输入");
        assertThat(items("DRAFT", 20002))
                .extracting(FlowTaskWorkViews.Item::draftId)
                .containsExactly(replacement.id());

        engine.getRuntimeService().activateProcessInstanceById(suspended.getProcessInstanceId());
        assertThat(items("DRAFT", 10001))
                .extracting(FlowTaskWorkViews.Item::draftId)
                .containsExactly(suspendedDraft.id());
    }

    @Test
    void writeRevocationReturnsReadOnlyAndReadRevocationHidesNames() throws Exception {
        business.grantMember(Set.of("READ", "CREATE"), Set.of(business.nameField));
        var task = start(20002);
        var draft = draft(task, 20002, "实时授权草稿");
        assertThat(items("DRAFT", 20002).getFirst().writable()).isTrue();

        business.grantMember(Set.of("READ"), Set.of(business.nameField));
        var item = items("DRAFT", 20002).getFirst();
        assertThat(item.draftId()).isEqualTo(draft.id());
        assertThat(item.writable()).isFalse();
        assertThat(item.blockedReason()).isNotBlank();

        revokeMember();
        assertThat(items("DRAFT", 20002)).isEmpty();
    }

    @Test
    void submittedIndexIsRestrictedToOriginalSubmitterAndCurrentMaterialReadPermission()
            throws Exception {
        business.grantMember(Set.of("READ", "CREATE"), Set.of(business.nameField));
        var task = start(10001);
        var original = draft(task, 10001, "前任草稿内容");
        FlowTaskIntegrationTest.engine.getTaskService().setAssignee(task.getId(), "20002");
        var actual = draft(task, 20002, "正式材料字段值");
        var material =
                FlowTaskIntegrationTest.flows.submit(
                        new FlowTasks.Submit(
                                task.getId(),
                                actual.id(),
                                actual.revision(),
                                UUID.randomUUID().toString(),
                                "索引材料回归"),
                        20002);
        var personal = personal("个人材料");
        business.work.submit(
                new WorkDrafts.Submit(
                        personal.id(), personal.revision(), UUID.randomUUID().toString()),
                10001);

        assertThat(items("DRAFT", 10001)).isEmpty();
        assertThat(items("DRAFT", 20002)).isEmpty();
        assertThat(items("SUBMITTED", 10001)).isEmpty();
        var items = items("SUBMITTED", 20002);
        assertThat(items).hasSize(1);
        var item = items.getFirst();
        assertThat(item.draftId()).isEqualTo(actual.id()).isNotEqualTo(original.id());
        assertThat(item.submissionId()).isEqualTo(material.id());
        assertThat(item.processInstanceId()).isEqualTo(task.getProcessInstanceId());
        assertThat(item.writable()).isFalse();
        assertThat(item.blockedReason()).isNotBlank();
        assertThat(mapper.writeValueAsString(items)).doesNotContain("正式材料字段值", "前任草稿内容", "values");

        business.grantMember(Set.of("READ"), Set.of());
        assertThat(items("SUBMITTED", 20002)).hasSize(1);
        revokeMember();
        assertThat(items("SUBMITTED", 20002)).isEmpty();
    }

    @Test
    void cursorAdvancesPastHiddenCandidatesAtMicrosecondPrecisionAndRemainsOnlyAPosition()
            throws Exception {
        var oldestTask = start(10001);
        var oldest = draft(oldestTask, 10001, "末条可见");
        var visibleTask = start(10001);
        var visible = draft(visibleTask, 10001, "中间可见");
        var hiddenTask = start(10001);
        var hidden = draft(hiddenTask, 10001, "首条隐藏");
        // 固定为夹具自身的未来时间，确保测试不依赖用户已有草稿的数量与创建时刻。
        timestamp(oldest.id(), "2099-01-01T00:00:00.123454");
        timestamp(visible.id(), "2099-01-01T00:00:00.123455");
        timestamp(hidden.id(), "2099-01-01T00:00:00.123456");
        FlowTaskIntegrationTest.engine.getTaskService().setAssignee(hiddenTask.getId(), "20002");

        var first = index.page(new FlowTaskWorkViews.Query("DRAFT", null, 1), 10001);
        assertThat(first.items()).isEmpty();
        assertThat(first.before()).isNotNull();
        assertThat(first.before().id()).isEqualTo(hidden.id());
        assertThat(first.before().createdAt()).isEqualTo("2099-01-01T00:00:00.123456");
        var second = index.page(new FlowTaskWorkViews.Query("DRAFT", first.before(), 1), 10001);
        assertThat(second.items())
                .extracting(FlowTaskWorkViews.Item::draftId)
                .containsExactly(visible.id());
        var third = index.page(new FlowTaskWorkViews.Query("DRAFT", second.before(), 1), 10001);
        assertThat(third.items())
                .extracting(FlowTaskWorkViews.Item::draftId)
                .containsExactly(oldest.id());
        assertThat(mapper.writeValueAsString(first))
                .doesNotContain("total", "formName", "objectName", "首条隐藏");

        var borrowed = index.page(new FlowTaskWorkViews.Query("DRAFT", first.before(), 50), 20002);
        assertThat(borrowed.items())
                .noneMatch(item -> item.applicationId().equals(business.resource.applicationId()));
        assertThat(items("DRAFT", 10001))
                .extracting(FlowTaskWorkViews.Item::draftId)
                .containsExactly(visible.id(), oldest.id());
    }

    @Test
    void draftResourceMismatchAndUnboundSourceDoNotLeakOrCreateBindings() throws Exception {
        var task = start(10001);
        var draft = draft(task, 10001, "来源篡改输入");
        var unopened = start(10001);
        // 模拟持久化来源损坏：同一候选不得被列表自动修复为其他节点的合法草稿。
        jdbc.update(
                "UPDATE public.nocode_work_draft SET object_id=? WHERE id=?",
                "987654321",
                draft.id());
        assertThat(items("DRAFT", 10001)).isEmpty();
        jdbc.update(
                "UPDATE public.nocode_work_draft SET object_id=?, source_id=? WHERE id=?",
                business.object.objectId(),
                unopened.getId(),
                draft.id());
        var before = storedCounts();
        assertThat(items("DRAFT", 10001)).hasSize(1);
        assertThat(storedCounts()).isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_flow_task_binding WHERE"
                                        + " task_id=?",
                                Integer.class,
                                unopened.getId()))
                .isZero();
    }

    @Test
    void rejectsInvalidStateLimitAndCursorAndDefaultsMissingLimit() throws Exception {
        var decoded = mapper.readValue("{\"state\":\"DRAFT\"}", FlowTaskWorkViews.Query.class);
        assertThat(decoded.limit()).isEqualTo(10);
        assertThatThrownBy(() -> index.page(null, 10001)).hasMessageContaining("参数");
        assertThatThrownBy(() -> index.page(decoded, 0)).hasMessageContaining("参数");
        for (var state : Arrays.asList(null, "ALL", "CANCELLED", "DRAFT' OR 1=1 --")) {
            assertThatThrownBy(
                            () -> index.page(new FlowTaskWorkViews.Query(state, null, 10), 10001))
                    .hasMessageContaining("状态");
        }
        for (var limit : List.of(0, -1, 51)) {
            assertThatThrownBy(
                            () ->
                                    index.page(
                                            new FlowTaskWorkViews.Query("DRAFT", null, limit),
                                            10001))
                    .hasMessageContaining("参数");
        }
        for (var cursor :
                List.of(
                        new WorkDraftViews.Cursor("not-a-time", UUID.randomUUID().toString()),
                        new WorkDraftViews.Cursor("2026-09-10T00:00:00", "forged-id"),
                        new WorkDraftViews.Cursor(null, UUID.randomUUID().toString()))) {
            assertThatThrownBy(
                            () ->
                                    index.page(
                                            new FlowTaskWorkViews.Query("DRAFT", cursor, 10),
                                            10001))
                    .hasMessageContaining("游标");
        }
    }

    private List<FlowTaskWorkViews.Item> items(String state, long actor) {
        var result = new ArrayList<FlowTaskWorkViews.Item>();
        WorkDraftViews.Cursor before = null;
        for (int scan = 0; scan < 200; scan++) {
            var page = index.page(new FlowTaskWorkViews.Query(state, before, 50), actor);
            page.items().stream()
                    .filter(item -> item.applicationId().equals(business.resource.applicationId()))
                    .forEach(result::add);
            before = page.before();
            if (before == null) return result;
        }
        throw new AssertionError("索引游标未在合理页数内收敛");
    }

    private List<Integer> storedCounts() {
        return List.of(
                jdbc.queryForObject(
                        "SELECT count(*) FROM public.nocode_work_draft WHERE"
                                + " resource_json->>'applicationId'=?",
                        Integer.class,
                        business.resource.applicationId()),
                jdbc.queryForObject(
                        "SELECT count(*) FROM public.nocode_flow_task_binding WHERE task_id IN"
                                + " (SELECT source_id FROM public.nocode_work_draft WHERE"
                                + " resource_json->>'applicationId'=?)",
                        Integer.class,
                        business.resource.applicationId()));
    }

    private void timestamp(String draftId, String time) {
        jdbc.update(
                "UPDATE public.nocode_work_draft SET create_time=CAST(? AS timestamp) WHERE id=?",
                time,
                draftId);
    }

    private void revokeMember() {
        var auth = servicesContext.getBean(ApplicationAuthorizationService.class);
        auth.save(
                new ApplicationAuthorization.Save(
                        business.resource.applicationId(),
                        auth.get(business.resource.applicationId()).revision(),
                        List.of()),
                10001);
    }

    private WorkDrafts.Draft draft(Task task, long actor, String text) {
        return FlowTaskIntegrationTest.flows.saveDraft(
                new FlowTasks.Save(task.getId(), null, null, Map.of(business.nameField, text)),
                actor);
    }

    private WorkDrafts.Draft personal(String text) {
        return business.work.saveDraft(
                new WorkDrafts.Save(
                        null,
                        null,
                        business.resource,
                        business.object.objectId(),
                        null,
                        null,
                        Map.of(business.nameField, text)),
                10001);
    }

    private Task start(long actor) throws Exception {
        var engine = FlowTaskIntegrationTest.engine;
        var key = "flow_work_index_" + UUID.randomUUID().toString().replace("-", "");
        var config =
                mapper.writeValueAsString(
                        new FlowTasks.Configuration(
                                business.resource, business.object.objectId(), "CREATE"));
        var escaped =
                config.replace("&", "&amp;")
                        .replace("\"", "&quot;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;");
        var xml =
                """
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" xmlns:os="%s" targetNamespace="flow_work_index">
  <process id="%s" isExecutable="true">
    <startEvent id="start"/><sequenceFlow id="a" sourceRef="start" targetRef="work"/>
    <userTask id="work" name="索引回归办理" flowable:assignee="%s" os:handler="nocode" os:configuration="%s"/>
    <sequenceFlow id="b" sourceRef="work" targetRef="end"/><endEvent id="end"/>
  </process>
</definitions>
"""
                        .formatted(BpmBusinessTaskBinding.NAMESPACE, key, actor, escaped);
        var deployment =
                engine.getRepositoryService()
                        .createDeployment()
                        .name(key)
                        .addString(key + ".bpmn20.xml", xml)
                        .deploy();
        fixture.deployments.add(deployment.getId());
        var instance = engine.getRuntimeService().startProcessInstanceByKey(key);
        var task =
                engine.getTaskService()
                        .createTaskQuery()
                        .processInstanceId(instance.getId())
                        .singleResult();
        fixture.taskIds.add(task.getId());
        return task;
    }
}
