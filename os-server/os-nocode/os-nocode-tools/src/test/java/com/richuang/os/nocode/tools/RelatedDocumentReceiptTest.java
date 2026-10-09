package com.richuang.os.nocode.tools;

import static org.junit.jupiter.api.Assertions.*;

import cn.hutool.crypto.digest.DigestUtil;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.richuang.os.nocode.api.ApplicationRecords;
import com.richuang.os.nocode.api.RelatedForms;
import com.richuang.os.nocode.runtime.service.record.DocumentReceipts;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

/** 用新增关联协议之前的 11 字段 DTO 计算已有收据摘要，不依赖数据库或新建收据。 */
class RelatedDocumentReceiptTest {
    record LegacySave(
            String applicationId,
            String objectId,
            String id,
            String expectedRevision,
            Map<String, Object> values,
            Map<String, List<ApplicationRecords.Row>> details,
            Map<String, List<String>> relations,
            ApplicationRecords.Context context,
            String formId,
            String requestKey,
            String actionCode) {}

    private DocumentReceipts receipts(ObjectMapper mapper) {
        var receipts = new DocumentReceipts();
        ReflectionTestUtils.setField(receipts, "json", mapper);
        ReflectionTestUtils.invokeMethod(receipts, "initialize");
        return receipts;
    }

    private String digest(DocumentReceipts receipts, ApplicationRecords.Save save) {
        return ReflectionTestUtils.invokeMethod(receipts, "digest", save);
    }

    @Test
    void emptyRelatedInputMatchesPreviouslyStoredDigest() throws Exception {
        for (var inclusion : List.of(JsonInclude.Include.ALWAYS, JsonInclude.Include.NON_NULL)) {
            var mapper =
                    new ObjectMapper()
                            .setSerializationInclusion(inclusion)
                            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
            var values = Map.<String, Object>of("b", "值B", "a", "值A");
            var details =
                    Map.of("detail", List.of(new ApplicationRecords.Row("1", "revision", values)));
            var legacyDetails =
                    Map.of(
                            "detail",
                            List.of(
                                    new ApplicationRecords.Row(
                                            "1", "revision", values, null, null, null)));
            var legacy =
                    new LegacySave(
                            "1",
                            "2",
                            "3",
                            "4",
                            values,
                            legacyDetails,
                            Map.of(),
                            null,
                            "form",
                            null,
                            null);
            var stored = DigestUtil.sha256Hex(mapper.writeValueAsString(legacy));
            var noRelated =
                    new ApplicationRecords.Save(
                            "1",
                            "2",
                            "3",
                            "4",
                            values,
                            details,
                            Map.of(),
                            null,
                            "form",
                            "request-key",
                            null);
            var emptyRelated =
                    new ApplicationRecords.Save(
                            "1",
                            "2",
                            "3",
                            "4",
                            values,
                            details,
                            Map.of(),
                            null,
                            "form",
                            "request-key",
                            null,
                            Map.of());
            assertEquals(stored, digest(receipts(mapper), noRelated));
            assertEquals(stored, digest(receipts(mapper), emptyRelated));
        }
    }

    @Test
    void relatedIntentParticipatesInDigest() {
        var receipts = receipts(new ObjectMapper());
        var first =
                new ApplicationRecords.Save(
                        "1",
                        "2",
                        null,
                        null,
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        null,
                        "form",
                        "request-key",
                        null,
                        Map.of(
                                "binding",
                                List.of(
                                        new RelatedForms.Row(
                                                null,
                                                null,
                                                Map.of("field", "one"),
                                                Map.of(),
                                                false))));
        var changed =
                new ApplicationRecords.Save(
                        "1",
                        "2",
                        null,
                        null,
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        null,
                        "form",
                        "request-key",
                        null,
                        Map.of(
                                "binding",
                                List.of(
                                        new RelatedForms.Row(
                                                null,
                                                null,
                                                Map.of("field", "two"),
                                                Map.of(),
                                                false))));
        assertEquals(digest(receipts, first), digest(receipts, first));
        assertNotEquals(digest(receipts, first), digest(receipts, changed));
    }
}
