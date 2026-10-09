package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.FieldRuleFixture.*;
import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.runtime.dal.mapper.RecordMapper;
import com.lingan.ucp.nocode.runtime.service.record.RecordSelectionSupport;

import org.junit.jupiter.api.*;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

/** 内部明细字段规则的运行期求值与候选（当前开发库真实读写），覆盖设计稿 15.6 B47、B48、B52、B53、B60。 */
class DetailRuleIntegrationTest {
    private FieldRuleFixture f;
    private DataCenter.Definition subject;
    private DataCenter.Definition voucher;
    private String app;
    private String lines;
    private String credit;
    private final Map<String, String> subjects = new LinkedHashMap<>();

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
        f = new FieldRuleFixture();
        subject =
                f.object(
                        "subject",
                        List.of(field("category", "科目分类", "TEXT"), field("company", "公司", "TEXT")),
                        Map.of(),
                        List.of(),
                        List.of());
        voucher =
                f.object(
                        "voucher",
                        List.of(field("company", "公司", "TEXT")),
                        Map.of(),
                        List.of(reference("credit", subject, "lines")),
                        List.of(
                                f.detail(
                                        "lines",
                                        List.of(
                                                field("cat_code", "贷方科目分类", "TEXT"),
                                                field("memo", "摘要", "TEXT")))));
        lines = detailId(voucher, "lines");
        credit = relationField(voucher, "credit");
        app = f.app(subject, voucher);
        for (String category : List.of("A", "B", "C"))
            for (String company : List.of("甲", "乙"))
                subjects.put(
                        category + company,
                        f.save(
                                        app,
                                        subject,
                                        values(
                                                id(subject, "name"),
                                                        category + "-" + company + "科目",
                                                id(subject, "category"), category,
                                                id(subject, "company"), company))
                                .id());
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    private String line(String code) {
        return detailField(voucher, "lines", code);
    }

    private void rule(String fieldId, FieldRules rules) {
        voucher = FieldRuleFixture.rules(voucher, fieldId, rules);
    }

    private FieldRules byRowCategory() {
        return filter(null, formField(id(subject, "category"), "eq", line("cat_code")));
    }

    private FieldRules byMasterCompany() {
        return filter(null, formField(id(subject, "company"), "eq", id(voucher, "company")));
    }

    private static FieldRules.RowInput row(
            String key, boolean creating, Map<String, Object> values, List<String> changed) {
        return new FieldRules.RowInput(key, null, creating, values, changed, List.of());
    }

    private List<FieldRules.Result> rows(FieldRules.Evaluation evaluation, String fieldId) {
        return evaluation.results().stream()
                .filter(r -> r.fieldId().equals(fieldId) && r.rowKey() != null)
                .toList();
    }

    /** B47：明细引用筛选引用本行字段，各行互相独立；行没选分类时 PENDING 且候选为空。 */
    @Test
    void rowScopedFilter() {
        rule(credit, byRowCategory());
        var a =
                f.selection(
                        app,
                        voucher,
                        lines,
                        credit,
                        Map.of(line("cat_code"), "A"),
                        List.of(),
                        null);
        assertThat(a.options())
                .extracting(SelectionFields.Option::label)
                .containsExactlyInAnyOrder("A-甲科目", "A-乙科目");
        var b =
                f.selection(
                        app,
                        voucher,
                        lines,
                        credit,
                        Map.of(line("cat_code"), "B"),
                        List.of(),
                        null);
        assertThat(b.options())
                .extracting(SelectionFields.Option::label)
                .containsExactlyInAnyOrder("B-甲科目", "B-乙科目");
        var none = f.selection(app, voucher, lines, credit, Map.of(), List.of(), null);
        assertThat(none.ruleState()).isEqualTo("PENDING_ROW_VALUE");
        assertThat(none.pendingFields()).containsExactly(line("cat_code"));
        assertThat(none.options()).isEmpty();
        var evaluation =
                f.evaluate(
                        app,
                        voucher,
                        Map.of(),
                        List.of(),
                        List.of(),
                        List.of(
                                new FieldRules.DetailRows(
                                        lines,
                                        List.of(
                                                row(
                                                        "r1",
                                                        false,
                                                        values(
                                                                line("cat_code"),
                                                                "A",
                                                                credit,
                                                                subjects.get("A甲")),
                                                        List.of(line("cat_code"))),
                                                row(
                                                        "r2",
                                                        false,
                                                        values(
                                                                line("cat_code"),
                                                                "B",
                                                                credit,
                                                                subjects.get("A甲")),
                                                        List.of(line("cat_code"))),
                                                row(
                                                        "r3",
                                                        false,
                                                        values(credit, subjects.get("A甲")),
                                                        List.of(line("cat_code")))))),
                        10001);
        var results = rows(evaluation, credit);
        assertThat(results).extracting(FieldRules.Result::rowKey).containsExactly("r1", "r2", "r3");
        assertThat(results).allSatisfy(r -> assertThat(r.kind()).isEqualTo("REFERENCE"));
        assertThat(results.get(0).inScope()).isTrue();
        assertThat(results.get(1).inScope()).isFalse();
        assertThat(results.get(2).state()).isEqualTo("PENDING_ROW_VALUE");
        assertThat(results.get(2).inScope()).isNull();
        assertThat(results).allSatisfy(r -> assertThat(r.detailId()).isEqualTo(lines));
    }

    /** B48：明细规则引用主表字段；主表字段变化后一次求值请求返回该明细所有行的新结果，行缓存跨行共享。 */
    @Test
    void masterChangeBatchesRows() {
        rule(
                line("memo"),
                linkage(
                        subject,
                        id(subject, "name"),
                        "FIRST",
                        List.of(
                                formField(id(subject, "company"), "eq", id(voucher, "company")),
                                constant(id(subject, "category"), "eq", "A"))));
        List<FieldRules.RowInput> input = new ArrayList<>();
        for (int i = 0; i < 20; i++) input.add(row("k" + i, false, Map.of(), List.of()));
        var support = servicesContext.getBean(RecordSelectionSupport.class);
        var original = (RecordMapper) ReflectionTestUtils.getField(support, "records");
        var spy = Mockito.mock(RecordMapper.class, AdditionalAnswers.delegatesTo(original));
        ReflectionTestUtils.setField(support, "records", spy);
        FieldRules.Evaluation evaluation;
        try {
            evaluation =
                    f.evaluate(
                            app,
                            voucher,
                            Map.of(id(voucher, "company"), "乙"),
                            List.of(id(voucher, "company")),
                            List.of(),
                            List.of(new FieldRules.DetailRows(lines, input)),
                            10001);
            Mockito.verify(spy, Mockito.times(1)).rows(Mockito.any());
        } finally {
            ReflectionTestUtils.setField(support, "records", original);
        }
        var memo = rows(evaluation, line("memo"));
        assertThat(memo).hasSize(20);
        assertThat(memo).allSatisfy(r -> assertThat(r.value()).isEqualTo("A-乙科目"));
        assertThat(memo).extracting(FieldRules.Result::rowKey).doesNotHaveDuplicates();
        var unrelated =
                f.evaluate(
                        app,
                        voucher,
                        Map.of(id(voucher, "company"), "乙"),
                        List.of(id(voucher, "name")),
                        List.of(),
                        List.of(new FieldRules.DetailRows(lines, input)),
                        10001);
        assertThat(rows(unrelated, line("memo"))).as("主表无关字段变化不重算明细").isEmpty();
        var created =
                f.evaluate(
                        app,
                        voucher,
                        Map.of(id(voucher, "company"), "甲"),
                        List.of(),
                        List.of(),
                        List.of(
                                new FieldRules.DetailRows(
                                        lines, List.of(row("new", true, Map.of(), List.of())))),
                        10001);
        assertThat(rows(created, line("memo")))
                .singleElement()
                .satisfies(r -> assertThat(r.value()).isEqualTo("A-甲科目"));
    }

    /** B52：100 行、3 种分类值时，引用检查查询次数为 3（按「关系 + 已解析范围」分组，每组一次 key IN）。 */
    @Test
    void queryCountAcrossRows() {
        rule(credit, byRowCategory());
        List<FieldRules.RowInput> input = new ArrayList<>();
        List<String> categories = List.of("A", "B", "C");
        for (int i = 0; i < 100; i++) {
            String category = categories.get(i % 3);
            String selected = subjects.get((i % 2 == 0 ? category : "A") + "甲");
            input.add(
                    row(
                            "row" + i,
                            false,
                            values(line("cat_code"), category, credit, selected),
                            List.of(line("cat_code"))));
        }
        var support = servicesContext.getBean(RecordSelectionSupport.class);
        var original = (RecordMapper) ReflectionTestUtils.getField(support, "records");
        var spy = Mockito.mock(RecordMapper.class, AdditionalAnswers.delegatesTo(original));
        ReflectionTestUtils.setField(support, "records", spy);
        FieldRules.Evaluation evaluation;
        try {
            evaluation =
                    f.evaluate(
                            app,
                            voucher,
                            Map.of(),
                            List.of(),
                            List.of(),
                            List.of(new FieldRules.DetailRows(lines, input)),
                            10001);
            Mockito.verify(spy, Mockito.times(3)).rows(Mockito.any());
        } finally {
            ReflectionTestUtils.setField(support, "records", original);
        }
        var results = rows(evaluation, credit);
        assertThat(results).hasSize(100);
        for (int i = 0; i < 100; i++) {
            String category = categories.get(i % 3);
            boolean expected = i % 2 == 0 || "A".equals(category);
            assertThat(results.get(i).inScope()).as("row" + i).isEqualTo(expected);
        }
    }

    /** B53：单次请求每组超过 500 行被拒；最多 20 组。 */
    @Test
    void rowBatchCap() {
        List<FieldRules.RowInput> many = new ArrayList<>();
        for (int i = 0; i < 501; i++) many.add(row("k" + i, true, Map.of(), List.of()));
        assertThatThrownBy(
                        () ->
                                f.evaluate(
                                        app,
                                        voucher,
                                        Map.of(),
                                        List.of(),
                                        List.of(),
                                        List.of(new FieldRules.DetailRows(lines, many)),
                                        10001))
                .hasMessageContaining("一次最多重算 500 行明细");
        var exactly = many.subList(0, 500);
        assertThatCode(
                        () ->
                                f.evaluate(
                                        app,
                                        voucher,
                                        Map.of(),
                                        List.of(),
                                        List.of(),
                                        List.of(new FieldRules.DetailRows(lines, exactly)),
                                        10001))
                .doesNotThrowAnyException();
        List<FieldRules.DetailRows> groups = new ArrayList<>();
        for (int i = 0; i < 21; i++) groups.add(new FieldRules.DetailRows(lines, List.of()));
        assertThatThrownBy(
                        () ->
                                f.evaluate(
                                        app, voucher, Map.of(), List.of(), List.of(), groups,
                                        10001))
                .hasMessageContaining("20 个明细组");
    }

    /** B60：批量求值返回 REFERENCE 的 inScope；主表依赖变化后，不符合筛选的已选行标为 false。 */
    @Test
    void inScopeAfterMasterChange() {
        rule(credit, byMasterCompany());
        var input =
                List.of(
                        row("jia", false, values(credit, subjects.get("A甲")), List.of()),
                        row("yi", false, values(credit, subjects.get("B乙")), List.of()),
                        row("empty", false, Map.of(), List.of()));
        var evaluation =
                f.evaluate(
                        app,
                        voucher,
                        Map.of(id(voucher, "company"), "乙"),
                        List.of(id(voucher, "company")),
                        List.of(),
                        List.of(new FieldRules.DetailRows(lines, input)),
                        10001);
        var results = rows(evaluation, credit);
        assertThat(results)
                .extracting(FieldRules.Result::rowKey)
                .containsExactly("jia", "yi", "empty");
        assertThat(results.get(0).inScope()).isFalse();
        assertThat(results.get(0).message()).contains("不符合当前筛选");
        assertThat(results.get(1).inScope()).isTrue();
        assertThat(results.get(2).inScope()).as("当前值为空时为 null").isNull();
        assertThat(results).allSatisfy(r -> assertThat(r.state()).isEqualTo("APPLIED"));
    }

    /**
     * 运行模型投影：主表与明细字段的规则只保留服务端计算的 dependsOn 与 readOnly（2026-09-29：有只读联动——readOnly 为 true 或
     * null——或公式默认值时为 true），来源、条件与公式不下发。
     */
    @Test
    void modelExposesOnlyDependsOn() {
        rule(credit, byMasterCompany());
        rule(
                line("memo"),
                new FieldRules(
                        null,
                        new FieldRules.Linkage(
                                subject.objectId(),
                                List.of(
                                        formField(
                                                id(subject, "company"),
                                                "eq",
                                                id(voucher, "company"))),
                                id(subject, "name"),
                                "FIRST",
                                true,
                                null,
                                null),
                        null,
                        "HALF_UP",
                        null,
                        null));
        rule(id(voucher, "name"), linkage(subject, id(subject, "name"), "FIRST", List.of()));
        rule(
                id(voucher, "company"),
                new FieldRules(
                        null,
                        new FieldRules.Linkage(
                                subject.objectId(),
                                List.of(),
                                id(subject, "company"),
                                "FIRST",
                                null,
                                null,
                                null),
                        null,
                        null,
                        null,
                        null));
        rule(line("cat_code"), new FieldRules(null, null, "'A'", null, null, null));
        var projection =
                servicesContext.getBean(
                        com.lingan.ucp.nocode.runtime.service.record.RecordModelProjection.class);
        var caps =
                new ApplicationAuthorization.Capabilities(
                        Set.of("READ"),
                        new HashSet<>(voucher.fields().stream().map(FieldDefinition::id).toList()),
                        Set.of(),
                        Set.of(lines),
                        Set.of(),
                        Set.of(),
                        Set.of());
        DataCenter.Definition visible =
                ReflectionTestUtils.invokeMethod(projection, "visibleDefinition", voucher, caps);
        var detail = visible.details().getFirst();
        var rules = detail.fieldOptions().get(credit).rules();
        assertThat(rules.reference()).isNull();
        assertThat(rules.linkage()).isNull();
        assertThat(rules.dependsOn()).containsExactly(id(voucher, "company"));
        assertThat(rules.readOnly()).isNull();
        var json = servicesContext.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
        assertThat(json.valueToTree(rules).toString())
                .isEqualTo("{\"dependsOn\":[\"" + id(voucher, "company") + "\"]}");
        assertThat(json.valueToTree(detail.fieldOptions().get(credit)).toString())
                .doesNotContain("filter")
                .doesNotContain(id(subject, "company"));
        // 只读联动只暴露 readOnly=true，来源对象、条件、取值字段、档位与取整不下发。
        var memo = detail.fieldOptions().get(line("memo")).rules();
        assertThat(memo.linkage().readOnly()).isTrue();
        assertThat(memo.linkage().sourceObjectId()).isNull();
        assertThat(memo.linkage().conditions()).isEmpty();
        assertThat(memo.linkage().valueFieldId()).isNull();
        assertThat(memo.linkage().multiRow()).isNull();
        assertThat(memo.rounding()).isNull();
        assertThat(memo.readOnly()).isTrue();
        assertThat(memo.dependsOn()).containsExactly(id(voucher, "company"));
        assertThat(json.valueToTree(memo).toString())
                .doesNotContain(subject.objectId())
                .doesNotContain(id(subject, "name"));
        // 非只读联动整个 linkage 置空，只留 dependsOn（此处为空，序列化为 {}）。
        var name = visible.fieldOptions().get(id(voucher, "name")).rules();
        assertThat(name.linkage()).isNull();
        assertThat(name.readOnly()).isNull();
        assertThat(json.valueToTree(name).toString()).isEqualTo("{}");
        // readOnly 为 null 的联动按只读下发：linkage.readOnly=true（兼容已有读取）与 readOnly=true。
        var company = visible.fieldOptions().get(id(voucher, "company")).rules();
        assertThat(company.linkage().readOnly()).isTrue();
        assertThat(company.linkage().sourceObjectId()).isNull();
        assertThat(company.readOnly()).isTrue();
        assertThat(json.valueToTree(company).get("readOnly").asBoolean()).isTrue();
        // 公式默认值一律只读：只下发 readOnly，公式本身不下发。
        var fixed = detail.fieldOptions().get(line("cat_code")).rules();
        assertThat(fixed.defaultFormula()).isNull();
        assertThat(fixed.linkage()).isNull();
        assertThat(fixed.readOnly()).isTrue();
        assertThat(json.valueToTree(fixed).toString()).isEqualTo("{\"readOnly\":true}");
    }

    /** 实施裁定：主表 changed 里出现明细字段 ID 时不报错、不退化成全量；明细组照常按 details 求值，只算变化的那一行。 */
    @Test
    void detailIdsInMasterChangedAreIgnored() {
        rule(credit, byRowCategory());
        rule(id(voucher, "name"), linkage(subject, id(subject, "name"), "FIRST", List.of()));
        var evaluation =
                f.evaluate(
                        app,
                        voucher,
                        Map.of(),
                        List.of(line("cat_code")),
                        List.of(),
                        List.of(
                                new FieldRules.DetailRows(
                                        lines,
                                        List.of(
                                                row(
                                                        "changed",
                                                        false,
                                                        values(
                                                                line("cat_code"),
                                                                "A",
                                                                credit,
                                                                subjects.get("A甲")),
                                                        List.of(line("cat_code"))),
                                                row(
                                                        "untouched",
                                                        false,
                                                        values(
                                                                line("cat_code"),
                                                                "B",
                                                                credit,
                                                                subjects.get("A甲")),
                                                        List.of())))),
                        10001);
        assertThat(evaluation.results()).as("主表规则不被当成全量求值").noneMatch(r -> r.rowKey() == null);
        assertThat(evaluation.results())
                .extracting(FieldRules.Result::rowKey)
                .containsExactly("changed");
        assertThat(evaluation.results().getFirst().inScope()).isTrue();
    }
}
