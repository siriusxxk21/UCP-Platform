package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.DataCenter.*;
import com.richuang.os.nocode.schema.service.convert.FieldSwitchPreviewService;

import org.junit.jupiter.api.*;

import java.util.*;

/** 当前开发库验证按列转换、精确确认和事务回滚；仅清理本测试前缀的表与元数据。 */
class FieldConversionIntegrationTest {
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

    private Design create(String suffix, String type) {
        SaveObjectDraft request = fixture.createRequest(suffix);
        List<FieldDefinition> fields = new ArrayList<>(request.fields());
        fields.add(fixture.field("value", "value", type, 1));
        fields.add(fixture.field("other", "other", "TEXT", 2));
        Map<String, FieldOptions> options = new HashMap<>();
        if ("SELECT".equals(type))
            options.put(
                    "value",
                    new FieldOptions(
                            null,
                            "NORMAL",
                            null,
                            null,
                            null,
                            null,
                            null,
                            "ACTIVE",
                            List.of(new Option("legacy_code", "原选项", false)),
                            null,
                            null,
                            "NONE",
                            null,
                            false,
                            false));
        Design design =
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
        PublishPlan plan = plan(design);
        assertThat(plan.checks()).noneMatch(Check::blocking);
        publisher.execute(new ExecutePlan(plan.id(), "字段转换夹具首发"), 10001);
        return designs.get(design.draft().id());
    }

    private FieldDefinition value(Design design) {
        return design.draft().fields().stream()
                .filter(f -> f.code().equals("value"))
                .findFirst()
                .orElseThrow();
    }

    private Design change(
            Design published, String type, boolean required, List<Relation> relations) {
        return change(published, type, null, null, required, relations);
    }

    private Design change(
            Design published,
            String type,
            Integer precision,
            Integer scale,
            boolean required,
            List<Relation> relations) {
        Design editable =
                designs.editPublished(
                        new Revision(published.draft().id(), published.draft().lockVersion(), null),
                        10001);
        FieldDefinition value = value(editable);
        List<FieldDefinition> fields =
                editable.draft().fields().stream()
                        .map(
                                f ->
                                        f.id().equals(value.id())
                                                ? new FieldDefinition(
                                                        f.key(), f.id(), f.code(), f.name(), type,
                                                        null, precision, scale, required, false,
                                                        f.sort())
                                                : f)
                        .toList();
        ObjectDraft draft = editable.draft();
        Map<String, FieldOptions> options = new HashMap<>(editable.fieldOptions());
        FieldOptions before = options.get(value.id());
        boolean sameSelectionSource =
                Set.of("SELECT", "MULTI_SELECT").contains(value.type())
                        && Set.of("SELECT", "MULTI_SELECT").contains(type);
        options.put(
                value.id(),
                new FieldOptions(
                        before.columnName(),
                        before.classification(),
                        null,
                        before.description(),
                        null,
                        null,
                        null,
                        before.state(),
                        sameSelectionSource ? before.options() : List.of(),
                        null,
                        null,
                        null,
                        before.nativeType(),
                        before.primaryKey(),
                        false,
                        sameSelectionSource ? before.selection() : null));
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
                                fields,
                                List.of()),
                        editable.settings(),
                        options,
                        relations,
                        editable.indexes(),
                        editable.details()),
                10001);
    }

    private PublishPlan plan(Design design) {
        return publisher.plan(
                new Revision(design.draft().id(), design.draft().lockVersion(), null), 10001);
    }

    private void insert(Design design, String value, boolean deleted) {
        jdbc.update(
                "INSERT INTO public.\""
                        + design.draft().tableName()
                        + "\" (name,value,other,deleted) VALUES (?,?,?,?)",
                "原记录",
                value,
                "保留其他列",
                deleted ? 1 : 0);
    }

    private Design withConstraints(Design design, String minimum, String maximum, String pattern) {
        Map<String, FieldOptions> options = new HashMap<>(design.fieldOptions());
        String fieldId = value(design).id();
        FieldOptions current = options.get(fieldId);
        options.put(
                fieldId,
                new FieldOptions(
                        current.columnName(),
                        current.classification(),
                        current.defaultValue(),
                        current.description(),
                        pattern,
                        minimum,
                        maximum,
                        current.state(),
                        current.options(),
                        current.expression(),
                        current.resultType(),
                        current.resolver(),
                        current.nativeType(),
                        current.primaryKey(),
                        current.generated(),
                        current.selection()));
        return designs.save(
                new SaveDesign(
                        fixture.edit(
                                design.draft(),
                                design.draft().fields(),
                                List.of(),
                                design.draft().titleFieldId()),
                        design.settings(),
                        options,
                        design.relations(),
                        design.indexes(),
                        design.details()),
                10001);
    }

    @Test
    void strictNumericTextPreservesAllValuesWithoutClearConfirmation() {
        Design source = create("strict_numeric", "TEXT");
        insert(source, "  +0012  ", false);
        insert(source, "-3", true);
        Design changed = change(source, "INTEGER", true, List.of());
        PublishPlan plan = plan(changed);
        assertThat(plan.checks()).noneMatch(Check::blocking);
        assertThat(plan.conversions())
                .singleElement()
                .satisfies(
                        conversion -> {
                            assertThat(conversion.action()).isEqualTo("PRESERVE_VALUES");
                            assertThat(conversion.affectedRows()).isEqualTo(2);
                            assertThat(conversion.failedRows()).isZero();
                        });
        FieldConversions.Page previewRows =
                servicesContext
                        .getBean(FieldSwitchPreviewService.class)
                        .rows(
                                new FieldSwitchPreview.RowsRequest(
                                        source.draft().id(),
                                        null,
                                        value(source).id(),
                                        "INTEGER",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        false,
                                        true,
                                        false,
                                        1,
                                        20,
                                        null,
                                        null,
                                        null,
                                        null));
        assertThat(previewRows.rows())
                .extracting(FieldConversions.Row::newValue)
                .containsExactly("12", "-3");
        assertThat(previewRows.rows()).filteredOn(FieldConversions.Row::deleted).hasSize(1);
        publisher.execute(new ExecutePlan(plan.id(), "按严格规则保留数值"), 10001);
        assertThat(
                        jdbc.queryForList(
                                "SELECT value FROM public.\""
                                        + source.draft().tableName()
                                        + "\" ORDER BY id",
                                Long.class))
                .containsExactly(12L, -3L);
        assertThat(
                        jdbc.queryForList(
                                "SELECT other FROM public.\"" + source.draft().tableName() + "\"",
                                String.class))
                .containsOnly("保留其他列");
    }

    @Test
    void failedStrictParsingOffersOnlyWholeColumnClear() {
        Design source = create("strict_reject", "TEXT");
        insert(source, "12", false);
        insert(source, "1e2", false);
        Design changed = change(source, "INTEGER", false, List.of());
        PublishPlan plan = plan(changed);
        assertThat(plan.conversions())
                .singleElement()
                .satisfies(
                        conversion -> {
                            assertThat(conversion.action()).isEqualTo("CLEAR_COLUMN");
                            assertThat(conversion.failedRows()).isEqualTo(1);
                            assertThat(conversion.affectedRows()).isEqualTo(2);
                        });
        FieldConversions.Page previewRows =
                servicesContext
                        .getBean(FieldSwitchPreviewService.class)
                        .rows(
                                new FieldSwitchPreview.RowsRequest(
                                        source.draft().id(),
                                        null,
                                        value(source).id(),
                                        "INTEGER",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        false,
                                        false,
                                        false,
                                        1,
                                        20,
                                        null,
                                        null,
                                        null,
                                        null));
        assertThat(previewRows.rows())
                .extracting(FieldConversions.Row::newValue)
                .containsExactly(null, null);
        assertThat(previewRows.rows().get(0).failureCodes()).isEmpty();
        assertThat(previewRows.rows().get(0).failureReason()).contains("整列清空");
        assertThat(previewRows.rows().get(1).failureCodes()).containsExactly("CONVERSION");
        assertThatThrownBy(() -> publisher.execute(new ExecutePlan(plan.id(), "未确认"), 10001))
                .hasMessageContaining("确认发布计划中全部待清空字段");
        publisher.execute(
                new ExecutePlan(plan(changed).id(), "确认清空整列", List.of(value(changed).id())), 10001);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(value) FROM public.\""
                                        + source.draft().tableName()
                                        + "\"",
                                Long.class))
                .isZero();
    }

    @Test
    void numericWideningPreservesValuesAndPrecisionOverflowRequiresClear() {
        Design source = create("numeric_widen", "INTEGER");
        jdbc.update(
                "INSERT INTO public.\""
                        + source.draft().tableName()
                        + "\" (name,value,other) VALUES ('原记录',?, '保留其他列')",
                12);
        Design changed = change(source, "DECIMAL", 4, 2, false, List.of());
        PublishPlan plan = plan(changed);
        assertThat(plan.conversions())
                .singleElement()
                .satisfies(
                        conversion -> assertThat(conversion.action()).isEqualTo("PRESERVE_VALUES"));
        publisher.execute(new ExecutePlan(plan.id(), "整数精确转小数"), 10001);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT value::text FROM public.\""
                                        + source.draft().tableName()
                                        + "\"",
                                String.class))
                .isEqualTo("12.00");

        Design large = create("numeric_overflow", "INTEGER");
        jdbc.update(
                "INSERT INTO public.\""
                        + large.draft().tableName()
                        + "\" (name,value,other) VALUES ('原记录',?, '保留其他列')",
                123);
        Design narrower = change(large, "DECIMAL", 4, 2, false, List.of());
        assertThat(plan(narrower).conversions())
                .singleElement()
                .satisfies(
                        conversion -> {
                            assertThat(conversion.action()).isEqualTo("CLEAR_COLUMN");
                            assertThat(conversion.failedRows()).isEqualTo(1);
                        });
    }

    @Test
    void sameSqlStorageStillChecksBusinessValueSemantics() {
        Design plain = create("plain_to_multiline", "TEXT");
        insert(plain, "完整保留", false);
        Design multiline = change(plain, "TEXTAREA", false, List.of());
        assertThat(plan(multiline).conversions())
                .singleElement()
                .satisfies(
                        conversion -> assertThat(conversion.action()).isEqualTo("PRESERVE_VALUES"));

        Design richSource = create("multiline_to_rich", "TEXTAREA");
        insert(richSource, "原始纯文本", false);
        Design richTarget = change(richSource, "RICH_TEXT", false, List.of());
        assertThat(plan(richTarget).conversions())
                .singleElement()
                .satisfies(
                        conversion -> {
                            assertThat(conversion.action()).isEqualTo("CLEAR_COLUMN");
                            assertThat(conversion.affectedRows()).isEqualTo(1);
                        });
    }

    @Test
    void targetRangeAndPatternAreCheckedBeforePreservingValues() {
        Design source = create("target_range", "INTEGER");
        jdbc.update(
                "INSERT INTO public.\""
                        + source.draft().tableName()
                        + "\" (name,value,other) VALUES ('原记录',12,'保留其他列')");
        Design decimal =
                withConstraints(
                        change(source, "DECIMAL", 4, 2, false, List.of()), null, "10", null);
        assertThat(plan(decimal).conversions())
                .singleElement()
                .satisfies(
                        conversion -> {
                            assertThat(conversion.action()).isEqualTo("CLEAR_COLUMN");
                            assertThat(conversion.failedRows()).isEqualTo(1);
                        });

        Design text = create("target_pattern", "TEXT");
        insert(text, "lowercase", false);
        Design multiline =
                withConstraints(change(text, "TEXTAREA", false, List.of()), null, null, "[A-Z]+");
        assertThat(plan(multiline).conversions())
                .singleElement()
                .satisfies(
                        conversion -> {
                            assertThat(conversion.action()).isEqualTo("CLEAR_COLUMN");
                            assertThat(conversion.failedRows()).isEqualTo(1);
                        });
    }

    @Test
    void urlLinkAndSameSourceSelectionShapesPreserveCompleteValues() {
        Design url = create("url_to_text", "URL");
        jdbc.update(
                "INSERT INTO public.\""
                        + url.draft().tableName()
                        + "\" (name,value,other) VALUES ('原记录',?::jsonb,'保留其他列')",
                "{\"link\":\"https://example.com/a\",\"text\":\"\"}");
        Design text = change(url, "TEXT", false, List.of());
        PublishPlan urlPlan = plan(text);
        assertThat(urlPlan.conversions())
                .singleElement()
                .satisfies(c -> assertThat(c.action()).isEqualTo("PRESERVE_VALUES"));
        publisher.execute(new ExecutePlan(urlPlan.id(), "提取完整链接"), 10001);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT value FROM public.\"" + url.draft().tableName() + "\"",
                                String.class))
                .isEqualTo("https://example.com/a");

        Design selection = create("selection_shape", "SELECT");
        insert(selection, "legacy_code", false);
        Design multi = change(selection, "MULTI_SELECT", false, List.of());
        PublishPlan multiPlan = plan(multi);
        assertThat(multiPlan.conversions())
                .singleElement()
                .satisfies(c -> assertThat(c.action()).isEqualTo("PRESERVE_VALUES"));
        publisher.execute(new ExecutePlan(multiPlan.id(), "单选完整转多选"), 10001);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT value::text FROM public.\""
                                        + selection.draft().tableName()
                                        + "\"",
                                String.class))
                .isEqualTo("[\"legacy_code\"]");
        Design back = change(designs.get(selection.draft().id()), "SELECT", false, List.of());
        PublishPlan backPlan = plan(back);
        assertThat(backPlan.conversions())
                .singleElement()
                .satisfies(c -> assertThat(c.action()).isEqualTo("PRESERVE_VALUES"));
        publisher.execute(new ExecutePlan(backPlan.id(), "单项多选完整转单选"), 10001);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT value FROM public.\""
                                        + selection.draft().tableName()
                                        + "\"",
                                String.class))
                .isEqualTo("legacy_code");
    }

    @Test
    void tableWithRowsAndEmptyColumnCanChangeWithoutClearingConfirmation() {
        Design source = create("emptycol", "TEXT");
        insert(source, null, false);
        Design changed = change(source, "INTEGER", false, List.of());
        PublishPlan plan = plan(changed);
        assertThat(plan.checks()).noneMatch(Check::blocking);
        assertThat(plan.conversions())
                .singleElement()
                .satisfies(c -> assertThat(c.affectedRows()).isZero());
        publisher.execute(new ExecutePlan(plan.id(), "空列改型"), 10001);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT other FROM public.\"" + source.draft().tableName() + "\"",
                                String.class))
                .isEqualTo("保留其他列");
        assertThat(
                        databaseMetadata
                                .readTable("public", source.draft().tableName())
                                .orElseThrow()
                                .columns())
                .anyMatch(c -> c.name().equals("value") && c.nativeType().equals("bigint"));
    }

    @Test
    void nonEmptyAndDeletedValuesRequireExactConfirmationAndKeepRecordIdentity() {
        Design source = create("clearcol", "TEXT");
        insert(source, "旧值", false);
        insert(source, "已删旧值", true);
        List<Long> ids =
                jdbc.queryForList(
                        "SELECT id FROM public.\"" + source.draft().tableName() + "\" ORDER BY id",
                        Long.class);
        Design changed = change(source, "INTEGER", false, List.of());
        PublishPlan plan = plan(changed);
        assertThat(plan.checks()).noneMatch(Check::blocking);
        assertThat(plan.conversions())
                .singleElement()
                .satisfies(
                        c -> {
                            assertThat(c.affectedRows()).isEqualTo(2);
                            assertThat(c.deletedRows()).isEqualTo(1);
                        });
        FieldConversions.Page rows =
                publisher.conversionRows(plan.id(), value(changed).id(), 1, 20);
        assertThat(rows.rows())
                .extracting(FieldConversions.Row::oldValue)
                .containsExactly("旧值", "已删旧值");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(value) FROM public.\""
                                        + source.draft().tableName()
                                        + "\"",
                                Long.class))
                .isEqualTo(2);
        assertThatThrownBy(() -> publisher.execute(new ExecutePlan(plan.id(), "未确认"), 10001))
                .hasMessageContaining("确认发布计划中全部待清空字段");
        PublishPlan confirmed = plan(changed);
        publisher.execute(
                new ExecutePlan(confirmed.id(), "确认仅清空本列", List.of(value(changed).id())), 10001);
        assertThat(
                        jdbc.queryForList(
                                "SELECT id FROM public.\""
                                        + source.draft().tableName()
                                        + "\" ORDER BY id",
                                Long.class))
                .isEqualTo(ids);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(value) FROM public.\""
                                        + source.draft().tableName()
                                        + "\"",
                                Long.class))
                .isZero();
        assertThat(
                        jdbc.queryForList(
                                "SELECT other FROM public.\"" + source.draft().tableName() + "\"",
                                String.class))
                .containsOnly("保留其他列");
    }

    @Test
    void sameCountButChangedValueInvalidatesConfirmation() {
        Design source = create("stale", "TEXT");
        insert(source, "旧值", false);
        Design changed = change(source, "INTEGER", false, List.of());
        PublishPlan plan = plan(changed);
        jdbc.update("UPDATE public.\"" + source.draft().tableName() + "\" SET value=?", "新值");
        assertThatThrownBy(
                        () ->
                                publisher.execute(
                                        new ExecutePlan(
                                                plan.id(), "确认旧预览", List.of(value(changed).id())),
                                        10001))
                .hasMessageContaining("已变化");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT value FROM public.\"" + source.draft().tableName() + "\"",
                                String.class))
                .isEqualTo("新值");
    }

    @Test
    void transactionFailureRestoresValuesAndPhysicalType() {
        Design source = create("rollback", "TEXT");
        insert(source, "保留旧值", false);
        Design changed = change(source, "INTEGER", false, List.of());
        PublishPlan plan = plan(changed);
        writeFailure.failAfter(
                "UPDATE \"public\".\"" + source.draft().tableName() + "\" SET \"value\" = NULL");
        assertThatThrownBy(
                        () ->
                                publisher.execute(
                                        new ExecutePlan(
                                                plan.id(), "触发事务回滚", List.of(value(changed).id())),
                                        10001))
                .hasMessageContaining("回滚");
        writeFailure.clear();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT value FROM public.\"" + source.draft().tableName() + "\"",
                                String.class))
                .isEqualTo("保留旧值");
        assertThat(
                        databaseMetadata
                                .readTable("public", source.draft().tableName())
                                .orElseThrow()
                                .columns())
                .anyMatch(
                        c ->
                                c.name().equals("value")
                                        && c.nativeType().startsWith("character varying"));
        assertThat(designs.published(source.draft().id()).fields())
                .anyMatch(f -> f.code().equals("value") && f.type().equals("TEXT"));
    }

    @Test
    void requiredTargetCannotBeClearedAndConverted() {
        Design source = create("required", "TEXT");
        insert(source, "旧值", false);
        Design changed = change(source, "INTEGER", true, List.of());
        PublishPlan plan = plan(changed);
        assertThat(plan.state()).isEqualTo("BLOCKED");
        assertThat(plan.conversions())
                .singleElement()
                .satisfies(c -> assertThat(c.clearAllowed()).isFalse());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT value FROM public.\"" + source.draft().tableName() + "\"",
                                String.class))
                .isEqualTo("旧值");
    }

    @Test
    void savedSelectionConvertsToRelationOnSameColumnWithoutReinterpretingOldCodes() {
        Design target = create("target", "TEXT");
        Design source = create("selection", "SELECT");
        insert(source, "legacy_code", false);
        String fieldId = value(source).id();
        Relation relation =
                new Relation(
                        null,
                        "selected_record",
                        "选择记录",
                        "REFERENCE",
                        target.draft().id(),
                        fieldId,
                        null,
                        false,
                        "RESTRICT");
        Design changed = change(source, "REFERENCE", false, List.of(relation));
        assertThat(value(changed).id()).isEqualTo(fieldId);
        assertThat(changed.fieldOptions().get(fieldId).columnName()).isEqualTo("value");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT value FROM public.\"" + source.draft().tableName() + "\"",
                                String.class))
                .isEqualTo("legacy_code");
        PublishPlan plan = plan(changed);
        assertThat(plan.checks()).noneMatch(Check::blocking);
        publisher.execute(new ExecutePlan(plan.id(), "确认转换对象引用", List.of(fieldId)), 10001);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(value) FROM public.\""
                                        + source.draft().tableName()
                                        + "\"",
                                Long.class))
                .isZero();
        assertThat(
                        databaseMetadata
                                .readTable("public", source.draft().tableName())
                                .orElseThrow()
                                .columns())
                .anyMatch(c -> c.name().equals("value") && c.nativeType().equals("bigint"));
        assertThat(designs.published(source.draft().id()).relations())
                .singleElement()
                .satisfies(r -> assertThat(r.fieldId()).isEqualTo(fieldId));
    }

    @Test
    void zeroIntegerStillRequiresConfirmationWhenStorageTypeMatchesReference() {
        Design target = create("zero_target", "TEXT");
        Design source = create("zero_source", "INTEGER");
        jdbc.update(
                "INSERT INTO public.\""
                        + source.draft().tableName()
                        + "\" (name,value,other) VALUES (?,0,?)",
                "零值记录",
                "保留其他列");
        String fieldId = value(source).id();
        Relation relation =
                new Relation(
                        null,
                        "zero_record",
                        "选择记录",
                        "REFERENCE",
                        target.draft().id(),
                        fieldId,
                        null,
                        false,
                        "RESTRICT");
        Design changed = change(source, "REFERENCE", false, List.of(relation));
        PublishPlan plan = plan(changed);
        assertThat(plan.checks()).noneMatch(Check::blocking);
        assertThat(plan.conversions())
                .singleElement()
                .satisfies(
                        c -> {
                            assertThat(c.fromType()).isEqualTo(c.toType());
                            assertThat(c.affectedRows()).isEqualTo(1);
                            assertThat(c.fingerprint()).isEmpty();
                        });
        assertThat(publisher.conversionRows(plan.id(), fieldId, 1, 20).rows())
                .singleElement()
                .satisfies(r -> assertThat(r.oldValue()).isEqualTo("0"));
        publisher.execute(new ExecutePlan(plan.id(), "零值也必须明确清空", List.of(fieldId)), 10001);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.\""
                                        + source.draft().tableName()
                                        + "\" WHERE value IS NULL AND other='保留其他列'",
                                Long.class))
                .isEqualTo(1);
    }

    @Test
    void retainedPhysicalFormulaBlocksConversionEvenAfterFormulaWasRemovedFromPublishedDesign() {
        Design source = create("retained_formula", "INTEGER");
        Design editable =
                designs.editPublished(
                        new Revision(source.draft().id(), source.draft().lockVersion(), null),
                        10001);
        Map<String, FieldOptions> options = new HashMap<>(editable.fieldOptions());
        options.put(
                "formula",
                new FieldOptions(
                        null,
                        "NORMAL",
                        null,
                        null,
                        null,
                        null,
                        null,
                        "ACTIVE",
                        List.of(),
                        "value + 1",
                        "DECIMAL",
                        null,
                        null,
                        false,
                        false));
        Design formula =
                designs.save(
                        new SaveDesign(
                                fixture.edit(
                                        editable.draft(),
                                        List.of(
                                                fixture.field(
                                                        "formula",
                                                        "calculated_value",
                                                        "FORMULA",
                                                        3)),
                                        List.of(),
                                        editable.draft().titleFieldId()),
                                editable.settings(),
                                options,
                                editable.relations(),
                                editable.indexes(),
                                editable.details()),
                        10001);
        PublishPlan added = plan(formula);
        assertThat(added.checks()).noneMatch(Check::blocking);
        publisher.execute(new ExecutePlan(added.id(), "建立保留计算列夹具"), 10001);
        source = designs.get(source.draft().id());
        String formulaId =
                source.draft().fields().stream()
                        .filter(f -> f.code().equals("calculated_value"))
                        .findFirst()
                        .orElseThrow()
                        .id();
        editable =
                designs.editPublished(
                        new Revision(source.draft().id(), source.draft().lockVersion(), null),
                        10001);
        List<FieldDefinition> fields =
                editable.draft().fields().stream()
                        .filter(f -> !f.id().equals(formulaId))
                        .map(
                                f ->
                                        f.code().equals("value")
                                                ? new FieldDefinition(
                                                        f.key(), f.id(), f.code(), f.name(), "TEXT",
                                                        200, null, null, false, false, f.sort())
                                                : f)
                        .toList();
        options = new HashMap<>(editable.fieldOptions());
        options.remove(formulaId);
        Design removed =
                designs.save(
                        new SaveDesign(
                                fixture.edit(
                                        editable.draft(),
                                        fields,
                                        List.of(formulaId),
                                        editable.draft().titleFieldId()),
                                editable.settings(),
                                options,
                                editable.relations(),
                                editable.indexes(),
                                editable.details()),
                        10001);
        PublishPlan sameDraft = plan(removed);
        assertThat(sameDraft.checks()).anyMatch(Check::blocking);
        assertThat(sameDraft.conversions())
                .singleElement()
                .satisfies(
                        c ->
                                assertThat(c.impacts())
                                        .anyMatch(
                                                i ->
                                                        i.blocking()
                                                                && (i.location() + i.message())
                                                                        .contains(
                                                                                "calculated_value")));

        // 先只发布停用公式，再次转换时上一已发布定义也不含该字段，仍须看物理目录。
        Design onlyRemoved = change(removed, "INTEGER", false, List.of());
        PublishPlan deactivate = plan(onlyRemoved);
        assertThat(deactivate.checks()).noneMatch(Check::blocking);
        publisher.execute(new ExecutePlan(deactivate.id(), "停用公式保留物理列"), 10001);
        assertThat(designs.published(source.draft().id()).fields())
                .noneMatch(f -> f.id().equals(formulaId));
        Design later = change(designs.get(source.draft().id()), "TEXT", false, List.of());
        PublishPlan retained = plan(later);
        assertThat(retained.checks()).anyMatch(Check::blocking);
        assertThat(retained.conversions())
                .singleElement()
                .satisfies(
                        c ->
                                assertThat(c.impacts())
                                        .anyMatch(
                                                i ->
                                                        i.blocking()
                                                                && (i.location() + i.message())
                                                                        .contains(
                                                                                "calculated_value")));
    }

    @Test
    void physicalFormulaDependenciesUseColumnIdentityRatherThanExpressionSubstrings() {
        Design source = create("physical_dep", "INTEGER");
        String table =
                com.richuang.os.framework.mybatis.core.metadata.PostgreSqlCommands.table(
                        "public", source.draft().tableName());
        jdbc.execute(
                "ALTER TABLE "
                        + table
                        + " ADD COLUMN value_extra bigint, ADD COLUMN depends_on_value bigint"
                        + " GENERATED ALWAYS AS (value + 1) STORED, ADD COLUMN unrelated_generated"
                        + " bigint GENERATED ALWAYS AS (value_extra + 1) STORED, ADD COLUMN"
                        + " literal_generated text GENERATED ALWAYS AS ('value'::text) STORED");
        com.richuang.os.nocode.metadata.dal.mapper.FieldConversionMapper mapper =
                servicesContext.getBean(
                        com.richuang.os.nocode.metadata.dal.mapper.FieldConversionMapper.class);
        com.richuang.os.nocode.metadata.dal.mapper.FieldConversionMapper.Statement statement =
                new com.richuang.os.nocode.metadata.dal.mapper.FieldConversionMapper.Statement(
                        "public",
                        source.draft().tableName(),
                        "value",
                        "id",
                        "name",
                        null,
                        "text",
                        value(source).id(),
                        "10001",
                        0,
                        20);
        assertThat(mapper.generatedDependents(statement)).containsExactly("depends_on_value");
        jdbc.execute("ALTER TABLE " + table + " DROP COLUMN depends_on_value");
        assertThat(mapper.generatedDependents(statement)).isEmpty();
    }

    private FieldSwitchPreview.Result preview(Design design, String type) {
        return preview(design, type, false, null);
    }

    private FieldSwitchPreview.Result preview(
            Design design, String type, boolean detach, String target) {
        return servicesContext
                .getBean(FieldSwitchPreviewService.class)
                .preview(
                        new FieldSwitchPreview.Request(
                                design.draft().id(),
                                null,
                                value(design).id(),
                                type,
                                null,
                                null,
                                null,
                                null,
                                target,
                                detach));
    }

    @Test
    void switchPreviewCountsAllPhysicalValuesBeforeChangingDraft() {
        Design source = create("preview_all", "TEXT");
        insert(source, "", false);
        insert(source, "已删除记录的值", true);
        insert(source, null, false);
        int revision = source.draft().lockVersion();
        FieldSwitchPreview.Result result = preview(source, "INTEGER");
        assertThat(result.deploymentState()).isEqualTo(FieldSwitchPreview.DeploymentState.DEPLOYED);
        assertThat(result.decision()).isEqualTo(FieldSwitchPreview.Decision.CLEAR_COLUMN);
        assertThat(result.totalRows()).isEqualTo(3);
        assertThat(result.valueRows()).isEqualTo(2);
        assertThat(result.sourceType()).isEqualTo("TEXT");
        assertThat(result.targetType()).isEqualTo("INTEGER");
        assertThat(result.storage().actualType()).isEqualTo("character varying(200)");
        assertThat(result.storage().targetType()).isEqualTo("bigint");
        assertThat(result.storage().tableName()).isEqualTo(source.draft().tableName());
        assertThat(result.storage().columnName()).isEqualTo("value");
        assertThat(result.storage().nullable()).isTrue();
        assertThat(result.storage().primaryKey()).isFalse();
        assertThat(result.storage().ddlRequired()).isTrue();
        assertThat(result.storage().ddlExplanation()).contains("ALTER COLUMN TYPE");
        assertThat(designs.get(source.draft().id()).draft().lockVersion()).isEqualTo(revision);
        assertThat(value(designs.get(source.draft().id())).type()).isEqualTo("TEXT");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(value) FROM public.\""
                                        + source.draft().tableName()
                                        + "\"",
                                Long.class))
                .isEqualTo(2);
    }

    @Test
    void sameTypeConstraintsKeepValuesAndReportExactUnionWithNullableRows() {
        Design source = create("same_type_constraints", "TEXT");
        insert(source, "dup", false);
        insert(source, "dup", true);
        insert(source, null, false);
        FieldSwitchPreviewService service =
                servicesContext.getBean(FieldSwitchPreviewService.class);
        FieldSwitchPreview.Request request =
                new FieldSwitchPreview.Request(
                        source.draft().id(),
                        null,
                        value(source).id(),
                        "TEXT",
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        true,
                        true,
                        null,
                        null,
                        "^OK$",
                        null);
        FieldSwitchPreview.Result preview = service.preview(request);
        assertThat(preview.decision()).isEqualTo(FieldSwitchPreview.Decision.BLOCKED);
        assertThat(preview.failedRows()).isEqualTo(3);
        assertThat(preview.conflicts())
                .extracting(FieldSwitchPreview.Conflict::code)
                .containsExactly("REQUIRED", "PATTERN", "UNIQUE");
        assertThat(preview.conflicts())
                .extracting(FieldSwitchPreview.Conflict::count)
                .containsExactly(1L, 2L, 2L);
        assertThat(preview.impacts())
                .filteredOn(
                        impact ->
                                impact.location().contains("REQUIRED")
                                        || impact.location().contains("必填"))
                .hasSize(1);
        FieldConversions.Page page =
                service.rows(
                        new FieldSwitchPreview.RowsRequest(
                                source.draft().id(),
                                null,
                                value(source).id(),
                                "TEXT",
                                null,
                                null,
                                null,
                                null,
                                null,
                                false,
                                true,
                                true,
                                1,
                                50,
                                null,
                                null,
                                "^OK$",
                                null));
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.rows()).hasSize(3);
        assertThat(page.rows())
                .filteredOn(row -> row.oldValue() == null)
                .singleElement()
                .satisfies(row -> assertThat(row.failureCodes()).contains("REQUIRED"));
        assertThat(page.rows())
                .filteredOn(row -> "dup".equals(row.oldValue()))
                .allSatisfy(row -> assertThat(row.failureCodes()).contains("PATTERN", "UNIQUE"));
        assertThat(page.rows()).anyMatch(FieldConversions.Row::deleted);
    }

    @Test
    void constraintOnlyDraftIsBlockedWithoutOfferingClearAndTypeConversionCanOfferClear() {
        Design source = create("constraint_only", "TEXT");
        insert(source, "bad", false);
        Design editable =
                designs.editPublished(
                        new Revision(source.draft().id(), source.draft().lockVersion(), null),
                        10001);
        Design changed = withConstraints(editable, null, null, "^OK$");
        PublishPlan plan = plan(changed);
        assertThat(plan.checks()).anyMatch(Check::blocking);
        assertThat(plan.conversions())
                .singleElement()
                .satisfies(
                        change -> {
                            assertThat(change.action()).isEqualTo("KEEP_COLUMN");
                            assertThat(change.clearAllowed()).isFalse();
                            assertThat(change.affectedRows()).isEqualTo(1);
                            assertThat(change.failedRows()).isEqualTo(1);
                        });
        FieldSwitchPreview.Result conversion =
                servicesContext
                        .getBean(FieldSwitchPreviewService.class)
                        .preview(
                                new FieldSwitchPreview.Request(
                                        source.draft().id(),
                                        null,
                                        value(source).id(),
                                        "INTEGER",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        false,
                                        false,
                                        true,
                                        null,
                                        null,
                                        null,
                                        null));
        assertThat(conversion.decision()).isEqualTo(FieldSwitchPreview.Decision.CLEAR_COLUMN);
        assertThat(conversion.failedRows()).isEqualTo(1);
    }

    @Test
    void localOptionLabelOrDisabledStateKeepsHistoricalCodeButRemovingCodeBlocks() {
        Design source = create("option_identity", "SELECT");
        insert(source, "legacy_code", true);
        insert(source, "orphan_from_old_import", false);
        FieldSwitchPreviewService service =
                servicesContext.getBean(FieldSwitchPreviewService.class);
        FieldSwitchPreview.Request retained =
                new FieldSwitchPreview.Request(
                        source.draft().id(),
                        null,
                        value(source).id(),
                        "SELECT",
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        false,
                        false,
                        null,
                        null,
                        null,
                        null,
                        List.of(new Option("legacy_code", "新名称", true)));
        FieldSwitchPreview.Result compatible = service.preview(retained);
        assertThat(compatible.decision()).isEqualTo(FieldSwitchPreview.Decision.PRESERVE);
        assertThat(compatible.failedRows()).isZero();
        FieldSwitchPreview.Result defaultOnly =
                service.preview(
                        new FieldSwitchPreview.Request(
                                source.draft().id(),
                                null,
                                value(source).id(),
                                "SELECT",
                                null,
                                null,
                                null,
                                null,
                                null,
                                false,
                                false,
                                false,
                                null,
                                null,
                                null,
                                "legacy_code"));
        // 选项类字段不设默认值（未作答与「选中默认项」无法区分，会污染统计）：只改默认值的预检被拦下并说明原因。
        assertThat(defaultOnly.decision()).isEqualTo(FieldSwitchPreview.Decision.BLOCKED);
        assertThat(defaultOnly.explanation()).contains("是选项类字段，不能设置默认值");
        FieldSwitchPreview.Request removed =
                new FieldSwitchPreview.Request(
                        source.draft().id(),
                        null,
                        value(source).id(),
                        "SELECT",
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        false,
                        false,
                        null,
                        null,
                        null,
                        null,
                        List.of(new Option("new_code", "新选项", false)));
        FieldSwitchPreview.Result blocked = service.preview(removed);
        assertThat(blocked.decision()).isEqualTo(FieldSwitchPreview.Decision.BLOCKED);
        assertThat(blocked.failedRows()).isEqualTo(1);
        assertThat(blocked.conflicts())
                .extracting(FieldSwitchPreview.Conflict::code)
                .containsExactly("SELECTION");
    }

    @Test
    void sameTypeRequiredAndUniquePublishCreatesPhysicalConstraintsWithoutChangingValues() {
        Design source = create("keep_constraints", "TEXT");
        insert(source, "alpha", false);
        insert(source, "beta", true);
        Design editable =
                designs.editPublished(
                        new Revision(source.draft().id(), source.draft().lockVersion(), null),
                        10001);
        String fieldId = value(editable).id();
        List<FieldDefinition> fields =
                editable.draft().fields().stream()
                        .map(
                                field ->
                                        field.id().equals(fieldId)
                                                ? new FieldDefinition(
                                                        field.key(),
                                                        field.id(),
                                                        field.code(),
                                                        field.name(),
                                                        field.type(),
                                                        field.length(),
                                                        field.precision(),
                                                        field.scale(),
                                                        true,
                                                        true,
                                                        field.sort())
                                                : field)
                        .toList();
        Design changed =
                designs.save(
                        new SaveDesign(
                                fixture.edit(
                                        editable.draft(),
                                        fields,
                                        List.of(),
                                        editable.draft().titleFieldId()),
                                editable.settings(),
                                editable.fieldOptions(),
                                editable.relations(),
                                editable.indexes(),
                                editable.details()),
                        10001);
        PublishPlan plan = plan(changed);
        assertThat(plan.checks()).noneMatch(Check::blocking);
        assertThat(plan.conversions())
                .singleElement()
                .satisfies(
                        change -> {
                            assertThat(change.action()).isEqualTo("KEEP_COLUMN");
                            assertThat(change.affectedRows()).isEqualTo(2);
                            assertThat(change.failedRows()).isZero();
                        });
        publisher.execute(new ExecutePlan(plan.id(), "同类型开启必填与唯一"), 10001);
        com.richuang.os.framework.mybatis.core.metadata.DatabaseMetadata.Table actual =
                databaseMetadata.readTable("public", source.draft().tableName()).orElseThrow();
        assertThat(actual.columns())
                .anyMatch(
                        column ->
                                column.name().equals("value")
                                        && Boolean.FALSE.equals(column.nullable()));
        assertThat(actual.indexes())
                .anyMatch(
                        index ->
                                index.name().equals("nocode_u_" + fieldId)
                                        && Boolean.TRUE.equals(index.unique()));
        assertThat(
                        jdbc.queryForList(
                                "SELECT value FROM public.\""
                                        + source.draft().tableName()
                                        + "\" ORDER BY id",
                                String.class))
                .containsExactly("alpha", "beta");
    }

    @Test
    void changingOnlyDefaultDoesNotBackfillExistingNullValues() {
        Design source = create("default_future_only", "TEXT");
        insert(source, null, false);
        insert(source, "existing", false);
        Design editable =
                designs.editPublished(
                        new Revision(source.draft().id(), source.draft().lockVersion(), null),
                        10001);
        String fieldId = value(editable).id();
        Map<String, FieldOptions> options = new HashMap<>(editable.fieldOptions());
        FieldOptions before = options.get(fieldId);
        options.put(
                fieldId,
                new FieldOptions(
                        before.columnName(),
                        before.classification(),
                        "future",
                        before.description(),
                        before.pattern(),
                        before.minimum(),
                        before.maximum(),
                        before.state(),
                        before.options(),
                        before.expression(),
                        before.resultType(),
                        before.resolver(),
                        before.nativeType(),
                        before.primaryKey(),
                        before.generated(),
                        before.selection()));
        Design changed =
                designs.save(
                        new SaveDesign(
                                fixture.edit(
                                        editable.draft(),
                                        editable.draft().fields(),
                                        List.of(),
                                        editable.draft().titleFieldId()),
                                editable.settings(),
                                options,
                                editable.relations(),
                                editable.indexes(),
                                editable.details()),
                        10001);
        PublishPlan plan = plan(changed);
        assertThat(plan.checks()).noneMatch(Check::blocking);
        assertThat(plan.conversions())
                .singleElement()
                .satisfies(change -> assertThat(change.action()).isEqualTo("KEEP_COLUMN"));
        publisher.execute(new ExecutePlan(plan.id(), "仅调整未来默认值"), 10001);
        assertThat(
                        jdbc.queryForList(
                                "SELECT value FROM public.\""
                                        + source.draft().tableName()
                                        + "\" ORDER BY id",
                                String.class))
                .containsExactly(null, "existing");
        jdbc.update(
                "INSERT INTO public.\"" + source.draft().tableName() + "\" (name) VALUES ('new')");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT value FROM public.\""
                                        + source.draft().tableName()
                                        + "\" ORDER BY id DESC LIMIT 1",
                                String.class))
                .isEqualTo("future");
    }

    @Test
    void switchPreviewSeparatesEmptyColumnCompatibleChangeAndIncompleteOptions() {
        Design source = create("preview_empty", "TEXT");
        insert(source, null, false);
        FieldSwitchPreview.Result empty = preview(source, "INTEGER");
        assertThat(empty.totalRows()).isEqualTo(1);
        assertThat(empty.valueRows()).isZero();
        assertThat(empty.decision()).isEqualTo(FieldSwitchPreview.Decision.PRESERVE);
        assertThat(empty.storage().ddlRequired()).isTrue();
        assertThat(empty.explanation()).doesNotContain("null");
        FieldSwitchPreview.Result unchanged = preview(source, "TEXT");
        assertThat(unchanged.storage().ddlRequired()).isFalse();
        assertThat(unchanged.explanation()).doesNotContain("null");
        insert(source, "普通文本", false);
        assertThat(preview(source, "TEXTAREA").decision())
                .isEqualTo(FieldSwitchPreview.Decision.PRESERVE);
        FieldSwitchPreview.Result selection = preview(source, "SELECT");
        assertThat(selection.decision()).isEqualTo(FieldSwitchPreview.Decision.BLOCKED);
        assertThat(selection.valueRows()).isEqualTo(1);
        assertThat(selection.impacts())
                .anyMatch(FieldConversionDependencyInspector.Impact::blocking);
    }

    @Test
    void switchPreviewCountsZeroAndFalseAsStoredValues() {
        Design numbers = create("preview_zero", "INTEGER");
        jdbc.update(
                "INSERT INTO public.\""
                        + numbers.draft().tableName()
                        + "\" (name,value) VALUES ('零',0)");
        assertThat(preview(numbers, "TEXT").valueRows()).isEqualTo(1);
        Design flags = create("preview_false", "BOOLEAN");
        jdbc.update(
                "INSERT INTO public.\""
                        + flags.draft().tableName()
                        + "\" (name,value) VALUES ('否',false)");
        assertThat(preview(flags, "TEXT").valueRows()).isEqualTo(1);
    }

    @Test
    void switchPreviewDoesNotInventZeroForUnpublishedOrMissingColumns() {
        SaveObjectDraft request = fixture.createRequest("preview_unpublished");
        List<FieldDefinition> fields = new ArrayList<>(request.fields());
        fields.add(fixture.field("value", "value", "TEXT", 1));
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
                                Map.of(),
                                List.of(),
                                List.of(),
                                List.of()),
                        10001);
        FieldSwitchPreview.Result unpublished = preview(draft, "INTEGER");
        assertThat(unpublished.deploymentState())
                .isEqualTo(FieldSwitchPreview.DeploymentState.UNPUBLISHED);
        assertThat(unpublished.decision()).isEqualTo(FieldSwitchPreview.Decision.UNPUBLISHED);
        assertThat(unpublished.totalRows()).isNull();
        assertThat(unpublished.valueRows()).isNull();
        Design source = create("preview_missing", "TEXT");
        jdbc.execute("ALTER TABLE public.\"" + source.draft().tableName() + "\" DROP COLUMN value");
        FieldSwitchPreview.Result missing = preview(source, "INTEGER");
        assertThat(missing.deploymentState())
                .isEqualTo(FieldSwitchPreview.DeploymentState.MISSING_COLUMN);
        assertThat(missing.decision()).isEqualTo(FieldSwitchPreview.Decision.BLOCKED);
        assertThat(missing.totalRows()).isNull();
        assertThat(missing.valueRows()).isNull();
    }

    private Design explicitReference(String suffix, String kind) {
        return explicitReference(suffix, kind, "MASTER_DETAIL".equals(kind));
    }

    private Design explicitReference(String suffix, String kind, boolean required) {
        Design target = create(suffix + "_target", "TEXT");
        Long targetId =
                jdbc.queryForObject(
                        "INSERT INTO public.\""
                                + target.draft().tableName()
                                + "\" (name) VALUES ('目标记录') RETURNING id",
                        Long.class);
        Design source = create(suffix + "_source", "INTEGER");
        Relation relation =
                new Relation(
                        null,
                        "selected_record",
                        "显式引用",
                        kind,
                        target.draft().id(),
                        value(source).id(),
                        null,
                        required,
                        "RESTRICT");
        Design reference = change(source, "REFERENCE", required, List.of(relation));
        PublishPlan plan = plan(reference);
        assertThat(plan.checks()).noneMatch(Check::blocking);
        publisher.execute(new ExecutePlan(plan.id(), "建立显式单值引用夹具"), 10001);
        jdbc.update(
                "INSERT INTO public.\""
                        + source.draft().tableName()
                        + "\" (name,value,other) VALUES (?,?,?)",
                "引用记录",
                targetId,
                "保留其他列");
        return designs.get(source.draft().id());
    }

    @Test
    void publishedSingleReferenceCanRetargetOnlyAfterClearingItsOwnValues() {
        Design reference = explicitReference("retarget", "REFERENCE", false);
        Design nextTarget = create("retarget_next", "TEXT");
        String fieldId = value(reference).id();
        Relation oldRelation = reference.relations().getFirst();
        FieldSwitchPreview.Result preview =
                preview(reference, "REFERENCE", false, nextTarget.draft().id());
        assertThat(preview.decision()).isEqualTo(FieldSwitchPreview.Decision.CLEAR_COLUMN);
        assertThat(preview.valueRows()).isEqualTo(1);
        Relation replacement =
                new Relation(
                        oldRelation.id(),
                        oldRelation.code(),
                        oldRelation.name(),
                        oldRelation.kind(),
                        nextTarget.draft().id(),
                        fieldId,
                        null,
                        false,
                        oldRelation.onDelete());
        Design changed = change(reference, "REFERENCE", false, List.of(replacement));
        PublishPlan plan = plan(changed);
        assertThat(plan.checks()).noneMatch(Check::blocking);
        assertThat(plan.conversions())
                .singleElement()
                .satisfies(
                        conversion -> {
                            assertThat(conversion.action()).isEqualTo("CLEAR_COLUMN");
                            assertThat(conversion.affectedRows()).isEqualTo(1);
                        });
        publisher.execute(new ExecutePlan(plan.id(), "更换引用目标并清空原列", List.of(fieldId)), 10001);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(value) FROM public.\""
                                        + reference.draft().tableName()
                                        + "\"",
                                Long.class))
                .isZero();
        assertThat(designs.published(reference.draft().id()).relations())
                .singleElement()
                .satisfies(r -> assertThat(r.targetObjectId()).isEqualTo(nextTarget.draft().id()));
    }

    @Test
    void explicitReferenceCanDetachAndClearSameColumnForNumericOrTextTarget() {
        for (String type : List.of("INTEGER", "TEXT")) {
            Design reference =
                    explicitReference(
                            "reverse_" + type.toLowerCase(), "REFERENCE", "TEXT".equals(type));
            String stableId = value(reference).id();
            String relationId = reference.relations().getFirst().id();
            List<Long> ids =
                    jdbc.queryForList(
                            "SELECT id FROM public.\"" + reference.draft().tableName() + "\"",
                            Long.class);
            assertThat(preview(reference, type).decision())
                    .isEqualTo(FieldSwitchPreview.Decision.BLOCKED);
            FieldSwitchPreview.Result permitted = preview(reference, type, true, null);
            assertThat(permitted.decision()).isEqualTo(FieldSwitchPreview.Decision.CLEAR_COLUMN);
            assertThat(permitted.valueRows()).isEqualTo(1);
            Design changed = change(reference, type, false, List.of());
            assertThat(value(changed).id()).isEqualTo(stableId);
            assertThat(changed.fieldOptions().get(stableId).nativeType()).isNull();
            PublishPlan plan = plan(changed);
            assertThat(plan.checks()).noneMatch(Check::blocking);
            assertThat(plan.conversions())
                    .singleElement()
                    .satisfies(
                            c -> {
                                assertThat(c.affectedRows()).isEqualTo(1);
                                assertThat(c.fromFieldType()).isEqualTo("REFERENCE");
                                assertThat(c.toFieldType()).isEqualTo(type);
                            });
            publisher.execute(new ExecutePlan(plan.id(), "解除引用并仅清空本列", List.of(stableId)), 10001);
            assertThat(
                            jdbc.queryForList(
                                    "SELECT id FROM public.\""
                                            + reference.draft().tableName()
                                            + "\"",
                                    Long.class))
                    .isEqualTo(ids);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM public.\""
                                            + reference.draft().tableName()
                                            + "\" WHERE value IS NULL AND other='保留其他列'",
                                    Long.class))
                    .isEqualTo(1);
            assertThat(
                            databaseMetadata
                                    .readTable("public", reference.draft().tableName())
                                    .orElseThrow()
                                    .constraints())
                    .noneMatch(c -> c.name().equals("nocode_fk_r_" + relationId));
            assertThat(designs.published(reference.draft().id()).relations()).isEmpty();
        }
    }

    @Test
    void reversePreviewCannotDetachMasterDetailOrBypassPhysicalCalculation() {
        Design master = explicitReference("reverse_master", "MASTER_DETAIL");
        assertThat(preview(master, "INTEGER", true, null).decision())
                .isEqualTo(FieldSwitchPreview.Decision.BLOCKED);
        Design source = explicitReference("reverse_formula", "REFERENCE");
        jdbc.execute(
                "ALTER TABLE public.\""
                        + source.draft().tableName()
                        + "\" ADD COLUMN kept_formula bigint GENERATED ALWAYS AS (value + 1)"
                        + " STORED");
        FieldSwitchPreview.Result blocked = preview(source, "INTEGER", true, null);
        assertThat(blocked.decision()).isEqualTo(FieldSwitchPreview.Decision.BLOCKED);
        assertThat(blocked.impacts())
                .anyMatch(
                        i -> i.blocking() && (i.location() + i.message()).contains("kept_formula"));
    }

    @Test
    void switchPreviewRejectsFieldFromOtherObjectAndUnspecifiedReferenceTarget() {
        Design first = create("preview_first", "TEXT");
        Design second = create("preview_second", "TEXT");
        assertThatThrownBy(
                        () ->
                                servicesContext
                                        .getBean(FieldSwitchPreviewService.class)
                                        .preview(
                                                new FieldSwitchPreview.Request(
                                                        first.draft().id(),
                                                        null,
                                                        value(second).id(),
                                                        "INTEGER",
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        false)))
                .hasMessageContaining("不属于指定对象");
        insert(first, "历史内容", false);
        FieldSwitchPreview.Result missingTarget = preview(first, "REFERENCE");
        assertThat(missingTarget.valueRows()).isEqualTo(1);
        assertThat(missingTarget.decision()).isEqualTo(FieldSwitchPreview.Decision.BLOCKED);
        assertThat(missingTarget.explanation()).contains("目标业务数据对象");
    }

    @Test
    void reverseOneToOneReleasesItsOwnUniqueIndexesAndKeepsOrdinaryValuesWritable() {
        Design reference = explicitReference("reverse_unique", "ONE_TO_ONE");
        String relationId = reference.relations().getFirst().id();
        String fieldId = value(reference).id();
        // 模拟旧版保存的一对一专属索引，只调整本夹具的部署基线。
        jdbc.execute(
                "CREATE UNIQUE INDEX \"nocode_ur_"
                        + relationId
                        + "\" ON public.\""
                        + reference.draft().tableName()
                        + "\" (value)");
        String structure = tables.capture(designs.published(reference.draft().id()));
        assertThat(
                        jdbc.update(
                                "UPDATE public.nocode_deployment SET structure_hash=?,"
                                        + " structure_json=CAST(? AS jsonb) WHERE object_id=? AND"
                                        + " version_no=?",
                                cn.hutool.crypto.digest.DigestUtil.sha256Hex(structure),
                                structure,
                                Long.parseLong(reference.draft().id()),
                                reference.publishedVersion()))
                .isEqualTo(1);
        assertThat(tables.drift(reference.draft().id())).isEmpty();
        Design changed = change(reference, "INTEGER", false, List.of());
        PublishPlan plan = plan(changed);
        assertThat(plan.checks()).noneMatch(Check::blocking);
        publisher.execute(new ExecutePlan(plan.id(), "解除一对一并清空引用值", List.of(fieldId)), 10001);
        assertThat(
                        databaseMetadata
                                .readTable("public", reference.draft().tableName())
                                .orElseThrow()
                                .indexes())
                .noneMatch(
                        i ->
                                i.name().equals("nocode_ur_" + relationId)
                                        || i.name().equals("nocode_u_" + fieldId));
        jdbc.update(
                "INSERT INTO public.\""
                        + reference.draft().tableName()
                        + "\" (name,value) VALUES ('普通值一',7),('普通值二',7)");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.\""
                                        + reference.draft().tableName()
                                        + "\" WHERE value=7",
                                Long.class))
                .isEqualTo(2);
    }

    @Test
    void internalDetailPreviewAndReverseConversionKeepParentAndOtherColumns() {
        Design target = create("detail_reverse_target", "TEXT");
        Long targetId =
                jdbc.queryForObject(
                        "INSERT INTO public.\""
                                + target.draft().tableName()
                                + "\" (name) VALUES ('明细引用目标') RETURNING id",
                        Long.class);
        SaveObjectDraft request = fixture.createRequest("detail_reverse");
        Design source =
                designs.save(
                        new SaveDesign(
                                request,
                                Settings.defaults(),
                                Map.of(),
                                List.of(
                                        new Relation(
                                                null,
                                                "detail_ref",
                                                "明细显式引用",
                                                "REFERENCE",
                                                target.draft().id(),
                                                "detail_value",
                                                null,
                                                false,
                                                "RESTRICT",
                                                "detail:entries")),
                                List.of(),
                                List.of(
                                        new Detail(
                                                null,
                                                "entries",
                                                "内部明细",
                                                "biz_" + fixture.prefix + "_reverse_entries",
                                                "ACTIVE",
                                                List.of(
                                                        fixture.field(
                                                                "detail_value",
                                                                "value",
                                                                "REFERENCE",
                                                                0),
                                                        fixture.field(
                                                                "detail_other",
                                                                "other",
                                                                "TEXT",
                                                                1)),
                                                Map.of(),
                                                List.of()))),
                        10001);
        PublishPlan initial = plan(source);
        assertThat(initial.checks()).noneMatch(Check::blocking);
        publisher.execute(new ExecutePlan(initial.id(), "建立明细显式引用"), 10001);
        source = designs.get(source.draft().id());
        Detail detail = source.details().getFirst();
        FieldDefinition field =
                detail.fields().stream()
                        .filter(f -> f.code().equals("value"))
                        .findFirst()
                        .orElseThrow();
        Long parentId =
                jdbc.queryForObject(
                        "INSERT INTO public.\""
                                + source.draft().tableName()
                                + "\" (name) VALUES ('主记录') RETURNING id",
                        Long.class);
        jdbc.update(
                "INSERT INTO public.\""
                        + detail.tableName()
                        + "\" (parent_id,value,other,deleted) VALUES (?,?,?,1),(?,NULL,?,0)",
                parentId,
                targetId,
                "保留已删明细",
                parentId,
                "保留空值明细");
        FieldSwitchPreview.Result preview =
                servicesContext
                        .getBean(FieldSwitchPreviewService.class)
                        .preview(
                                new FieldSwitchPreview.Request(
                                        source.draft().id(),
                                        detail.id(),
                                        field.id(),
                                        "TEXT",
                                        200,
                                        null,
                                        null,
                                        null,
                                        null,
                                        true));
        assertThat(preview.totalRows()).isEqualTo(2);
        assertThat(preview.valueRows()).isEqualTo(1);
        assertThat(preview.decision()).isEqualTo(FieldSwitchPreview.Decision.CLEAR_COLUMN);
        Design editable =
                designs.editPublished(
                        new Revision(source.draft().id(), source.draft().lockVersion(), null),
                        10001);
        Detail editing = editable.details().getFirst();
        Detail changedDetail =
                new Detail(
                        editing.id(),
                        editing.code(),
                        editing.name(),
                        editing.tableName(),
                        editing.state(),
                        editing.fields().stream()
                                .map(
                                        f ->
                                                f.id().equals(field.id())
                                                        ? new FieldDefinition(
                                                                f.key(), f.id(), f.code(), f.name(),
                                                                "TEXT", 200, null, null, false,
                                                                false, f.sort())
                                                        : f)
                                .toList(),
                        editing.fieldOptions(),
                        editing.indexes(),
                        editing.binding());
        Design changed =
                designs.save(
                        new SaveDesign(
                                fixture.edit(
                                        editable.draft(),
                                        editable.draft().fields(),
                                        List.of(),
                                        editable.draft().titleFieldId()),
                                editable.settings(),
                                editable.fieldOptions(),
                                List.of(),
                                editable.indexes(),
                                List.of(changedDetail)),
                        10001);
        PublishPlan conversion = plan(changed);
        assertThat(conversion.checks()).noneMatch(Check::blocking);
        assertThat(conversion.conversions())
                .singleElement()
                .satisfies(c -> assertThat(c.detailId()).isEqualTo(detail.id()));
        publisher.execute(new ExecutePlan(conversion.id(), "仅清空明细引用列", List.of(field.id())), 10001);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.\""
                                        + detail.tableName()
                                        + "\" WHERE value IS NULL AND parent_id=?",
                                Long.class,
                                parentId))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForList(
                                "SELECT other FROM public.\"" + detail.tableName() + "\"",
                                String.class))
                .containsExactlyInAnyOrder("保留已删明细", "保留空值明细");
    }

    @Test
    void pendingPublicDictionaryCanPreviewButCannotBeSavedBeforeSelection() {
        Design source = create("pending_dictionary", "SELECT");
        insert(source, "legacy_code", false);
        String fieldId = value(source).id();
        SelectionFields.Source pending =
                new SelectionFields.Source(
                        "SYSTEM_DICTIONARY", null, null, List.of(), false, List.of(), "NONE");
        FieldSwitchPreview.Result preview =
                servicesContext
                        .getBean(FieldSwitchPreviewService.class)
                        .preview(
                                new FieldSwitchPreview.Request(
                                        source.draft().id(),
                                        null,
                                        fieldId,
                                        "SELECT",
                                        null,
                                        null,
                                        null,
                                        pending,
                                        null,
                                        false));
        assertThat(preview.totalRows()).isEqualTo(1);
        assertThat(preview.valueRows()).isEqualTo(1);
        assertThat(preview.decision()).isEqualTo(FieldSwitchPreview.Decision.CLEAR_COLUMN);
        assertThat(preview.explanation()).contains("目标公共字典尚未选择").contains("发布时再次校验");
        assertThat(SelectionFields.source(value(source), source.fieldOptions().get(fieldId)).kind())
                .isEqualTo("LOCAL_OPTIONS");
        Design editable =
                designs.editPublished(
                        new Revision(source.draft().id(), source.draft().lockVersion(), null),
                        10001);
        FieldOptions old = editable.fieldOptions().get(fieldId);
        Map<String, FieldOptions> options = new HashMap<>(editable.fieldOptions());
        options.put(
                fieldId,
                new FieldOptions(
                        old.columnName(),
                        old.classification(),
                        null,
                        old.description(),
                        null,
                        null,
                        null,
                        old.state(),
                        List.of(),
                        null,
                        null,
                        "NONE",
                        old.nativeType(),
                        false,
                        false,
                        pending));
        assertThatThrownBy(
                        () ->
                                designs.save(
                                        new SaveDesign(
                                                fixture.edit(
                                                        editable.draft(),
                                                        editable.draft().fields(),
                                                        List.of(),
                                                        editable.draft().titleFieldId()),
                                                editable.settings(),
                                                options,
                                                editable.relations(),
                                                editable.indexes(),
                                                editable.details()),
                                        10001))
                .hasMessageContaining("请选择平台公共字典");
        Design retained = designs.get(source.draft().id());
        assertThat(
                        SelectionFields.source(
                                        value(retained), retained.fieldOptions().get(fieldId))
                                .kind())
                .isEqualTo("LOCAL_OPTIONS");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT value FROM public.\"" + source.draft().tableName() + "\"",
                                String.class))
                .isEqualTo("legacy_code");
    }
}
