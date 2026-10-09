package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.FieldRuleFixture.*;
import static com.lingan.ucp.nocode.tools.LinkageSyncFixture.*;
import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.application.service.resource.ApplicationLinkageTriggers;
import com.lingan.ucp.nocode.metadata.service.object.LinkageTriggerPlan;

import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * 反向索引的登记（应用发布时，按「应用 × 应用版本」）、发布时的字段级成环检查，以及自动更新联动与自动更新规则写同一字段的互斥。
 *
 * <p>索引行是不可变输入的纯函数：表内容必须等于按发布快照推导的结果；重建入口幂等。
 */
class LinkageTriggerRegistrationIntegrationTest {
    private LinkageSyncFixture x;
    private ApplicationLinkageTriggers triggers;

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
        triggers = servicesContext.getBean(ApplicationLinkageTriggers.class);
    }

    @AfterEach
    void cleanup() {
        x.cleanup();
    }

    private List<String> stored(int version) {
        return jdbc.queryForList(
                "SELECT source_object_id || '|' || target_object_id || '|' || target_object_version"
                    + " || '|' || target_field_id || '|' || anchor || '|' || anchor_field_id || '|'"
                    + " || signature FROM public.nocode_linkage_trigger WHERE application_id = ?"
                    + " AND application_version = ? AND deleted = 0 ORDER BY target_object_id,"
                    + " target_field_id",
                String.class,
                Long.valueOf(x.app),
                version);
    }

    private List<String> derived(int version) {
        var published = x.f.applications.published(x.app, version);
        List<String> rows = new ArrayList<>();
        for (LinkageTriggerPlan.Row row : triggers.derive(published.definition()))
            rows.add(
                    String.join(
                            "|",
                            row.sourceObjectId(),
                            row.targetObjectId(),
                            Integer.toString(row.targetObjectVersion()),
                            row.targetFieldId(),
                            row.anchor(),
                            row.anchorFieldId(),
                            row.signature()));
        return rows;
    }

    private ApplicationCenter.Row head() {
        return x.f.applications.get(x.app).application();
    }

    /** 发布后表内容 = 推导结果；再发布一个版本，旧版本的行仍在；恢复历史版本、停用后发布启用同样登记；重新启用不重登。 */
    @Test
    void everyPublishEntryRegistersItsOwnVersion() {
        x.benchmark();
        assertThat(stored(1)).hasSize(1).isEqualTo(derived(1));
        String row = stored(1).getFirst();
        assertThat(row)
                .startsWith(
                        x.voucher.objectId()
                                + "|"
                                + x.flow.objectId()
                                + "|1|"
                                + id(x.flow, "status")
                                + "|CURRENT_RECORD|"
                                + relationField(x.voucher, "flow")
                                + "|");
        // 再发布：新版本登记自己的行，旧版本的行不动。
        x.f.applications.publish(
                new ApplicationCenter.Revision(x.app, head().revision(), "再发布"), 10001);
        assertThat(head().publishedVersion()).isEqualTo(2);
        assertThat(stored(2)).isEqualTo(derived(2)).hasSize(1);
        assertThat(stored(1)).containsExactly(row);
        // 恢复历史版本：生成新版本并登记。
        x.f.applications.restore(
                new ApplicationCenter.Restore(x.app, head().revision(), 1, "恢复第一版"), 10001);
        assertThat(head().publishedVersion()).isEqualTo(3);
        assertThat(stored(3)).isEqualTo(derived(3)).hasSize(1);
        // 停用后直接启用旧版本：沿用已有版本的行，不重登。
        x.f.applications.status(
                new ApplicationCenter.Revision(x.app, head().revision(), "停用"), "DISABLED", 10001);
        x.f.applications.status(
                new ApplicationCenter.Revision(x.app, head().revision(), "启用"), "ACTIVE", 10001);
        assertThat(head().publishedVersion()).isEqualTo(3);
        assertThat(x.triggerRows(x.app)).isEqualTo(3);
        // 停用后「发布启用」：新版本并登记。
        x.f.applications.status(
                new ApplicationCenter.Revision(x.app, head().revision(), "停用"), "DISABLED", 10001);
        x.f.applications.publishAndEnable(
                new ApplicationCenter.Revision(x.app, head().revision(), "发布启用"), 10001);
        assertThat(head().publishedVersion()).isEqualTo(4);
        assertThat(stored(4)).isEqualTo(derived(4)).hasSize(1);
        assertThat(x.triggerRows(x.app)).isEqualTo(4);
        assertThat(stored(1)).containsExactly(row);
    }

    /** 按发布快照重建：结果 = 表内容；被清掉或被改坏的行能重建回来；内容一致时不动（行 ID 不变）。 */
    @Test
    void rebuildIsIdempotentAndRestoresTheDerivedRows() {
        x.benchmark();
        var definition = x.f.applications.published(x.app, 1).definition();
        var tx = new TransactionTemplate(manager);
        long id =
                jdbc.queryForObject(
                        "SELECT id FROM public.nocode_linkage_trigger WHERE application_id = ?",
                        Long.class,
                        Long.valueOf(x.app));
        tx.executeWithoutResult(status -> triggers.rebuild(x.app, 1, definition, 10001));
        assertThat(stored(1)).isEqualTo(derived(1));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT id FROM public.nocode_linkage_trigger WHERE application_id"
                                        + " = ?",
                                Long.class,
                                Long.valueOf(x.app)))
                .as("内容一致时重建不动表")
                .isEqualTo(id);
        jdbc.update(
                "UPDATE public.nocode_linkage_trigger SET signature = 'broken' WHERE application_id"
                        + " = ?",
                Long.valueOf(x.app));
        tx.executeWithoutResult(status -> triggers.rebuild(x.app, 1, definition, 10001));
        assertThat(stored(1)).isEqualTo(derived(1));
        jdbc.update(
                "DELETE FROM public.nocode_linkage_trigger WHERE application_id = ?",
                Long.valueOf(x.app));
        var rebuilt = tx.execute(status -> triggers.rebuild(x.app, 1, definition, 10001));
        assertThat(rebuilt).hasSize(1);
        assertThat(stored(1)).isEqualTo(derived(1));
    }

    /** 标杆形状：凭证从流水取值（没开自动更新）、流水从凭证取状态（开）。对象双向、字段无环，发布通过。 */
    @Test
    void bidirectionalObjectsWithoutFieldCyclePublish() {
        x.flow = x.flowObject(DataCenter.Settings.defaults());
        x.voucher = x.voucherObject(x.flow, "RESTRICT", DataCenter.Settings.defaults());
        x.flow = rules(x.flow, id(x.flow, "status"), x.benchmarkRule("FIRST"));
        x.voucher =
                rules(
                        x.voucher,
                        id(x.voucher, "kind"),
                        new FieldRules(
                                null,
                                new FieldRules.Linkage(
                                        x.flow.objectId(),
                                        List.of(
                                                formField(
                                                        "$record",
                                                        "eq",
                                                        relationField(x.voucher, "flow"))),
                                        id(x.flow, "memo"),
                                        "FIRST",
                                        true,
                                        null,
                                        null),
                                null,
                                null,
                                null,
                                null));
        x.app = x.f.app(x.flow, x.voucher);
        assertThat(x.triggerRows(x.app)).isEqualTo(1);
    }

    /** 联动 ↔ 联动成环：应用发布被拒，报出完整路径；索引没有登记任何行。 */
    @Test
    void publishRejectsFieldLevelCycle() {
        x.flow = x.flowObject(DataCenter.Settings.defaults());
        x.voucher = x.voucherObject(x.flow, "RESTRICT", DataCenter.Settings.defaults());
        x.flow = rules(x.flow, id(x.flow, "status"), x.benchmarkRule("FIRST"));
        x.voucher =
                rules(
                        x.voucher,
                        id(x.voucher, "status"),
                        auto(
                                x.flow,
                                id(x.flow, "status"),
                                "FIRST",
                                null,
                                formField("$record", "eq", relationField(x.voucher, "flow"))));
        assertThatThrownBy(() -> x.f.app(x.flow, x.voucher))
                .hasMessage(
                        "数据联动自动更新存在循环："
                                + x.voucher.objectName()
                                + " · 凭证状态 → "
                                + x.flow.objectName()
                                + " · 状态 → "
                                + x.voucher.objectName()
                                + " · 凭证状态；请关闭其中一条联动的自动更新");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_linkage_trigger WHERE"
                                        + " target_object_id = ?",
                                Integer.class,
                                Long.valueOf(x.flow.objectId())))
                .isZero();
    }

    private ApplicationCenter.Resource maintain(String targetFieldCode) {
        var config =
                new ApplicationAutomations.Config(
                        x.voucher.objectId(),
                        x.flow.objectId(),
                        true,
                        "MAINTAIN",
                        Set.of("CREATE", "UPDATE", "DELETE"),
                        null,
                        new ApplicationAutomations.Binding(
                                x.voucher.relations().getFirst().id(), "OUTGOING"),
                        List.of(
                                new ApplicationAutomations.Assignment(
                                        id(x.flow, targetFieldCode),
                                        "EXISTS",
                                        null,
                                        "ylr",
                                        "wdj")));
        return new ApplicationCenter.Resource(
                "maintain",
                "AUTOMATION",
                "maintain",
                "维护凭证标记",
                mapper.convertValue(config, new TypeReference<Map<String, Object>>() {}));
    }

    /** 应用发布方向：自动更新规则要写的字段在对象上开了自动更新的联动 ⇒ 拒绝。写别的字段 ⇒ 通过，两套并存。 */
    @Test
    void applicationPublishRejectsMaintainRuleWritingAnAutoUpdateField() {
        x.flow = x.flowObject(DataCenter.Settings.defaults());
        x.voucher = x.voucherObject(x.flow, "SET_NULL", DataCenter.Settings.defaults());
        x.flow = rules(x.flow, id(x.flow, "status"), x.benchmarkRule("FIRST"));
        assertThatThrownBy(() -> x.f.app(List.of(maintain("status")), x.flow, x.voucher))
                .hasMessageContaining("自动更新“维护凭证标记”要写入的字段「状态」")
                .hasMessageContaining("配置了数据联动")
                .hasMessageContaining("同一字段只能由一处写入");
        // 反例：规则写别的字段时发布通过，且运行期两套同时生效（目标对象上既有被规则维护的字段，又有自动更新的联动字段）。
        x.app = x.f.app(List.of(maintain("memo")), x.flow, x.voucher);
        var a = x.createFlow("流水");
        assertThat(x.flowStatus(a.id())).isEqualTo("wdj");
        var v = x.createVoucher("凭证", a.id(), "ysh");
        assertThat(x.column(x.flow, a.id(), "memo")).as("自动更新规则维护的字段").isEqualTo("ylr");
        assertThat(x.flowStatus(a.id())).as("自动更新的联动字段").isEqualTo("ysh");
        x.delete(x.voucher, v.id());
        assertThat(x.column(x.flow, a.id(), "memo")).isEqualTo("wdj");
        assertThat(x.flowStatus(a.id())).isEqualTo("wdj");
    }

    /** 对象发布方向：字段已被已发布应用的自动更新规则写入 ⇒ 给它配自动更新联动的对象发布被拦。 */
    @Test
    void objectPublishRejectsAutoUpdateLinkageOnFieldWrittenByMaintainRule() {
        x.flow = x.flowObject(DataCenter.Settings.defaults());
        x.voucher = x.voucherObject(x.flow, "SET_NULL", DataCenter.Settings.defaults());
        x.app = x.f.app(List.of(maintain("status")), x.flow, x.voucher);
        String status = id(x.flow, "status");
        var current = designs.get(x.flow.objectId());
        var draft =
                designs.editPublished(
                        new DataCenter.Revision(
                                current.draft().id(), current.draft().lockVersion(), null),
                        10001);
        var options = new LinkedHashMap<>(draft.fieldOptions());
        options.put(status, draft.fieldOptions().get(status).withRules(x.benchmarkRule("FIRST")));
        var ruled =
                designs.save(
                        new DataCenter.SaveDesign(
                                x.f.support.edit(
                                        draft.draft(),
                                        draft.draft().fields(),
                                        List.of(),
                                        draft.draft().titleFieldId()),
                                draft.settings(),
                                options,
                                draft.relations(),
                                draft.indexes(),
                                draft.details(),
                                draft.mainBinding()),
                        10001);
        var plan =
                publisher.plan(
                        new DataCenter.Revision(
                                ruled.draft().id(), ruled.draft().lockVersion(), null),
                        10001);
        assertThat(plan.checks())
                .anySatisfy(
                        check -> {
                            assertThat(check.blocking()).isTrue();
                            assertThat(check.message())
                                    .contains("配置了数据联动")
                                    .contains("自动更新“维护凭证标记”也在写入该字段")
                                    .contains("同一字段只能由一处写入");
                        });
        assertThat(plan.state()).isEqualTo("BLOCKED");
    }

    /** 真实的设计保存链路：保存 → 发布 → 再保存再发布，自动更新开关、空值填入与「等于当前记录」条件原样还在。 */
    @Test
    void rulesSurviveDesignSaveAndRepublish() {
        x.flow = x.flowObject(DataCenter.Settings.defaults());
        x.voucher = x.voucherObject(x.flow, "RESTRICT", DataCenter.Settings.defaults());
        String status = id(x.flow, "status"), memo = id(x.flow, "memo");
        var rule = x.benchmarkRule("FIRST", constant(id(x.voucher, "kind"), "eq", "正式"));
        x.flow =
                x.f.republish(
                        x.flow, Map.of(status, x.flow.fieldOptions().get(status).withRules(rule)));
        assertThat(x.flow.fieldOptions().get(status).rules().linkage()).isEqualTo(rule.linkage());
        // 再保存一次（改一个无关字段的说明）并发布。
        x.flow =
                x.f.republish(
                        x.flow,
                        Map.of(
                                memo,
                                DataCenter.FieldOptions.copyOf(x.flow.fieldOptions().get(memo))
                                        .description("第二次保存")
                                        .build()));
        assertThat(x.flow.fieldOptions().get(status).rules().linkage()).isEqualTo(rule.linkage());
        // 「等于当前记录」锚在来源对象指向【原对象】的引用上：副本没有被来源引用，这条规则在副本上不成立，复制被明确拒绝
        // （不是悄悄把自动更新丢掉）。
        assertThatThrownBy(
                        () ->
                                designs.copy(
                                        new DataCenter.Copy(
                                                x.flow.objectId(),
                                                x.f.prefix() + "lscopy",
                                                "流水副本",
                                                "biz_" + x.f.prefix() + "lscopy"),
                                        10001))
                .hasMessage("字段「状态」的数据联动：「等于当前记录」只能用于来源对象上指向「流水副本」的单选关联字段");
    }

    /** 「按记录匹配 等于 当前字段」锚点的规则随对象复制：自动更新开关、空值填入原样在，当前字段改指副本自己的引用字段。 */
    @Test
    void recordKeyAnchoredRuleSurvivesObjectCopy() {
        var customer =
                x.object(
                        "customer",
                        List.of(field("level", "等级", "TEXT")),
                        Map.of(),
                        List.of(),
                        DataCenter.Settings.defaults());
        var order =
                x.object(
                        "order",
                        List.of(field("customer_level", "客户等级", "TEXT")),
                        Map.of(),
                        List.of(reference("customer", customer)),
                        DataCenter.Settings.defaults());
        String level = id(order, "customer_level");
        var rule =
                auto(
                        customer,
                        id(customer, "level"),
                        "FIRST",
                        "无",
                        formField("$record", "eq", relationField(order, "customer")));
        order =
                x.f.republish(
                        order, Map.of(level, order.fieldOptions().get(level).withRules(rule)));
        assertThat(order.fieldOptions().get(level).rules().linkage()).isEqualTo(rule.linkage());
        var copied =
                designs.copy(
                        new DataCenter.Copy(
                                order.objectId(),
                                x.f.prefix() + "lscopy",
                                "订单副本",
                                "biz_" + x.f.prefix() + "lscopy"),
                        10001);
        String copiedLevel =
                copied.draft().fields().stream()
                        .filter(fd -> fd.code().equals("customer_level"))
                        .findFirst()
                        .orElseThrow()
                        .id();
        var linkage = copied.fieldOptions().get(copiedLevel).rules().linkage();
        assertThat(linkage.autoUpdate()).isTrue();
        assertThat(linkage.emptyValue()).isEqualTo("无");
        assertThat(linkage.sourceObjectId()).isEqualTo(customer.objectId());
        assertThat(linkage.valueFieldId()).isEqualTo(id(customer, "level"));
        var condition = linkage.conditions().getFirst();
        assertThat(condition.fieldId()).isEqualTo("$record");
        assertThat(condition.valueSource()).isEqualTo("FORM_FIELD");
        assertThat(condition.formFieldId())
                .as("当前字段改指副本自己的引用字段")
                .isNotEqualTo(relationField(order, "customer"))
                .isEqualTo(
                        copied.relations().stream()
                                .filter(r -> r.code().equals("customer"))
                                .findFirst()
                                .orElseThrow()
                                .fieldId());
    }
}
