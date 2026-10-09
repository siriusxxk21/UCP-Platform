package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.richuang.os.module.bpm.api.task.*;
import com.richuang.os.module.bpm.controller.admin.task.vo.task.BpmTaskApproveReqVO;
import com.richuang.os.module.bpm.dal.dataobject.definition.BpmProcessDefinitionInfoDO;
import com.richuang.os.module.bpm.enums.task.BpmTaskStatusEnum;
import com.richuang.os.module.bpm.framework.flowable.core.enums.BpmnVariableConstants;
import com.richuang.os.module.bpm.framework.flowable.core.util.*;
import com.richuang.os.module.bpm.service.definition.BpmProcessDefinitionService;
import com.richuang.os.module.bpm.service.task.*;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.workflow.*;
import com.richuang.os.nocode.workflow.service.material.*;

import org.flowable.task.api.Task;
import org.junit.jupiter.api.*;

import java.util.*;

/** 真实 PostgreSQL、已发布应用与 Flowable 跨人串行审批；只删除自己创建的 UUID 夹具。 */
class FlowMaterialIntegrationTest {
    FlowTaskIntegrationTest fixture;
    FlowMaterialService materials;
    BpmTaskService approvals;

    @BeforeAll
    static void open() throws Exception {
        FlowTaskIntegrationTest.open();
        FlowTaskIntegrationTest.flowContext.registerBean(FlowMaterialServiceImpl.class);
        FlowTaskIntegrationTest.flowContext.getBean(FlowMaterialServiceImpl.class);
    }

    @AfterAll
    static void closeAll() {
        FlowTaskIntegrationTest.shutdown();
    }

    @BeforeEach
    void setup() {
        fixture = new FlowTaskIntegrationTest();
        fixture.setup();
        materials = FlowTaskIntegrationTest.flowContext.getBean(FlowMaterialService.class);
        approvals = FlowTaskIntegrationTest.flowContext.getBean(BpmTaskService.class);
        reset(FlowTaskIntegrationTest.flowContext.getBean(BpmProcessDefinitionService.class));
    }

    @AfterEach
    void cleanup() {
        fixture.clean();
    }

    @Test
    void explicitTaskGrantReadsFrozenBusinessAcrossUsersWithoutOpeningDraftsAndApprovesWithToken()
            throws Exception {
        Task review = submittedBusiness("TASK", true);
        int before = draftCount();
        var page = list(review);
        assertThat(page.blockedReason()).isNull();
        assertThat(page.reviewRequired()).isTrue();
        assertThat(page.items()).hasSize(1);
        var detail =
                materials.detail(
                        new FlowMaterials.Get(
                                review.getProcessInstanceId(),
                                review.getId(),
                                page.items().getFirst().id()),
                        20002);
        assertThat(detail.businessForm().values()).containsEntry(fixture.business.nameField, "封存值");
        assertThat(detail.businessForm().model().permissions().writeFields()).isEmpty();
        assertThat(detail.businessForm().model().object().tableName()).isNull();
        assertThat(draftCount()).isEqualTo(before);
        assertThatThrownBy(() -> approvals.approveTask(20002L, approve(review, null)))
                .hasMessageContaining("重新查阅");
        approvals.approveTask(20002L, approve(review, page.reviewToken()));
        assertThat(
                        FlowTaskIntegrationTest.engine
                                .getRuntimeService()
                                .createProcessInstanceQuery()
                                .processInstanceId(review.getProcessInstanceId())
                                .count())
                .isZero();
        assertThat(list(review).items()).hasSize(1);
    }

    @Test
    void oldPolicyRetainsBusinessPermissionAndOwnerDraftIsolation() throws Exception {
        Task review = submittedBusiness(null, false);
        assertThat(list(review).items().getFirst().state()).isEqualTo("UNAVAILABLE");
        fixture.business.grantMember(Set.of("READ"), Set.of(fixture.business.nameField));
        assertThat(list(review).blockedReason()).isNull();
        String sourceTask = fixture.taskIds.getFirst();
        assertThatThrownBy(() -> FlowTaskIntegrationTest.flows.getSubmission(sourceTask, 20002))
                .hasMessageContaining("无权访问");
        fixture.business.grantMember(Set.of("READ"), Set.of());
        assertThat(list(review).blockedReason()).isNotBlank();
    }

    @Test
    void completedOwnerReadsOwnBusinessMaterialDespiteMissingLegacyStartSnapshot()
            throws Exception {
        Task review = submittedBusiness("TASK", false);
        String sourceId = fixture.taskIds.getFirst();
        var ownerQuery = new FlowMaterials.Query(review.getProcessInstanceId(), sourceId);
        var own = materials.list(ownerQuery, 10001);
        assertThat(own.reviewRequired()).isFalse();
        assertThat(own.items()).extracting(FlowMaterials.Item::taskId).containsExactly(sourceId);
        assertThatThrownBy(() -> materials.list(ownerQuery, 30003))
                .isInstanceOf(RuntimeException.class);
        approvals.approveTask(20002L, approve(review, list(review).reviewToken()));

        when(FlowTaskIntegrationTest.flowContext
                        .getBean(BpmProcessDefinitionService.class)
                        .getProcessDefinitionInfo(review.getProcessDefinitionId()))
                .thenReturn(
                        new BpmProcessDefinitionInfoDO()
                                .setFormType(10)
                                .setFormId(1L)
                                .setFormConf("{}")
                                .setFormFields(
                                        List.of("{\"type\":\"input\",\"field\":\"old_start\"}")));
        var ended = materials.list(ownerQuery, 10001);
        assertThat(ended.items()).hasSize(2);
        assertThat(ended.reviewRequired()).isFalse();
        assertThat(ended.reviewToken()).isNull();
        assertThat(ended.blockedReason()).doesNotContain("权限", "通过");
        var missing =
                ended.items().stream()
                        .filter(item -> item.taskId().startsWith("START:"))
                        .findFirst()
                        .orElseThrow();
        assertThat(missing.warning()).contains("未封存初始提交值");
        assertThat(missing.state()).isEqualTo("UNAVAILABLE");
        var submitted =
                ended.items().stream()
                        .filter(item -> item.taskId().equals(sourceId))
                        .findFirst()
                        .orElseThrow();
        assertThat(submitted.state()).isEqualTo("CURRENT");
        var detail =
                materials.detail(
                        new FlowMaterials.Get(
                                review.getProcessInstanceId(), sourceId, submitted.id()),
                        10001);
        assertThat(detail.businessForm().values()).containsEntry(fixture.business.nameField, "封存值");
    }

    @Test
    void completedFlowFormOwnerReadsOwnLocalSubmissionWithoutLaterGlobalOverwrite()
            throws Exception {
        Task source = startFlowForm(false);
        approvals.approveTask(
                10001L,
                new BpmTaskApproveReqVO()
                        .setId(source.getId())
                        .setReason("提交本节点")
                        .setVariables(Map.of("visible", "本人提交值")));
        var review = current(source.getProcessInstanceId());
        FlowTaskIntegrationTest.engine
                .getRuntimeService()
                .setVariable(source.getProcessInstanceId(), "visible", "后续全局值");
        var page =
                materials.list(
                        new FlowMaterials.Query(source.getProcessInstanceId(), source.getId()),
                        10001);
        assertThat(page.items())
                .extracting(FlowMaterials.Item::taskId)
                .containsExactly(source.getId());
        var detail =
                materials.detail(
                        new FlowMaterials.Get(
                                source.getProcessInstanceId(),
                                source.getId(),
                                page.items().getFirst().id()),
                        10001);
        assertThat(detail.flowForm().values()).containsExactlyEntriesOf(Map.of("visible", "本人提交值"));
        assertThat(current(source.getProcessInstanceId()).getId()).isEqualTo(review.getId());
    }

    @Test
    void finalNoneApprovalListsTwoSequentialSubmissionsWithSameFieldWithoutGlobalOverwrite()
            throws Exception {
        var config =
                escape(
                        mapper.writeValueAsString(
                                new FlowTasks.Configuration(
                                        fixture.business.resource,
                                        fixture.business.object.objectId(),
                                        "CREATE")));
        String source =
                "<userTask id=\"work\" name=\"第一份材料\" flowable:assignee=\"10001\""
                        + " os:handler=\"nocode\" os:configuration=\""
                        + config
                        + "\"/><sequenceFlow id=\"middle\" sourceRef=\"work\""
                        + " targetRef=\"work2\"/><userTask id=\"work2\" name=\"第二份材料\""
                        + " flowable:assignee=\"10001\" os:handler=\"nocode\" os:configuration=\""
                        + config
                        + "\"/>";
        Task first = deploy(source, "TASK", true);
        var one =
                FlowTaskIntegrationTest.flows.saveDraft(
                        new FlowTasks.Save(
                                first.getId(),
                                null,
                                null,
                                Map.of(fixture.business.nameField, "第一轮值")),
                        10001);
        FlowTaskIntegrationTest.flows.submit(
                new FlowTasks.Submit(
                        first.getId(),
                        one.id(),
                        one.revision(),
                        UUID.randomUUID().toString(),
                        "第一份"),
                10001);
        Task second = current(first.getProcessInstanceId());
        fixture.taskIds.add(second.getId());
        var two =
                FlowTaskIntegrationTest.flows.saveDraft(
                        new FlowTasks.Save(
                                second.getId(),
                                null,
                                null,
                                Map.of(fixture.business.nameField, "第二轮值")),
                        10001);
        FlowTaskIntegrationTest.flows.submit(
                new FlowTasks.Submit(
                        second.getId(),
                        two.id(),
                        two.revision(),
                        UUID.randomUUID().toString(),
                        "第二份"),
                10001);
        Task review = current(first.getProcessInstanceId());
        FlowTaskIntegrationTest.engine
                .getRuntimeService()
                .setVariable(review.getProcessInstanceId(), fixture.business.nameField, "全局覆盖值");
        var page = list(review);
        assertThat(page.items()).hasSize(2);
        assertThat(page.blockedReason()).isNull();
        var values =
                page.items().stream()
                        .map(
                                item ->
                                        materials
                                                .detail(
                                                        new FlowMaterials.Get(
                                                                review.getProcessInstanceId(),
                                                                review.getId(),
                                                                item.id()),
                                                        20002)
                                                .businessForm()
                                                .values()
                                                .get(fixture.business.nameField))
                        .toList();
        assertThat(values).containsExactly("第一轮值", "第二轮值");
        approvals.approveTask(20002L, approve(review, page.reviewToken()));
        assertThat(current(review.getProcessInstanceId())).isNull();
    }

    @Test
    void transferCancelCrossInstanceAndTenantNeverGrantMaterialAccess() throws Exception {
        Task review = submittedBusiness("TASK", false);
        var page = list(review);
        Task other = submittedBusiness("TASK", false);
        assertThatThrownBy(
                        () ->
                                materials.detail(
                                        new FlowMaterials.Get(
                                                other.getProcessInstanceId(),
                                                other.getId(),
                                                page.items().getFirst().id()),
                                        20002))
                .hasMessageContaining("不属于");
        assertThatThrownBy(
                        () ->
                                materials.list(
                                        new FlowMaterials.Query(
                                                other.getProcessInstanceId(), review.getId()),
                                        20002))
                .isInstanceOf(RuntimeException.class);
        var engine = FlowTaskIntegrationTest.engine;
        engine.getTaskService().setAssignee(review.getId(), "30003");
        assertThatThrownBy(() -> list(review)).isInstanceOf(RuntimeException.class);
        engine.getTaskService().setAssignee(review.getId(), "20002");
        engine.getRuntimeService().suspendProcessInstanceById(review.getProcessInstanceId());
        assertThatThrownBy(() -> list(review)).isInstanceOf(RuntimeException.class);
        engine.getRuntimeService().activateProcessInstanceById(review.getProcessInstanceId());
        engine.getRuntimeService().deleteProcessInstance(review.getProcessInstanceId(), "材料回归取消");
        assertThatThrownBy(() -> list(review)).isInstanceOf(RuntimeException.class);
        com.richuang.os.framework.tenant.core.context.TenantContextHolder.setTenantId(998877L);
        try {
            assertThatThrownBy(() -> list(other)).isInstanceOf(RuntimeException.class);
        } finally {
            com.richuang.os.framework.tenant.core.context.TenantContextHolder.clear();
        }
    }

    @Test
    void changedReadProjectionInvalidatesExistingApprovalToken() throws Exception {
        Task review = submittedBusiness("BUSINESS", false);
        fixture.business.grantMember(Set.of("READ"), Set.of(fixture.business.nameField));
        var page = list(review);
        fixture.business.grantMember(Set.of("READ"), Set.of());
        assertThatThrownBy(() -> approvals.approveTask(20002L, approve(review, page.reviewToken())))
                .hasMessageContaining("材料");
        assertThat(
                        FlowTaskIntegrationTest.engine
                                .getTaskService()
                                .createTaskQuery()
                                .taskId(review.getId())
                                .count())
                .isEqualTo(1);
    }

    @Test
    void actualRejectKeepsActorEvidenceButPropagatedRejectCannotGrantHistoryAccess()
            throws Exception {
        Task review = submittedBusiness("TASK", false);
        approvals.rejectTask(
                20002L,
                new com.richuang.os.module.bpm.controller.admin.task.vo.task.BpmTaskRejectReqVO()
                        .setId(review.getId())
                        .setReason("不同意"));
        assertThat(list(review).items()).hasSize(1);
        assertThat(list(review).reviewRequired()).isFalse();
        Task propagated = submittedBusiness("TASK", false);
        var engine = FlowTaskIntegrationTest.engine;
        engine.getTaskService()
                .setVariableLocal(
                        propagated.getId(),
                        BpmnVariableConstants.TASK_VARIABLE_STATUS,
                        BpmTaskStatusEnum.REJECT.getStatus());
        engine.getRuntimeService()
                .deleteProcessInstance(propagated.getProcessInstanceId(), "模拟加签根被级联拒绝");
        assertThatThrownBy(() -> list(propagated)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void applicationRepublishCannotChangeFrozenSubmittedForm() throws Exception {
        Task review = submittedBusiness("TASK", false);
        var current = fixture.business.applications.get(fixture.business.resource.applicationId());
        var saved =
                fixture.business.applications.save(
                        new ApplicationCenter.Save(
                                current.application().id(),
                                current.application().revision(),
                                current.application().code(),
                                current.application().name(),
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        current.draft().objects(), List.of())),
                        10001);
        fixture.business.applications.publish(
                new ApplicationCenter.Revision(
                        saved.application().id(), saved.application().revision(), "移除新版表单"),
                10001);
        var page = list(review);
        assertThat(page.blockedReason()).isNull();
        var detail =
                materials.detail(
                        new FlowMaterials.Get(
                                review.getProcessInstanceId(),
                                review.getId(),
                                page.items().getFirst().id()),
                        20002);
        assertThat(detail.item().formName()).isEqualTo("工作表单");
        assertThat(detail.businessForm().values()).containsEntry(fixture.business.nameField, "封存值");
    }

    @Test
    void newlySensitiveClassificationRevokesTaskScopedReadAndApproval() throws Exception {
        Task review = submittedBusiness("TASK", false);
        var page = list(review);
        var head = designs.get(fixture.business.object.objectId());
        var draft =
                designs.editPublished(
                        new DataCenter.Revision(
                                head.draft().id(), head.draft().lockVersion(), null),
                        10001);
        var options = new LinkedHashMap<>(fixture.business.object.fieldOptions());
        var old = options.get(fixture.business.nameField);
        options.put(
                fixture.business.nameField,
                new DataCenter.FieldOptions(
                        old.columnName(),
                        "SENSITIVE",
                        old.defaultValue(),
                        old.description(),
                        old.pattern(),
                        old.minimum(),
                        old.maximum(),
                        old.state(),
                        old.options(),
                        old.expression(),
                        old.resultType(),
                        old.resolver(),
                        old.nativeType(),
                        old.primaryKey(),
                        old.generated()));
        var changed =
                designs.save(
                        new DataCenter.SaveDesign(
                                fixture.business.fixture.edit(
                                        draft.draft(),
                                        List.of(),
                                        List.of(),
                                        draft.draft().titleFieldId()),
                                null,
                                options,
                                null,
                                null,
                                null),
                        10001);
        var plan =
                publisher.plan(
                        new DataCenter.Revision(
                                changed.draft().id(), changed.draft().lockVersion(), null),
                        10001);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "提高材料字段敏感等级"), 10001)
                                .state())
                .isEqualTo("SUCCEEDED");
        assertThat(list(review).items().getFirst().state()).isEqualTo("UNAVAILABLE");
        assertThatThrownBy(() -> approvals.approveTask(20002L, approve(review, page.reviewToken())))
                .hasMessageContaining("材料");
    }

    @Test
    void publicStartSealsInitialFormBeforeGlobalValuesChangeAndNoneNodeCanReadIt()
            throws Exception {
        Task example =
                deploy(
                        "<userTask id=\"work\" name=\"首位审批\" flowable:assignee=\"10001\"/>",
                        "TASK",
                        false);
        var engine = FlowTaskIntegrationTest.engine;
        var definitions =
                FlowTaskIntegrationTest.flowContext.getBean(BpmProcessDefinitionService.class);
        var info =
                new BpmProcessDefinitionInfoDO()
                        .setFormType(10)
                        .setFormId(1L)
                        .setFormConf("{}")
                        .setFormFields(
                                List.of(
                                        "{\"type\":\"input\",\"field\":\"initial\",\"title\":\"发起内容\"}"));
        when(definitions.getProcessDefinitionInfo(example.getProcessDefinitionId()))
                .thenReturn(info);
        when(definitions.getProcessDefinition(example.getProcessDefinitionId()))
                .thenReturn(
                        engine.getRepositoryService()
                                .createProcessDefinitionQuery()
                                .processDefinitionId(example.getProcessDefinitionId())
                                .singleResult());
        when(definitions.canUserStartProcessDefinition(info, 10001L)).thenReturn(true);
        var service = spy(new BpmProcessInstanceServiceImpl());
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "runtimeService", engine.getRuntimeService());
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "processDefinitionService", definitions);
        var preview =
                new com.richuang.os.module.bpm.controller.admin.task.vo.instance
                        .BpmApprovalDetailRespVO();
        preview.setActivityNodes(new ArrayList<>());
        doReturn(preview).when(service).getApprovalDetail(eq(10001L), any());
        var proxy = new org.springframework.aop.framework.ProxyFactory(service);
        proxy.addAdvice(
                new org.springframework.transaction.interceptor.TransactionInterceptor(
                        servicesContext.getBean(
                                org.springframework.transaction.PlatformTransactionManager.class),
                        new org.springframework.transaction.annotation
                                .AnnotationTransactionAttributeSource()));
        var instanceService = (BpmProcessInstanceService) proxy.getProxy();
        String id =
                instanceService.createProcessInstance(
                        10001L,
                        new com.richuang.os.module.bpm.controller.admin.task.vo.instance
                                        .BpmProcessInstanceCreateReqVO()
                                .setProcessDefinitionId(example.getProcessDefinitionId())
                                .setVariables(Map.of("initial", "真正发起值")));
        engine.getRuntimeService().setVariable(id, "initial", "审批后改值");
        Task current = current(id);
        var page = materials.list(new FlowMaterials.Query(id, current.getId()), 10001);
        assertThat(page.items()).hasSize(1);
        var detail =
                materials.detail(
                        new FlowMaterials.Get(id, current.getId(), page.items().getFirst().id()),
                        10001);
        assertThat(detail.flowForm().values()).containsEntry("initial", "真正发起值");
        assertThat(page.items().getFirst().nodeName()).isEqualTo("发起申请");
        assertThatThrownBy(
                        () ->
                                approvals.approveTask(
                                        10001L,
                                        new BpmTaskApproveReqVO()
                                                .setId(current.getId())
                                                .setVariables(
                                                        Map.of(
                                                                BpmMaterialContextServiceImpl
                                                                        .START_MATERIAL,
                                                                "伪造"))))
                .hasMessageContaining("不能由客户端替换");
    }

    @Test
    void startUserAssignmentAutoCompletesButMaterialReviewAndTimeoutKeepHumanTask()
            throws Exception {
        Task example =
                deploy(
                        "<userTask id=\"StartUserNode\" name=\"发起人\" flowable:assignee=\"10001\"/>",
                        "TASK",
                        true);
        var engine = FlowTaskIntegrationTest.engine;
        var context = FlowTaskIntegrationTest.flowContext;
        var definitions = context.getBean(BpmProcessDefinitionService.class);
        var info =
                new BpmProcessDefinitionInfoDO()
                        .setFormType(10)
                        .setFormId(1L)
                        .setFormConf("{}")
                        .setFormFields(
                                List.of(
                                        "{\"type\":\"input\",\"field\":\"initial\",\"title\":\"发起内容\"}"));
        when(definitions.getProcessDefinitionInfo(example.getProcessDefinitionId()))
                .thenReturn(info);
        var user = new com.richuang.os.module.system.api.user.dto.AdminUserRespDTO();
        user.setId(10001L);
        user.setNickname("材料发起人");
        when(context.getBean(com.richuang.os.module.system.api.user.AdminUserApi.class)
                        .getUser(10001L))
                .thenReturn(user);
        var listener =
                new com.richuang.os.module.bpm.framework.flowable.core.listener
                        .BpmTaskEventListener();
        org.springframework.test.util.ReflectionTestUtils.setField(
                listener, "taskService", approvals);
        org.springframework.test.util.ReflectionTestUtils.setField(
                listener,
                "modelService",
                context.getBean(
                        com.richuang.os.module.bpm.service.definition.BpmModelService.class));
        var priorSpring = cn.hutool.extra.spring.SpringUtil.getApplicationContext();
        var priorFactory =
                org.springframework.test.util.ReflectionTestUtils.getField(
                        cn.hutool.extra.spring.SpringUtil.class, "beanFactory");
        new cn.hutool.extra.spring.SpringUtil().setApplicationContext(context);
        new cn.hutool.extra.spring.SpringUtil().postProcessBeanFactory(context.getBeanFactory());
        var dispatcher =
                ((org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl)
                                engine.getProcessEngineConfiguration())
                        .getEventDispatcher();
        dispatcher.addEventListener(
                listener,
                org.flowable.common.engine.api.delegate.event.FlowableEngineEventType
                        .TASK_ASSIGNED);
        try {
            var variables = new HashMap<String, Object>();
            variables.put(
                    BpmMaterialContextServiceImpl.START_MATERIAL,
                    mapper.writeValueAsString(
                            new com.richuang.os.module.bpm.api.task.dto.BpmMaterialReviewContextDTO
                                    .Form(
                                    "发起表单",
                                    "{}",
                                    info.getFormFields(),
                                    Map.of("initial", "发起材料"))));
            org.flowable.common.engine.impl.identity.Authentication.setAuthenticatedUserId("10001");
            var instance =
                    engine.getRuntimeService()
                            .startProcessInstanceById(example.getProcessDefinitionId(), variables);
            Task review = current(instance.getId());
            assertThat(review.getTaskDefinitionKey()).isEqualTo("review");
            assertThat(
                            engine.getHistoryService()
                                    .createHistoricTaskInstanceQuery()
                                    .processInstanceId(instance.getId())
                                    .taskDefinitionKey("StartUserNode")
                                    .finished()
                                    .count())
                    .isEqualTo(1);
            assertThat(list(review).items()).hasSize(1);
            approvals.processTaskTimeout(
                    instance.getId(),
                    "review",
                    com.richuang.os.module.bpm.enums.definition.BpmUserTaskTimeoutHandlerTypeEnum
                            .APPROVE
                            .getType());
            assertThat(current(instance.getId()).getId()).isEqualTo(review.getId());
            var page = list(review);
            approvals.approveTask(20002L, approve(review, page.reviewToken()));
            assertThat(current(instance.getId())).isNull();
        } finally {
            dispatcher.removeEventListener(listener);
            org.flowable.common.engine.impl.identity.Authentication.setAuthenticatedUserId(null);
            new cn.hutool.extra.spring.SpringUtil().setApplicationContext(priorSpring);
            new cn.hutool.extra.spring.SpringUtil()
                    .postProcessBeanFactory(
                            (org.springframework.beans.factory.config
                                            .ConfigurableListableBeanFactory)
                                    priorFactory);
        }
    }

    @Test
    void sameActorAutoApprovalPolicyPreservesMaterialReviewTaskWithoutAfterCommitError()
            throws Exception {
        Task review = submittedBusiness("TASK", false);
        var context = FlowTaskIntegrationTest.flowContext;
        var engine = FlowTaskIntegrationTest.engine;
        when(context.getBean(BpmProcessDefinitionService.class)
                        .getProcessDefinitionInfo(review.getProcessDefinitionId()))
                .thenReturn(new BpmProcessDefinitionInfoDO().setAutoApprovalType(1));
        engine.getRuntimeService()
                .setVariable(
                        review.getProcessInstanceId(),
                        BpmnVariableConstants.PROCESS_INSTANCE_VARIABLE_START_USER_ID,
                        10001L);
        // 发起身份通常由引擎认证上下文持久化；夹具源实例无 startUserId，给通知转换提供真实测试身份。
        var instance =
                engine.getRuntimeService()
                        .createProcessInstanceQuery()
                        .processInstanceId(review.getProcessInstanceId())
                        .singleResult();
        jdbc.update(
                "UPDATE act_ru_execution SET start_user_id_='10001' WHERE id_=?", instance.getId());
        engine.getTaskService().setAssignee(review.getId(), "10001");
        var tx =
                new org.springframework.transaction.support.TransactionTemplate(
                        servicesContext.getBean(
                                org.springframework.transaction.PlatformTransactionManager.class));
        assertThatCode(
                        () ->
                                tx.executeWithoutResult(
                                        status ->
                                                approvals.processTaskAssigned(
                                                        engine.getTaskService()
                                                                .createTaskQuery()
                                                                .taskId(review.getId())
                                                                .singleResult())))
                .doesNotThrowAnyException();
        assertThat(engine.getTaskService().createTaskQuery().taskId(review.getId()).count())
                .isEqualTo(1);
    }

    @Test
    void independentFlowFormSealsLocalValuesAndNoneReviewerStillReads() throws Exception {
        Task source = startFlowForm(false);
        approvals.approveTask(
                10001L,
                new BpmTaskApproveReqVO()
                        .setId(source.getId())
                        .setReason("提交表单")
                        .setVariables(Map.of("visible", "提交时值", "hidden", "隐藏值")));
        var review = current(source.getProcessInstanceId());
        FlowTaskIntegrationTest.engine
                .getRuntimeService()
                .setVariable(review.getProcessInstanceId(), "visible", "全局后改值");
        var page = list(review);
        assertThat(page.items()).hasSize(1);
        assertThat(page.blockedReason()).isNull();
        var detail =
                materials.detail(
                        new FlowMaterials.Get(
                                review.getProcessInstanceId(),
                                review.getId(),
                                page.items().getFirst().id()),
                        20002);
        assertThat(detail.flowForm().values()).containsExactlyEntriesOf(Map.of("visible", "提交时值"));
        approvals.approveTask(20002L, approve(review, page.reviewToken()));
    }

    @Test
    void legacyFlowFormMissingLocalValuesIsExplicitAndNeverCopiesGlobalState() throws Exception {
        Task source = startFlowForm(true);

        var engine = FlowTaskIntegrationTest.engine;
        engine.getRuntimeService()
                .setVariables(
                        source.getProcessInstanceId(),
                        Map.of("visible", "全局当前值", "hidden", "全局敏感值"));
        engine.getTaskService()
                .setVariableLocal(
                        source.getId(),
                        BpmnVariableConstants.TASK_VARIABLE_STATUS,
                        BpmTaskStatusEnum.APPROVE.getStatus());
        engine.getTaskService().complete(source.getId());
        var page = list(current(source.getProcessInstanceId()));
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().state()).isEqualTo("UNAVAILABLE");
        assertThat(page.items().getFirst().warning()).contains("未以流程当前变量补齐");
    }

    @Test
    void legacyTaskLocalFormFieldsStillBlockUnsupportedBranchWithoutReadingGlobalValues()
            throws Exception {
        Task example = startFlowForm(true);
        var engine = FlowTaskIntegrationTest.engine;
        var model =
                BpmnModelUtils.getBpmnModel(
                        new org.flowable.bpmn.converter.BpmnXMLConverter()
                                .convertToXML(
                                        engine.getRepositoryService()
                                                .getBpmnModel(example.getProcessDefinitionId())));
        String key = "material_branch_" + UUID.randomUUID().toString().replace("-", "");
        model.getMainProcess().setId(key);
        var gateway = new org.flowable.bpmn.model.ExclusiveGateway();
        gateway.setId("branch");
        model.getMainProcess().addFlowElement(gateway);
        ((org.flowable.bpmn.model.SequenceFlow) model.getFlowElement("b")).setSourceRef("branch");
        var into = new org.flowable.bpmn.model.SequenceFlow("work", "branch");
        into.setId("intoBranch");
        model.getMainProcess().addFlowElement(into);
        var deployment =
                engine.getRepositoryService()
                        .createDeployment()
                        .addBytes(
                                key + ".bpmn20.xml",
                                new org.flowable.bpmn.converter.BpmnXMLConverter()
                                        .convertToXML(model))
                        .deploy();
        fixture.deployments.add(deployment.getId());
        var instance = engine.getRuntimeService().startProcessInstanceByKey(key);
        Task source = current(instance.getId());
        engine.getTaskService()
                .setVariableLocal(
                        source.getId(),
                        BpmnVariableConstants.TASK_VARIABLE_STATUS,
                        BpmTaskStatusEnum.APPROVE.getStatus());
        engine.getTaskService().complete(source.getId(), Map.of("visible", "历史局部字段"), true);
        Task review = current(instance.getId());
        engine.getRuntimeService().setVariable(instance.getId(), "visible", "不能拿来补材料的全局值");
        var historical =
                engine.getHistoryService()
                        .createHistoricTaskInstanceQuery()
                        .taskId(source.getId())
                        .includeTaskLocalVariables()
                        .singleResult();
        assertThat(historical.getTaskLocalVariables())
                .containsKey("visible")
                .doesNotContainKey(BpmMaterialContextServiceImpl.FORM_MATERIAL);
        var page = list(review);
        assertThat(page.blockedReason()).contains("分支");
        assertThat(page.items()).isEmpty();
        assertThat(page.reviewRequired()).isTrue();
        assertThat(page.reviewToken()).isNull();
        assertThatThrownBy(() -> approvals.approveTask(20002L, approve(review, null)))
                .hasMessageContaining("分支");
        assertThat(current(instance.getId()).getId()).isEqualTo(review.getId());
    }

    private Task submittedBusiness(String access, boolean sequential) throws Exception {
        var config =
                mapper.writeValueAsString(
                        new FlowTasks.Configuration(
                                fixture.business.resource,
                                fixture.business.object.objectId(),
                                "CREATE"));
        Task source =
                deploy(
                        "<userTask id=\"work\" name=\"业务办理\" flowable:assignee=\"10001\""
                                + " os:handler=\"nocode\" os:configuration=\""
                                + escape(config)
                                + "\"/>",
                        access,
                        sequential);
        var draft =
                FlowTaskIntegrationTest.flows.saveDraft(
                        new FlowTasks.Save(
                                source.getId(),
                                null,
                                null,
                                Map.of(fixture.business.nameField, "封存值")),
                        10001);
        FlowTaskIntegrationTest.flows.submit(
                new FlowTasks.Submit(
                        source.getId(),
                        draft.id(),
                        draft.revision(),
                        UUID.randomUUID().toString(),
                        "材料"),
                10001);
        return current(source.getProcessInstanceId());
    }

    private Task startFlowForm(boolean legacy) throws Exception {
        var binding =
                Map.of(
                        "mode",
                        "OVERRIDE",
                        "taskMode",
                        "APPROVAL",
                        "source",
                        Map.of("kind", "FLOW_FORM", "formId", "1"),
                        "formId",
                        "1",
                        "formName",
                        "前序表单",
                        "formConf",
                        "{}",
                        "formFields",
                        List.of(
                                "{\"type\":\"input\",\"field\":\"visible\",\"title\":\"内容\"}",
                                "{\"type\":\"input\",\"field\":\"hidden\",\"title\":\"隐藏内容\"}"));
        return deploy(
                "<userTask id=\"work\" name=\"填表\" flowable:assignee=\"10001\" nf:resolved=\""
                        + escape(mapper.writeValueAsString(binding))
                        + "\"/>",
                "TASK",
                false);
    }

    private Task deploy(String source, String access, boolean sequential) throws Exception {
        String key = "material_" + UUID.randomUUID().toString().replace("-", "");
        var rule = new LinkedHashMap<String, Object>();
        rule.put("mode", "OVERRIDE");
        rule.put("taskMode", "APPROVAL");
        rule.put("source", Map.of("kind", "NONE"));
        if (access != null)
            rule.put("materialReview", Map.of("scope", "PREVIOUS", "access", access));
        String loop =
                sequential
                        ? "<multiInstanceLoopCharacteristics"
                              + " isSequential=\"true\"><loopCardinality>1</loopCardinality></multiInstanceLoopCharacteristics>"
                        : "";
        String xml =
                "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                        + " xmlns:flowable=\"http://flowable.org/bpmn\" xmlns:os=\""
                        + BpmBusinessTaskBinding.NAMESPACE
                        + "\" xmlns:nf=\""
                        + BpmNodeFormBinding.NAMESPACE
                        + "\" targetNamespace=\"material\"><process id=\""
                        + key
                        + "\" isExecutable=\"true\"><startEvent id=\"start\"/><sequenceFlow"
                        + " id=\"a\" sourceRef=\"start\" targetRef=\"work\"/>"
                        + source
                        + "<sequenceFlow id=\"b\" sourceRef=\"work\""
                        + " targetRef=\"review\"/><userTask id=\"review\" name=\"材料审批\""
                        + " flowable:assignee=\"20002\" nf:resolved=\""
                        + escape(mapper.writeValueAsString(rule))
                        + "\"><extensionElements><flowable:fieldsPermission field=\"hidden\""
                        + " permission=\"3\"/></extensionElements>"
                        + loop
                        + "</userTask><sequenceFlow id=\"c\" sourceRef=\"review\""
                        + " targetRef=\"end\"/><endEvent id=\"end\"/></process></definitions>";
        var engine = FlowTaskIntegrationTest.engine;
        if (source.contains("id=\"StartUserNode\""))
            xml =
                    xml.replace("targetRef=\"work\"", "targetRef=\"StartUserNode\"")
                            .replace("sourceRef=\"work\"", "sourceRef=\"StartUserNode\"");
        if (source.contains("id=\"work2\""))
            xml = xml.replace("id=\"b\" sourceRef=\"work\"", "id=\"b\" sourceRef=\"work2\"");
        var deployment =
                engine.getRepositoryService()
                        .createDeployment()
                        .addString(key + ".bpmn20.xml", xml)
                        .deploy();
        fixture.deployments.add(deployment.getId());
        var instance = engine.getRuntimeService().startProcessInstanceByKey(key);
        var task = current(instance.getId());
        fixture.taskIds.add(task.getId());
        return task;
    }

    private Task current(String instance) {
        return FlowTaskIntegrationTest.engine
                .getTaskService()
                .createTaskQuery()
                .processInstanceId(instance)
                .singleResult();
    }

    private FlowMaterials.Page list(Task task) {
        return materials.list(
                new FlowMaterials.Query(task.getProcessInstanceId(), task.getId()), 20002);
    }

    private BpmTaskApproveReqVO approve(Task task, String token) {
        return new BpmTaskApproveReqVO()
                .setId(task.getId())
                .setReason("已核对材料")
                .setMaterialReviewToken(token);
    }

    private int draftCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM public.nocode_work_draft WHERE object_id=?",
                Integer.class,
                fixture.business.object.objectId());
    }

    private String escape(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }
}
