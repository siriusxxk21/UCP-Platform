package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.richuang.os.module.bpm.api.task.BpmBusinessTaskBinding;
import com.richuang.os.module.bpm.api.task.BpmProcessTaskApi;
import com.richuang.os.module.bpm.api.task.dto.BpmBusinessTaskCompleteReqDTO;
import com.richuang.os.module.bpm.service.definition.BpmBusinessModelValidationService;
import com.richuang.os.module.bpm.service.definition.BpmBusinessModelValidationServiceImpl;
import com.richuang.os.module.bpm.service.definition.BpmModelService;
import com.richuang.os.module.bpm.service.task.BpmProcessInstanceService;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.work.*;
import com.richuang.os.nocode.api.workflow.FlowTasks;
import com.richuang.os.nocode.work.dal.mapper.event.WorkEventMapper;
import com.richuang.os.nocode.work.service.event.WorkEventServiceImpl;
import com.richuang.os.nocode.workflow.adapter.bpm.FlowTaskCompletionGuard;
import com.richuang.os.nocode.workflow.adapter.bpm.FlowTaskModelGuard;
import com.richuang.os.nocode.workflow.dal.mapper.task.FlowTaskBindingMapper;
import com.richuang.os.nocode.workflow.service.task.*;

import org.flowable.engine.ProcessEngine;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;

import java.util.*;
import java.util.concurrent.*;

/** 真实发布资源、业务 Mapper、来源 Guard、审批服务、Flowable 和同库事务的组合回归。 */
class FlowTaskIntegrationTest {
    static AnnotationConfigApplicationContext flowContext;
    static ProcessEngine engine;
    static FlowTaskService flows;
    WorkDraftIntegrationTest business;
    final List<String> deployments = new ArrayList<>();
    final List<String> taskIds = new ArrayList<>();

    @BeforeAll
    static void open() throws Exception {
        connect();
        session.getConfiguration().addMapper(FlowTaskBindingMapper.class);
        session.getConfiguration().addMapper(WorkEventMapper.class);
        flowContext = new AnnotationConfigApplicationContext();
        flowContext.setParent(servicesContext);
        flowContext.register(Fixture.class);
        // 真实 Flowable 联动夹具优先使用真实任务 API，覆盖父级公共记录夹具的契约替身。
        flowContext.addBeanFactoryPostProcessor(
                factory ->
                        factory.getBeanDefinition(
                                        com.richuang.os.module.bpm.api.task.BpmProcessTaskApiImpl
                                                .class
                                                .getName())
                                .setPrimary(true));
        flowContext.refresh();
        engine = flowContext.getBean(ProcessEngine.class);
        flows = flowContext.getBean(FlowTaskService.class);
        when(flowContext.getBean(BpmModelService.class).getBpmnModelByDefinitionId(anyString()))
                .thenAnswer(i -> engine.getRepositoryService().getBpmnModel(i.getArgument(0)));
        when(flowContext.getBean(BpmProcessInstanceService.class).getProcessInstance(anyString()))
                .thenAnswer(
                        i ->
                                engine.getRuntimeService()
                                        .createProcessInstanceQuery()
                                        .processInstanceId(i.getArgument(0))
                                        .includeProcessVariables()
                                        .singleResult());
    }

    @AfterAll
    static void shutdown() {
        if (flowContext != null) flowContext.close();
        close();
    }

    @BeforeEach
    void setup() {
        business = new WorkDraftIntegrationTest();
        business.setup();
    }

    @AfterEach
    void clean() {
        writeFailure.clear();
        for (var id : deployments) engine.getRepositoryService().deleteDeployment(id, true);
        for (var id : taskIds) {
            jdbc.update(
                    "DELETE FROM public.nocode_work_event WHERE source_type='FLOW_TASK' AND"
                            + " source_id=?",
                    id);
            jdbc.update("DELETE FROM public.nocode_flow_task_binding WHERE task_id=?", id);
        }
        business.cleanup();
    }

    @Test
    void effectiveBindingsIncludeSuspendedDefinitionsUntilTheirInstancesEnd() throws Exception {
        var task = start(10001, false, configuration());
        var definitions =
                new com.richuang.os.module.bpm.service.definition.BpmProcessDefinitionServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(
                definitions, "repositoryService", engine.getRepositoryService());
        org.springframework.test.util.ReflectionTestUtils.setField(
                definitions, "runtimeService", engine.getRuntimeService());
        var id = task.getProcessDefinitionId();
        assertThat(definitions.getEffectiveBusinessBindings("nocode"))
                .anyMatch(
                        binding ->
                                id.equals(binding.processDefinitionId())
                                        && "work".equals(binding.nodeId()));
        engine.getRepositoryService().suspendProcessDefinitionById(id, false, null);
        assertThat(definitions.getEffectiveBusinessBindings("nocode"))
                .anyMatch(binding -> id.equals(binding.processDefinitionId()));
        engine.getRuntimeService()
                .deleteProcessInstance(task.getProcessInstanceId(), "本次回收站绑定测试清理");
        assertThat(definitions.getEffectiveBusinessBindings("nocode"))
                .noneMatch(binding -> id.equals(binding.processDefinitionId()));
        engine.getRepositoryService().activateProcessDefinitionById(id, false, null);
        assertThat(definitions.getEffectiveBusinessBindings("nocode"))
                .anyMatch(binding -> id.equals(binding.processDefinitionId()));
    }

    @Test
    void validatesBoundResourceAndManualRulesBeforePublishing() throws Exception {
        var task = start(10001, false, configuration());
        var model = engine.getRepositoryService().getBpmnModel(task.getProcessDefinitionId());
        var node = (org.flowable.bpmn.model.UserTask) model.getFlowElement("work");
        com.richuang.os.module.bpm.framework.flowable.core.util.BpmnModelUtils.addExtensionElement(
                node, "approveType", "1");
        com.richuang.os.module.bpm.framework.flowable.core.util.BpmnModelUtils.addExtensionElement(
                node, "assignStartUserHandlerType", "1");
        var xml = new org.flowable.bpmn.converter.BpmnXMLConverter().convertToXML(model);
        var validator = flowContext.getBean(BpmBusinessModelValidationService.class);
        assertThatCode(() -> validator.validate(xml, 0, 10001)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(xml, 1, 10001)).hasMessageContaining("人工单人办理");
        assertThatThrownBy(() -> validator.validate(xml, 0, 20002))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void opensOnceRestoresDraftAndReadsMaterialAfterTaskEnds() throws Exception {
        var task = start(10001, false, configuration());
        var first = flows.open(task.getId(), 10001);
        assertThat(first.processInstanceId()).isEqualTo(task.getProcessInstanceId());
        assertThat(first.work().writable()).isTrue();
        assertThat(first.work().draft().values()).isEmpty();
        assertThat(flows.open(task.getId(), 10001).work().draft().id())
                .isEqualTo(first.work().draft().id());
        assertThat(business.recordCount()).isZero();
        assertThatThrownBy(() -> flows.open(task.getId(), 20002))
                .isInstanceOf(RuntimeException.class);
        var saved =
                flows.saveDraft(
                        new FlowTasks.Save(
                                task.getId(),
                                first.work().draft().id(),
                                first.work().draft().revision(),
                                Map.of(business.nameField, "页面恢复填写")),
                        10001);
        assertThat(flows.open(task.getId(), 10001).work().draft().values())
                .containsEntry(business.nameField, "页面恢复填写");
        var material = flows.submit(command(task, saved), 10001);
        var completed = flows.open(task.getId(), 10001);
        assertThat(completed.work().writable()).isFalse();
        assertThat(completed.work().submission().id()).isEqualTo(material.id());
        assertThat(completed.work().submission().values())
                .containsEntry(business.nameField, "页面恢复填写");
        assertThatThrownBy(() -> flows.open(task.getId(), 20002)).hasMessageContaining("无权访问");
    }

    @Test
    void submitsRealBusinessAndMaterialAndProtectsTerminalOutput() throws Exception {
        var task = start(10001, false, configuration());
        var draft = draft(task, 10001, "正式流程成果");
        assertThat(flows.context(task.getId(), 10001).draftId()).isEqualTo(draft.id());
        assertThatThrownBy(() -> draft(task, 10001, "重复新建")).hasMessageContaining("已有草稿");
        assertThat(business.recordCount()).isZero();
        var command = command(task, draft);
        var material = flows.submit(command, 10001);
        assertThat(flows.getSubmission(task.getId(), 10001).id()).isEqualTo(material.id());
        assertThatThrownBy(() -> flows.getSubmission(task.getId(), 20002))
                .hasMessageContaining("无权访问");
        assertThat(engine.getTaskService().createTaskQuery().taskId(task.getId()).count()).isZero();
        assertThat(business.recordCount()).isEqualTo(1);
        assertThat(
                        business.records
                                .get(
                                        business.resource.applicationId(),
                                        business.object.objectId(),
                                        material.recordId(),
                                        10001)
                                .record()
                                .values())
                .containsEntry(business.nameField, "正式流程成果");
        assertThat(events(task)).isEqualTo(1);
        assertThat(flows.submit(command, 10001).id()).isEqualTo(material.id());
        assertThat(events(task)).isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                flows.submit(
                                        new FlowTasks.Submit(
                                                task.getId(),
                                                draft.id(),
                                                draft.revision(),
                                                command.idempotencyKey(),
                                                "改变意见"),
                                        10001))
                .hasMessageContaining("不能替换");
        assertThatThrownBy(() -> business.work.getDraft(draft.id(), 10001))
                .hasMessageContaining("无权访问");
        assertThatThrownBy(() -> business.work.getSubmission(material.id(), 10001))
                .hasMessageContaining("无权访问");
        assertThatThrownBy(
                        () ->
                                business.records.save(
                                        new ApplicationRecords.Save(
                                                business.resource.applicationId(),
                                                business.object.objectId(),
                                                material.recordId(),
                                                material.recordRevision(),
                                                Map.of(business.nameField, "篡改"),
                                                null),
                                        10001))
                .hasMessageContaining("流程保护");
        assertThatThrownBy(
                        () ->
                                business.records.delete(
                                        new ApplicationRecords.Delete(
                                                business.resource.applicationId(),
                                                business.object.objectId(),
                                                material.recordId(),
                                                material.recordRevision()),
                                        10001))
                .hasMessageContaining("流程保护");
    }

    @Test
    void downstreamFailureRollsBackRealBusinessMaterialEventAndBindingResult() throws Exception {
        var task = start(10001, true, configuration());
        var draft = draft(task, 10001, "整体回滚");
        var command = command(task, draft);
        assertThatThrownBy(() -> flows.submit(command, 10001))
                .hasRootCauseMessage("flow-integration-downstream-failure");
        assertUnsubmitted(task, draft);
        engine.getRuntimeService().setVariable(task.getProcessInstanceId(), "allowNext", true);
        assertThat(flows.submit(command, 10001).values()).containsEntry(business.nameField, "整体回滚");
        assertThat(business.recordCount()).isEqualTo(1);
    }

    @Test
    void eventWriteFailureRollsBackBeforeEngineCompletion() throws Exception {
        var task = start(10001, false, configuration());
        var draft = draft(task, 10001, "事件失败");
        writeFailure.failAfter("INSERT INTO public.nocode_work_event");
        assertThatThrownBy(() -> flows.submit(command(task, draft), 10001))
                .hasRootCauseMessage("B1 intentional failure after MyBatis database write");
        writeFailure.clear();
        assertUnsubmitted(task, draft);
    }

    @Test
    void personalAndOtherTaskDraftsCannotBeSubmittedHere() throws Exception {
        var task = start(10001, false, configuration());
        var other = start(10001, false, configuration());
        var personal =
                business.work.saveDraft(
                        new WorkDrafts.Save(
                                null,
                                null,
                                business.resource,
                                business.object.objectId(),
                                null,
                                null,
                                Map.of(business.nameField, "个人草稿")),
                        10001);
        assertThatThrownBy(() -> flows.submit(command(task, personal), 10001))
                .hasMessageContaining("无权访问");
        var otherDraft = draft(other, 10001, "其他任务");
        assertThatThrownBy(() -> flows.submit(command(task, otherDraft), 10001))
                .hasMessageContaining("无权访问");
        assertThatThrownBy(
                        () ->
                                business.work.submit(
                                        new WorkDrafts.Submit(
                                                otherDraft.id(),
                                                otherDraft.revision(),
                                                UUID.randomUUID().toString()),
                                        10001))
                .hasMessageContaining("无权访问");
        assertThat(business.recordCount()).isZero();
    }

    @Test
    void assignmentAndCurrentBusinessPermissionAreBothRequired() throws Exception {
        var task = start(20002, false, configuration());
        assertThatThrownBy(() -> flows.context(task.getId(), 10001)).hasMessageContaining("审批人");
        assertThatThrownBy(() -> flows.context(task.getId(), 20002))
                .isInstanceOf(com.richuang.os.framework.common.exception.ServiceException.class);
        business.grantMember(Set.of("READ", "CREATE"), Set.of(business.nameField));
        var draft = draft(task, 20002, "撤权测试");
        business.grantMember(Set.of("READ"), Set.of(business.nameField));
        assertThatThrownBy(() -> flows.submit(command(task, draft), 20002))
                .hasMessageContaining("权限");
        assertThat(business.recordCount()).isZero();
        business.grantMember(Set.of("READ", "CREATE"), Set.of(business.nameField));
        engine.getTaskService().setAssignee(task.getId(), "10001");
        assertThatThrownBy(() -> flows.getDraft(task.getId(), draft.id(), 20002))
                .hasMessageContaining("审批人");
        assertThatThrownBy(() -> flows.getDraft(task.getId(), draft.id(), 10001))
                .hasMessageContaining("无权访问");
    }

    @Test
    void reassignedTaskCreatesAnIsolatedDraftAndOnlyNewSubmitterCanReadMaterial() throws Exception {
        business.grantMember(Set.of("READ", "CREATE"), Set.of(business.nameField));
        var task = start(10001, false, configuration());
        var original = draft(task, 10001, "原办理人未提交输入");
        engine.getTaskService().setAssignee(task.getId(), "20002");

        assertTaskUnavailable(task, original, 10001, "审批人");
        assertThatThrownBy(() -> flows.getDraft(task.getId(), original.id(), 20002))
                .hasMessageContaining("无权访问");
        var replacement = flows.open(task.getId(), 20002).work().draft();
        assertThat(replacement.id()).isNotEqualTo(original.id());
        assertThat(replacement.values()).isEmpty();
        assertThat(flows.open(task.getId(), 20002).work().draft().id()).isEqualTo(replacement.id());
        var saved =
                flows.saveDraft(
                        new FlowTasks.Save(
                                task.getId(),
                                replacement.id(),
                                replacement.revision(),
                                Map.of(business.nameField, "新办理人的成果")),
                        20002);
        var command = command(task, saved);
        var material = flows.submit(command, 20002);
        var completed = flows.open(task.getId(), 20002).work();
        assertThat(completed.writable()).isFalse();
        assertThat(completed.submission().id()).isEqualTo(material.id());
        assertThat(completed.submission().values()).containsEntry(business.nameField, "新办理人的成果");
        // 原办理人即使拥有应用管理权，也不继承新办理人的草稿和提交材料。
        assertThatThrownBy(() -> flows.getSubmission(task.getId(), 10001))
                .hasMessageContaining("无权访问");
        assertThatThrownBy(() -> flows.open(task.getId(), 10001)).hasMessageContaining("无权访问");
        assertThatThrownBy(() -> flows.submit(command, 10001)).hasMessageContaining("不能替换");
        assertThat(business.recordCount()).isEqualTo(1);
        assertThat(events(task)).isEqualTo(1);
    }

    @Test
    void cancelledProcessCannotReopenSaveOrSubmitItsExistingDraft() throws Exception {
        var task = start(10001, false, configuration());
        var draft = draft(task, 10001, "取消前草稿");
        engine.getRuntimeService().deleteProcessInstance(task.getProcessInstanceId(), "流程草稿取消回归");

        assertTaskUnavailable(task, draft, 10001, "不存在");
        assertThatThrownBy(() -> flows.getSubmission(task.getId(), 10001))
                .hasMessageContaining("不存在或无权访问");
        assertThat(business.recordCount()).isZero();
        assertThat(events(task)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_work_submission WHERE"
                                        + " draft_id=?",
                                Integer.class,
                                draft.id()))
                .isZero();
    }

    @Test
    void suspendedProcessBlocksDraftOperationsAndResumeRestoresTheSameInput() throws Exception {
        var task = start(10001, false, configuration());
        var draft = draft(task, 10001, "挂起前草稿");
        engine.getRuntimeService().suspendProcessInstanceById(task.getProcessInstanceId());

        assertTaskUnavailable(task, draft, 10001, "挂起");
        assertThat(business.recordCount()).isZero();
        assertThat(events(task)).isZero();
        engine.getRuntimeService().activateProcessInstanceById(task.getProcessInstanceId());
        var restored = flows.open(task.getId(), 10001).work().draft();
        assertThat(restored.id()).isEqualTo(draft.id());
        assertThat(restored.revision()).isEqualTo(draft.revision());
        assertThat(restored.values()).containsEntry(business.nameField, "挂起前草稿");
        assertThat(flows.submit(command(task, restored), 10001).values())
                .containsEntry(business.nameField, "挂起前草稿");
        assertThat(events(task)).isEqualTo(1);
    }

    @Test
    void currentPermissionAlsoFiltersCompletedRetry() throws Exception {
        business.grantMember(Set.of("READ", "CREATE"), Set.of(business.nameField));
        var task = start(20002, false, configuration());
        var draft = draft(task, 20002, "历史敏感字段");
        var command = command(task, draft);
        flows.submit(command, 20002);
        business.grantMember(Set.of("READ"), Set.of());
        assertThat(flows.submit(command, 20002).values()).doesNotContainKey(business.nameField);
        assertThat(flows.getSubmission(task.getId(), 20002).values())
                .doesNotContainKey(business.nameField);
        var completed = flows.open(task.getId(), 20002).work();
        assertThat(completed.writable()).isFalse();
        assertThat(completed.draft().values()).doesNotContainKey(business.nameField);
        assertThat(completed.submission().values()).doesNotContainKey(business.nameField);
        assertThat(SelectionFields.presentations(completed.form().nodes()))
                .doesNotContainKey(business.nameField);
        assertThat(completed.model().permissions().writeFields()).isEmpty();
        var auth =
                servicesContext.getBean(
                        com.richuang.os.nocode.application.service.authorization
                                .ApplicationAuthorizationService.class);
        auth.save(
                new ApplicationAuthorization.Save(
                        business.resource.applicationId(),
                        auth.get(business.resource.applicationId()).revision(),
                        List.of()),
                10001);
        assertThatThrownBy(() -> flows.submit(command, 20002)).hasMessageContaining("权限");
        assertThatThrownBy(() -> flows.getSubmission(task.getId(), 20002))
                .hasMessageContaining("权限");
        assertThatThrownBy(() -> flows.open(task.getId(), 20002)).hasMessageContaining("权限");
    }

    @Test
    void fixedDeploymentVersionAllowsNewTaskDraftAfterNewApplicationPublish() throws Exception {
        var task = start(10001, false, configuration());
        var current = business.applications.get(business.resource.applicationId());
        var saved =
                business.applications.save(
                        new ApplicationCenter.Save(
                                business.resource.applicationId(),
                                current.application().revision(),
                                current.application().code(),
                                current.application().name(),
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        current.draft().objects(), List.of())),
                        10001);
        business.applications.publish(
                new ApplicationCenter.Revision(
                        business.resource.applicationId(),
                        saved.application().revision(),
                        "移除当前表单"),
                10001);
        var draft = draft(task, 10001, "旧版节点");
        assertThat(draft.resource().applicationVersion()).isEqualTo(1);
        assertThat(flows.submit(command(task, draft), 10001).resource())
                .isEqualTo(business.resource);
        assertThatThrownBy(
                        () ->
                                business.work.saveDraft(
                                        new WorkDrafts.Save(
                                                null,
                                                null,
                                                business.resource,
                                                business.object.objectId(),
                                                null,
                                                null,
                                                Map.of()),
                                        10001))
                .hasMessageContaining("新版本");
    }

    @Test
    void concurrentRetriesCreateOneRecordMaterialAndEvent() throws Exception {
        var task = start(10001, false, configuration());
        var draft = draft(task, 10001, "并发流程提交");
        var command = command(task, draft);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<WorkDrafts.Submission> run =
                    () -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS))
                            throw new IllegalStateException("起跑超时");
                        return flows.submit(command, 10001);
                    };
            var one = executor.submit(run);
            var two = executor.submit(run);
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            assertThat(one.get(25, TimeUnit.SECONDS).id())
                    .isEqualTo(two.get(25, TimeUnit.SECONDS).id());
        }
        assertThat(business.recordCount()).isEqualTo(1);
        assertThat(events(task)).isEqualTo(1);
    }

    @Test
    void unsupportedOrForgedNodeConfigurationIsRejected() throws Exception {
        for (var config :
                List.of(
                        configuration().replace("CREATE", "UPDATE"),
                        configuration().replace(business.resource.applicationChecksum(), "forged"),
                        "{}")) {
            var task = start(10001, false, config);
            assertThatThrownBy(() -> flows.context(task.getId(), 10001))
                    .isInstanceOf(
                            com.richuang.os.framework.common.exception.ServiceException.class);
        }
        assertThat(business.recordCount()).isZero();
    }

    @Test
    void productionGuardRejectsUnboundCompletionAndStaleDraftRevision() throws Exception {
        var task = start(10001, false, configuration());
        var draft = draft(task, 10001, "入口保护");
        assertThatThrownBy(() -> engine.getTaskService().complete(task.getId()))
                .hasMessageContaining("业务办理入口");
        assertThatThrownBy(
                        () ->
                                flowContext
                                        .getBean(BpmProcessTaskApi.class)
                                        .completeBusinessTask(
                                                10001,
                                                new BpmBusinessTaskCompleteReqDTO(
                                                        task.getId(),
                                                        UUID.randomUUID().toString(),
                                                        "伪造材料",
                                                        Map.of())))
                .hasMessageContaining("匹配的业务材料");
        var newer =
                flows.saveDraft(
                        new FlowTasks.Save(
                                task.getId(),
                                draft.id(),
                                draft.revision(),
                                Map.of(business.nameField, "新版")),
                        10001);
        assertThatThrownBy(() -> flows.submit(command(task, draft), 10001))
                .hasMessageContaining("已修改");
        assertThat(flows.submit(command(task, newer), 10001).values())
                .containsEntry(business.nameField, "新版");
    }

    private void assertTaskUnavailable(
            Task task, WorkDrafts.Draft draft, long actor, String message) {
        assertThatThrownBy(() -> flows.context(task.getId(), actor)).hasMessageContaining(message);
        assertThatThrownBy(() -> flows.open(task.getId(), actor)).hasMessageContaining(message);
        assertThatThrownBy(() -> flows.getDraft(task.getId(), draft.id(), actor))
                .hasMessageContaining(message);
        assertThatThrownBy(
                        () ->
                                flows.saveDraft(
                                        new FlowTasks.Save(
                                                task.getId(),
                                                draft.id(),
                                                draft.revision(),
                                                Map.of()),
                                        actor))
                .hasMessageContaining(message);
        assertThatThrownBy(() -> flows.submit(command(task, draft), actor))
                .hasMessageContaining(message);
    }

    private void assertUnsubmitted(Task task, WorkDrafts.Draft draft) {
        assertThat(business.recordCount()).isZero();
        assertThat(events(task)).isZero();
        assertThat(engine.getTaskService().createTaskQuery().taskId(task.getId()).count())
                .isEqualTo(1);
        assertThat(flows.getDraft(task.getId(), draft.id(), 10001).state()).isEqualTo("DRAFT");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_work_submission WHERE"
                                        + " draft_id=?",
                                Integer.class,
                                draft.id()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT submission_id FROM public.nocode_flow_task_binding WHERE"
                                        + " task_id=?",
                                String.class,
                                task.getId()))
                .isNull();
    }

    private int events(Task task) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM public.nocode_work_event WHERE source_id=?",
                Integer.class,
                task.getId());
    }

    private WorkDrafts.Draft draft(Task task, long actor, String text) {
        return flows.saveDraft(
                new FlowTasks.Save(task.getId(), null, null, Map.of(business.nameField, text)),
                actor);
    }

    private FlowTasks.Submit command(Task task, WorkDrafts.Draft draft) {
        return new FlowTasks.Submit(
                task.getId(), draft.id(), draft.revision(), UUID.randomUUID().toString(), "流程材料提交");
    }

    private String configuration() throws Exception {
        return mapper.writeValueAsString(
                new FlowTasks.Configuration(
                        business.resource, business.object.objectId(), "CREATE"));
    }

    private Task start(long actor, boolean failing, String config) {
        var key = "flow_work_" + UUID.randomUUID().toString().replace("-", "");
        var escaped =
                config.replace("&", "&amp;")
                        .replace("\"", "&quot;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;");
        var expression = failing ? "${allowNext ? 1 : flowWorkFailure.fail()}" : "${1}";
        var xml =
                """
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" xmlns:os="%s" targetNamespace="flow_work">
  <process id="%s" isExecutable="true">
    <startEvent id="start"/><sequenceFlow id="a" sourceRef="start" targetRef="work"/>
    <userTask id="work" name="业务办理" flowable:assignee="%s" os:handler="nocode" os:configuration="%s"/>
    <sequenceFlow id="b" sourceRef="work" targetRef="next"/>
    <serviceTask id="next" flowable:expression="%s"/>
    <sequenceFlow id="c" sourceRef="next" targetRef="end"/><endEvent id="end"/>
  </process>
</definitions>
"""
                        .formatted(
                                BpmBusinessTaskBinding.NAMESPACE, key, actor, escaped, expression);
        var deployment =
                engine.getRepositoryService()
                        .createDeployment()
                        .name(key)
                        .addString(key + ".bpmn20.xml", xml)
                        .deploy();
        deployments.add(deployment.getId());
        var instance =
                engine.getRuntimeService()
                        .startProcessInstanceByKey(key, Map.of("allowNext", false));
        var task =
                engine.getTaskService()
                        .createTaskQuery()
                        .processInstanceId(instance.getId())
                        .singleResult();
        taskIds.add(task.getId());
        return task;
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
        BpmTaskCompletionIntegrationTest.Fixture.class,
        FlowTaskServiceImpl.class,
        FlowTaskBindingServiceImpl.class,
        FlowTaskCompletionGuard.class,
        WorkEventServiceImpl.class,
        BpmBusinessModelValidationServiceImpl.class,
        FlowTaskModelGuard.class
    })
    static class Fixture {
        @Bean
        EngineFailure flowWorkFailure() {
            return new EngineFailure();
        }

        @Bean
        FlowTaskBindingMapper flowTaskBindingMapper() {
            return session.getMapper(FlowTaskBindingMapper.class);
        }

        @Bean
        WorkEventMapper workEventMapper() {
            return session.getMapper(WorkEventMapper.class);
        }
    }

    public static class EngineFailure {
        public int fail() {
            throw new IllegalStateException("flow-integration-downstream-failure");
        }
    }
}
