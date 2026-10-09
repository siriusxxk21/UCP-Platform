package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.nocode.api.DataCenter.*;
import com.richuang.os.nocode.api.FieldDefinition;
import com.richuang.os.nocode.api.SaveObjectDraft;
import com.richuang.os.nocode.enums.FieldTypeEnum;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

/** 标题模板与字段增量保存的契约回归；仅使用本例生成的对象夹具。 */
class TitleTemplatePatchIntegrationTest {
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

    private Design create() {
        return designs.save(
                new SaveDesign(
                        fixture.createRequest("template"),
                        new Settings(null, null, null, "{{name}}"),
                        null,
                        List.of(),
                        List.of(),
                        List.of()),
                10001);
    }

    @Test
    void unchangedTemplateAcceptsOmittedExistingFields() {
        var before = create();
        // SaveObjectDraft 契约明确规定：fields 中省略的既有字段应保持不变。
        var saved =
                designs.save(
                        new SaveDesign(
                                fixture.edit(
                                        before.draft(),
                                        List.of(),
                                        List.of(),
                                        before.draft().titleFieldId()),
                                null,
                                null,
                                List.of(),
                                List.of(),
                                List.of()),
                        10001);
        assertThat(saved.draft().fields()).isEqualTo(before.draft().fields());
        assertThat(saved.settings().titleTemplate()).isEqualTo("{{name}}");
        assertThat(saved.draft().lockVersion()).isEqualTo(before.draft().lockVersion() + 1);
    }

    @Test
    void unchangedTemplateAcceptsFullExistingFields() {
        var before = create();
        var saved =
                designs.save(
                        new SaveDesign(
                                fixture.edit(
                                        before.draft(),
                                        before.draft().fields(),
                                        List.of(),
                                        before.draft().titleFieldId()),
                                null,
                                null,
                                List.of(),
                                List.of(),
                                List.of()),
                        10001);
        assertThat(saved.draft().fields()).isEqualTo(before.draft().fields());
        assertThat(saved.settings().titleTemplate()).isEqualTo("{{name}}");
    }

    private SaveDesign patch(
            Design before,
            List<FieldDefinition> changes,
            List<String> removals,
            Settings settings) {
        return new SaveDesign(
                fixture.edit(before.draft(), changes, removals, before.draft().titleFieldId()),
                settings,
                null,
                List.of(),
                List.of(),
                List.of());
    }

    private void assertRejectedWithoutChanges(Design before, SaveDesign request) {
        assertThatThrownBy(() -> designs.save(request, 10001))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("标题模板");
        var after = designs.get(before.draft().id());
        assertThat(after.draft()).isEqualTo(before.draft());
        assertThat(after.settings()).isEqualTo(before.settings());
    }

    @Test
    void addingOnlyOneFieldKeepsOmittedTitleField() {
        var before = create();
        var saved =
                designs.save(
                        patch(
                                before,
                                List.of(
                                        fixture.field(
                                                "new-code",
                                                "code",
                                                FieldTypeEnum.TEXT.getCode(),
                                                1)),
                                List.of(),
                                null),
                        10001);
        assertThat(saved.draft().fields()).hasSize(2).containsAll(before.draft().fields());
        assertThat(saved.draft().titleFieldId()).isEqualTo(before.draft().titleFieldId());
        assertThat(saved.settings().titleTemplate()).isEqualTo("{{name}}");
    }

    @Test
    void updatedTemplateCanReferenceNewAndRetainedFieldsTogether() {
        var before = create();
        var saved =
                designs.save(
                        patch(
                                before,
                                List.of(
                                        fixture.field(
                                                "new-code",
                                                "code",
                                                FieldTypeEnum.TEXT.getCode(),
                                                1)),
                                List.of(),
                                new Settings(null, null, null, "{{name}} · {{code}}")),
                        10001);
        var after = designs.get(saved.draft().id());
        assertThat(after.draft().fields()).hasSize(2).containsAll(before.draft().fields());
        assertThat(after.settings().titleTemplate()).isEqualTo("{{name}} · {{code}}");
    }

    @Test
    void removingReferencedNonTitleFieldIsRejectedAtomically() {
        var before =
                designs.save(
                        patch(
                                create(),
                                List.of(
                                        fixture.field(
                                                "new-code",
                                                "code",
                                                FieldTypeEnum.TEXT.getCode(),
                                                1)),
                                List.of(),
                                new Settings(null, null, null, "{{code}}")),
                        10001);
        var dependency =
                before.draft().fields().stream()
                        .filter(f -> f.code().equals("code"))
                        .findFirst()
                        .orElseThrow();
        assertRejectedWithoutChanges(
                before, patch(before, List.of(), List.of(dependency.id()), null));
    }

    @ParameterizedTest
    @EnumSource(
            value = FieldTypeEnum.class,
            names = {
                "MULTI_SELECT",
                "IMAGE",
                "ATTACHMENT",
                "REGION",
                "CASCADE",
                "SUMMARY",
                "RICH_TEXT"
            })
    void changingReferencedFieldToUnsupportedTypeIsRejectedAtomically(FieldTypeEnum type) {
        var before = create();
        var title = before.draft().fields().getFirst();
        var changed =
                new FieldDefinition(
                        title.id(),
                        title.id(),
                        title.code(),
                        title.name(),
                        type.getCode(),
                        null,
                        null,
                        null,
                        false,
                        false,
                        title.sort());
        assertRejectedWithoutChanges(before, patch(before, List.of(changed), List.of(), null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{{missing}}", "{{name}", "{{name}} }}", "没有字段引用"})
    void invalidReplacementTemplateIsRejectedAtomically(String template) {
        var before = create();
        assertRejectedWithoutChanges(
                before,
                patch(before, List.of(), List.of(), new Settings(null, null, null, template)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{{missing}}", "{{name}} }}"})
    void invalidTemplateStillCannotCreateAnObject(String template) {
        var request = fixture.createRequest("invalid_template");
        assertThatThrownBy(
                        () ->
                                designs.save(
                                        new SaveDesign(
                                                request,
                                                new Settings(null, null, null, template),
                                                null,
                                                List.of(),
                                                List.of(),
                                                List.of()),
                                        10001))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("标题模板");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM nocode_object WHERE object_code=?",
                                Integer.class,
                                request.objectCode()))
                .isZero();
    }

    @Test
    void multiValueTemplateStillCannotCreateAnObject() {
        var base = fixture.createRequest("invalid_type");
        var request =
                new SaveObjectDraft(
                        null,
                        null,
                        base.objectCode(),
                        base.objectName(),
                        base.description(),
                        base.tableName(),
                        base.titleFieldKey(),
                        List.of(
                                fixture.field(
                                        base.titleFieldKey(),
                                        "name",
                                        FieldTypeEnum.MULTI_SELECT.getCode(),
                                        0)),
                        List.of());
        assertThatThrownBy(
                        () ->
                                designs.save(
                                        new SaveDesign(
                                                request,
                                                new Settings(null, null, null, "{{name}}"),
                                                null,
                                                List.of(),
                                                List.of(),
                                                List.of()),
                                        10001))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("标题模板字段不存在或不支持：name");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM nocode_object WHERE object_code=?",
                                Integer.class,
                                request.objectCode()))
                .isZero();
    }

    @Test
    void templateLengthLimitIsPreserved() {
        var template = "{{name}}" + "字".repeat(504);
        var before =
                designs.save(
                        patch(
                                create(),
                                List.of(),
                                List.of(),
                                new Settings(null, null, null, template)),
                        10001);
        assertThat(designs.get(before.draft().id()).settings().titleTemplate()).hasSize(512);
        assertRejectedWithoutChanges(
                before,
                patch(
                        before,
                        List.of(),
                        List.of(),
                        new Settings(null, null, null, template + "字")));
    }
}
