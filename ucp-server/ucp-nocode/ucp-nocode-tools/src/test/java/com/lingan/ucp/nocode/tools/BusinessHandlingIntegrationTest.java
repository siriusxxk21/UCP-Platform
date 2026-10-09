package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lingan.ucp.module.bpm.api.definition.*;
import com.lingan.ucp.module.bpm.api.definition.dto.BpmProcessDefinitionDTO;
import com.lingan.ucp.module.bpm.api.event.*;
import com.lingan.ucp.module.bpm.api.task.*;
import com.lingan.ucp.module.bpm.api.task.dto.*;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.work.*;
import com.lingan.ucp.nocode.runtime.service.handling.*;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;

/** 真实发布、数据库和事务的申请专项；BPM 边界使用契约替身，实际引擎链路另行验收。 */
class BusinessHandlingIntegrationTest {
    @Test
    void rejectedMaterialCanBeEditedAsANewRequestWithoutChangingTheOriginal() {
        policy("APPROVAL", "APPROVAL");
        var original = service.submit(command("原申请材料"), 10001).request();
        assertThatThrownBy(() -> service.reopen(original.id(), 10001)).hasMessageContaining("已驳回");
        event(original, BpmProcessInstanceStatus.REJECTED);
        assertThatThrownBy(() -> service.reopen(original.id(), 20002)).hasMessageContaining("无权");
        var reopened = service.reopen(original.id(), 10001);
        assertThat(reopened.initial().record().values()).containsEntry(business.nameField, "原申请材料");
        assertThat(reopened.initial().record().id()).isNull();
        var next = service.submit(command("修正后的材料"), 10001).request();
        assertThat(next.id()).isNotEqualTo(original.id());
        assertThat(service.detail(original.id(), null, 10001).material().values())
                .containsEntry(business.nameField, "原申请材料");
        assertThat(service.detail(original.id(), null, 10001).request().status())
                .isEqualTo("REJECTED");
    }

    @Test
    void reopeningRechecksWritePermissionAndOriginalBusinessRevision() {
        var before = business.records.save(command("有效记录"), 10001);
        business.grantMember(Set.of("READ", "CREATE"), Set.of(business.nameField));
        policy("APPROVAL", "APPROVAL");
        var request = service.submit(command("员工原申请"), 20002).request();
        event(request, BpmProcessInstanceStatus.REJECTED);
        business.grantMember(Set.of("READ"), Set.of());
        assertThatThrownBy(() -> service.reopen(request.id(), 20002)).hasMessageContaining("权限");
        var change =
                service.submit(command("待修正变更", before, UUID.randomUUID().toString()), 10001)
                        .request();
        event(change, BpmProcessInstanceStatus.REJECTED);
        var newer =
                service.submit(command("新版本有效值", before, UUID.randomUUID().toString()), 10001)
                        .request();
        event(newer, BpmProcessInstanceStatus.APPROVED);
        assertThat(service.detail(newer.id(), null, 10001).request().status())
                .isEqualTo("APPROVED");
        assertThatThrownBy(() -> service.reopen(change.id(), 10001)).hasMessageContaining("已变化");
    }

    @Test
    void concurrentDifferentKeysCannotCreateTwoPendingChangesForOneRecord() throws Exception {
        var before = business.records.save(command("原记录"), 10001);
        policy("DIRECT", "APPROVAL");
        var started = new java.util.concurrent.atomic.AtomicInteger();
        var rejected = new java.util.concurrent.atomic.AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 2; i++)
                futures.add(
                        pool.submit(
                                () -> {
                                    try {
                                        service.submit(
                                                command(
                                                        "并发变更",
                                                        before,
                                                        UUID.randomUUID().toString()),
                                                10001);
                                        started.incrementAndGet();
                                    } catch (
                                            com.lingan.ucp.framework.common.exception
                                                            .ServiceException
                                                    e) {
                                        assertThat(e.getMessage()).contains("保护");
                                        rejected.incrementAndGet();
                                    }
                                }));
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
        }
        assertThat(started.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(1);
    }

    @Test
    void myRequestsPaginatesBeforeReturningAndRestrictsOwnerAndState() {
        policy("APPROVAL", "DIRECT");
        var a = service.submit(command("申请一"), 10001).request();
        var b = service.submit(command("申请二"), 10001).request();
        event(a, BpmProcessInstanceStatus.REJECTED);
        var first =
                service.mine(
                        new BusinessHandling.Query(1, 1, null, business.resource.applicationId()),
                        10001);
        var second =
                service.mine(
                        new BusinessHandling.Query(2, 1, null, business.resource.applicationId()),
                        10001);
        assertThat(first.getTotal()).isEqualTo(2);
        assertThat(first.getList()).hasSize(1);
        assertThat(second.getList()).hasSize(1);
        assertThat(first.getList().getFirst().id()).isNotEqualTo(second.getList().getFirst().id());
        assertThat(
                        service.mine(
                                        new BusinessHandling.Query(
                                                1,
                                                20,
                                                "PENDING",
                                                business.resource.applicationId()),
                                        10001)
                                .getList())
                .extracting(BusinessHandling.Request::id)
                .containsExactly(b.id());
        assertThat(
                        service.mine(
                                        new BusinessHandling.Query(
                                                1, 20, null, business.resource.applicationId()),
                                        20002)
                                .getTotal())
                .isZero();
    }

    WorkDraftIntegrationTest business;
    BusinessHandlingService service;
    BpmProcessInstanceApi instances;

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
        business = new WorkDraftIntegrationTest();
        business.setup();
        service = servicesContext.getBean(BusinessHandlingService.class);
        instances = servicesContext.getBean(BpmProcessInstanceApi.class);
        reset(instances);
        var definition = new BpmProcessDefinitionDTO();
        definition.setId("approval:1");
        definition.setKey("approval");
        definition.setName("审批契约夹具");
        definition.setFormType(20);
        definition.setFormCustomViewPath("/nocode-app/process-record");
        when(servicesContext
                        .getBean(BpmProcessDefinitionApi.class)
                        .getProcessDefinition("approval:1"))
                .thenReturn(definition);
        when(instances.createProcessInstance(anyLong(), any()))
                .thenAnswer(i -> UUID.randomUUID().toString());
    }

    @AfterEach
    void cleanup() {
        jdbc.update(
                "DELETE FROM public.nocode_handling_request WHERE application_id=CAST(? AS bigint)",
                business.resource.applicationId());
        business.cleanup();
    }

    void policy(String create, String update) {
        var head = designs.get(business.object.objectId());
        var current =
                designs.editPublished(
                        new DataCenter.Revision(
                                head.draft().id(), head.draft().lockVersion(), "调整办理策略"),
                        10001);
        var d = current.draft();
        var s = current.settings();
        var p =
                new DocumentPolicy(
                        List.of(), null, new BusinessHandling.Policy(rule(create), rule(update)));
        var design =
                designs.save(
                        new DataCenter.SaveDesign(
                                new SaveObjectDraft(
                                        d.id(),
                                        d.lockVersion(),
                                        d.objectCode(),
                                        d.objectName(),
                                        d.description(),
                                        d.tableName(),
                                        d.titleFieldId(),
                                        d.fields(),
                                        List.of()),
                                new DataCenter.Settings(
                                        s.icon(),
                                        s.ownerId(),
                                        s.organizationId(),
                                        s.titleTemplate(),
                                        p),
                                current.fieldOptions(),
                                current.relations(),
                                current.indexes(),
                                current.details()),
                        10001);
        var plan =
                publisher.plan(
                        new DataCenter.Revision(d.id(), design.draft().lockVersion(), null), 10001);
        assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "审批策略验收"), 10001)
                                .state())
                .isEqualTo("SUCCEEDED");
    }

    BusinessHandling.Rule rule(String mode) {
        return "DIRECT".equals(mode)
                ? null
                : new BusinessHandling.Rule(
                        mode,
                        "approval:1",
                        "CONDITIONAL".equals(mode)
                                ? new DocumentPolicy.Expression(
                                        "EQ",
                                        null,
                                        null,
                                        null,
                                        List.of(
                                                new DocumentPolicy.Expression(
                                                        "FIELD",
                                                        business.nameField,
                                                        null,
                                                        null,
                                                        List.of()),
                                                new DocumentPolicy.Expression(
                                                        "VALUE", null, null, "审批", List.of())))
                                : null,
                        Map.of());
    }

    ApplicationRecords.Save command(String value, ApplicationRecords.Aggregate before, String key) {
        return new ApplicationRecords.Save(
                business.resource.applicationId(),
                business.object.objectId(),
                before == null ? null : before.record().id(),
                before == null ? null : before.record().revision(),
                Map.of(business.nameField, value),
                Map.of(),
                Map.of(),
                null,
                business.resource.resourceId(),
                key,
                null);
    }

    ApplicationRecords.Save command(String value) {
        return command(value, null, UUID.randomUUID().toString());
    }

    void event(BusinessHandling.Request request, int status) {
        var e = new BpmProcessInstanceStatusEvent(this);
        e.setId(request.processInstanceId());
        e.setBusinessKey("nocode-handling:" + request.id());
        e.setProcessDefinitionKey("approval");
        e.setStatus(status);
        servicesContext.publishEvent(e);
    }

    BusinessHandling.Request request(String id) {
        return service.detail(id, null, 10001).request();
    }

    @Test
    void directAndConditionalRespectLatestObjectPolicyAcrossOldApplicationVersions() {
        policy("CONDITIONAL", "DIRECT");
        assertThat(service.submit(command("直接"), 10001).outcome()).isEqualTo("EFFECTIVE");
        var cmd = command("审批");
        assertThatThrownBy(() -> business.records.save(cmd, 10001)).hasMessageContaining("需要审批");
        var pending = service.submit(cmd, 10001);
        assertThat(pending.outcome()).isEqualTo("SUBMITTED");
        assertThat(business.recordCount()).isEqualTo(1);
        event(pending.request(), BpmProcessInstanceStatus.APPROVED);
        assertThat(request(pending.request().id()).status()).isEqualTo("APPROVED");
        assertThat(business.recordCount()).isEqualTo(2);
        event(pending.request(), BpmProcessInstanceStatus.APPROVED);
        service.reconcile();
        assertThat(business.recordCount()).isEqualTo(2);
        assertThat(
                        service.receipt(
                                        new BusinessHandling.Receipt(
                                                cmd.applicationId(),
                                                cmd.objectId(),
                                                cmd.requestKey()),
                                        10001)
                                .outcome())
                .isEqualTo("EFFECTIVE");
    }

    @Test
    void pendingUpdateKeepsOldValuesProtectsRecordAndRejectionUnlocks() {
        var before = business.records.save(command("原值"), 10001);
        policy("DIRECT", "APPROVAL");
        var pending =
                service.submit(command("新值", before, UUID.randomUUID().toString()), 10001)
                        .request();
        assertThat(
                        business.records
                                .get(
                                        business.resource.applicationId(),
                                        business.object.objectId(),
                                        before.record().id(),
                                        10001)
                                .record()
                                .values()
                                .get(business.nameField))
                .isEqualTo("原值");
        assertThatThrownBy(
                        () ->
                                service.submit(
                                        command("冲突申请", before, UUID.randomUUID().toString()),
                                        10001))
                .hasMessageContaining("保护");
        assertThatThrownBy(
                        () ->
                                business.records.delete(
                                        new ApplicationRecords.Delete(
                                                business.resource.applicationId(),
                                                business.object.objectId(),
                                                before.record().id(),
                                                before.record().revision()),
                                        10001))
                .hasMessageContaining("保护");
        event(pending, BpmProcessInstanceStatus.REJECTED);
        assertThat(request(pending.id()).status()).isEqualTo("REJECTED");
        var next =
                service.submit(command("再次变更", before, UUID.randomUUID().toString()), 10001)
                        .request();
        event(next, BpmProcessInstanceStatus.APPROVED);
        assertThat(request(next.id()).status()).isEqualTo("APPROVED");
        assertThat(
                        business.records
                                .get(
                                        business.resource.applicationId(),
                                        business.object.objectId(),
                                        before.record().id(),
                                        10001)
                                .record()
                                .values()
                                .get(business.nameField))
                .isEqualTo("再次变更");
    }

    @Test
    void invalidAndFailedStartLeaveNoBusinessOrMaterialAndRepeatedCommandsStartOnlyOnce()
            throws Exception {
        policy("APPROVAL", "DIRECT");
        assertThatThrownBy(() -> service.submit(command(""), 10001));
        when(instances.createProcessInstance(anyLong(), any()))
                .thenThrow(new IllegalStateException("模拟引擎启动失败"));
        assertThatThrownBy(() -> service.submit(command("失败"), 10001));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_handling_request WHERE"
                                        + " application_id=CAST(? AS bigint)",
                                Long.class,
                                business.resource.applicationId()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_work_draft WHERE"
                                        + " resource_json->>'applicationId'=?",
                                Long.class,
                                business.resource.applicationId()))
                .isZero();
        doAnswer(i -> UUID.randomUUID().toString())
                .when(instances)
                .createProcessInstance(anyLong(), any());
        clearInvocations(instances);
        var cmd = command("并发提交");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> service.submit(cmd, 10001));
            var b = pool.submit(() -> service.submit(cmd, 10001));
            assertThat(a.get(20, TimeUnit.SECONDS).request().id())
                    .isEqualTo(b.get(20, TimeUnit.SECONDS).request().id());
        }
        verify(instances, times(1)).createProcessInstance(anyLong(), any());
        assertThat(business.recordCount()).isZero();
        assertThatThrownBy(() -> service.submit(command("另一份内容", null, cmd.requestKey()), 10001))
                .hasMessageContaining("请求标识");
    }

    @Test
    void revokedPermissionFailsApplicationTruthfullyAndRetryAppliesOnlyOnce() {
        policy("APPROVAL", "DIRECT");
        business.grantMember(Set.of("READ", "CREATE"), Set.of(business.nameField));
        var pending = service.submit(command("员工申请"), 20002).request();
        business.grantMember(Set.of("READ"), Set.of());
        event(pending, BpmProcessInstanceStatus.APPROVED);
        var failed = service.detail(pending.id(), null, 20002).request();
        assertThat(failed.status()).isEqualTo("APPLY_FAILED");
        assertThat(business.recordCount()).isZero();
        assertThatThrownBy(
                        () ->
                                service.retry(
                                        new BusinessHandling.Retry(failed.id(), failed.revision()),
                                        10001))
                .hasMessageContaining("无权");
        business.grantMember(Set.of("READ", "CREATE"), Set.of(business.nameField));
        assertThat(
                        service.retry(
                                        new BusinessHandling.Retry(failed.id(), failed.revision()),
                                        20002)
                                .status())
                .isEqualTo("APPROVED");
        assertThat(business.recordCount()).isEqualTo(1);
        assertThatThrownBy(
                () ->
                        service.retry(
                                new BusinessHandling.Retry(failed.id(), failed.revision()), 20002));
    }

    @Test
    void autoCompletionBeforeInstanceAttachmentAndDuplicateTerminalEventsAreSafe() {
        policy("APPROVAL", "DIRECT");
        when(instances.createProcessInstance(anyLong(), any()))
                .thenAnswer(
                        i -> {
                            var cmd = i.getArgument(1, BpmProcessInstanceCreateReqDTO.class);
                            var id = UUID.randomUUID().toString();
                            var e = new BpmProcessInstanceStatusEvent(this);
                            e.setId(id);
                            e.setBusinessKey(cmd.getBusinessKey());
                            e.setProcessDefinitionKey("approval");
                            e.setStatus(BpmProcessInstanceStatus.APPROVED);
                            servicesContext.publishEvent(e);
                            return id;
                        });
        var result = service.submit(command("自动审批"), 10001);
        assertThat(request(result.request().id()).status()).isEqualTo("APPROVED");
        assertThat(business.recordCount()).isEqualTo(1);
        event(result.request(), BpmProcessInstanceStatus.REJECTED);
        assertThat(request(result.request().id()).status()).isEqualTo("APPROVED");
    }

    @Test
    void onlyOwnerOrActualAssignedApproverReadsSealedMaterialAndWithdrawUsesBpm() {
        policy("APPROVAL", "DIRECT");
        var pending = service.submit(command("待撤回"), 10001).request();
        assertThatThrownBy(() -> service.detail(pending.id(), null, 20002))
                .hasMessageContaining("无权");
        var tasks = servicesContext.getBean(BpmProcessTaskApi.class);
        when(tasks.getAssignedTask(20002, "review"))
                .thenReturn(
                        new BpmBusinessTaskDTO(
                                "review",
                                pending.processInstanceId(),
                                pending.processDefinitionId(),
                                "execution",
                                "review",
                                "20002",
                                null,
                                null));
        assertThat(
                        service.detail(pending.id(), "review", 20002)
                                .material()
                                .values()
                                .get(business.nameField))
                .isEqualTo("待撤回");
        when(tasks.getAssignedTask(20002, "wrong"))
                .thenReturn(
                        new BpmBusinessTaskDTO(
                                "wrong",
                                "other",
                                pending.processDefinitionId(),
                                "execution",
                                "review",
                                "20002",
                                null,
                                null));
        assertThatThrownBy(() -> service.detail(pending.id(), "wrong", 20002))
                .hasMessageContaining("不属于");
        doAnswer(
                        i -> {
                            event(pending, BpmProcessInstanceStatus.CANCELED);
                            return null;
                        })
                .when(instances)
                .cancelByStarter(eq(10001L), eq(pending.processInstanceId()), anyString());
        assertThat(
                        service.withdraw(
                                        new BusinessHandling.Withdraw(
                                                pending.id(), pending.revision(), "重新填写"),
                                        10001)
                                .status())
                .isEqualTo("CANCELED");
        assertThat(business.recordCount()).isZero();
    }

    @Test
    void metadataChangeRequiresFreshApprovalAndDoesNotWriteOriginalProposal() {
        policy("APPROVAL", "DIRECT");
        var pending = service.submit(command("配置变化"), 10001).request();
        policy("CONDITIONAL", "DIRECT");
        event(pending, BpmProcessInstanceStatus.APPROVED);
        var failed = request(pending.id());
        assertThat(failed.status()).isEqualTo("APPLY_FAILED");
        assertThat(failed.error()).contains("配置已变化");
        assertThat(business.recordCount()).isZero();
        assertThat(
                        service.withdraw(
                                        new BusinessHandling.Withdraw(
                                                failed.id(), failed.revision(), "按新配置重新申请"),
                                        10001)
                                .status())
                .isEqualTo("CANCELED");
    }

    @Test
    void failureAfterBusinessWriteRollsBackRecordHistoryAndReceiptThenRetries() {
        policy("APPROVAL", "DIRECT");
        var cmd = command("事务回滚");
        var pending = service.submit(cmd, 10001).request();
        writeFailure.failAfter("INSERT INTO public.nocode_document_receipt");
        try {
            event(pending, BpmProcessInstanceStatus.APPROVED);
        } finally {
            writeFailure.clear();
        }
        var failed = request(pending.id());
        assertThat(failed.status()).isEqualTo("APPLY_FAILED");
        assertThat(business.recordCount()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_document_receipt WHERE"
                                        + " application_id=CAST(? AS bigint)",
                                Long.class,
                                cmd.applicationId()))
                .isZero();
        assertThat(
                        service.retry(
                                        new BusinessHandling.Retry(failed.id(), failed.revision()),
                                        10001)
                                .status())
                .isEqualTo("APPROVED");
        assertThat(business.recordCount()).isEqualTo(1);
    }
}
