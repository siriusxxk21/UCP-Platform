package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;
import static com.lingan.ucp.nocode.tools.RuleFixtures.formula;
import static com.lingan.ucp.nocode.tools.RuleFixtures.reference;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.schema.service.convert.FieldSwitchPreviewService;

import org.junit.jupiter.api.*;

import java.util.*;

/**
 * 字段对象规则不能在对象保存链路上被悄悄清掉（两条开发线合并后的最高优先守卫）：复用列的单值关系每次保存都会重写引用列的存储配置，
 * 已保存单选原地转对象引用、字段类型转换预检都会按旧配置推演新配置——这些位置必须把 rules 原样带上。仅清理本测试前缀拥有的夹具。
 */
class FieldRulesSurviveConversionIntegrationTest {
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
        writeFailure.clear();
        fixture.clean();
    }

    /** 发布一个带 value 字段的对象；extra 为追加字段，options 按字段临时 key 给出。 */
    private Design published(
            String suffix,
            String valueType,
            List<FieldDefinition> extra,
            Map<String, FieldOptions> options) {
        SaveObjectDraft request = fixture.createRequest(suffix);
        List<FieldDefinition> fields = new ArrayList<>(request.fields());
        fields.add(fixture.field("value", "value", valueType, 1));
        fields.addAll(extra);
        Design draft =
                designs.save(
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
                                List.of(),
                                List.of(),
                                List.of()),
                        10001);
        PublishPlan plan =
                publisher.plan(
                        new Revision(draft.draft().id(), draft.draft().lockVersion(), null), 10001);
        assertThat(plan.checks()).noneMatch(Check::blocking);
        publisher.execute(new ExecutePlan(plan.id(), "规则保留夹具首发"), 10001);
        return designs.get(draft.draft().id());
    }

    private static FieldDefinition field(Design design, String code) {
        return design.draft().fields().stream()
                .filter(field -> field.code().equals(code))
                .findFirst()
                .orElseThrow();
    }

    private Design editable(Design design) {
        return designs.editPublished(
                new Revision(design.draft().id(), design.draft().lockVersion(), null), 10001);
    }

    /** 把 value 字段改成对象引用（复用原列），并按 options 覆盖它的字段配置后保存草稿。 */
    private Design saveAsReference(Design design, FieldOptions options, List<Relation> relations) {
        FieldDefinition value = field(design, "value");
        List<FieldDefinition> fields =
                design.draft().fields().stream()
                        .map(
                                field ->
                                        field.id().equals(value.id())
                                                ? new FieldDefinition(
                                                        field.key(),
                                                        field.id(),
                                                        field.code(),
                                                        field.name(),
                                                        "REFERENCE",
                                                        null,
                                                        null,
                                                        null,
                                                        false,
                                                        false,
                                                        field.sort())
                                                : field)
                        .toList();
        Map<String, FieldOptions> all = new HashMap<>(design.fieldOptions());
        all.put(value.id(), options);
        return designs.save(
                new SaveDesign(
                        fixture.edit(
                                design.draft(), fields, List.of(), design.draft().titleFieldId()),
                        design.settings(),
                        all,
                        relations,
                        design.indexes(),
                        design.details()),
                10001);
    }

    private static Relation relationTo(Design target, String fieldId) {
        return new Relation(
                null,
                "kept_reference",
                "保留规则的引用",
                "REFERENCE",
                target.draft().id(),
                fieldId,
                null,
                false,
                "RESTRICT");
    }

    @Test
    void reusedReferenceColumnKeepsRulesAcrossRepeatedSaves() {
        Design target = published("keep_target", "TEXT", List.of(), Map.of());
        Design source = editable(published("keep_source", "TEXT", List.of(), Map.of()));
        String fieldId = field(source, "value").id();
        FieldRules rules = reference(field(target, "value").id());

        Design reference =
                saveAsReference(
                        source,
                        source.fieldOptions().get(fieldId).withRules(rules),
                        List.of(relationTo(target, fieldId)));
        assertThat(field(reference, "value").type()).isEqualTo("REFERENCE");
        assertThat(reference.fieldOptions().get(fieldId).nativeType()).isEqualTo("bigint");
        assertThat(reference.fieldOptions().get(fieldId).rules()).isEqualTo(rules);

        // 复用列的关系每次保存对象都会重写引用列配置：原样再保存两次，规则仍在。
        Design again =
                saveAsReference(
                        reference, reference.fieldOptions().get(fieldId), reference.relations());
        assertThat(again.fieldOptions().get(fieldId).rules()).isEqualTo(rules);
        Design third = saveAsReference(again, again.fieldOptions().get(fieldId), again.relations());
        assertThat(third.fieldOptions().get(fieldId).rules()).isEqualTo(rules);
        assertThat(designs.get(source.draft().id()).fieldOptions().get(fieldId).rules())
                .isEqualTo(rules);
    }

    @Test
    void savedSingleSelectConvertedInPlaceToReferenceKeepsItsRules() {
        Design target = published("select_target", "TEXT", List.of(), Map.of());
        FieldOptions choices =
                FieldOptions.copyOf(FieldOptions.defaults())
                        .options(List.of(new Option("legacy_code", "原选项", false)))
                        .build();
        Design source =
                editable(published("select_source", "SELECT", List.of(), Map.of("value", choices)));
        String fieldId = field(source, "value").id();
        FieldRules rules = reference(field(target, "value").id());
        // 前端在原地转换时清掉选项与选择来源，规则随字段带过来。
        FieldOptions converted =
                FieldOptions.copyOf(source.fieldOptions().get(fieldId))
                        .options(List.of())
                        .selection(null)
                        .rules(rules)
                        .build();

        Design reference = saveAsReference(source, converted, List.of(relationTo(target, fieldId)));
        assertThat(field(reference, "value").type()).isEqualTo("REFERENCE");
        assertThat(reference.relations())
                .singleElement()
                .extracting(Relation::fieldId)
                .isEqualTo(fieldId);
        assertThat(reference.fieldOptions().get(fieldId).rules()).isEqualTo(rules);

        Design again =
                saveAsReference(
                        reference, reference.fieldOptions().get(fieldId), reference.relations());
        assertThat(again.fieldOptions().get(fieldId).rules()).isEqualTo(rules);
    }

    @Test
    void conversionPreviewLeavesStoredRulesUntouched() {
        FieldRules rules = formula("price * qty", "HALF_UP");
        Design source =
                published(
                        "preview_rules",
                        "MONEY",
                        List.of(
                                fixture.field("price", "price", "DECIMAL", 2),
                                fixture.field("qty", "qty", "INTEGER", 3)),
                        Map.of("value", FieldOptions.defaults().withRules(rules)));
        String fieldId = field(source, "value").id();
        assertThat(source.fieldOptions().get(fieldId).rules()).isEqualTo(rules);
        FieldSwitchPreviewService preview =
                servicesContext.getBean(FieldSwitchPreviewService.class);

        // 金额 → 小数、金额 → 文本：预检只推演，不落库，也不因为字段带着规则而抛异常。
        for (String type : List.of("DECIMAL", "TEXT"))
            assertThatCode(
                            () ->
                                    preview.preview(
                                            new FieldSwitchPreview.Request(
                                                    source.draft().id(),
                                                    null,
                                                    fieldId,
                                                    type,
                                                    "TEXT".equals(type) ? 200 : null,
                                                    "DECIMAL".equals(type) ? 18 : null,
                                                    "DECIMAL".equals(type) ? 2 : null,
                                                    null,
                                                    null,
                                                    false)))
                    .doesNotThrowAnyException();
        // 预检请求不带规则：抽屉里把「公式默认值」改成「自定义默认值」时，库里尚未保存的旧公式不能让预检
        // 误报「两种值来源并存」。规则是否与新配置相容，由保存对象时的校验负责。
        FieldSwitchPreview.Result custom =
                preview.preview(
                        new FieldSwitchPreview.Request(
                                source.draft().id(),
                                null,
                                fieldId,
                                "DECIMAL",
                                null,
                                18,
                                2,
                                null,
                                null,
                                false,
                                false,
                                false,
                                null,
                                null,
                                null,
                                "5"));
        assertThat(custom.explanation()).doesNotContain("只能保留一种");
        assertThat(custom.impacts()).noneMatch(impact -> impact.message().contains("只能保留一种"));

        assertThat(designs.get(source.draft().id()).fieldOptions().get(fieldId).rules())
                .isEqualTo(rules);
        assertThat(
                        servicesContext
                                .getBean(DataObjectApi.class)
                                .getPublished(source.draft().id())
                                .fieldOptions()
                                .get(fieldId)
                                .rules())
                .isEqualTo(rules);
    }
}
