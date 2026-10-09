package com.richuang.os.nocode.metadata.service.object;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.DataCenter.FieldOptions;

import org.junit.jupiter.api.Test;

import java.util.*;

/** 合并新对象规则后仍兼容已有选项默认值，继续按候选项与类型校验。 */
class OptionDefaultCompatibilityTest {
    private static FieldDefinition field(String type) {
        return new FieldDefinition(
                "f", "f", "c_zffs", "支付方式", type, null, null, null, false, false, 0);
    }

    private static FieldOptions withOptions(String defaultValue) {
        return FieldOptions.copyOf(FieldOptions.defaults())
                .options(List.of(new DataCenter.Option("CASH", "现金", false)))
                .defaultValue(defaultValue)
                .build();
    }

    @Test
    void objectDefault() {
        for (Map.Entry<String, String> entry :
                Map.of(
                                "SELECT", "CASH",
                                "MULTI_SELECT", "[\"CASH\"]",
                                "REGION", "[\"CASH\"]",
                                "CASCADE", "[\"CASH\"]")
                        .entrySet())
            assertThatCode(
                            () ->
                                    FieldDefaultValidation.validate(
                                            field(entry.getKey()),
                                            withOptions(entry.getValue()),
                                            new ObjectMapper()))
                    .as(entry.getKey())
                    .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                FieldDefaultValidation.validate(
                                        field("SELECT"), withOptions(null), new ObjectMapper()))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                FieldDefaultValidation.validate(
                                        field("TEXT"), withOptions("现金"), new ObjectMapper()))
                .doesNotThrowAnyException();
    }

    private static DataCenter.Definition definition(String type) {
        FieldDefinition f = field(type);
        return new DataCenter.Definition(
                "1",
                "o1",
                "订单",
                null,
                "public",
                "biz_o1",
                "GENERATED",
                false,
                "f",
                DataCenter.Settings.defaults(),
                List.of(f),
                Map.of("f", withOptions(null)),
                List.of(),
                List.of(),
                List.of());
    }

    private static ApplicationUi.Form form(Object defaultValue) {
        return new ApplicationUi.Form(
                "1",
                List.of(
                        new ApplicationUi.Node(
                                "n",
                                "FIELD",
                                "f",
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
                                        new SelectionFields.Presentation(
                                                "SELECT",
                                                List.of(),
                                                false,
                                                null,
                                                null,
                                                defaultValue)))),
                List.of());
    }

    @Test
    void formDefault() {
        assertThatCode(
                        () ->
                                SelectionFields.validatePresentations(
                                        form("CASH"), definition("SELECT"), Map.of()))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                SelectionFields.validatePresentations(
                                        form(List.of("CASH")),
                                        definition("MULTI_SELECT"),
                                        Map.of()))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                SelectionFields.validatePresentations(
                                        form(null), definition("SELECT"), Map.of()))
                .doesNotThrowAnyException();
        // 人员、组织等目录字段的表单默认值不是选项类，仍按原规则放行。
        assertThatCode(
                        () ->
                                SelectionFields.validatePresentations(
                                        form("10001"), definition("USER"), Map.of()))
                .doesNotThrowAnyException();
    }
}
