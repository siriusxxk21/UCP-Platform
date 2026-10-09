package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.FieldRuleFixture.*;
import static com.richuang.os.nocode.tools.LinkageSyncFixture.*;
import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationRecords.*;
import com.richuang.os.nocode.application.service.published.ApplicationVersionContext;
import com.richuang.os.nocode.application.service.published.ApplicationVersionTestAccess;
import com.richuang.os.nocode.runtime.service.record.LinkageWriteScope;
import com.richuang.os.nocode.runtime.service.record.LinkageWriteScopeTestAccess;
import com.richuang.os.nocode.runtime.service.task.TaskEntryRuntimeScope;
import com.richuang.os.nocode.runtime.service.task.TaskEntryScopeTestAccess;

import org.junit.jupiter.api.*;

import java.util.*;

/**
 * 数据联动「来源变化时自动更新」的触发与回写（测试库真实读写）。每条用例都读业务表列值核对，不只看接口返回。
 *
 * <p>夹具是与标杆场景同形状的虚构对象：流水.状态 ← 凭证.凭证状态，条件「凭证的流水 等于 当前记录」，没有凭证时填「未登记」。
 */
class LinkageAutoUpdateIntegrationTest {
    private LinkageSyncFixture x;

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
    }

    @AfterEach
    void cleanup() {
        x.cleanup();
    }

    private String voucherField(String code) {
        return id(x.voucher, code);
    }

    private String voucherFlow() {
        return relationField(x.voucher, "flow");
    }

    private long vouchers() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM public.\"" + x.voucher.tableName() + "\" WHERE deleted = 0",
                Long.class);
    }

    // ── 1 CURRENT_RECORD 锚点全生命周期 ──

    /** 新建流水 ⇒ 未登记；新建凭证 ⇒ 流水变；改状态 ⇒ 跟着变；改挂 ⇒ 旧流水回落、新流水变；删凭证 ⇒ 回落。 */
    @Test
    void currentRecordAnchorFollowsTheWholeLifecycle() {
        x.benchmark();
        assertThat(x.triggerRows(x.app)).isEqualTo(1);
        var a = x.createFlow("流水甲");
        var b = x.createFlow("流水乙");
        assertThat(x.flowStatus(a.id())).as("新建流水没有凭证").isEqualTo("wdj");
        var v = x.createVoucher("凭证一", a.id(), "ylr");
        assertThat(x.flowStatus(a.id())).as("新建凭证后").isEqualTo("ylr");
        assertThat(x.flowStatus(b.id())).isEqualTo("wdj");
        x.update(x.voucher, v.id(), Map.of(voucherField("status"), "ysh"));
        assertThat(x.flowStatus(a.id())).as("改凭证状态后").isEqualTo("ysh");
        x.update(x.voucher, v.id(), Map.of(voucherFlow(), b.id()));
        assertThat(x.flowStatus(a.id())).as("改挂后旧流水回落").isEqualTo("wdj");
        assertThat(x.flowStatus(b.id())).as("改挂后新流水").isEqualTo("ysh");
        x.delete(x.voucher, v.id());
        assertThat(x.flowStatus(b.id())).as("删除凭证后回落").isEqualTo("wdj");
        assertThat(x.pending()).as("全程与预告对账一致").isZero();
    }

    // ── 2 RECORD_KEY 锚点 ──

    private DataCenter.Definition customer;
    private DataCenter.Definition order;

    /** 客户（来源）与订单（目标）：订单.客户等级 ← 客户.等级，条件「按记录匹配 等于 当前字段·客户」。 */
    private void recordKeyScenario(String onDelete) {
        customer =
                x.object(
                        "customer",
                        List.of(field("level", "等级", "TEXT")),
                        Map.of(),
                        List.of(),
                        DataCenter.Settings.defaults());
        order =
                x.object(
                        "order",
                        List.of(field("customer_level", "客户等级", "TEXT")),
                        Map.of(),
                        List.of(
                                new DataCenter.Relation(
                                        null,
                                        "customer",
                                        "客户",
                                        "REFERENCE",
                                        customer.objectId(),
                                        null,
                                        null,
                                        false,
                                        onDelete,
                                        null)),
                        DataCenter.Settings.defaults());
        order =
                rules(
                        order,
                        id(order, "customer_level"),
                        auto(
                                customer,
                                id(customer, "level"),
                                "FIRST",
                                "无",
                                formField("$record", "eq", relationField(order, "customer"))));
        x.app = x.f.app(customer, order);
    }

    private Row createCustomer(String name, String level) {
        return x.f.save(
                x.app, customer, values(id(customer, "name"), name, id(customer, "level"), level));
    }

    private Row createOrder(String name, String customerId) {
        var values = values(id(order, "name"), name);
        if (customerId != null) values.put(relationField(order, "customer"), customerId);
        return x.f.save(x.app, order, values);
    }

    private String orderLevel(String orderId) {
        return x.column(order, orderId, "customer_level");
    }

    @Test
    void recordKeyAnchorFollowsTheWholeLifecycle() {
        recordKeyScenario("SET_NULL");
        var c1 = createCustomer("客户甲", "A");
        var c2 = createCustomer("客户乙", "B");
        var o1 = createOrder("订单一", c1.id());
        var o2 = createOrder("订单二", null);
        assertThat(orderLevel(o1.id())).isEqualTo("A");
        assertThat(orderLevel(o2.id())).as("没选客户时填空值").isEqualTo("无");
        // 来源改值：指向它的目标跟着变，不指向它的不动。
        x.update(customer, c1.id(), Map.of(id(customer, "level"), "S"));
        assertThat(orderLevel(o1.id())).isEqualTo("S");
        assertThat(orderLevel(o2.id())).isEqualTo("无");
        assertThat(x.linkageHistory(order, o1.id())).hasSize(1);
        assertThat(x.linkageHistory(order, o1.id()).getFirst().path("sourceRecordId").asText())
                .isEqualTo(c1.id());
        // 目标改挂（目标自己的保存）。
        x.update(order, o1.id(), Map.of(relationField(order, "customer"), c2.id()));
        assertThat(orderLevel(o1.id())).isEqualTo("B");
        // 删除来源：置空引用后目标回落到空值填入，删除本身不报错。
        x.delete(customer, c2.id());
        assertThat(orderLevel(o1.id())).isEqualTo("无");
        assertThat(
                        x.pending(
                                x.app,
                                LinkageSync.BASIS_PUBLISHED,
                                order,
                                id(order, "customer_level")))
                .isZero();
    }

    /** 别的应用只固定了来源对象、没固定目标对象：总览里列为 TARGET_NOT_PINNED。 */
    @Test
    void overviewListsApplicationsThatDoNotPinTheTarget() {
        recordKeyScenario("SET_NULL");
        String other = x.f.app(customer);
        var overview = x.sync.overview(x.app, LinkageSync.BASIS_PUBLISHED, 10001);
        assertThat(overview.applicationVersion()).isEqualTo(1);
        assertThat(overview.fields()).hasSize(1);
        var fieldRow = overview.fields().getFirst();
        assertThat(fieldRow.anchor()).isEqualTo("RECORD_KEY");
        assertThat(fieldRow.anchorFieldId()).isEqualTo(relationField(order, "customer"));
        assertThat(fieldRow.change()).isEqualTo("NEW");
        assertThat(fieldRow.divergent())
                .extracting(LinkageSync.Divergent::applicationId, LinkageSync.Divergent::reason)
                .containsExactly(tuple(other, "TARGET_NOT_PINNED"));
    }

    // ── 3 多行四档 ──

    @Test
    void firstConcatAndSumFollowASecondSourceRow() {
        x.flow = x.flowObject(DataCenter.Settings.defaults());
        x.voucher = x.voucherObject(x.flow, "RESTRICT", DataCenter.Settings.defaults());
        var anchor = currentRecord(relationField(x.voucher, "flow"));
        x.flow = rules(x.flow, id(x.flow, "status"), x.benchmarkRule("FIRST"));
        x.flow =
                rules(
                        x.flow,
                        id(x.flow, "memo"),
                        auto(x.voucher, id(x.voucher, "kind"), "CONCAT", null, anchor));
        x.flow =
                rules(
                        x.flow,
                        id(x.flow, "total"),
                        auto(x.voucher, id(x.voucher, "amount"), "SUM", "0", anchor));
        x.app = x.f.app(x.flow, x.voucher);
        assertThat(x.triggerRows(x.app)).isEqualTo(3);
        var a = x.createFlow("流水");
        assertThat(x.column(x.flow, a.id(), "memo")).isNull();
        assertThat(x.column(x.flow, a.id(), "total")).as("金额的空值填入").isEqualTo("0");
        var first = voucher("甲", a.id(), "ylr", "100");
        assertThat(x.flowStatus(a.id())).isEqualTo("ylr");
        assertThat(x.column(x.flow, a.id(), "memo")).isEqualTo("甲");
        assertThat(x.column(x.flow, a.id(), "total")).isEqualTo("100");
        voucher("乙", a.id(), "ysh", "250");
        assertThat(x.flowStatus(a.id())).as("取第一行不随第二行变化").isEqualTo("ylr");
        assertThat(x.column(x.flow, a.id(), "memo")).as("拼接").isEqualTo("甲,乙");
        assertThat(x.column(x.flow, a.id(), "total")).as("求和").isEqualTo("350");
        x.delete(x.voucher, first.id());
        assertThat(x.flowStatus(a.id())).isEqualTo("ysh");
        assertThat(x.column(x.flow, a.id(), "memo")).isEqualTo("乙");
        assertThat(x.column(x.flow, a.id(), "total")).isEqualTo("250");
    }

    private Row voucher(String kind, String flowId, String status, String amount) {
        return x.f.save(
                x.app,
                x.voucher,
                values(
                        id(x.voucher, "name"),
                        "凭证" + kind,
                        id(x.voucher, "kind"),
                        kind,
                        id(x.voucher, "status"),
                        status,
                        id(x.voucher, "amount"),
                        amount,
                        relationField(x.voucher, "flow"),
                        flowId));
    }

    /** 「报错」档：第二张凭证的保存被拒、第一张对应的值不变、报错点名目标对象与字段。 */
    @Test
    void errorModeRejectsTheSecondSourceRow() {
        x.benchmark("ERROR", "RESTRICT");
        var a = x.createFlow("流水");
        x.createVoucher("凭证一", a.id(), "ylr");
        assertThat(x.flowStatus(a.id())).isEqualTo("ylr");
        String digest = x.rowDigest(x.flow, a.id());
        assertThatThrownBy(() -> x.createVoucher("凭证二", a.id(), "ysh"))
                .isInstanceOf(ServiceException.class)
                .hasMessage(
                        "数据联动自动更新「"
                                + x.flow.objectName()
                                + " · 状态」失败：命中 2 行，而多行匹配配的是「报错」，不填值；本次数据变更未保存");
        assertThat(vouchers()).as("第二张凭证没有保存").isEqualTo(1);
        assertThat(x.flowStatus(a.id())).isEqualTo("ylr");
        assertThat(x.rowDigest(x.flow, a.id())).isEqualTo(digest);
    }

    // ── 4 附加条件 ──

    /** 常量条件、当前字段条件与锚点并存：来源从满足变为不满足时目标回落；目标自己改了依赖字段也重算。 */
    @Test
    void extraConditionsNarrowTheMatch() {
        x.flow = x.flowObject(DataCenter.Settings.defaults());
        x.voucher = x.voucherObject(x.flow, "RESTRICT", DataCenter.Settings.defaults());
        x.flow =
                rules(
                        x.flow,
                        id(x.flow, "status"),
                        x.benchmarkRule(
                                "FIRST",
                                constant(id(x.voucher, "kind"), "eq", "正式"),
                                formField(id(x.voucher, "name"), "eq", id(x.flow, "memo"))));
        x.app = x.f.app(x.flow, x.voucher);
        var a = x.f.save(x.app, x.flow, values(id(x.flow, "name"), "流水", id(x.flow, "memo"), "M1"));
        var v =
                x.f.save(
                        x.app,
                        x.voucher,
                        values(
                                id(x.voucher, "name"),
                                "M1",
                                id(x.voucher, "kind"),
                                "正式",
                                id(x.voucher, "status"),
                                "ylr",
                                relationField(x.voucher, "flow"),
                                a.id()));
        assertThat(x.flowStatus(a.id())).isEqualTo("ylr");
        x.update(x.voucher, v.id(), Map.of(id(x.voucher, "kind"), "草稿"));
        assertThat(x.flowStatus(a.id())).as("常量条件不再满足").isEqualTo("wdj");
        x.update(x.voucher, v.id(), Map.of(id(x.voucher, "kind"), "正式"));
        assertThat(x.flowStatus(a.id())).isEqualTo("ylr");
        x.update(x.voucher, v.id(), Map.of(id(x.voucher, "name"), "M2"));
        assertThat(x.flowStatus(a.id())).as("当前字段条件不再满足").isEqualTo("wdj");
        x.update(x.flow, a.id(), Map.of(id(x.flow, "memo"), "M2"));
        assertThat(x.flowStatus(a.id())).as("目标自己改了依赖字段后重算").isEqualTo("ylr");
    }

    // ── 5 值没变不写 ──

    @Test
    void unchangedValueWritesNothing() {
        x.benchmark();
        var a = x.createFlow("流水");
        var v = x.createVoucher("凭证", a.id(), "ylr");
        String digest = x.rowDigest(x.flow, a.id());
        long history = x.historyCount(x.flow, a.id());
        String revision =
                x.runtime.get(x.app, x.flow.objectId(), a.id(), 10001).record().revision();
        // 来源改了无关字段、又原值重存了一次状态：目标都不该被写。
        x.update(x.voucher, v.id(), Map.of(voucherField("kind"), "无关改动"));
        x.update(x.voucher, v.id(), Map.of(voucherField("status"), "ylr"));
        assertThat(x.rowDigest(x.flow, a.id())).as("整行内容与更新时间").isEqualTo(digest);
        assertThat(x.historyCount(x.flow, a.id())).as("历史条数").isEqualTo(history);
        assertThat(x.runtime.get(x.app, x.flow.objectId(), a.id(), 10001).record().revision())
                .as("修订号")
                .isEqualTo(revision);
    }

    // ── 6 历史 ──

    @Test
    void historyRecordsLinkageSourceOnTheTargetOnly() {
        x.benchmark();
        var a = x.createFlow("流水");
        var v = x.createVoucher("凭证", a.id(), "ylr");
        var linkage = x.linkageHistory(x.flow, a.id());
        assertThat(linkage).hasSize(1);
        var source = linkage.getFirst();
        List<String> keys = new ArrayList<>();
        source.fieldNames().forEachRemaining(keys::add);
        assertThat(keys)
                .containsExactlyInAnyOrder(
                        "kind",
                        "applicationId",
                        "version",
                        "name",
                        "fieldIds",
                        "sourceObjectId",
                        "sourceRecordId");
        assertThat(source.path("kind").asText()).isEqualTo("LINKAGE");
        assertThat(source.path("applicationId").asText()).isEqualTo(x.app);
        assertThat(source.path("version").asInt()).isEqualTo(1);
        assertThat(source.path("name").asText()).isEqualTo("状态");
        assertThat(source.path("fieldIds").size()).isEqualTo(1);
        assertThat(source.path("fieldIds").get(0).asText()).isEqualTo(id(x.flow, "status"));
        assertThat(source.path("sourceObjectId").asText()).isEqualTo(x.voucher.objectId());
        assertThat(source.path("sourceRecordId").asText()).isEqualTo(v.id());
        // 操作人是触发来源保存的人；来源记录自己的历史不带联动来源。
        assertThat(
                        jdbc.queryForObject(
                                "SELECT creator FROM public.nocode_record_history WHERE object_id ="
                                    + " ? AND record_id = ? AND source_json->>'kind' = 'LINKAGE'",
                                String.class,
                                Long.valueOf(x.flow.objectId()),
                                a.id()))
                .isEqualTo("10001");
        assertThat(x.linkageHistory(x.voucher, v.id())).isEmpty();
        assertThat(x.historyCount(x.voucher, v.id())).isEqualTo(1);
    }

    // ── 7 下游 ──

    /** 目标上的公式默认值依赖该联动字段：系统回写时一并更新（它不在声明的联动字段里，但同属服务端维护）。 */
    @Test
    void downstreamDefaultFormulaIsRecomputed() {
        x.flow = x.flowObject(DataCenter.Settings.defaults());
        x.voucher = x.voucherObject(x.flow, "RESTRICT", DataCenter.Settings.defaults());
        x.flow = rules(x.flow, id(x.flow, "status"), x.benchmarkRule("FIRST"));
        x.flow =
                rules(
                        x.flow,
                        id(x.flow, "note"),
                        new FieldRules(null, null, "status", null, null, null));
        x.app = x.f.app(x.flow, x.voucher);
        var a = x.createFlow("流水");
        assertThat(x.column(x.flow, a.id(), "note")).isEqualTo("wdj");
        x.createVoucher("凭证", a.id(), "ylr");
        assertThat(x.flowStatus(a.id())).isEqualTo("ylr");
        assertThat(x.column(x.flow, a.id(), "note")).as("依赖联动字段的公式默认值").isEqualTo("ylr");
    }

    // ── 8 连锁 ──

    /** 两级连锁：凭证 → 流水.状态 → 口座.流水状态；第二级的历史来源是流水。 */
    @Test
    void chainedTargetsUpdateInTheSameSave() {
        var account =
                x.object(
                        "account",
                        List.of(field("flow_status", "流水状态", "SELECT")),
                        Map.of("flow_status", options(STATES)),
                        List.of(),
                        DataCenter.Settings.defaults());
        x.flow =
                x.object(
                        "flow",
                        List.of(field("status", "状态", "SELECT")),
                        Map.of("status", options(STATES)),
                        List.of(reference("account", account)),
                        DataCenter.Settings.defaults());
        x.voucher = x.voucherObject(x.flow, "RESTRICT", DataCenter.Settings.defaults());
        x.flow = rules(x.flow, id(x.flow, "status"), x.benchmarkRule("FIRST"));
        account =
                rules(
                        account,
                        id(account, "flow_status"),
                        auto(
                                x.flow,
                                id(x.flow, "status"),
                                "FIRST",
                                null,
                                currentRecord(relationField(x.flow, "account"))));
        x.app = x.f.app(account, x.flow, x.voucher);
        var acc = x.f.save(x.app, account, values(id(account, "name"), "口座"));
        var a =
                x.f.save(
                        x.app,
                        x.flow,
                        values(
                                id(x.flow, "name"),
                                "流水",
                                relationField(x.flow, "account"),
                                acc.id()));
        assertThat(x.column(account, acc.id(), "flow_status")).as("流水新建时即带出").isEqualTo("wdj");
        var v = x.createVoucher("凭证", a.id(), "ylr");
        assertThat(x.flowStatus(a.id())).isEqualTo("ylr");
        assertThat(x.column(account, acc.id(), "flow_status")).as("第二级").isEqualTo("ylr");
        var last = x.linkageHistory(account, acc.id()).getLast();
        assertThat(last.path("sourceObjectId").asText()).isEqualTo(x.flow.objectId());
        assertThat(last.path("sourceRecordId").asText()).isEqualTo(a.id());
        x.delete(x.voucher, v.id());
        assertThat(x.column(account, acc.id(), "flow_status")).isEqualTo("wdj");
    }

    /** 连锁超过 5 层：整体回滚，来源也不保存。 */
    @Test
    void chainDeeperThanFiveLevelsRollsBack() {
        x.benchmark();
        var a = x.createFlow("流水");
        var deep =
                new LinkageWriteScope.Context(
                        x.app,
                        1,
                        "0",
                        "0",
                        List.of(),
                        List.of(),
                        Set.of(),
                        null,
                        null,
                        false,
                        LinkageWriteScope.MAX_DEPTH);
        assertThatThrownBy(
                        () ->
                                LinkageWriteScopeTestAccess.run(
                                        deep, () -> x.createVoucher("凭证", a.id(), "ylr")))
                .hasMessage("数据联动自动更新连锁超过 5 层，本次数据变更未保存");
        assertThat(vouchers()).isZero();
        assertThat(x.flowStatus(a.id())).isEqualTo("wdj");
        // 第 5 层本身还在上限内。
        var allowed =
                new LinkageWriteScope.Context(
                        x.app,
                        1,
                        "0",
                        "0",
                        List.of(),
                        List.of(),
                        Set.of(),
                        null,
                        null,
                        false,
                        LinkageWriteScope.MAX_DEPTH - 1);
        LinkageWriteScopeTestAccess.run(allowed, () -> x.createVoucher("凭证", a.id(), "ylr"));
        assertThat(x.flowStatus(a.id())).isEqualTo("ylr");
    }

    // ── 9 流程保护 ──

    /** 目标有运行中的流程：来源照常保存、目标照常更新；对照组——同一时刻对同一目标的普通保存仍被拦。 */
    @Test
    void targetInRunningProcessIsStillUpdated() {
        x.benchmark();
        var a = x.createFlow("流水");
        runningProcess(a.id());
        assertThatThrownBy(() -> x.update(x.flow, a.id(), Map.of(id(x.flow, "memo"), "手改")))
                .as("对照组：普通保存仍受流程保护")
                .hasMessageContaining("记录受流程保护");
        var v = x.createVoucher("凭证", a.id(), "ylr");
        assertThat(x.flowStatus(a.id())).isEqualTo("ylr");
        x.update(x.voucher, v.id(), Map.of(voucherField("status"), "ysh"));
        assertThat(x.flowStatus(a.id())).isEqualTo("ysh");
        x.delete(x.voucher, v.id());
        assertThat(x.flowStatus(a.id())).isEqualTo("wdj");
        assertThat(x.column(x.flow, a.id(), "memo")).as("被拦的手改没有落库").isNull();
    }

    private void runningProcess(String flowId) {
        jdbc.update(
                "INSERT INTO public.nocode_record_process(application_id, application_version,"
                    + " object_id, object_version, record_id, action_id, name, business_key,"
                    + " process_definition_id, process_definition_key, status, creator, updater)"
                    + " VALUES (?, 1, ?, 1, ?, 'act', '审批', ?, 'approval:1', 'approval', 'RUNNING',"
                    + " '10001', '10001')",
                Long.valueOf(x.app),
                Long.valueOf(x.flow.objectId()),
                flowId,
                "nocode:" + UUID.randomUUID());
    }

    private LinkageWriteScope.Context scopeFor(String flowId) {
        return new LinkageWriteScope.Context(
                x.app,
                1,
                x.flow.objectId(),
                flowId,
                List.of(id(x.flow, "status")),
                List.of("状态"),
                Set.of(),
                x.voucher.objectId(),
                "0",
                false,
                1);
    }

    /** 系统写通道的窄授权只给声明的联动字段：Scope 内的命令夹带别的字段，按无权修改拒绝。 */
    @Test
    void scopeGrantsOnlyTheDeclaredFields() {
        x.benchmark();
        var a = x.createFlow("流水");
        String status = id(x.flow, "status"), memo = id(x.flow, "memo");
        String revision =
                x.runtime.get(x.app, x.flow.objectId(), a.id(), 10001).record().revision();
        assertThatThrownBy(
                        () ->
                                LinkageWriteScopeTestAccess.run(
                                        scopeFor(a.id()),
                                        () ->
                                                x.runtime.save(
                                                        new Save(
                                                                x.app,
                                                                x.flow.objectId(),
                                                                a.id(),
                                                                revision,
                                                                values(status, "ylr", memo, "夹带"),
                                                                null),
                                                        10001)))
                .hasMessageContaining("无权修改的字段");
        assertThat(x.column(x.flow, a.id(), "memo")).isNull();
        // 对照：同一个 Scope 里只带声明字段的命令可以写（值由服务端重算：没有凭证 ⇒ 未登记）。
        jdbc.update(
                "UPDATE public.\""
                        + x.flow.tableName()
                        + "\" SET status = 'ysh' WHERE id::text = ?",
                a.id());
        String stale = x.runtime.get(x.app, x.flow.objectId(), a.id(), 10001).record().revision();
        LinkageWriteScopeTestAccess.run(
                scopeFor(a.id()),
                () ->
                        x.runtime.save(
                                new Save(
                                        x.app,
                                        x.flow.objectId(),
                                        a.id(),
                                        stale,
                                        values(status, "ylr"),
                                        null),
                                10001));
        assertThat(x.flowStatus(a.id())).as("命令里带的值不被信任，落库的是服务端重算的值").isEqualTo("wdj");
    }

    /** 流程保护的豁免只给与 Scope 对得上的那一条命令：Scope 的目标是另一条记录时，流程中的记录照旧拦住。 */
    @Test
    void scopeDoesNotExemptOtherRecordsFromProcessProtection() {
        x.benchmark();
        var a = x.createFlow("流程中的流水");
        var other = x.createFlow("另一条流水");
        runningProcess(a.id());
        String status = id(x.flow, "status");
        String revision =
                x.runtime.get(x.app, x.flow.objectId(), a.id(), 10001).record().revision();
        assertThatThrownBy(
                        () ->
                                LinkageWriteScopeTestAccess.run(
                                        scopeFor(other.id()),
                                        () ->
                                                x.runtime.save(
                                                        new Save(
                                                                x.app,
                                                                x.flow.objectId(),
                                                                a.id(),
                                                                revision,
                                                                values(status, "ylr"),
                                                                null),
                                                        10001)))
                .hasMessageContaining("记录受流程保护");
        // 带了请求键等别的输入也不算系统写入。
        assertThatThrownBy(
                        () ->
                                LinkageWriteScopeTestAccess.run(
                                        scopeFor(a.id()),
                                        () ->
                                                x.runtime.save(
                                                        new Save(
                                                                x.app,
                                                                x.flow.objectId(),
                                                                a.id(),
                                                                revision,
                                                                values(status, "ylr"),
                                                                null,
                                                                null,
                                                                null,
                                                                null,
                                                                UUID.randomUUID().toString(),
                                                                null),
                                                        10001)))
                .hasMessageContaining("记录受流程保护");
        assertThat(x.flowStatus(a.id())).isEqualTo("wdj");
    }

    // ── 10 版本口径 ──

    /** 应用固定旧对象版本（没有这条规则）时不触发；同步并发布后触发；两个应用各按各的版本；绑在旧应用版本上按旧版本的规则。 */
    @Test
    void rulesFollowTheVersionPinnedByTheTriggeringApplication() {
        x.flow = x.flowObject(DataCenter.Settings.defaults());
        x.voucher = x.voucherObject(x.flow, "RESTRICT", DataCenter.Settings.defaults());
        x.app = x.f.app(x.flow, x.voucher);
        // 这条用例验证「规则跟着触发应用固定的对象版本走」：把这个应用的自动跟随单独关掉，它才会停在旧版本上。
        stopFollowing(x.app);
        String statusId = id(x.flow, "status");
        // 对象发布新版本带上规则（走真实的设计保存与对象发布校验）；应用还固定着旧版本。
        x.flow =
                x.f.republish(
                        x.flow,
                        Map.of(
                                statusId,
                                x.flow.fieldOptions()
                                        .get(statusId)
                                        .withRules(x.benchmarkRule("FIRST"))));
        var stored = x.flow.fieldOptions().get(statusId).rules().linkage();
        assertThat(stored.autoUpdate()).isTrue();
        assertThat(stored.emptyValue()).isEqualTo("wdj");
        assertThat(x.triggerRows(x.app)).as("应用没同步：没有登记任何规则").isZero();
        var a = x.createFlow("流水");
        assertThat(x.flowStatus(a.id())).as("固定的旧版本没有规则：新建流水是空").isNull();
        var v = x.createVoucher("凭证一", a.id(), "ylr");
        assertThat(x.flowStatus(a.id())).as("经没同步的应用保存来源：不触发").isNull();

        // 另一个应用固定了新版本：经它保存来源就按新规则触发。
        String synced = x.f.app(x.flow, x.voucher);
        assertThat(x.triggerRows(synced)).isEqualTo(1);
        x.update(synced, x.voucher, v.id(), Map.of(voucherField("kind"), "经新应用保存"), 10001);
        assertThat(x.flowStatus(a.id())).isEqualTo("ylr");
        var overview = x.sync.overview(synced, LinkageSync.BASIS_PUBLISHED, 10001);
        assertThat(overview.fields().getFirst().divergent())
                .extracting(LinkageSync.Divergent::applicationId, LinkageSync.Divergent::reason)
                .containsExactly(tuple(x.app, "NO_RULE"));
        // 经没同步的应用再改来源：仍不触发，值过期（已知缺口，靠预告对账）。
        x.update(x.voucher, v.id(), Map.of(voucherField("status"), "ysh"));
        assertThat(x.flowStatus(a.id())).isEqualTo("ylr");
        assertThat(x.pending(synced, LinkageSync.BASIS_PUBLISHED, x.flow, statusId)).isEqualTo(1);

        // 同步并发布后：经它保存就触发。
        x.syncApplication(x.app);
        assertThat(x.triggerRows(x.app)).isEqualTo(1);
        assertThat(
                        x.sync.overview(synced, LinkageSync.BASIS_PUBLISHED, 10001)
                                .fields()
                                .getFirst()
                                .divergent())
                .isEmpty();
        x.update(x.voucher, v.id(), Map.of(voucherField("kind"), "同步后保存"));
        assertThat(x.flowStatus(a.id())).isEqualTo("ysh");

        // 绑在旧应用版本（1：没有规则）上办理：按旧版本，不触发。
        var versions = servicesContext.getBean(ApplicationVersionContext.class);
        ApplicationVersionTestAccess.bind(
                versions,
                x.app,
                1,
                () -> x.update(x.voucher, v.id(), Map.of(voucherField("status"), "ylr")));
        assertThat(x.flowStatus(a.id())).as("绑定旧应用版本时按旧版本的规则（没有规则）").isEqualTo("ysh");
        x.update(x.voucher, v.id(), Map.of(voucherField("kind"), "当前版本再保存"));
        assertThat(x.flowStatus(a.id())).isEqualTo("ylr");
    }

    // ── 12 权限 ──

    /** 来源只按操作者可见范围读就会算错：成员只看得到自己的凭证，联动仍按全部数据取第一行。 */
    @Test
    void sourceIsReadInFullRegardlessOfOperatorScope() {
        x.benchmark();
        var a = x.createFlow("流水甲");
        var b = x.createFlow("流水乙");
        x.createVoucher("创建人的凭证", a.id(), "ylr");
        x.authorize(
                grant(x.flow, "ALL", Set.of("READ"), allFields(x.flow), Set.of()),
                grant(
                        x.voucher,
                        "OWN",
                        Set.of("READ", "CREATE", "UPDATE"),
                        allFields(x.voucher),
                        allFields(x.voucher)));
        // 成员给同一条流水再做一张凭证：他只看得到自己这张，按他的范围算会得出「已审核」。
        var mine = x.createVoucher(x.app, "成员的凭证", a.id(), "ysh", 20002);
        assertThat(x.flowStatus(a.id())).as("按全部数据：第一行仍是创建人的凭证").isEqualTo("ylr");
        // 成员对流水没有任何写权限（只有查看）：系统照常写入。
        var own = x.createVoucher(x.app, "成员的第二张", b.id(), "ysh", 20002);
        assertThat(x.flowStatus(b.id())).as("操作者对目标无写权限").isEqualTo("ysh");
        assertThat(x.linkageHistory(x.flow, b.id())).hasSize(1);
        // 成员连目标记录都看不到（流水改成只能看本人的）：照常写入。
        x.authorize(
                grant(x.flow, "OWN", Set.of("READ"), allFields(x.flow), Set.of()),
                grant(
                        x.voucher,
                        "OWN",
                        Set.of("READ", "CREATE", "UPDATE"),
                        allFields(x.voucher),
                        allFields(x.voucher)));
        x.update(x.app, x.voucher, own.id(), Map.of(voucherField("status"), "ylr"), 20002);
        assertThat(x.flowStatus(b.id())).as("操作者看不到目标记录").isEqualTo("ylr");
        x.update(x.app, x.voucher, mine.id(), Map.of(voucherField("kind"), "无关"), 20002);
        assertThat(x.flowStatus(a.id())).isEqualTo("ylr");
    }

    /** 操作者对目标的联动字段没有写权限时新建目标：字段是「没有匹配记录时填入」的值，不是空。 */
    @Test
    void targetCreatedWithoutFieldWritePermissionGetsEmptyValue() {
        x.benchmark();
        x.authorize(
                grant(
                        x.flow,
                        "ALL",
                        Set.of("READ", "CREATE"),
                        allFields(x.flow),
                        Set.of(id(x.flow, "name"))),
                grant(x.voucher, "ALL", Set.of("READ"), allFields(x.voucher), Set.of()));
        var created = x.createFlow("成员建的流水", 20002);
        assertThat(x.flowStatus(created.id())).isEqualTo("wdj");
        // 客户端自己提交这个字段仍按写权限拒绝。
        assertThatThrownBy(
                        () ->
                                x.f.save(
                                        x.app,
                                        x.flow,
                                        values(
                                                id(x.flow, "name"),
                                                "夹带",
                                                id(x.flow, "status"),
                                                "ysh"),
                                        20002))
                .hasMessageContaining("无权修改的字段");
    }

    // ── 13 任务入口 ──

    /** 任务入口只声明了来源对象：目标对象不在入口授权里，系统回写照常进行。 */
    @Test
    void taskEntryThatDoesNotDeclareTheTargetStillWrites() {
        x.benchmark();
        var a = x.createFlow("流水");
        var v = x.createVoucher("凭证", a.id(), "ylr");
        var scope = servicesContext.getBean(TaskEntryRuntimeScope.class);
        var invocation =
                new TaskEntryRuntimeScope.Invocation(
                        x.app,
                        "entry",
                        1,
                        "凭证办理",
                        x.voucher.objectId(),
                        10001,
                        Map.of(
                                x.voucher.objectId(),
                                List.of(
                                        grant(
                                                x.voucher,
                                                "ALL",
                                                Set.of("READ", "UPDATE"),
                                                allFields(x.voucher),
                                                allFields(x.voucher)))));
        TaskEntryScopeTestAccess.run(
                scope,
                invocation,
                () -> x.update(x.voucher, v.id(), Map.of(voucherField("status"), "ysh")));
        assertThat(x.flowStatus(a.id())).isEqualTo("ysh");
        // 对照：入口里直接读目标对象是没有授权的。
        assertThatThrownBy(
                        () ->
                                TaskEntryScopeTestAccess.run(
                                        scope,
                                        invocation,
                                        () ->
                                                x.runtime.get(
                                                        x.app, x.flow.objectId(), a.id(), 10001)))
                .isInstanceOf(ServiceException.class);
    }

    // ── 14 级联 / 置空删除 ──

    @Test
    void deletingTheTargetCascadesWithoutWritingBackToIt() {
        x.benchmark("FIRST", "CASCADE");
        var a = x.createFlow("流水");
        x.createVoucher("凭证", a.id(), "ylr");
        int before = x.linkageHistory(x.flow, a.id()).size();
        x.delete(x.flow, a.id());
        assertThat(vouchers()).as("凭证被级联删除").isZero();
        assertThat(x.linkageHistory(x.flow, a.id())).as("被删的流水没有多出联动历史").hasSize(before);
    }

    @Test
    void deletingTheTargetNullsReferencesWithoutWritingBackToIt() {
        x.benchmark("FIRST", "SET_NULL");
        var a = x.createFlow("流水");
        var v = x.createVoucher("凭证", a.id(), "ylr");
        int before = x.linkageHistory(x.flow, a.id()).size();
        x.delete(x.flow, a.id());
        assertThat(x.column(x.voucher, v.id(), refColumn(x.voucher, "flow"))).as("引用被置空").isNull();
        assertThat(vouchers()).isEqualTo(1);
        assertThat(x.linkageHistory(x.flow, a.id())).hasSize(before);
    }

    /** 来源指向的目标记录已不存在（这里直接把目标行标成已删除）：跳过它，来源照常保存，不报错。 */
    @Test
    void missingTargetIsSkipped() {
        x.benchmark();
        var a = x.createFlow("流水");
        var v = x.createVoucher("凭证", a.id(), "ylr");
        jdbc.update(
                "UPDATE public.\"" + x.flow.tableName() + "\" SET deleted = 1 WHERE id::text = ?",
                a.id());
        x.update(x.voucher, v.id(), Map.of(voucherField("status"), "ysh"));
        assertThat(x.column(x.voucher, v.id(), "status")).as("来源照常保存").isEqualTo("ysh");
        assertThat(x.flowStatus(a.id())).as("已删除的目标没有被改写").isEqualTo("ylr");
    }

    // ── 15 幂等收据 ──

    @Test
    void replayedSourceSaveDoesNotTriggerTwice() {
        x.benchmark();
        var a = x.createFlow("流水");
        var command =
                new Save(
                        x.app,
                        x.voucher.objectId(),
                        null,
                        null,
                        values(
                                voucherField("name"),
                                "凭证",
                                voucherField("status"),
                                "ylr",
                                voucherFlow(),
                                a.id()),
                        null,
                        null,
                        null,
                        null,
                        UUID.randomUUID().toString(),
                        null);
        var first = x.runtime.save(command, 10001);
        String digest = x.rowDigest(x.flow, a.id());
        assertThat(x.runtime.save(command, 10001).record().id()).isEqualTo(first.record().id());
        assertThat(vouchers()).isEqualTo(1);
        assertThat(x.rowDigest(x.flow, a.id())).isEqualTo(digest);
        assertThat(x.linkageHistory(x.flow, a.id())).hasSize(1);
    }

    // ── 16 上限 ──

    private void insertOrders(String customerId, int count, String tag) {
        String column =
                order.fields().stream()
                        .filter(fd -> fd.id().equals(relationField(order, "customer")))
                        .findFirst()
                        .orElseThrow()
                        .code();
        jdbc.update(
                "INSERT INTO public.\""
                        + order.tableName()
                        + "\"(name, customer_level, \""
                        + column
                        + "\", creator, updater, create_time, update_time, deleted) SELECT ? || g,"
                        + " 'A', CAST(? AS bigint), '10001', '10001', clock_timestamp(),"
                        + " clock_timestamp(), 0 FROM generate_series(1, ?) g",
                tag,
                customerId,
                count);
    }

    private long ordersAt(String level) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM public.\""
                        + order.tableName()
                        + "\" WHERE deleted = 0 AND customer_level = ?",
                Long.class,
                level);
    }

    /** 一个来源牵动 200 条目标：成功；201 条：来源保存失败、一条目标都没被改。 */
    @Test
    void twoHundredTargetsSucceedAndOneMoreFailsAtomically() {
        recordKeyScenario("SET_NULL");
        var c = createCustomer("大客户", "A");
        insertOrders(c.id(), 200, "批量");
        x.update(customer, c.id(), Map.of(id(customer, "level"), "B"));
        assertThat(ordersAt("B")).isEqualTo(200);
        insertOrders(c.id(), 1, "第 201 条");
        assertThatThrownBy(() -> x.update(customer, c.id(), Map.of(id(customer, "level"), "C")))
                .hasMessage(
                        "数据联动自动更新「"
                                + order.objectName()
                                + " · 客户等级」一次牵动的记录超过 200 条，本次数据变更未保存；请缩小关联范围");
        assertThat(ordersAt("C")).as("超限时一条目标都没被改").isZero();
        assertThat(ordersAt("B")).isEqualTo(200);
        assertThat(x.column(customer, c.id(), "level")).as("来源保存整体回滚").isEqualTo("B");
    }

    // ── 17 目标自身校验不过 ──

    /** 目标记录自身不满足整单规则（历史数据）：系统回写被目标的校验挡住，来源保存回滚，报错点名目标对象与字段。 */
    @Test
    void targetFailingItsOwnValidationRollsBackTheSource() {
        x.flow = x.flowObject(LinkageSyncFixture.nonNegativeTotal());
        x.voucher = x.voucherObject(x.flow, "RESTRICT", DataCenter.Settings.defaults());
        x.flow = rules(x.flow, id(x.flow, "status"), x.benchmarkRule("FIRST"));
        x.app = x.f.app(x.flow, x.voucher);
        var good = x.createFlowWithTotal("正常流水", "0");
        var bad = x.createFlowWithTotal("历史脏数据", "0");
        jdbc.update(
                "UPDATE public.\"" + x.flow.tableName() + "\" SET total = -5 WHERE id::text = ?",
                bad.id());
        x.createVoucher("凭证一", good.id(), "ylr");
        assertThat(x.flowStatus(good.id())).isEqualTo("ylr");
        assertThatThrownBy(() -> x.createVoucher("凭证二", bad.id(), "ylr"))
                .isInstanceOf(ServiceException.class)
                .hasMessageStartingWith("数据联动自动更新「" + x.flow.objectName() + " · 状态」失败：")
                .hasMessageContaining("合计不能为负")
                .hasMessageEndingWith("；本次数据变更未保存");
        assertThat(vouchers()).isEqualTo(1);
        assertThat(x.flowStatus(bad.id())).isEqualTo("wdj");
    }

    // ── 18 导入 ──

    @Test
    void importedBatchUpdatesEveryTarget() {
        x.benchmark();
        int size = 500;
        jdbc.update(
                "INSERT INTO public.\""
                        + x.flow.tableName()
                        + "\"(name, status, creator, updater, create_time, update_time, deleted)"
                        + " SELECT '导入流水' || g, 'wdj', '10001', '10001', clock_timestamp(),"
                        + " clock_timestamp(), 0 FROM generate_series(1, ?) g",
                size);
        List<String> flows =
                jdbc.queryForList(
                        "SELECT id::text FROM public.\"" + x.flow.tableName() + "\" ORDER BY id",
                        String.class);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < size; i++)
            rows.add(
                    values(
                            voucherField("name"),
                            "导入凭证" + i,
                            voucherField("status"),
                            i % 2 == 0 ? "ylr" : "ysh",
                            voucherFlow(),
                            flows.get(i)));
        assertThat(x.runtime.importRecords(x.app, x.voucher.objectId(), rows, 10001))
                .isEqualTo(size);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.\""
                                        + x.flow.tableName()
                                        + "\" t JOIN public.\""
                                        + x.voucher.tableName()
                                        + "\" v ON v.\""
                                        + refColumn(x.voucher, "flow")
                                        + "\"::text = t.id::text WHERE t.status = v.status",
                                Long.class))
                .isEqualTo(size);
        assertThat(x.pending()).isZero();
    }

    @Test
    void importFailingInErrorModeRollsBackTheWholeBatchWithRowNumber() {
        x.benchmark("ERROR", "RESTRICT");
        var a = x.createFlow("流水甲");
        var b = x.createFlow("流水乙");
        List<Map<String, Object>> rows =
                List.of(
                        values(
                                voucherField("name"),
                                "一",
                                voucherField("status"),
                                "ylr",
                                voucherFlow(),
                                a.id()),
                        values(
                                voucherField("name"),
                                "二",
                                voucherField("status"),
                                "ysh",
                                voucherFlow(),
                                b.id()),
                        values(
                                voucherField("name"),
                                "三",
                                voucherField("status"),
                                "ysh",
                                voucherFlow(),
                                a.id()));
        assertThatThrownBy(() -> x.runtime.importRecords(x.app, x.voucher.objectId(), rows, 10001))
                .hasMessageStartingWith("第 4 行：数据联动自动更新「" + x.flow.objectName() + " · 状态」失败：")
                .hasMessageContaining("本批未写入");
        assertThat(vouchers()).isZero();
        assertThat(x.flowStatus(a.id())).isEqualTo("wdj");
        assertThat(x.flowStatus(b.id())).isEqualTo("wdj");
    }

    /** 存量联动（没有 autoUpdate 这个键）：形状与可自动更新的联动一模一样，来源变化时目标不变，索引里也没有它。 */
    @Test
    void legacyLinkageWithoutTheKeyIsNeverTriggered() {
        customer =
                x.object(
                        "customer",
                        List.of(field("level", "等级", "TEXT")),
                        Map.of(),
                        List.of(),
                        DataCenter.Settings.defaults());
        order =
                x.object(
                        "order",
                        List.of(field("customer_level", "客户等级", "TEXT")),
                        Map.of(),
                        List.of(reference("customer", customer)),
                        DataCenter.Settings.defaults());
        // 只读联动、有「按记录匹配」的锚点，唯独没有 autoUpdate 这个键。
        order =
                rules(
                        order,
                        id(order, "customer_level"),
                        new FieldRules(
                                null,
                                new FieldRules.Linkage(
                                        customer.objectId(),
                                        List.of(
                                                formField(
                                                        "$record",
                                                        "eq",
                                                        relationField(order, "customer"))),
                                        id(customer, "level"),
                                        "FIRST",
                                        true,
                                        null,
                                        null),
                                null,
                                null,
                                null,
                                null));
        x.app = x.f.app(customer, order);
        var c = createCustomer("客户", "A");
        var o = createOrder("订单", c.id());
        assertThat(orderLevel(o.id())).as("目标自己保存时照常带出").isEqualTo("A");
        String digest = x.rowDigest(order, o.id());
        x.update(customer, c.id(), Map.of(id(customer, "level"), "S"));
        assertThat(orderLevel(o.id())).as("没开自动更新：来源变化时目标不变").isEqualTo("A");
        assertThat(x.rowDigest(order, o.id())).isEqualTo(digest);
        assertThat(x.linkageHistory(order, o.id())).isEmpty();
        assertThat(x.triggerRows(x.app)).as("没开自动更新的联动不登记索引").isZero();
    }

    /** 「没有匹配记录时填入」只在没有匹配时生效：命中了行但取到的那一格是空，结果就是空，不填它。 */
    @Test
    void emptyValueIsNotUsedWhenTheMatchedCellIsEmpty() {
        x.benchmark();
        var a = x.createFlow("流水");
        assertThat(x.flowStatus(a.id())).isEqualTo("wdj");
        var v = x.createVoucher("没填状态的凭证", a.id(), null);
        assertThat(x.flowStatus(a.id())).as("命中了行、那一格为空 ⇒ 空").isNull();
        x.update(x.voucher, v.id(), Map.of(voucherField("status"), "ylr"));
        assertThat(x.flowStatus(a.id())).isEqualTo("ylr");
        x.delete(x.voucher, v.id());
        assertThat(x.flowStatus(a.id())).as("没有匹配了 ⇒ 填入值").isEqualTo("wdj");
    }
}
