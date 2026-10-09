package com.richuang.os.nocode.metadata.service.object;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.nocode.api.DataCenter.FieldOptions;
import com.richuang.os.nocode.api.FieldDefinition;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/** 纯默认值校验：不装配数据库，不对开发服务发起极端数值运算。 */
class FieldDefaultValidationTest {
    private void validate(String type, String raw, Integer precision, Integer scale) {
        var field =
                new FieldDefinition(
                        "value", null, "value", "默认值测试", type, null, precision, scale, false, false,
                        0);
        var options =
                new FieldOptions(
                        null, "NORMAL", raw, null, null, null, null, "ACTIVE", List.of(), null,
                        null, "NONE", null, false, false);
        FieldDefaultValidation.validate(field, options, new ObjectMapper());
    }

    @Test
    void uuidMustUseCanonicalGroups() {
        assertThatCode(() -> validate("UUID", "0cc30233-d044-4128-b101-cb0c2ab59f51", null, null))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validate("UUID", "1-1-1-1-1", null, null))
                .hasMessageContaining("默认值");
    }

    @Test
    void decimalRejectsUnrepresentableExponentBeforeRescaling() {
        for (String value : List.of("1e999999999", "1e-999999999", "123456789.01", "1.001"))
            assertThatThrownBy(() -> validate("DECIMAL", value, 10, 2)).hasMessageContaining("默认值");
        assertThatCode(() -> validate("DECIMAL", "99999999.99", 10, 2)).doesNotThrowAnyException();
        assertThatCode(() -> validate("DECIMAL", "1.2300", 10, 2)).doesNotThrowAnyException();
        assertThatCode(() -> validate("DECIMAL", "0", 2, 2)).doesNotThrowAnyException();
    }

    @Test
    void hyperlinkAcceptsLegacyAddressAndStructuredValueWithSameValidation() {
        assertThatCode(() -> validate("URL", "https://example.com/purchase", null, null))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                validate(
                                        "URL",
                                        "{\"link\":\"https://example.com/purchase\",\"text\":\"采购说明\"}",
                                        null,
                                        null))
                .doesNotThrowAnyException();
        assertThatCode(() -> validate("URL", "\"https://example.com/purchase\"", null, null))
                .doesNotThrowAnyException();
        for (String raw :
                List.of(
                        "javascript:alert(1)",
                        "{\"link\":\"javascript:alert(1)\"}",
                        "file:///local/file"))
            assertThatThrownBy(() -> validate("URL", raw, null, null)).hasMessageContaining("默认值");
    }

    @Test
    void hyperlinkDefaultIsPersistableJsonForLegacyAndCurrentContracts() throws Exception {
        var json = new ObjectMapper();
        var field =
                new FieldDefinition(
                        "value", null, "value", "链接", "URL", null, null, null, false, false, 0);
        for (String raw :
                List.of(
                        "https://example.com/purchase",
                        "\"https://example.com/purchase\"",
                        "{\"link\":\"https://example.com/purchase\",\"text\":\"\"}")) {
            var options =
                    new FieldOptions(
                            null, "NORMAL", raw, null, null, null, null, "ACTIVE", List.of(), null,
                            null, "NONE", null, false, false);
            String normalized = FieldDefaultValidation.normalizeDefault(field, options, json);
            assertThat(json.readValue(normalized, Map.class))
                    .isEqualTo(Map.of("link", "https://example.com/purchase", "text", ""));
        }
        var named =
                new FieldOptions(
                        null,
                        "NORMAL",
                        "{\"link\":\" https://example.com/purchase \",\"text\":\" 采购说明 \"}",
                        null,
                        null,
                        null,
                        null,
                        "ACTIVE",
                        List.of(),
                        null,
                        null,
                        "NONE",
                        null,
                        false,
                        false);
        assertThat(
                        json.readValue(
                                FieldDefaultValidation.normalizeDefault(field, named, json),
                                Map.class))
                .isEqualTo(Map.of("link", "https://example.com/purchase", "text", "采购说明"));
    }

    @Test
    void hyperlinkNormalizationCannotPersistAnOverlongDefault() {
        var field =
                new FieldDefinition(
                        "value", null, "value", "链接", "URL", null, null, null, false, false, 0);
        String base = "https://example.com/";
        String address = base + "a".repeat(4000 - base.length());
        assertThat(address).hasSize(4000);
        validate("URL", address, null, null);
        var options =
                new FieldOptions(
                        null, "NORMAL", address, null, null, null, null, "ACTIVE", List.of(), null,
                        null, "NONE", null, false, false);
        assertThatThrownBy(
                        () ->
                                FieldDefaultValidation.normalizeDefault(
                                        field, options, new ObjectMapper()))
                .hasMessageContaining("默认链接过长");
    }
}
