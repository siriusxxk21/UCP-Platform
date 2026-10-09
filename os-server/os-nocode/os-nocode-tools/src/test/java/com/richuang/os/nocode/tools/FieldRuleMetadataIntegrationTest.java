package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;
import static com.richuang.os.nocode.tools.RuleFixtures.current;
import static com.richuang.os.nocode.tools.RuleFixtures.formula;
import static com.richuang.os.nocode.tools.RuleFixtures.linkage;
import static com.richuang.os.nocode.tools.RuleFixtures.reference;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.DataCenter.*;

import org.junit.jupiter.api.*;

import java.util.*;

/**
 * 对象规则在开发库上的完整往返：保存（临时 key 改写为稳定 ID）→ 读回 → 发布快照 → 开新草稿 → 再保存，关系生成列与对象复制都不丢规则， 发布时登记 OBJECT_RULE
 * 依赖并阻止来源字段停用。仅清理本测试前缀拥有的夹具。
 */
class FieldRuleMetadataIntegrationTest {
    private NocodeIntegrationSupport fixture;

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
        fixture = new NocodeIntegrationSupport();
        fixture.name();
    }

    @AfterEach
    void cleanup() {
        fixture.clean();
    }

    private Design create(
            String suffix,
            List<FieldDefinition> extra,
            Map<String, FieldOptions> options,
            List<Relation> relations) {
        var request = fixture.createRequest(suffix);
        var fields = new ArrayList<>(request.fields());
        fields.addAll(extra);
        return designs.save(
                new SaveDesign(
                        new SaveObjectDraft(
                                null,
                                null,
                                request.objectCode(),
                                request.objectName(),
                                null,
                                request.tableName(),
                                request.titleFieldKey(),
                                fields,
                                List.of()),
                        Settings.defaults(),
                        options,
                        relations,
                        List.of(),
                        List.of()),
                10001);
    }

    /** 以当前草稿为底再保存一次；options、relations 为空表示保持原值。 */
    private Design resave(
            Design d,
            Map<String, FieldOptions> options,
            List<Relation> relations,
            List<String> removed) {
        var draft = d.draft();
        return designs.save(
                new SaveDesign(
                        new SaveObjectDraft(
                                draft.id(),
                                draft.lockVersion(),
                                draft.objectCode(),
                                draft.objectName(),
                                draft.description(),
                                draft.tableName(),
                                draft.titleFieldId(),
                                draft.fields().stream()
                                        .filter(f -> !removed.contains(f.id()))
                                        .toList(),
                                removed),
                        null,
                        options,
                        relations,
                        null,
                        null),
                10001);
    }

    private void publish(Design d) {
        var p = publisher.plan(new Revision(d.draft().id(), d.draft().lockVersion(), null), 10001);
        assertThat(p.checks()).noneMatch(Check::blocking);
        assertThat(publisher.execute(new ExecutePlan(p.id(), "对象规则验证"), 10001).state())
                .isEqualTo("SUCCEEDED");
    }

    private Design edit(Design d) {
        var latest = designs.get(d.draft().id());
        return designs.editPublished(
                new Revision(latest.draft().id(), latest.draft().lockVersion(), null), 10001);
    }

    private static String id(Design d, String code) {
        return d.draft().fields().stream()
                .filter(f -> f.code().equals(code))
                .map(FieldDefinition::id)
                .findFirst()
                .orElseThrow();
    }

    private Design source() {
        var source =
                create(
                        "src",
                        List.of(
                                fixture.field("bank", "bank", "TEXT", 1),
                                fixture.field("acct", "acct", "TEXT", 2)),
                        null,
                        List.of());
        publish(source);
        return designs.get(source.draft().id());
    }

    @Test
    void rulesSurviveSaveReadBackPublishAndNewDraft() {
        var source = source();
        String bank = id(source, "bank"), acct = id(source, "acct");
        var options = new HashMap<String, FieldOptions>();
        // 新建字段只有临时 key：当前字段条件写临时 key，保存后改写为稳定 ID。
        options.put(
                "bank_name",
                FieldOptions.defaults()
                        .withRules(
                                linkage(
                                        source.draft().id(),
                                        bank,
                                        "FIRST",
                                        current(acct, "eq", "memo"))));
        options.put("total", FieldOptions.defaults().withRules(formula("price * qty", "HALF_UP")));
        var target =
                create(
                        "tgt",
                        List.of(
                                fixture.field("memo", "memo", "TEXT", 1),
                                fixture.field("bank_name", "bank_name", "TEXT", 2),
                                fixture.field("price", "price", "DECIMAL", 3),
                                fixture.field("qty", "qty", "INTEGER", 4),
                                fixture.field("total", "total", "MONEY", 5)),
                        options,
                        List.of());
        String memo = id(target, "memo"), bankName = id(target, "bank_name");
        var saved = target.fieldOptions().get(bankName).rules();
        assertThat(saved.linkage().conditions().getFirst().formFieldId()).isEqualTo(memo);
        assertThat(saved.dependsOn()).isNull();
        assertThat(target.fieldOptions().get(id(target, "total")).rules().rounding())
                .isEqualTo("HALF_UP");

        publish(target);
        var published =
                servicesContext.getBean(DataObjectApi.class).getPublished(target.draft().id());
        assertThat(published.fieldOptions().get(bankName).rules()).isEqualTo(saved);
        assertThat(designs.get(source.draft().id()).dependencies())
                .anySatisfy(
                        dep -> {
                            assertThat(dep.sourceKind()).isEqualTo("OBJECT_RULE");
                            assertThat(dep.sourceKey()).isEqualTo(target.draft().id());
                            assertThat(dep.fieldIds()).containsExactlyInAnyOrder(bank, acct);
                        });

        var draft = edit(target);
        assertThat(draft.fieldOptions().get(bankName).rules()).isEqualTo(saved);
        var again = resave(draft, null, null, List.of());
        assertThat(again.fieldOptions().get(bankName).rules()).isEqualTo(saved);

        // 规则引用的来源字段不能停用；依赖随本对象发布登记，不需要应用参与。
        var sourceDraft = edit(source);
        assertThatThrownBy(() -> resave(sourceDraft, null, null, List.of(bank)))
                .hasMessageContaining("字段仍被引用");
    }

    @Test
    void generatedRelationFieldKeepsReferenceRuleAcrossSaves() {
        var company =
                create("co", List.of(fixture.field("cname", "cname", "TEXT", 1)), null, List.of());
        publish(company);
        String cname = id(designs.get(company.draft().id()), "cname");
        var target =
                create(
                        "rel",
                        List.of(),
                        null,
                        List.of(
                                new Relation(
                                        null,
                                        "company",
                                        "公司",
                                        "REFERENCE",
                                        company.draft().id(),
                                        null,
                                        null,
                                        false,
                                        "RESTRICT")));
        var relation = target.relations().getFirst();
        var rules = reference(cname);
        var configured =
                resave(
                        target,
                        Map.of(
                                relation.fieldId(),
                                target.fieldOptions().get(relation.fieldId()).withRules(rules)),
                        target.relations(),
                        List.of());
        assertThat(configured.fieldOptions().get(relation.fieldId()).rules()).isEqualTo(rules);
        // 再次提交关系时，生成引用列的存储配置由关系重写，已写入的对象规则必须保留。
        var again = resave(configured, null, configured.relations(), List.of());
        assertThat(again.fieldOptions().get(relation.fieldId()).rules()).isEqualTo(rules);
        assertThat(again.fieldOptions().get(relation.fieldId()).generated()).isTrue();
        publish(again);
        assertThat(
                        servicesContext
                                .getBean(DataObjectApi.class)
                                .getPublished(target.draft().id())
                                .fieldOptions()
                                .get(relation.fieldId())
                                .rules())
                .isEqualTo(rules);
    }

    @Test
    void copyRemapsCurrentFieldsAndSelfLinkage() {
        var source = source();
        String bank = id(source, "bank"), acct = id(source, "acct");
        var target =
                create(
                        "cp",
                        List.of(
                                fixture.field("memo", "memo", "TEXT", 1),
                                fixture.field("bank_name", "bank_name", "TEXT", 2),
                                fixture.field("note", "note", "TEXT", 3)),
                        Map.of(
                                "bank_name",
                                FieldOptions.defaults()
                                        .withRules(
                                                linkage(
                                                        source.draft().id(),
                                                        bank,
                                                        "FIRST",
                                                        current(acct, "eq", "memo")))),
                        List.of());
        String memo = id(target, "memo"), note = id(target, "note");
        // 来源为自身的联动：取本对象另一条记录的备注。
        var self = linkage(target.draft().id(), memo, "FIRST", current(memo, "eq", memo));
        target =
                resave(
                        target,
                        Map.of(note, target.fieldOptions().get(note).withRules(self)),
                        null,
                        List.of());
        var copied =
                designs.copy(
                        new Copy(
                                target.draft().id(),
                                fixture.prefix + "copy",
                                "规则副本",
                                "biz_" + fixture.prefix + "copy"),
                        10001);
        String copiedMemo = id(copied, "memo");
        assertThat(copiedMemo).isNotEqualTo(memo);
        var linked = copied.fieldOptions().get(id(copied, "bank_name")).rules().linkage();
        assertThat(linked.sourceObjectId()).isEqualTo(source.draft().id());
        assertThat(linked.valueFieldId()).isEqualTo(bank);
        assertThat(linked.conditions().getFirst().fieldId()).isEqualTo(acct);
        assertThat(linked.conditions().getFirst().formFieldId()).isEqualTo(copiedMemo);
        var own = copied.fieldOptions().get(id(copied, "note")).rules().linkage();
        assertThat(own.sourceObjectId()).isEqualTo(copied.draft().id());
        assertThat(own.valueFieldId()).isEqualTo(copiedMemo);
        assertThat(own.conditions().getFirst().fieldId()).isEqualTo(copiedMemo);
        assertThat(own.conditions().getFirst().formFieldId()).isEqualTo(copiedMemo);
    }

    @Test
    void saveRejectsRuleFromUnpublishedSource() {
        var unpublished =
                create("draft", List.of(fixture.field("bank", "bank", "TEXT", 1)), null, List.of());
        assertThatThrownBy(
                        () ->
                                create(
                                        "bad",
                                        List.of(fixture.field("bank_name", "bank_name", "TEXT", 1)),
                                        Map.of(
                                                "bank_name",
                                                FieldOptions.defaults()
                                                        .withRules(
                                                                linkage(
                                                                        unpublished.draft().id(),
                                                                        id(unpublished, "bank"),
                                                                        "FIRST"))),
                                        List.of()))
                .hasMessage("字段「字段1」的数据联动：来源对象「验证对象」未发布或已停用");
    }
}
