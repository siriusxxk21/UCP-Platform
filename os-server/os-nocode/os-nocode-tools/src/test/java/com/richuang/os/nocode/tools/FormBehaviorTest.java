package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.metadata.service.form.FormBehaviors;

import org.junit.jupiter.api.Test;

import java.util.*;

/** 动态条件的服务端边界；真实保存集成另由 ApplicationIntegrationTest 覆盖。 */
class FormBehaviorTest {
    private static DocumentPolicy.Expression expr(String op, DocumentPolicy.Expression... args) {
        return new DocumentPolicy.Expression(op, null, null, null, List.of(args));
    }

    private static DocumentPolicy.Expression field(String id) {
        return new DocumentPolicy.Expression("FIELD", id, null, null, List.of());
    }

    private static DocumentPolicy.Expression value(Object value) {
        return new DocumentPolicy.Expression("VALUE", null, null, value, List.of());
    }

    private static ApplicationUi.Form form(ApplicationUi.FieldBehavior behavior) {
        return new ApplicationUi.Form(
                "object",
                List.of(
                        new ApplicationUi.Node(
                                "type", "FIELD", "type", null, null, null, List.of()),
                        new ApplicationUi.Node(
                                "note",
                                "FIELD",
                                "note",
                                null,
                                null,
                                null,
                                List.of(),
                                null,
                                new ApplicationUi.FieldPresentation(
                                        "说明", null, null, false, null, behavior))),
                List.of());
    }

    private static Map<String, Object> apply(
            ApplicationUi.FieldBehavior behavior,
            Map<String, Object> input,
            Map<String, Object> previous) {
        var result =
                FormBehaviors.apply(
                        form(behavior), input, previous, Set.of("type", "note"), List.of());
        var candidate = new HashMap<>(previous);
        candidate.putAll(result);
        FormBehaviors.require(form(behavior), candidate, List.of());
        return result;
    }

    @Test
    void hiddenValuesArePreservedUnlessClearingWasExplicitlyRequested() {
        var input = Map.<String, Object>of("type", "其他", "note", "保留输入");
        var show = expr("EQ", field("type"), value("采购"));
        assertThat(apply(new ApplicationUi.FieldBehavior(show, null, null, false), input, Map.of()))
                .containsEntry("note", "保留输入");
        assertThat(apply(new ApplicationUi.FieldBehavior(show, null, null, true), input, Map.of()))
                .containsEntry("note", null);
        assertThat(input).containsEntry("note", "保留输入");
    }

    @Test
    void conditionalRequiredRejectsEmptyButAcceptsFalseAndZero() {
        var behavior = new ApplicationUi.FieldBehavior(null, value(true), null, false);
        assertThatThrownBy(() -> apply(behavior, Map.of("note", " "), Map.of()))
                .hasMessageContaining("条件必填");
        assertThat(apply(behavior, Map.of("note", 0), Map.of())).containsEntry("note", 0);
        assertThat(apply(behavior, Map.of("note", false), Map.of())).containsEntry("note", false);
    }

    @Test
    void updateUsesPreviousValuesAndReadOnlyCannotBeBypassedWithPayload() {
        var behavior =
                new ApplicationUi.FieldBehavior(
                        null, null, expr("EQ", field("type"), value("锁定")), false);
        var previous = Map.<String, Object>of("type", "锁定", "note", "旧说明");
        assertThatThrownBy(() -> apply(behavior, Map.of("note", "新说明"), previous))
                .hasMessageContaining("只读");
        assertThat(apply(behavior, Map.of("note", "旧说明"), previous)).containsEntry("note", "旧说明");
        assertThat(apply(behavior, Map.of(), previous)).isEmpty();
    }

    @Test
    void unreadableConditionFieldsDoNotBecomeSideChannels() {
        var behavior =
                new ApplicationUi.FieldBehavior(
                        expr("EQ", field("type"), value("采购")), null, null, false);
        assertThatThrownBy(
                        () ->
                                FormBehaviors.apply(
                                        form(behavior),
                                        Map.of("note", "n"),
                                        Map.of("type", "采购"),
                                        Set.of("note"),
                                        List.of()))
                .hasMessageContaining("当前权限无法计算");
    }

    @Test
    void hiddenRequiredDoesNotPreventSavingUnrelatedFields() {
        assertThat(
                        apply(
                                new ApplicationUi.FieldBehavior(
                                        value(false), value(true), null, false),
                                Map.of("type", "其他"),
                                Map.of()))
                .containsOnlyKeys("type");
    }

    @Test
    void decimalConditionsCompareNumericStringsWithFieldTypes() {
        var f =
                new FieldDefinition(
                        "type", "type", "type", "金额", "DECIMAL", null, 30, 0, false, false, 0);
        var behavior =
                new ApplicationUi.FieldBehavior(
                        null, expr("LT", field("type"), value(10)), null, false);
        assertThatThrownBy(
                        () ->
                                FormBehaviors.require(
                                        form(behavior), Map.of("type", "2"), List.of(f)))
                .hasMessageContaining("条件必填");
    }
}
