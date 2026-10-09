package com.lingan.ucp.framework.apilog.core.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.lingan.ucp.framework.apilog.core.util.ApiLogSanitizeUtils.MASK;
import static org.assertj.core.api.Assertions.assertThat;

class ApiLogSanitizeUtilsTest {

    @Test
    void sanitizeJson_masksNestedAndCaseInsensitiveKeys_keepsOtherFields() {
        String body = "{\"username\":\"admin\",\"password\":\"Pwd-Plain-001\","
                + "\"captchaVerification\":\"CAPTCHA-PLAIN-002\","
                + "\"profile\":{\"OldPassword\":\"Pwd-Plain-003\",\"nickname\":\"nick\"},"
                + "\"items\":[{\"TOKEN\":\"TOKEN-PLAIN-004\",\"id\":7}],"
                + "\"clientSecret\":\"SECRET-PLAIN-005\",\"Authorization\":\"Bearer AUTH-PLAIN-006\"}";

        String result = ApiLogSanitizeUtils.sanitizeJson(body);

        assertThat(result).doesNotContain("Pwd-Plain-001", "CAPTCHA-PLAIN-002", "Pwd-Plain-003",
                "TOKEN-PLAIN-004", "SECRET-PLAIN-005", "AUTH-PLAIN-006");
        assertThat(result).contains("\"password\":\"" + MASK + "\"", "\"OldPassword\":\"" + MASK + "\"",
                "\"TOKEN\":\"" + MASK + "\"", "\"captchaVerification\":\"" + MASK + "\"");
        assertThat(result).contains("\"username\":\"admin\"", "\"nickname\":\"nick\"", "\"id\":7");
    }

    @Test
    void sanitizeJson_extraKeysFromAnnotation() {
        String result = ApiLogSanitizeUtils.sanitizeJson("{\"idCard\":\"ID-PLAIN-007\",\"name\":\"n\"}", "IDCARD");

        assertThat(result).doesNotContain("ID-PLAIN-007").contains("\"idCard\":\"" + MASK + "\"", "\"name\":\"n\"");
    }

    @Test
    void sanitizeJson_unparseableBody_neverReturnsRawText() {
        String result = ApiLogSanitizeUtils.sanitizeJson("{\"password\":\"Pwd-Plain-008\"");

        assertThat(result).doesNotContain("Pwd-Plain-008").contains("unparseable");
    }

    @Test
    void sanitizeParamMap_returnsMaskedCopy_andDoesNotMutateInput() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("token", "TOKEN-PLAIN-009");
        params.put("RefreshToken", "TOKEN-PLAIN-010");
        params.put("tenantId", "1");

        Map<String, String> result = ApiLogSanitizeUtils.sanitizeParamMap(params);

        assertThat(result).containsEntry("token", MASK).containsEntry("RefreshToken", MASK).containsEntry("tenantId", "1");
        assertThat(params).containsEntry("token", "TOKEN-PLAIN-009");
    }

}
