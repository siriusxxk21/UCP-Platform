package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.metadata.service.form.DetailForms;
import com.richuang.os.nocode.metadata.service.form.FormFillBindings;
import com.richuang.os.nocode.runtime.service.selection.SelectionCatalog;

import org.junit.jupiter.api.Test;

import java.util.*;

/** 与业务名称无关的主表、明细共用规则；避免只靠采购示例证明通用性。 */
class GenericFormInteractionsTest {
    private static FieldDefinition field(String id, String type) {
        return new FieldDefinition(id, id, id, id, type, null, null, null, false, false, 0);
    }

    private static DataCenter.Definition definition(
            String id,
            List<FieldDefinition> fields,
            Map<String, DataCenter.FieldOptions> options,
            List<DataCenter.Relation> relations,
            List<DataCenter.Detail> details) {
        return new DataCenter.Definition(
                id,
                id,
                id,
                null,
                "public",
                "t_" + id,
                "GENERATED",
                false,
                null,
                DataCenter.Settings.defaults(),
                fields,
                options,
                relations,
                List.of(),
                details);
    }

    private static DataCenter.Relation relation(String field, String target, String detail) {
        return new DataCenter.Relation(
                "r_" + field,
                field,
                field,
                "REFERENCE",
                target,
                field,
                null,
                false,
                "RESTRICT",
                detail);
    }

    private static DataCenter.FieldOptions options(DataCenter.Option... choices) {
        return new DataCenter.FieldOptions(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "ACTIVE",
                List.of(choices),
                null,
                null,
                null,
                null,
                false,
                false);
    }

    private static ApplicationUi.Node node(String field, String link, FormFills.Binding fill) {
        return new ApplicationUi.Node(
                "node_" + field,
                "FIELD",
                field,
                null,
                null,
                null,
                List.of(),
                null,
                new ApplicationUi.FieldPresentation(
                        null,
                        null,
                        null,
                        false,
                        link == null
                                ? null
                                : new SelectionFields.Presentation(
                                        "AUTO", List.of(), false, link, "target", null),
                        null,
                        fill));
    }

    private static ApplicationUi.Form form(ApplicationUi.Node... nodes) {
        return new ApplicationUi.Form("main", List.of(nodes), List.of());
    }

    private static DataCenter.Definition main() {
        return definition(
                "main",
                List.of(field("a", "REFERENCE"), field("b", "REFERENCE"), field("c", "TEXT")),
                Map.of(),
                List.of(relation("a", "source", null), relation("b", "target", null)),
                List.of());
    }

    @Test
    void legacyBindingKeepsSnapshotAndExplicitClearOnlyActsOnSourceTransition() throws Exception {
        var legacy =
                new ObjectMapper()
                        .readValue(
                                "{\"sourceFieldId\":\"a\",\"valueFieldId\":\"name\",\"mode\":\"SOURCE_CHANGE\"}",
                                FormFills.Binding.class);
        assertThat(legacy.clearOnSourceEmpty()).isNull();
        var previous = Map.<String, Object>of("a", "1", "c", "历史快照");
        var clearing = new HashMap<String, Object>();
        clearing.put("a", null);
        assertThat(
                        FormFillBindings.clearEmptySources(
                                form(node("c", null, legacy)),
                                clearing,
                                previous,
                                Set.of("a", "c")))
                .doesNotContainKey("c");
        var configured =
                form(node("c", null, new FormFills.Binding("a", "name", "SOURCE_CHANGE", true)));
        assertThat(
                        FormFillBindings.clearEmptySources(
                                configured, clearing, previous, Set.of("a", "c")))
                .containsEntry("c", null);
        assertThat(
                        FormFillBindings.clearEmptySources(
                                configured,
                                Map.of("c", "人工值"),
                                Map.of("c", "历史值"),
                                Set.of("a", "c")))
                .containsEntry("c", "人工值");
        assertThat(
                        FormFillBindings.clearEmptySources(
                                configured, Map.of("a", "2"), previous, Set.of("a", "c")))
                .doesNotContainKey("c");
        assertThatThrownBy(
                        () ->
                                FormFillBindings.clearEmptySources(
                                        configured, clearing, previous, Set.of("a")))
                .hasMessageContaining("无法清空关联带入目标");
    }

    @Test
    void explicitClearPropagatesThroughReferenceChainAndDoesNotTouchUnrelatedFields() {
        var form =
                form(
                        node("b", null, new FormFills.Binding("a", "ref", "SOURCE_CHANGE", true)),
                        node("c", null, new FormFills.Binding("b", "name", "DEFAULT", true)));
        var input = new HashMap<String, Object>();
        input.put("a", null);
        input.put("note", "保留");
        assertThat(
                        FormFillBindings.clearEmptySources(
                                form,
                                input,
                                Map.of("a", "1", "b", "2", "c", "快照"),
                                Set.of("a", "b", "c", "note")))
                .containsEntry("b", null)
                .containsEntry("c", null)
                .containsEntry("note", "保留");
    }

    @Test
    void combinedDependenciesRejectMixedCycleAndPermitMainToRowChain() {
        var mixed =
                form(
                        node("a", "b", null),
                        node("b", null, new FormFills.Binding("a", "ref", "DEFAULT")));
        assertThatThrownBy(() -> FormDependencies.validate(mixed, main()))
                .hasMessageContaining("循环联动");
        var row =
                List.of(
                        node("r1", "a", null),
                        node("r2", null, new FormFills.Binding("r1", "name", "DEFAULT")));
        var root =
                new ApplicationUi.Form(
                        "main",
                        List.of(node("a", null, null)),
                        List.of("detail"),
                        null,
                        Map.of("detail", row));
        assertThatCode(() -> FormDependencies.validate(root, main())).doesNotThrowAnyException();
        var cyclicRow =
                new ApplicationUi.Form(
                        "main",
                        root.nodes(),
                        root.detailIds(),
                        null,
                        Map.of(
                                "detail",
                                List.of(
                                        node("r1", "r2", null),
                                        node(
                                                "r2",
                                                null,
                                                new FormFills.Binding("r1", "name", "DEFAULT")))));
        assertThatThrownBy(() -> FormDependencies.validate(cyclicRow, main()))
                .hasMessageContaining("循环联动");
    }

    @Test
    void linkCompatibilityPreservesReferenceIdentityInMainAndDetailProjection() {
        var detail =
                new DataCenter.Detail(
                        "detail",
                        "detail",
                        "内部明细",
                        "t_detail",
                        "ACTIVE",
                        List.of(field("r1", "INTEGER")),
                        Map.of(),
                        List.of());
        var root =
                definition(
                        "main",
                        List.of(field("a", "INTEGER")),
                        Map.of(),
                        List.of(
                                relation("a", "catalog", null),
                                relation("r1", "catalog", "detail")),
                        List.of(detail));
        var target =
                definition(
                        "target",
                        List.of(field("filter", "INTEGER")),
                        Map.of(),
                        List.of(relation("filter", "catalog", null)),
                        List.of());
        var projected = DetailForms.selectionDefinition(root, detail);
        assertThat(projected.relations()).hasSize(2);
        assertThat(
                        SelectionFields.linkCompatible(
                                projected,
                                root.fields().getFirst(),
                                target,
                                target.fields().getFirst()))
                .isTrue();
        assertThat(
                        SelectionFields.linkCompatible(
                                projected,
                                detail.fields().getFirst(),
                                target,
                                target.fields().getFirst()))
                .isTrue();
        var wrongTarget =
                definition(
                        "target",
                        target.fields(),
                        Map.of(),
                        List.of(relation("filter", "another", null)),
                        List.of());
        assertThat(
                        SelectionFields.linkCompatible(
                                projected,
                                root.fields().getFirst(),
                                wrongTarget,
                                wrongTarget.fields().getFirst()))
                .isFalse();
        var scalar = definition("scalar", target.fields(), Map.of(), List.of(), List.of());
        assertThat(
                        SelectionFields.linkCompatible(
                                projected,
                                root.fields().getFirst(),
                                scalar,
                                scalar.fields().getFirst()))
                .isFalse();
        assertThat(
                        SelectionFields.linkCompatible(
                                scalar, field("x", "TEXT"), scalar, field("y", "INTEGER")))
                .isFalse();
        assertThat(
                        SelectionFields.linkCompatible(
                                scalar, field("x", "DECIMAL"), scalar, field("y", "MONEY")))
                .isTrue();
    }

    @Test
    void selectFillUsesStableCodeAndLabelIdentityAndChecksAvailabilitySeparately() {
        var field = field("choice", "SELECT");
        var active =
                options(
                        new DataCenter.Option("A", "甲", false),
                        new DataCenter.Option("B", "乙", false));
        var reordered =
                options(
                        new DataCenter.Option("B", "乙", true),
                        new DataCenter.Option("A", "甲", false));
        assertThat(SelectionCompatibility.compatible(field, active, field, reordered)).isTrue();
        assertThat(
                        SelectionCompatibility.compatible(
                                field,
                                active,
                                field,
                                options(new DataCenter.Option("A", "乙", false))))
                .isFalse();
        assertThat(SelectionCompatibility.compatible(field, active, field("text", "TEXT"), active))
                .isFalse();
        var dictionary =
                new SelectionFields.Source(
                        "SYSTEM_DICTIONARY", null, "unit", List.of(), false, List.of(), "NONE");
        assertThat(
                        SelectionCompatibility.compatible(
                                field,
                                active.withSelection(dictionary),
                                field,
                                reordered.withSelection(dictionary)))
                .isTrue();
        assertThat(
                        SelectionCompatibility.compatible(
                                field,
                                active.withSelection(dictionary),
                                field,
                                active.withSelection(
                                        new SelectionFields.Source(
                                                "SYSTEM_DICTIONARY",
                                                null,
                                                "other",
                                                List.of(),
                                                false,
                                                List.of(),
                                                "NONE"))))
                .isFalse();
        var catalog = new SelectionCatalog();
        assertThatCode(() -> catalog.validateFillValue(field, active, "A"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> catalog.validateFillValue(field, reordered, "B"))
                .hasMessageContaining("已停用");
        assertThatThrownBy(() -> catalog.validateFillValue(field, active, "missing"))
                .hasMessageContaining("不存在");
        assertThatThrownBy(() -> catalog.validateFillValue(field, active, List.of("A")))
                .hasMessageContaining("格式无效");
    }

    @Test
    void selectFillPublishValidationWorksForMainAndProjectedDetail() {
        var options = options(new DataCenter.Option("A", "甲", false));
        var source =
                definition(
                        "source",
                        List.of(field("unit", "SELECT")),
                        Map.of("unit", options),
                        List.of(),
                        List.of());
        var detail =
                new DataCenter.Detail(
                        "detail",
                        "detail",
                        "明细",
                        "t_detail",
                        "ACTIVE",
                        List.of(field("row_ref", "INTEGER"), field("row_unit", "SELECT")),
                        Map.of("row_unit", options),
                        List.of());
        var root =
                definition(
                        "main",
                        List.of(field("ref", "INTEGER"), field("unit", "SELECT")),
                        Map.of("unit", options),
                        List.of(
                                relation("ref", "source", null),
                                relation("row_ref", "source", "detail")),
                        List.of(detail));
        var rootForm =
                new ApplicationUi.Form(
                        "main",
                        List.of(
                                node("ref", null, null),
                                node(
                                        "unit",
                                        null,
                                        new FormFills.Binding(
                                                "ref", "unit", "SOURCE_CHANGE", true))),
                        List.of("detail"),
                        null,
                        Map.of(
                                "detail",
                                List.of(
                                        node("row_ref", null, null),
                                        node(
                                                "row_unit",
                                                null,
                                                new FormFills.Binding(
                                                        "row_ref",
                                                        "unit",
                                                        "SOURCE_CHANGE",
                                                        true)))));
        assertThatCode(() -> FormFillBindings.validate(rootForm, root, Map.of("source", source)))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                FormFillBindings.validate(
                                        DetailForms.form(rootForm, "detail"),
                                        DetailForms.definition(root, detail),
                                        Map.of("source", source)))
                .doesNotThrowAnyException();
        var incompatible =
                definition(
                        "source",
                        source.fields(),
                        Map.of("unit", options(new DataCenter.Option("A", "另一含义", false))),
                        List.of(),
                        List.of());
        assertThatThrownBy(
                        () ->
                                FormFillBindings.validate(
                                        rootForm, root, Map.of("source", incompatible)))
                .hasMessageContaining("一致的选项编码和标签");
    }
}
