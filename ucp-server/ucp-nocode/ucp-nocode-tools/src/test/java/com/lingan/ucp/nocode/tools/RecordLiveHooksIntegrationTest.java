package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.lingan.ucp.module.bpm.api.event.BpmProcessInstanceStatus;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationRecords.*;
import com.lingan.ucp.nocode.enums.RecordChangeOperationEnum;
import com.lingan.ucp.nocode.runtime.service.live.RecordChangeBatch;
import com.lingan.ucp.nocode.runtime.service.maintenance.ObjectDataMaintenanceService;
import com.lingan.ucp.nocode.runtime.service.maintenance.OrderedCalibrationService;

import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 实时推送的六个挂点（契约 3.5）：经真实的公共保存 / 删除 / 导入 / 动作 / 维护入口，在真实事务与数据库上核对
 * 「提交后恰好交付一份、对象与记录、操作类型」。静态守卫只看得见调用字样；登记确实执行到、确实在提交后才交付，由本类钉。
 */
class RecordLiveHooksIntegrationTest {
    private RecordLiveFixture live;
    private BusinessHandlingIntegrationTest handling;

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
        live = new RecordLiveFixture();
    }

    @AfterEach
    void cleanup() {
        if (handling != null) handling.cleanup();
        live.cleanup();
    }

    @Test
    void i1_createDeliversOneBatchWithTheNewIdAfterCommit() {
        DataCenter.Definition ledger = live.ledger("ON_SAVE");
        String app = live.app(List.of(), ledger);
        List<Integer> visibleToOthers = new ArrayList<>();
        try (var handle =
                closing(
                        live.collector.listen(
                                batch -> visibleToOthers.add(rowsOnOtherConnection(ledger))))) {
            live.batches.clear();
            Row created = live.create(app, ledger, live.entry(ledger, "A", 10, "10"));
            assertThat(live.batches).as("一次保存提交后恰好一份").hasSize(1);
            assertThat(live.batches.getFirst().objects())
                    .as("只有流水对象；新 ID 在 created（同事务内余额回写不把它挪到 updated）")
                    .containsExactly(
                            new RecordChangeBatch.ObjectChange(
                                    ledger.objectId(),
                                    false,
                                    List.of(created.id()),
                                    List.of(),
                                    List.of()));
            assertThat(visibleToOthers).as("交付时新行已对别的连接可见").containsExactly(1);
        }
    }

    @Test
    void i2_updateAndDeleteAreReportedWithTheirOwnOperation() {
        DataCenter.Definition plain = live.plain(false);
        String app = live.app(List.of(), plain);
        Row row = live.named(app, plain, "原名称");
        live.batches.clear();
        Row changed =
                live.runtime
                        .save(
                                new Save(
                                        app,
                                        plain.objectId(),
                                        row.id(),
                                        row.revision(),
                                        Map.of(live.field(plain, "name"), "新名称"),
                                        null),
                                RecordLiveFixture.OWNER)
                        .record();
        assertThat(live.batches).hasSize(1);
        assertThat(live.batches.getFirst().objects())
                .as("修改 ⇒ updated")
                .containsExactly(
                        new RecordChangeBatch.ObjectChange(
                                plain.objectId(), false, List.of(), List.of(row.id()), List.of()));
        live.batches.clear();
        live.runtime.delete(
                new Delete(app, plain.objectId(), changed.id(), changed.revision()),
                RecordLiveFixture.OWNER);
        assertThat(live.batches).hasSize(1);
        assertThat(live.batches.getFirst().objects())
                .as("删除 ⇒ deleted")
                .containsExactly(
                        new RecordChangeBatch.ObjectChange(
                                plain.objectId(), false, List.of(), List.of(), List.of(row.id())));
    }

    @Test
    void i2b_deleteRejectedByRevisionConflictDeliversNothing() {
        DataCenter.Definition plain = live.plain(false);
        String app = live.app(List.of(), plain);
        Row row = live.named(app, plain, "原名称");
        Row changed =
                live.runtime
                        .save(
                                new Save(
                                        app,
                                        plain.objectId(),
                                        row.id(),
                                        row.revision(),
                                        Map.of(live.field(plain, "name"), "别人先改了"),
                                        null),
                                RecordLiveFixture.OWNER)
                        .record();
        assertThat(changed.revision()).isNotEqualTo(row.revision());
        live.batches.clear();
        assertThatThrownBy(
                        () ->
                                live.runtime.delete(
                                        new Delete(app, plain.objectId(), row.id(), row.revision()),
                                        RecordLiveFixture.OWNER))
                .isInstanceOf(com.lingan.ucp.framework.common.exception.ServiceException.class);
        assertThat(live.batches).as("删除因版本冲突失败 ⇒ 零份").isEmpty();
        assertThat(live.get(app, plain, row.id()).revision()).isEqualTo(changed.revision());
    }

    @Test
    void i3_earlierEntryReportsTheRecalculatedLaterRowsInTheSameBatch() {
        DataCenter.Definition ledger = live.ledger("ON_SAVE");
        String app = live.app(List.of(), ledger);
        Row later = live.create(app, ledger, live.entry(ledger, "A", 20, "20"));
        Row otherAccount = live.create(app, ledger, live.entry(ledger, "B", 20, "7"));
        live.batches.clear();
        Row earlier = live.create(app, ledger, live.entry(ledger, "A", 10, "10"));
        assertThat(new java.math.BigDecimal(balance(app, ledger, later)))
                .as("对照：后面那行的余额确实被重算")
                .isEqualByComparingTo("30");
        assertThat(live.batches).as("新增与牵动的重算同一事务 ⇒ 一份").hasSize(1);
        RecordChangeBatch.ObjectChange change =
                RecordLiveFixture.of(live.batches.getFirst(), ledger);
        assertThat(live.batches.getFirst().objects()).hasSize(1);
        assertThat(change.many()).isFalse();
        assertThat(change.created()).containsExactly(earlier.id());
        assertThat(change.updated())
                .as("后面被重算余额的行在 updated；别的账户没被牵动")
                .containsExactly(later.id())
                .doesNotContain(otherAccount.id());
        assertThat(change.deleted()).isEmpty();
    }

    @Test
    void i4_documentWithInternalDetailsReportsOnlyTheMainRecord() {
        DataCenter.Definition voucher = live.plain(true);
        String app = live.app(List.of(), voucher);
        DataCenter.Detail detail = voucher.details().getFirst();
        String quantity = detail.fields().getFirst().id();
        // 先垫三张单据：这张凭证的三条明细行 ID 是 1–3，主记录 ID 落在其外，批里出现明细行 ID 时能被认出来。
        live.named(app, voucher, "垫单一");
        live.named(app, voucher, "垫单二");
        live.named(app, voucher, "垫单三");
        live.batches.clear();
        Aggregate saved =
                live.runtime.save(
                        new Save(
                                app,
                                voucher.objectId(),
                                null,
                                null,
                                Map.of(live.field(voucher, "name"), "虚构凭证"),
                                Map.of(
                                        detail.id(),
                                        List.of(
                                                new Row(null, null, Map.of(quantity, "1")),
                                                new Row(null, null, Map.of(quantity, "2")),
                                                new Row(null, null, Map.of(quantity, "3"))))),
                        RecordLiveFixture.OWNER);
        List<String> lineIds = saved.details().get(detail.id()).stream().map(Row::id).toList();
        assertThat(lineIds).hasSize(3).doesNotContain(saved.record().id());
        assertThat(live.batches).hasSize(1);
        assertThat(live.batches.getFirst().objects())
                .as("批里只有主记录 ID，没有明细行 ID")
                .containsExactly(
                        new RecordChangeBatch.ObjectChange(
                                voucher.objectId(),
                                false,
                                List.of(saved.record().id()),
                                List.of(),
                                List.of()));
        // 只改明细：仍归到主记录的 updated。
        live.batches.clear();
        Row first = saved.details().get(detail.id()).getFirst();
        live.runtime.save(
                new Save(
                        app,
                        voucher.objectId(),
                        saved.record().id(),
                        saved.record().revision(),
                        Map.of(),
                        Map.of(
                                detail.id(),
                                List.of(
                                        new Row(
                                                first.id(),
                                                first.revision(),
                                                Map.of(quantity, "9"))))),
                RecordLiveFixture.OWNER);
        assertThat(live.batches).hasSize(1);
        assertThat(live.batches.getFirst().objects())
                .containsExactly(
                        new RecordChangeBatch.ObjectChange(
                                voucher.objectId(),
                                false,
                                List.of(),
                                List.of(saved.record().id()),
                                List.of()));
    }

    @Test
    void i5_cascadeAndSetNullReportEachTouchedObjectWithItsOwnOperation() {
        for (String deletion : List.of("SET_NULL", "CASCADE")) {
            DataCenter.Definition target = live.plain(false);
            DataCenter.Definition source =
                    live.related(
                            target,
                            deletion.equals("CASCADE") ? "MASTER_DETAIL" : "REFERENCE",
                            deletion);
            String app = live.app(List.of(), target, source);
            String reference = source.relations().getFirst().fieldId();
            Row parent = live.named(app, target, "主记录");
            Row child =
                    live.create(
                            app,
                            source,
                            Map.of(live.field(source, "name"), "从记录", reference, parent.id()));
            live.batches.clear();
            live.runtime.delete(
                    new Delete(app, target.objectId(), parent.id(), parent.revision()),
                    RecordLiveFixture.OWNER);
            assertThat(live.batches).as(deletion + "：整个删除一个事务 ⇒ 一份").hasSize(1);
            assertThat(live.batches.getFirst().objects())
                    .as(deletion + "：被牵动的对象各有一条，操作类型各自正确")
                    .containsExactlyInAnyOrder(
                            new RecordChangeBatch.ObjectChange(
                                    target.objectId(),
                                    false,
                                    List.of(),
                                    List.of(),
                                    List.of(parent.id())),
                            deletion.equals("CASCADE")
                                    ? new RecordChangeBatch.ObjectChange(
                                            source.objectId(),
                                            false,
                                            List.of(),
                                            List.of(),
                                            List.of(child.id()))
                                    : new RecordChangeBatch.ObjectChange(
                                            source.objectId(),
                                            false,
                                            List.of(),
                                            List.of(child.id()),
                                            List.of()));
        }
    }

    @Test
    void i6_maintainAutomationReportsTheWrittenTargetObjectInTheSameBatch() {
        DataCenter.Definition order = live.plain(false);
        // 「订单」上被持续维护的两列，「收款」引用订单。
        DataCenter.Definition target = orderWithMaintainedColumns();
        DataCenter.Definition source = live.related(target, "REFERENCE", "SET_NULL");
        String relation = source.relations().getFirst().id();
        var config =
                new ApplicationAutomations.Config(
                        source.objectId(),
                        target.objectId(),
                        true,
                        "MAINTAIN",
                        Set.of("CREATE", "UPDATE", "DELETE"),
                        null,
                        new ApplicationAutomations.Binding(relation, "OUTGOING"),
                        List.of(
                                new ApplicationAutomations.Assignment(
                                        live.field(target, "count"), "COUNT", null, null, null)));
        var rule =
                new ApplicationCenter.Resource(
                        "maintain",
                        "AUTOMATION",
                        "maintain",
                        "maintain",
                        mapper.convertValue(config, new TypeReference<Map<String, Object>>() {}));
        String app = live.app(List.of(rule), source, target, order);
        Row parent = live.named(app, target, "虚构订单");
        live.batches.clear();
        Row payment =
                live.create(
                        app,
                        source,
                        Map.of(
                                live.field(source, "name"),
                                "虚构收款",
                                source.relations().getFirst().fieldId(),
                                parent.id()));
        assertThat(
                        live.get(app, target, parent.id())
                                .values()
                                .get(live.field(target, "count"))
                                .toString())
                .as("对照：持续维护确实回写了订单")
                .isEqualTo("1");
        assertThat(live.batches).hasSize(1);
        assertThat(live.batches.getFirst().objects())
                .as("来源新增与目标回写在同一份批里")
                .containsExactlyInAnyOrder(
                        new RecordChangeBatch.ObjectChange(
                                source.objectId(),
                                false,
                                List.of(payment.id()),
                                List.of(),
                                List.of()),
                        new RecordChangeBatch.ObjectChange(
                                target.objectId(),
                                false,
                                List.of(),
                                List.of(parent.id()),
                                List.of()));
    }

    /**
     * I7（并入数据联动「来源变化时自动更新」之后补）：凭证保存带动流水「状态」回写时，被回写的那条流水也在同一份批里—— 开着流水列表的人靠它才收得到通知（契约第 12 章
     * S7）。凭证改挂、删除同理。
     */
    @Test
    void i7_linkageAutoUpdateReportsTheWrittenTargetRecordsInTheSameBatch() {
        LinkageSyncFixture x = new LinkageSyncFixture();
        try {
            x.benchmark();
            Row first = x.createFlow("虚构流水甲");
            Row second = x.createFlow("虚构流水乙");
            live.batches.clear();
            Row voucher = x.createVoucher("虚构凭证", first.id(), "ylr");
            assertThat(x.flowStatus(first.id())).as("对照：联动确实回写了流水").isEqualTo("ylr");
            assertThat(live.batches).as("来源保存与目标回写在同一个事务里，提交后恰好一份").hasSize(1);
            assertThat(live.batches.getFirst().objects())
                    .as("凭证的新 ID 在 created；被回写的流水在 updated")
                    .containsExactlyInAnyOrder(
                            new RecordChangeBatch.ObjectChange(
                                    x.voucher.objectId(),
                                    false,
                                    List.of(voucher.id()),
                                    List.of(),
                                    List.of()),
                            new RecordChangeBatch.ObjectChange(
                                    x.flow.objectId(),
                                    false,
                                    List.of(),
                                    List.of(first.id()),
                                    List.of()));

            // 凭证改挂到另一条流水：原来那条回到「未登记」、新的那条变「已录入」，两条都要通知到。
            live.batches.clear();
            x.update(
                    x.voucher,
                    voucher.id(),
                    Map.of(FieldRuleFixture.relationField(x.voucher, "flow"), second.id()));
            assertThat(List.of(x.flowStatus(first.id()), x.flowStatus(second.id())))
                    .as("对照：两条流水都被回写")
                    .containsExactly("wdj", "ylr");
            assertThat(live.batches).hasSize(1);
            assertThat(changeOf(live.batches.getFirst(), x.voucher).updated())
                    .containsExactly(voucher.id());
            assertThat(changeOf(live.batches.getFirst(), x.flow).updated())
                    .as("改挂前后的两条流水都在 updated")
                    .containsExactlyInAnyOrder(first.id(), second.id());

            // 删除凭证：流水回到「未登记」，同样通知到。
            live.batches.clear();
            x.delete(x.voucher, voucher.id());
            assertThat(x.flowStatus(second.id())).isEqualTo("wdj");
            assertThat(live.batches).hasSize(1);
            assertThat(changeOf(live.batches.getFirst(), x.voucher).deleted())
                    .containsExactly(voucher.id());
            assertThat(changeOf(live.batches.getFirst(), x.flow).updated())
                    .containsExactly(second.id());
        } finally {
            x.cleanup();
        }
    }

    @Test
    void i8_importOfThreeHundredRowsIsOneBulkBatchAndAFailedImportDeliversNothing() {
        DataCenter.Definition ledger = live.ledger("ON_SAVE");
        String app = live.app(List.of(), ledger);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 1; index <= 300; index++) rows.add(live.entry(ledger, "A", index, "1"));
        live.batches.clear();
        assertThat(
                        live.runtime.importRecords(
                                app, ledger.objectId(), rows, RecordLiveFixture.OWNER))
                .isEqualTo(300);
        assertThat(live.batches).as("整批导入一个事务 ⇒ 一份，不是 300 份").hasSize(1);
        assertThat(live.batches.getFirst().objects())
                .as("超过 200 条 ⇒ many，不带 ID")
                .containsExactly(
                        new RecordChangeBatch.ObjectChange(
                                ledger.objectId(), true, List.of(), List.of(), List.of()));

        List<Map<String, Object>> broken = new ArrayList<>();
        for (int index = 301; index <= 600; index++)
            broken.add(live.entry(ledger, "A", index, index == 450 ? "不是数字" : "1"));
        live.batches.clear();
        assertThatThrownBy(
                        () ->
                                live.runtime.importRecords(
                                        app, ledger.objectId(), broken, RecordLiveFixture.OWNER))
                .hasMessageContaining("第 151 行");
        assertThat(live.batches).as("第 150 条数据出错整批回滚 ⇒ 零份").isEmpty();
        assertThat(rowsOnOtherConnection(ledger)).isEqualTo(300);
    }

    @Test
    void i9_previewAndPendingApprovalDeliverNothingUntilTheApprovalTakesEffect() {
        handling = new BusinessHandlingIntegrationTest();
        handling.setup();
        var business = handling.business;
        // 预备校验：写到一半就返回，事务里没有任何登记。
        live.batches.clear();
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        status ->
                                business.records.prepareHandling(
                                        handling.command("预备校验"), RecordLiveFixture.OWNER));
        assertThat(live.batches).as("预备校验 ⇒ 零份").isEmpty();
        assertThat(business.recordCount()).isZero();

        handling.policy("APPROVAL", "APPROVAL");
        live.batches.clear();
        var pending = handling.service.submit(handling.command("待审批"), RecordLiveFixture.OWNER);
        assertThat(pending.outcome()).isEqualTo("SUBMITTED");
        assertThat(business.recordCount()).isZero();
        assertThat(live.batches).as("需要审批的提交只落申请 ⇒ 零份").isEmpty();

        handling.event(pending.request(), BpmProcessInstanceStatus.APPROVED);
        assertThat(business.recordCount()).isEqualTo(1);
        assertThat(live.batches).as("审批通过生效 ⇒ 一份").hasSize(1);
        RecordChangeBatch.ObjectChange change =
                RecordLiveFixture.of(live.batches.getFirst(), business.object);
        assertThat(live.batches.getFirst().objects()).hasSize(1);
        assertThat(change.created()).hasSize(1);
        assertThat(change.updated()).isEmpty();
        assertThat(change.deleted()).isEmpty();
    }

    @Test
    void i10_startProcessActionReportsTheRecordAsUpdated() {
        DataCenter.Definition plain = live.plain(false);
        var definition = new com.lingan.ucp.module.bpm.api.definition.dto.BpmProcessDefinitionDTO();
        definition.setId(live.fixture.prefix + "process:1");
        definition.setKey(live.fixture.prefix + "process");
        definition.setName("虚构审批");
        definition.setFormType(20);
        definition.setFormCustomViewPath("/nocode-app/process-record");
        org.mockito.Mockito.when(
                        servicesContext
                                .getBean(
                                        com.lingan.ucp.module.bpm.api.definition
                                                .BpmProcessDefinitionApi.class)
                                .getProcessDefinition(definition.getId()))
                .thenReturn(definition);
        var instances =
                servicesContext.getBean(
                        com.lingan.ucp.module.bpm.api.task.BpmProcessInstanceApi.class);
        org.mockito.Mockito.reset(instances);
        org.mockito.Mockito.when(
                        instances.createProcessInstance(
                                org.mockito.ArgumentMatchers.eq(RecordLiveFixture.OWNER),
                                org.mockito.ArgumentMatchers.any()))
                .thenReturn(live.fixture.prefix + "instance");
        var action =
                new ApplicationCenter.Resource(
                        "submit_process",
                        "ACTION",
                        "submit_process",
                        "提交审批",
                        Map.of(
                                "objectId",
                                plain.objectId(),
                                "kind",
                                "START_PROCESS",
                                "processDefinitionId",
                                definition.getId(),
                                "variables",
                                Map.of("nc_title", live.field(plain, "name"))));
        String app = live.app(List.of(action), plain);
        Row row = live.named(app, plain, "待审批");
        live.batches.clear();
        Aggregate submitted =
                live.runtime.execute(
                        new ApplicationBusiness.Execute(
                                app, plain.objectId(), action.id(), row.id(), row.revision()),
                        RecordLiveFixture.OWNER);
        assertThat(submitted.processes()).hasSize(1);
        assertThat(submitted.record().revision()).isNotEqualTo(row.revision());
        assertThat(live.batches).hasSize(1);
        assertThat(live.batches.getFirst().objects())
                .as("发起流程推进了记录版本 ⇒ updated")
                .containsExactly(
                        new RecordChangeBatch.ObjectChange(
                                plain.objectId(), false, List.of(), List.of(row.id()), List.of()));
    }

    @Test
    void i11_deletingARecordLinkedManyToManyReportsTheOtherSideAsUpdated() {
        DataCenter.Definition target = live.plain(false);
        DataCenter.Definition source = live.related(target, "MANY_TO_MANY", "SET_NULL");
        String app = live.app(List.of(), target, source);
        String relation = source.relations().getFirst().id();
        Row targetRow = live.named(app, target, "被引用的记录");
        Row sourceRow =
                live.runtime
                        .save(
                                new Save(
                                        app,
                                        source.objectId(),
                                        null,
                                        null,
                                        Map.of(live.field(source, "name"), "引用方"),
                                        null,
                                        Map.of(relation, List.of(targetRow.id()))),
                                RecordLiveFixture.OWNER)
                        .record();
        live.batches.clear();
        live.runtime.delete(
                new Delete(app, target.objectId(), targetRow.id(), targetRow.revision()),
                RecordLiveFixture.OWNER);
        assertThat(live.get(app, source, sourceRow.id()).revision())
                .as("对照：解除关系后引用方的版本被推进")
                .isNotEqualTo(sourceRow.revision());
        assertThat(live.batches).hasSize(1);
        assertThat(live.batches.getFirst().objects())
                .as("被删的在 deleted；解除多对多的对方记录在对方对象的 updated")
                .containsExactlyInAnyOrder(
                        new RecordChangeBatch.ObjectChange(
                                target.objectId(),
                                false,
                                List.of(),
                                List.of(),
                                List.of(targetRow.id())),
                        new RecordChangeBatch.ObjectChange(
                                source.objectId(),
                                false,
                                List.of(),
                                List.of(sourceRow.id()),
                                List.of()));
    }

    @Test
    void i12_i14_objectMaintenanceSaveDeleteAndClearColumn() {
        ObjectDataMaintenanceService maintenance =
                servicesContext.getBean(ObjectDataMaintenanceService.class);
        DataCenter.Definition plain = noteObject();
        String object = plain.objectId();
        ObjectDataMaintenance.Model model = maintenance.model(object, RecordLiveFixture.OWNER);
        String title = model.model().object().titleFieldId();
        String note = live.field(plain, "note");

        // I14：对象数据维护的保存（没有应用上下文）照常交付。
        live.batches.clear();
        Row kept =
                maintenance
                        .save(
                                new ObjectDataMaintenance.Save(
                                        object,
                                        model.versionNo(),
                                        model.checksum(),
                                        null,
                                        null,
                                        Map.of(title, "保留行", note, "旧值一"),
                                        UUID.randomUUID().toString()),
                                RecordLiveFixture.OWNER)
                        .record();
        assertThat(live.batches).hasSize(1);
        assertThat(live.batches.getFirst().objects())
                .containsExactly(
                        new RecordChangeBatch.ObjectChange(
                                object, false, List.of(kept.id()), List.of(), List.of()));
        Row removed =
                maintenance
                        .save(
                                new ObjectDataMaintenance.Save(
                                        object,
                                        model.versionNo(),
                                        model.checksum(),
                                        null,
                                        null,
                                        Map.of(title, "删除行", note, "旧值二"),
                                        UUID.randomUUID().toString()),
                                RecordLiveFixture.OWNER)
                        .record();

        // I14：对象数据维护的删除。
        var deletePreview =
                maintenance.previewDelete(
                        new ObjectDataMaintenance.Delete(
                                object,
                                model.versionNo(),
                                model.checksum(),
                                removed.id(),
                                removed.revision(),
                                null),
                        RecordLiveFixture.OWNER);
        assertThat(deletePreview.allowed()).isTrue();
        live.batches.clear();
        maintenance.delete(
                new ObjectDataMaintenance.Delete(
                        object,
                        model.versionNo(),
                        model.checksum(),
                        removed.id(),
                        removed.revision(),
                        deletePreview.impactToken()),
                RecordLiveFixture.OWNER);
        assertThat(live.batches).hasSize(1);
        assertThat(live.batches.getFirst().objects())
                .containsExactly(
                        new RecordChangeBatch.ObjectChange(
                                object, false, List.of(), List.of(), List.of(removed.id())));

        // I12：整列清空 ⇒ 该对象 many，不带 ID。
        var clearPreview =
                maintenance.previewClearColumn(
                        new ObjectDataMaintenance.ClearColumn(
                                object, model.versionNo(), model.checksum(), null, note, null),
                        RecordLiveFixture.OWNER);
        assertThat(clearPreview.allowed()).isTrue();
        live.batches.clear();
        maintenance.clearColumn(
                new ObjectDataMaintenance.ClearColumn(
                        object,
                        model.versionNo(),
                        model.checksum(),
                        null,
                        note,
                        clearPreview.impactToken()),
                RecordLiveFixture.OWNER);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.\""
                                        + plain.tableName()
                                        + "\" WHERE note IS NOT NULL",
                                Long.class))
                .as("对照：列确实被清空")
                .isZero();
        assertThat(live.batches).hasSize(1);
        assertThat(live.batches.getFirst().objects())
                .as("整列清空 ⇒ many")
                .containsExactly(
                        new RecordChangeBatch.ObjectChange(
                                object, true, List.of(), List.of(), List.of()));
    }

    @Test
    void i13_calibrationDeliversAfterItsOwnTransactionEvenWhenTheCallerRollsBack() {
        DataCenter.Definition live0 = live.ledger("LIVE");
        String app = live.app(List.of(), live0);
        Row first = live.create(app, live0, live.entry(live0, "A", 10, "10"));
        Row second = live.create(app, live0, live.entry(live0, "A", 20, "20"));
        DataCenter.Definition ledger = live.switchLedgerMode(live0, "ON_SAVE");
        String balance = live.field(ledger, "balance");
        OrderedCalibrationService calibration =
                servicesContext.getBean(OrderedCalibrationService.class);
        DataObjectApi.PublishedObject version = live.objects.getVersion(ledger.objectId(), null);
        OrderedCalculationCalibration.Preview preview =
                calibration.preview(
                        new OrderedCalculationCalibration.PreviewRequest(
                                ledger.objectId(),
                                version.versionNo(),
                                version.checksum(),
                                List.of(balance)),
                        RecordLiveFixture.OWNER);
        OrderedCalculationCalibration.Command command =
                new OrderedCalculationCalibration.Command(
                        ledger.objectId(),
                        preview.versionNo(),
                        preview.checksum(),
                        List.of(balance),
                        preview.fields().stream()
                                .collect(
                                        Collectors.toMap(
                                                OrderedCalculationCalibration.FieldPreview::fieldId,
                                                OrderedCalculationCalibration.FieldPreview
                                                        ::signature)),
                        UUID.randomUUID().toString(),
                        10);
        List<Long> filledWhenDelivered = new ArrayList<>();
        try (var handle =
                closing(
                        live.collector.listen(
                                batch ->
                                        filledWhenDelivered.add(
                                                filledOnOtherConnection(ledger))))) {
            live.batches.clear();
            // 调用方自己的事务里已有一本登记簿，随后回滚：校准在自己的独立事务里提交，交付不依赖调用方。
            assertThatThrownBy(
                            () ->
                                    new TransactionTemplate(manager)
                                            .executeWithoutResult(
                                                    status -> {
                                                        live.collector.changed(
                                                                "999999",
                                                                "outer",
                                                                RecordChangeOperationEnum.UPDATE);
                                                        var result =
                                                                calibration.calibrate(
                                                                        command,
                                                                        RecordLiveFixture.OWNER);
                                                        assertThat(result.complete()).isTrue();
                                                        throw new IllegalStateException("调用方回滚");
                                                    }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("调用方回滚");
        }
        assertThat(live.batches).as("校准自己的事务提交后交付").isNotEmpty();
        Set<String> updated = new TreeSet<>();
        for (RecordChangeBatch batch : live.batches) {
            assertThat(batch.objects())
                    .as("校准的批里只有流水对象，没有调用方登记簿里的记录")
                    .allMatch(change -> change.objectId().equals(ledger.objectId()));
            batch.objects().forEach(change -> updated.addAll(change.updated()));
        }
        assertThat(updated).containsExactlyInAnyOrder(first.id(), second.id());
        assertThat(filledWhenDelivered)
                .as("交付时校准写入的余额已对别的连接可见")
                .isNotEmpty()
                .allMatch(count -> count > 0);
    }

    @Test
    void c3_twoThreadsSavingDifferentRecordsEachDeliverTheirOwnVisibleBatch() throws Exception {
        DataCenter.Definition plain = live.plain(false);
        String app = live.app(List.of(), plain);
        live.named(app, plain, "预热");
        Map<String, Long> visibleWhenDelivered = new java.util.concurrent.ConcurrentHashMap<>();
        try (var handle =
                        closing(
                                live.collector.listen(
                                        batch ->
                                                RecordLiveFixture.of(batch, plain)
                                                        .created()
                                                        .forEach(
                                                                id ->
                                                                        visibleWhenDelivered.put(
                                                                                id,
                                                                                queryOnOtherConnection(
                                                                                        "SELECT"
                                                                                            + " count(*)"
                                                                                            + " FROM"
                                                                                            + " public.\""
                                                                                                + plain
                                                                                                        .tableName()
                                                                                                + "\" WHERE"
                                                                                                + " id="
                                                                                                + Long
                                                                                                        .parseLong(
                                                                                                                id))))));
                var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            live.batches.clear();
            var start = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<Row>> saves = new ArrayList<>();
            for (String name : List.of("甲的记录", "乙的记录"))
                saves.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return live.named(app, plain, name);
                                }));
            start.countDown();
            List<String> ids = new ArrayList<>();
            for (var save : saves)
                ids.add(save.get(60, java.util.concurrent.TimeUnit.SECONDS).id());
            assertThat(live.batches).as("两个事务 ⇒ 两份批").hasSize(2);
            assertThat(
                            live.batches.stream()
                                    .map(batch -> RecordLiveFixture.of(batch, plain).created())
                                    .toList())
                    .as("各自只含自己的记录")
                    .containsExactlyInAnyOrder(List.of(ids.get(0)), List.of(ids.get(1)));
            assertThat(live.batches).allMatch(batch -> batch.objects().size() == 1);
            assertThat(visibleWhenDelivered)
                    .as("任一份被交付时，其数据已对别的连接可见")
                    .containsOnlyKeys(ids)
                    .containsEntry(ids.get(0), 1L)
                    .containsEntry(ids.get(1), 1L);
        }
    }

    private DataCenter.Definition orderWithMaintainedColumns() {
        SaveObjectDraft base = live.fixture.createRequest("order");
        return live.publish(
                designs.save(
                        new DataCenter.SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        base.objectCode(),
                                        "虚构订单",
                                        null,
                                        base.tableName(),
                                        "name",
                                        List.of(
                                                live.fixture.field("name", "name", "TEXT", 0),
                                                live.fixture.field("count", "count", "INTEGER", 1)),
                                        List.of()),
                                DataCenter.Settings.defaults(),
                                Map.of(),
                                List.of(),
                                List.of(),
                                List.of()),
                        RecordLiveFixture.OWNER));
    }

    private DataCenter.Definition noteObject() {
        SaveObjectDraft base = live.fixture.createRequest("note");
        List<FieldDefinition> fields = new ArrayList<>(base.fields());
        fields.add(live.fixture.field("note", "note", "TEXT", 1));
        return live.publish(
                designs.save(
                        new DataCenter.SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        base.objectCode(),
                                        base.objectName(),
                                        base.description(),
                                        base.tableName(),
                                        base.titleFieldKey(),
                                        fields,
                                        List.of()),
                                DataCenter.Settings.defaults(),
                                null,
                                List.of(),
                                List.of(),
                                List.of()),
                        RecordLiveFixture.OWNER));
    }

    private String balance(String app, DataCenter.Definition ledger, Row row) {
        return live.get(app, ledger, row.id())
                .values()
                .get(live.field(ledger, "balance"))
                .toString();
    }

    /** 用另一条连接数未删除的行：只看得到已提交的数据。 */
    private static RecordChangeBatch.ObjectChange changeOf(
            RecordChangeBatch batch, DataCenter.Definition d) {
        return batch.objects().stream()
                .filter(change -> change.objectId().equals(d.objectId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("批里没有对象 " + d.objectName()));
    }

    private static int rowsOnOtherConnection(DataCenter.Definition d) {
        return (int)
                queryOnOtherConnection("SELECT count(*) FROM public.\"" + d.tableName() + "\"");
    }

    private static long filledOnOtherConnection(DataCenter.Definition d) {
        return queryOnOtherConnection(
                "SELECT count(*) FROM public.\"" + d.tableName() + "\" WHERE balance IS NOT NULL");
    }

    private static long queryOnOtherConnection(String sql) {
        try (var connection = ds.getConnection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        } catch (java.sql.SQLException error) {
            throw new IllegalStateException(error);
        }
    }

    private static Closing closing(AutoCloseable handle) {
        return () -> {
            try {
                handle.close();
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        };
    }

    private interface Closing extends AutoCloseable {
        @Override
        void close();
    }
}
