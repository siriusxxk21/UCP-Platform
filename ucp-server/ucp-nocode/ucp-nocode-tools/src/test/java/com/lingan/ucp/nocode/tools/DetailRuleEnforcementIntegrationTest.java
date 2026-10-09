package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.runtime.service.rules.ScriptedFieldRuleEvaluator.*;
import static com.lingan.ucp.nocode.tools.FieldRuleEnforcementFixture.*;
import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationRecords.*;
import com.lingan.ucp.nocode.application.service.authorization.ApplicationAuthorizationService;

import org.junit.jupiter.api.*;

import java.util.*;

/** 内部明细逐行的保存时规则（B54–B58）：新增行、修改行、删除行、API 省略明细组时服务端补齐，以及行级错误定位。 求值器为脚本替身。 */
class DetailRuleEnforcementIntegrationTest {
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
        fx.rule(fx.rate, fx.linkage(true, fx.company))
                .rule(fx.amount, fx.formula("unit * line_qty", null))
                .rule(fx.subject, fx.filter(fx.category))
                .app();
        fx.evaluator
                .linkage(fx.rate, v -> applied("税率-" + v.get(fx.company)))
                .reference(
                        fx.subject,
                        v ->
                                v.get(fx.category) == null
                                        ? scopePending(fx.category)
                                        : "A".equals(v.get(fx.category))
                                                ? within(fx.accountA)
                                                : within(fx.accountB));
    }

    @AfterEach
    void cleanup() {
        if (fx != null) fx.close();
    }

    private Row line(String key, Map<String, Object> values) {
        return new Row(null, null, values, null, Map.of(), key);
    }

    private Row existing(Row row, Map<String, Object> values) {
        return new Row(row.id(), row.revision(), values);
    }

    private Aggregate twoLines() {
        return fx.save(
                null,
                null,
                values(fx.name, "凭证", fx.company, "甲"),
                Map.of(
                        fx.lines,
                        List.of(
                                line("k1", values(fx.category, "A", fx.rate, "伪造", fx.note, "一")),
                                line("k2", values(fx.category, "B", fx.note, "二")))),
                10001);
    }

    private List<Row> rows(String id) {
        return fx.get(id).details().get(fx.lines);
    }

    @Test
    void newRowReadonlyForced() {
        var saved = twoLines();
        assertThat(rows(saved.record().id()))
                .extracting(r -> r.values().get(fx.rate))
                .containsExactly("税率-甲", "税率-甲");
        var call = fx.evaluator.calls("LINKAGE", fx.rate).getFirst();
        assertThat(call.detailId()).isEqualTo(fx.lines);
        assertThat(call.values()).containsEntry(fx.company, "甲");
    }

    /** 2026-09-29（取代「只在新增行算一次」）：已有行本行依赖变化即重算。 */
    @Test
    void rowFormulaRecomputedOnUpdate() {
        var saved =
                fx.save(
                        null,
                        null,
                        values(fx.name, "公式", fx.company, "甲"),
                        Map.of(
                                fx.lines,
                                List.of(line("k1", values(fx.unit, "2.5", fx.lineQty, 3)))),
                        10001);
        var first = rows(saved.record().id()).getFirst();
        assertThat(number(first.values().get(fx.amount))).isEqualByComparingTo("7");
        var updated =
                fx.save(
                        saved.record().id(),
                        saved.record().revision(),
                        values(),
                        Map.of(fx.lines, List.of(existing(first, values(fx.lineQty, 4)))),
                        10001);
        assertThat(number(updated.details().get(fx.lines).getFirst().values().get(fx.amount)))
                .isEqualByComparingTo("10");
    }

    /** 2026-09-29（取代「复制行保留值」）：公式默认值只读，复制行带来的值按本行依赖重算。 */
    @Test
    void duplicatedRowRecomputed() {
        var saved =
                fx.save(
                        null,
                        null,
                        values(fx.name, "复制", fx.company, "甲"),
                        Map.of(
                                fx.lines,
                                List.of(
                                        line("k1", values(fx.unit, "2", fx.lineQty, 3)),
                                        line(
                                                "copy",
                                                values(
                                                        fx.unit,
                                                        "2",
                                                        fx.lineQty,
                                                        3,
                                                        fx.amount,
                                                        "999")))),
                        10001);
        assertThat(rows(saved.record().id()))
                .extracting(r -> number(r.values().get(fx.amount)).intValue())
                .containsExactly(6, 6);
    }

    /** 2026-09-29：明细只读联动未命中时行上的值（含复制来的值、库中旧值）被清成空。 */
    @Test
    void rowNoMatchClearsValue() {
        var saved = twoLines();
        var current = rows(saved.record().id());
        fx.evaluator.linkage(
                fx.rate,
                v ->
                        "丙".equals(v.get(fx.company))
                                ? noMatch()
                                : applied("税率-" + v.get(fx.company)));
        var updated =
                fx.save(
                        saved.record().id(),
                        saved.record().revision(),
                        values(fx.company, "丙"),
                        Map.of(
                                fx.lines,
                                List.of(
                                        existing(current.get(0), values()),
                                        existing(current.get(1), values(fx.rate, "手填")))),
                        10001);
        assertThat(updated.details().get(fx.lines))
                .extracting(r -> r.values().get(fx.rate))
                .containsExactly(null, null);
    }

    @Test
    void unchangedRowNotRecomputed() {
        var saved = twoLines();
        // 来源数据变化后脚本给出新值；未改动的行与主表依赖都没变，保存时不重算、不复核。
        fx.evaluator.linkage(fx.rate, v -> applied("不应写入"));
        fx.evaluator.reference(fx.subject, v -> within());
        fx.evaluator.calls.clear();
        var current = rows(saved.record().id());
        var updated =
                fx.save(
                        saved.record().id(),
                        saved.record().revision(),
                        values(fx.name, "只改名称"),
                        Map.of(fx.lines, current.stream().map(r -> existing(r, values())).toList()),
                        10001);
        assertThat(updated.details().get(fx.lines))
                .extracting(r -> r.values().get(fx.rate))
                .containsExactly("税率-甲", "税率-甲");
        assertThat(fx.evaluator.calls).isEmpty();
    }

    @Test
    void masterChangeRecomputesRows() {
        var saved = twoLines();
        var current = rows(saved.record().id());
        fx.evaluator.rowBatches.set(0);
        var updated =
                fx.save(
                        saved.record().id(),
                        saved.record().revision(),
                        values(fx.company, "乙"),
                        Map.of(fx.lines, current.stream().map(r -> existing(r, values())).toList()),
                        10001);
        assertThat(updated.details().get(fx.lines))
                .extracting(r -> r.values().get(fx.rate))
                .containsExactly("税率-乙", "税率-乙");
        // 整组两行一次求值（行间缓存生效的前提）。
        assertThat(fx.evaluator.rowBatches.get()).isEqualTo(1);
    }

    @Test
    void deletedRowIgnored() {
        var saved = twoLines();
        var current = rows(saved.record().id());
        // 第二行将被删除：即使它的规则此时会失败，也不参与求值。
        fx.evaluator.linkage(
                fx.rate,
                v ->
                        "二".equals(v.get(fx.note))
                                ? failed("MULTI_ROW_ERROR", "被删除的行不应求值")
                                : applied("税率-" + v.get(fx.company)));
        fx.evaluator.calls.clear();
        var updated =
                fx.save(
                        saved.record().id(),
                        saved.record().revision(),
                        values(fx.company, "乙"),
                        Map.of(fx.lines, List.of(existing(current.getFirst(), values()))),
                        10001);
        assertThat(updated.details().get(fx.lines)).hasSize(1);
        assertThat(fx.evaluator.calls("LINKAGE", fx.rate)).hasSize(1);
    }

    @Test
    void omittedGroupSynthesized() {
        var saved = twoLines();
        var before = rows(saved.record().id());
        var updated =
                fx.save(
                        saved.record().id(),
                        saved.record().revision(),
                        values(fx.company, "乙"),
                        null,
                        10001);
        var after = updated.details().get(fx.lines);
        assertThat(after)
                .extracting(Row::id)
                .containsExactlyElementsOf(before.stream().map(Row::id).toList());
        assertThat(after).extracting(r -> r.values().get(fx.rate)).containsExactly("税率-乙", "税率-乙");
        assertThat(after).extracting(r -> r.values().get(fx.note)).containsExactly("一", "二");
    }

    @Test
    void omittedGroupWithoutMasterDependencyChangeIsNotTouched() {
        var saved = twoLines();
        var before = rows(saved.record().id());
        fx.evaluator.calls.clear();
        var updated =
                fx.save(
                        saved.record().id(),
                        saved.record().revision(),
                        values(fx.name, "只改名称"),
                        null,
                        10001);
        assertThat(fx.evaluator.calls).isEmpty();
        assertThat(updated.details().get(fx.lines))
                .extracting(Row::revision)
                .containsExactlyElementsOf(before.stream().map(Row::revision).toList());
    }

    @Test
    void noWriteNoSynthesis() {
        var saved = twoLines();
        long member = 20002L;
        var auth = servicesContext.getBean(ApplicationAuthorizationService.class);
        var fields = new HashSet<>(fx.voucher.fields().stream().map(FieldDefinition::id).toList());
        auth.save(
                new ApplicationAuthorization.Save(
                        fx.app,
                        auth.get(fx.app).revision(),
                        List.of(
                                new ApplicationAuthorization.Member(
                                        "USER",
                                        Long.toString(member),
                                        List.of(
                                                new ApplicationAuthorization.ObjectGrant(
                                                        fx.voucher.objectId(),
                                                        Set.of("READ", "UPDATE"),
                                                        "ALL",
                                                        fields,
                                                        fields,
                                                        Set.of(fx.lines),
                                                        Set.of(),
                                                        Set.of(),
                                                        Set.of()))))),
                10001);
        var updated =
                fx.records.save(
                        new Save(
                                fx.app,
                                fx.voucher.objectId(),
                                saved.record().id(),
                                saved.record().revision(),
                                values(fx.company, "乙"),
                                null),
                        member);
        assertThat(updated.record().values()).containsEntry(fx.company, "乙");
        assertThat(rows(saved.record().id()))
                .extracting(r -> r.values().get(fx.rate))
                .containsExactly("税率-甲", "税率-甲");
    }

    @Test
    void rowProblemLocated() {
        var error =
                (ServiceException)
                        catchThrowable(
                                () ->
                                        fx.save(
                                                null,
                                                null,
                                                values(fx.name, "定位", fx.company, "甲"),
                                                Map.of(
                                                        fx.lines,
                                                        List.of(
                                                                line(
                                                                        "k-ok",
                                                                        values(
                                                                                fx.category,
                                                                                "A",
                                                                                fx.subject,
                                                                                fx.accountA)),
                                                                line(
                                                                        "k-bad",
                                                                        values(
                                                                                fx.category,
                                                                                "B",
                                                                                fx.subject,
                                                                                fx.accountA)))),
                                                10001));
        assertThat(error).isNotNull();
        assertThat(error.getMessage()).contains("不符合对象引用筛选");
        @SuppressWarnings("unchecked")
        var problems =
                (List<DocumentPolicy.Problem>)
                        ((Map<String, Object>) error.getDetails()).get("problems");
        assertThat(problems)
                .singleElement()
                .satisfies(
                        p -> {
                            assertThat(p.scope()).isEqualTo("ROW");
                            assertThat(p.detailId()).isEqualTo(fx.lines);
                            assertThat(p.clientRowKey()).isEqualTo("k-bad");
                            assertThat(p.fieldId()).isEqualTo(fx.subject);
                        });
        assertThat(fx.count()).isZero();
    }
}
