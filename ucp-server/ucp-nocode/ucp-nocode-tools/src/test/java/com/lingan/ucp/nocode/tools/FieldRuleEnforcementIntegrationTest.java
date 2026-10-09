package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.runtime.service.rules.ScriptedFieldRuleEvaluator.*;
import static com.lingan.ucp.nocode.tools.FieldRuleEnforcementFixture.*;
import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.work.*;
import com.lingan.ucp.nocode.runtime.service.work.WorkFormService;

import org.junit.jupiter.api.*;

import java.util.*;

/**
 * 保存时服务端校验与只读强制（B29、B30、B31、B41；B29/B31 按 2026-09-29 只读口径）。覆盖表单、无表单 API、导入、任务入口和工作草稿提交（流程任务提交走同一入口，
 * 另见 FieldRuleFlowTaskIntegrationTest）。求值器为脚本替身，断言它收到的是服务端合并值。
 */
class FieldRuleEnforcementIntegrationTest {
    private FieldRuleEnforcementFixture fx;

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
        fx = new FieldRuleEnforcementFixture().objects();
        fx.rule(fx.bank, fx.linkage(true, fx.company))
                .rule(fx.memo, fx.linkage(false, fx.company))
                .rule(fx.total, fx.formula("price * qty", "HALF_UP"))
                .rule(fx.account, fx.filter(fx.company));
    }

    @AfterEach
    void cleanup() {
        if (fx != null) fx.close();
    }

    private void scripts() {
        fx.evaluator
                .linkage(
                        fx.bank,
                        v ->
                                v.get(fx.company) == null
                                        ? pending(fx.company)
                                        : applied("银行-" + v.get(fx.company)))
                .linkage(fx.memo, v -> applied("服务端建议备注"))
                .reference(
                        fx.account,
                        v ->
                                v.get(fx.company) == null
                                        ? scopePending(fx.company)
                                        : "甲".equals(v.get(fx.company))
                                                ? within(fx.accountA)
                                                : within(fx.accountB));
    }

    @Test
    void readonlyForcedOnApi() {
        fx.app();
        scripts();
        var created =
                fx.save(
                        null,
                        null,
                        values(fx.name, "凭证", fx.company, "甲", fx.bank, "伪造"),
                        null,
                        10001);
        assertThat(fx.get(created.record().id()).record().values()).containsEntry(fx.bank, "银行-甲");
        // 只提交伪造的只读值：依赖取库中旧值「甲」，客户端值被覆盖。
        var forged =
                fx.save(
                        created.record().id(),
                        created.record().revision(),
                        values(fx.bank, "再伪造"),
                        null,
                        10001);
        assertThat(forged.record().values()).containsEntry(fx.bank, "银行-甲");
        assertThat(fx.evaluator.calls("LINKAGE", fx.bank).getLast().values())
                .containsEntry(fx.company, "甲");
        var moved =
                fx.save(
                        created.record().id(),
                        forged.record().revision(),
                        values(fx.company, "乙"),
                        null,
                        10001);
        assertThat(moved.record().values()).containsEntry(fx.bank, "银行-乙");
    }

    @Test
    void readonlyForcedThroughForm() {
        fx.app();
        scripts();
        var saved =
                fx.records.save(
                        new ApplicationRecords.Save(
                                fx.app,
                                fx.voucher.objectId(),
                                null,
                                null,
                                values(fx.name, "表单", fx.company, "乙", fx.bank, "伪造"),
                                null,
                                null,
                                null,
                                "form",
                                null,
                                null),
                        10001);
        assertThat(saved.record().values()).containsEntry(fx.bank, "银行-乙");
    }

    /** 2026-09-29（取代 B29「PENDING/NO_MATCH 接受用户值」）：只读联动未取到值时落库为空，手填值不被接受。 */
    @Test
    void pendingAndNoMatchClearUserValue() {
        fx.app();
        scripts();
        var saved = fx.save(null, null, values(fx.name, "未选公司", fx.bank, "手填银行"), null, 10001);
        assertThat(saved.record().values().get(fx.bank)).isNull();
        var matched = fx.save(null, null, values(fx.name, "命中", fx.company, "甲"), null, 10001);
        assertThat(matched.record().values()).containsEntry(fx.bank, "银行-甲");
        fx.evaluator.linkage(fx.bank, v -> noMatch());
        var unmatched =
                fx.save(
                        null,
                        null,
                        values(fx.name, "未命中", fx.company, "丙", fx.bank, "手填"),
                        null,
                        10001);
        assertThat(unmatched.record().values().get(fx.bank)).isNull();
        // 已有记录依赖变化后未命中：库中旧值被清掉。
        var moved =
                fx.save(
                        matched.record().id(),
                        matched.record().revision(),
                        values(fx.company, "丙"),
                        null,
                        10001);
        assertThat(moved.record().values().get(fx.bank)).isNull();
    }

    /** 2026-09-29：readOnly 为 null 的联动按只读强制（伪造值被覆盖），显式 false 仍可手改（见 nonReadonlyNotRecomputed）。 */
    @Test
    void nullReadOnlyLinkageForced() {
        fx.rule(fx.bank, fx.linkage(null, fx.company));
        fx.app();
        scripts();
        var saved =
                fx.save(
                        null,
                        null,
                        values(fx.name, "缺省只读", fx.company, "乙", fx.bank, "伪造"),
                        null,
                        10001);
        assertThat(saved.record().values()).containsEntry(fx.bank, "银行-乙");
    }

    @Test
    void errorStateRejectsSave() {
        fx.app();
        scripts();
        fx.evaluator.linkage(fx.bank, v -> failed("MULTI_ROW_ERROR", "来源命中 2 行"));
        assertThatThrownBy(
                        () ->
                                fx.save(
                                        null,
                                        null,
                                        values(fx.name, "失败", fx.company, "甲"),
                                        null,
                                        10001))
                .hasMessageContaining("数据联动无法求值：来源命中 2 行");
        assertThat(fx.count()).isZero();
    }

    @Test
    void nonReadonlyNotRecomputed() {
        fx.app();
        scripts();
        var saved =
                fx.save(
                        null,
                        null,
                        values(fx.name, "备注", fx.company, "甲", fx.memo, "用户自己的备注"),
                        null,
                        10001);
        assertThat(saved.record().values()).containsEntry(fx.memo, "用户自己的备注");
        assertThat(fx.evaluator.calls("LINKAGE", fx.memo)).isEmpty();
    }

    @Test
    void referenceOutOfFilterRejected() {
        fx.app();
        scripts();
        assertThatThrownBy(
                        () ->
                                fx.save(
                                        null,
                                        null,
                                        values(
                                                fx.name,
                                                "越界",
                                                fx.company,
                                                "甲",
                                                fx.account,
                                                fx.accountB),
                                        null,
                                        10001))
                .hasMessageContaining("所选记录不符合对象引用筛选");
        assertThatThrownBy(
                        () ->
                                fx.save(
                                        null,
                                        null,
                                        values(fx.name, "缺依赖", fx.account, fx.accountA),
                                        null,
                                        10001))
                .hasMessageContaining("请先填写")
                .hasMessageContaining("再选择");
        assertThat(fx.count()).isZero();
        var saved =
                fx.save(
                        null,
                        null,
                        values(fx.name, "合规", fx.company, "甲", fx.account, fx.accountA),
                        null,
                        10001);
        // 复核用的是服务端合并值：改公司而不改口座时按新公司复核并拒绝。
        assertThatThrownBy(
                        () ->
                                fx.save(
                                        saved.record().id(),
                                        saved.record().revision(),
                                        values(fx.company, "乙"),
                                        null,
                                        10001))
                .hasMessageContaining("所选记录不符合对象引用筛选");
    }

    @Test
    void legacyOutOfRangeKeptIfUnchanged() {
        fx.app();
        scripts();
        var saved =
                fx.save(
                        null,
                        null,
                        values(fx.name, "旧数据", fx.company, "甲", fx.account, fx.accountA),
                        null,
                        10001);
        // 来源数据变化后甲公司的口座 A 已不在范围内；只改无关字段仍可保存，不追溯旧值。
        fx.evaluator.reference(fx.account, v -> within(fx.accountB));
        fx.evaluator.calls.clear();
        var renamed =
                fx.save(
                        saved.record().id(),
                        saved.record().revision(),
                        values(fx.name, "只改名称"),
                        null,
                        10001);
        assertThat(renamed.record().values()).containsEntry(fx.name, "只改名称");
        assertThat(fx.evaluator.calls("REFERENCE", fx.account)).isEmpty();
    }

    /** 2026-09-29（取代 B31「只在新建时算一次、用户已填不覆盖」）：公式默认值一律只读，更新时依赖变化即重算，客户端值不被接受。 */
    @Test
    void formulaDefaultRecomputedOnUpdateAndClientValueIgnored() {
        fx.app();
        scripts();
        var created =
                fx.save(
                        null,
                        null,
                        values(fx.name, "公式", fx.price, "10.5", fx.qty, 1),
                        null,
                        10001);
        assertThat(number(created.record().values().get(fx.total))).isEqualByComparingTo("11");
        var updated =
                fx.save(
                        created.record().id(),
                        created.record().revision(),
                        values(fx.qty, 3),
                        null,
                        10001);
        // 10.5 × 3 = 31.5，按 HALF_UP 取整。
        assertThat(number(updated.record().values().get(fx.total))).isEqualByComparingTo("32");
        var renamed =
                fx.save(
                        created.record().id(),
                        updated.record().revision(),
                        values(fx.name, "只改名称"),
                        null,
                        10001);
        assertThat(number(renamed.record().values().get(fx.total))).isEqualByComparingTo("32");
        var forged =
                fx.save(
                        created.record().id(),
                        renamed.record().revision(),
                        values(fx.total, "5"),
                        null,
                        10001);
        assertThat(number(forged.record().values().get(fx.total))).isEqualByComparingTo("32");
        var cleared =
                fx.save(
                        created.record().id(),
                        forged.record().revision(),
                        values(fx.qty, null),
                        null,
                        10001);
        assertThat(cleared.record().values().get(fx.total)).isNull();
        var filled =
                fx.save(
                        null,
                        null,
                        values(fx.name, "已填", fx.price, "10", fx.qty, 2, fx.total, "5"),
                        null,
                        10001);
        assertThat(number(filled.record().values().get(fx.total))).isEqualByComparingTo("20");
        var empty =
                fx.save(
                        null,
                        null,
                        values(fx.name, "缺数量", fx.price, "10", fx.total, "5"),
                        null,
                        10001);
        assertThat(empty.record().values().get(fx.total)).isNull();
    }

    @Test
    void importPathEnforced() {
        fx.app();
        scripts();
        assertThat(
                        fx.records.importRecords(
                                fx.app,
                                fx.voucher.objectId(),
                                List.of(
                                        values(
                                                fx.name,
                                                "导入",
                                                fx.company,
                                                "乙",
                                                fx.bank,
                                                "伪造",
                                                fx.price,
                                                "2.5",
                                                fx.qty,
                                                1)),
                                10001))
                .isEqualTo(1);
        var id =
                jdbc.queryForObject(
                        "SELECT id::text FROM public.\""
                                + fx.voucher.tableName()
                                + "\" WHERE deleted=0",
                        String.class);
        var stored = fx.get(id).record().values();
        assertThat(stored).containsEntry(fx.bank, "银行-乙");
        assertThat(number(stored.get(fx.total))).isEqualByComparingTo("3");
        assertThatThrownBy(
                        () ->
                                fx.records.importRecords(
                                        fx.app,
                                        fx.voucher.objectId(),
                                        List.of(
                                                values(
                                                        fx.name,
                                                        "导入越界",
                                                        fx.company,
                                                        "乙",
                                                        fx.account,
                                                        fx.accountA)),
                                        10001))
                .hasMessageContaining("第 2 行")
                .hasMessageContaining("不符合对象引用筛选");
    }

    // 2026-10-04 同步 dev：旧版任务入口已退役（不能新增入口），「任务入口保存也走规则校验」这条路径随之不复存在，
    // 原用例 taskEntryPathEnforced 删除；应用记录保存 / 导入 / 工作草稿提交三条路径的规则校验仍由本类其余用例覆盖。

    @Test
    void workDraftSubmitPathEnforcedAndDraftReferencesChecked() {
        fx.app();
        scripts();
        var work = servicesContext.getBean(WorkFormService.class);
        // 草稿复核已填写的引用（挂点 ③）：越界口座不能暂存。
        assertThatThrownBy(
                        () ->
                                work.saveDraft(
                                        new WorkDrafts.Save(
                                                null,
                                                null,
                                                fx.form,
                                                fx.voucher.objectId(),
                                                null,
                                                null,
                                                values(fx.company, "甲", fx.account, fx.accountB),
                                                null,
                                                null),
                                        10001))
                .hasMessageContaining("不符合对象引用筛选");
        var draft =
                work.saveDraft(
                        new WorkDrafts.Save(
                                null,
                                null,
                                fx.form,
                                fx.voucher.objectId(),
                                null,
                                null,
                                values(fx.name, "草稿", fx.company, "乙", fx.bank, "伪造"),
                                null,
                                null),
                        10001);
        var submission =
                work.submit(
                        new WorkDrafts.Submit(
                                draft.id(), draft.revision(), UUID.randomUUID().toString()),
                        10001);
        assertThat(fx.get(submission.recordId()).record().values()).containsEntry(fx.bank, "银行-乙");
    }
}
