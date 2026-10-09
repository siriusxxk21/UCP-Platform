package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.FieldRuleFixture.*;
import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lingan.ucp.module.bpm.api.definition.BpmProcessDefinitionApi;
import com.lingan.ucp.module.bpm.api.definition.dto.BpmProcessDefinitionDTO;
import com.lingan.ucp.module.bpm.api.event.BpmProcessInstanceStatus;
import com.lingan.ucp.module.bpm.api.event.BpmProcessInstanceStatusEvent;
import com.lingan.ucp.module.bpm.api.task.BpmProcessInstanceApi;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationRecords.*;
import com.lingan.ucp.nocode.runtime.service.handling.BusinessHandlingService;

import org.junit.jupiter.api.*;

import java.util.*;

/**
 * 数据联动自动更新与审批办理的交界（真实发布、数据库和事务；BPM 边界用契约替身）：
 *
 * <ul>
 *   <li>目标对象配了「修改须审批」、目标记录有处理中的变更申请：来源照常保存、目标照常更新；对照组——普通保存仍被拦。
 *   <li>来源经审批通过后生效：目标同样更新（审批材料只约束被审批的那条来源命令）。
 * </ul>
 */
class LinkageProcessIntegrationTest {
    private LinkageSyncFixture x;
    private BusinessHandlingService handling;

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
        x = new LinkageSyncFixture();
        handling = servicesContext.getBean(BusinessHandlingService.class);
        var instances = servicesContext.getBean(BpmProcessInstanceApi.class);
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
        x.cleanup();
    }

    private static DataCenter.Settings approval(boolean create, boolean update) {
        var rule = new BusinessHandling.Rule("APPROVAL", "approval:1", null, Map.of());
        return new DataCenter.Settings(
                null,
                null,
                null,
                null,
                new DocumentPolicy(
                        List.of(),
                        null,
                        new BusinessHandling.Policy(create ? rule : null, update ? rule : null)));
    }

    private static ApplicationCenter.Resource form(
            String id, DataCenter.Definition d, String... fieldIds) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (String fieldId : fieldIds)
            nodes.add(
                    Map.of(
                            "id",
                            "node-" + fieldId,
                            "type",
                            "FIELD",
                            "fieldId",
                            fieldId,
                            "children",
                            List.of()));
        return new ApplicationCenter.Resource(
                id,
                "FORM",
                id.replace('-', '_'),
                "表单" + id,
                Map.of("objectId", d.objectId(), "detailIds", List.of(), "nodes", nodes));
    }

    private void event(BusinessHandling.Request request, int status) {
        var e = new BpmProcessInstanceStatusEvent(this);
        e.setId(request.processInstanceId());
        e.setBusinessKey("nocode-handling:" + request.id());
        e.setProcessDefinitionKey("approval");
        e.setStatus(status);
        servicesContext.publishEvent(e);
    }

    /** 目标对象「修改须审批」+ 目标记录有处理中的变更申请：系统照常写联动字段；普通保存仍被拦。 */
    @Test
    void targetRequiringApprovalAndUnderPendingRequestIsStillUpdated() {
        x.flow = x.flowObject(approval(false, true));
        x.voucher = x.voucherObject(x.flow, "RESTRICT", DataCenter.Settings.defaults());
        x.flow = rules(x.flow, id(x.flow, "status"), x.benchmarkRule("FIRST"));
        String memo = id(x.flow, "memo");
        x.app =
                x.f.app(
                        List.of(form("flow-form", x.flow, id(x.flow, "name"), memo)),
                        x.flow,
                        x.voucher);
        var a = x.createFlow("流水");
        assertThat(x.flowStatus(a.id())).isEqualTo("wdj");
        // 对照组：对象配了修改须审批，普通保存被拦。
        assertThatThrownBy(() -> x.update(x.flow, a.id(), Map.of(memo, "手改")))
                .hasMessageContaining("需要审批");
        // 系统写联动字段不走审批。
        var v = x.createVoucher("凭证", a.id(), "ylr");
        assertThat(x.flowStatus(a.id())).as("目标对象修改须审批").isEqualTo("ylr");

        // 给这条流水提交一份变更申请（处理中）：记录受保护。
        var current = x.runtime.get(x.app, x.flow.objectId(), a.id(), 10001).record();
        var pending =
                handling.submit(
                        new Save(
                                x.app,
                                x.flow.objectId(),
                                a.id(),
                                current.revision(),
                                Map.of(memo, "申请改备注"),
                                Map.of(),
                                Map.of(),
                                null,
                                "flow-form",
                                UUID.randomUUID().toString(),
                                null),
                        10001);
        assertThat(pending.outcome()).isEqualTo("SUBMITTED");
        assertThatThrownBy(() -> x.update(x.flow, a.id(), Map.of(memo, "再手改")))
                .as("对照组：处理中的变更申请保护着这条记录")
                .hasMessageContaining("受流程保护");
        x.update(x.voucher, v.id(), Map.of(id(x.voucher, "status"), "ysh"));
        assertThat(x.flowStatus(a.id())).as("目标有处理中的变更申请").isEqualTo("ysh");
        assertThat(x.column(x.flow, a.id(), "memo")).as("被拦的手改与未生效的申请都没有落库").isNull();

        // 已知后果（不在本期修）：申请提交之后目标被系统改过，审批通过后生效时会发现记录已变。把实际结果打出来留档。
        event(pending.request(), BpmProcessInstanceStatus.APPROVED);
        var after = handling.detail(pending.request().id(), null, 10001).request();
        System.out.println(
                "P3-PROBE 目标有在途变更申请时被系统更新，审批通过后的申请状态="
                        + after.status()
                        + " 备注列="
                        + x.column(x.flow, a.id(), "memo")
                        + " 申请="
                        + after);
        assertThat(after.status()).isIn("APPROVED", "APPLY_FAILED");
        assertThat(x.flowStatus(a.id())).isEqualTo("ysh");
    }

    /** 来源经审批通过后生效：目标更新。目标对象带单据策略时，不拿目标整单去和来源的审批材料比对。 */
    @Test
    void sourceAppliedAfterApprovalUpdatesTheTarget() {
        // 目标带一条整单规则：有单据策略，系统回写它时会走整单复核。
        x.flow = x.flowObject(LinkageSyncFixture.nonNegativeTotal());
        x.voucher = x.voucherObject(x.flow, "RESTRICT", approval(true, false));
        x.flow = rules(x.flow, id(x.flow, "status"), x.benchmarkRule("FIRST"));
        String name = id(x.voucher, "name"),
                status = id(x.voucher, "status"),
                flowRef = relationField(x.voucher, "flow");
        x.app =
                x.f.app(
                        List.of(form("voucher-form", x.voucher, name, status, flowRef)),
                        x.flow,
                        x.voucher);
        var a = x.createFlowWithTotal("流水", "0");
        var submitted =
                handling.submit(
                        new Save(
                                x.app,
                                x.voucher.objectId(),
                                null,
                                null,
                                values(name, "待审批的凭证", status, "ylr", flowRef, a.id()),
                                Map.of(),
                                Map.of(),
                                null,
                                "voucher-form",
                                UUID.randomUUID().toString(),
                                null),
                        10001);
        assertThat(submitted.outcome()).isEqualTo("SUBMITTED");
        assertThat(x.flowStatus(a.id())).as("审批通过前业务数据保持原状").isEqualTo("wdj");
        event(submitted.request(), BpmProcessInstanceStatus.APPROVED);
        var request = handling.detail(submitted.request().id(), null, 10001).request();
        assertThat(request.status()).as("申请生效：%s", request).isEqualTo("APPROVED");
        assertThat(x.flowStatus(a.id())).as("审批生效路径里目标同样更新").isEqualTo("ylr");
        assertThat(x.linkageHistory(x.flow, a.id())).hasSize(1);
    }
}
