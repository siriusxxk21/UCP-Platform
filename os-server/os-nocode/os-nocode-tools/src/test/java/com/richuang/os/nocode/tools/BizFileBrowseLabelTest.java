package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.nocode.api.CalculationOptions;
import com.richuang.os.nocode.api.DataCenter;
import com.richuang.os.nocode.api.FieldDefinition;
import com.richuang.os.nocode.enums.BusinessFileLabelStatusEnum;
import com.richuang.os.nocode.runtime.dal.query.BizFileStatement;
import com.richuang.os.nocode.runtime.service.bizfile.BizFileBrowseService;
import com.richuang.os.nocode.runtime.service.bizfile.BizFileDirectoryNamer;
import com.richuang.os.nocode.runtime.service.record.RecordCalculations;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

/** 名称求值失败不能中断已授权附件浏览，也不能退回实时计算字段残留的物理旧值。 */
class BizFileBrowseLabelTest {
    @Test
    void failedLiveTitleEvaluationKeepsFilesAvailableWithoutExposingStoredTitle() {
        BizFileBrowseService browse = new BizFileBrowseService();
        RecordCalculations calculations = mock(RecordCalculations.class);
        ReflectionTestUtils.setField(browse, "json", new ObjectMapper());
        ReflectionTestUtils.setField(browse, "calculations", calculations);
        ReflectionTestUtils.setField(browse, "namer", new BizFileDirectoryNamer());
        DataCenter.BusinessFilePolicy policy =
                new DataCenter.BusinessFilePolicy(
                        "验证空间", List.of(), List.of(), List.of("live"), List.of("files"));
        FieldDefinition title =
                new FieldDefinition(
                        "live",
                        "live",
                        "live_total",
                        "动态标题",
                        "FORMULA",
                        null,
                        null,
                        null,
                        false,
                        false,
                        0);
        DataCenter.FieldOptions options =
                DataCenter.FieldOptions.copyOf(DataCenter.FieldOptions.defaults())
                        .expression("amount * 2")
                        .resultType("DECIMAL")
                        .calculation(
                                new CalculationOptions(
                                        "LOCAL", "LIVE", null, null, null, null, "AND", List.of(),
                                        false, List.of(), null))
                        .build();
        DataCenter.Definition definition =
                new DataCenter.Definition(
                        "1",
                        "verify",
                        "验证对象",
                        null,
                        "public",
                        "biz_verify",
                        "GENERATED",
                        false,
                        "live",
                        new DataCenter.Settings(null, null, null, null, null, policy),
                        List.of(title),
                        Map.of("live", options),
                        List.of(),
                        List.of(),
                        List.of());
        List<BizFileStatement.Projected> labels =
                List.of(new BizFileStatement.Projected("live", "live_total"));
        when(calculations.enrich(isNull(), same(definition), anyList(), eq(10001L)))
                .thenThrow(invalid("计算来源未授予应用所需的计算取数权限"));
        List<JsonNode> rows =
                ReflectionTestUtils.invokeMethod(
                        browse,
                        "labelRows",
                        List.of(
                                "{\"recordId\":\"1\",\"lf0\":\"999999\",\"fileId\":\"10\"}",
                                "{\"recordId\":\"1\",\"lf0\":\"999999\",\"fileId\":\"11\"}"),
                        definition,
                        labels,
                        null,
                        10001L);
        assertThat(rows).hasSize(2);
        for (JsonNode row : rows) {
            BizFileDirectoryNamer.Label label =
                    ReflectionTestUtils.invokeMethod(
                            browse,
                            "recordLabel",
                            definition,
                            policy,
                            row,
                            labels,
                            "1",
                            null,
                            10001L);
            assertThat(label).isNotNull();
            assertThat(label.status()).isEqualTo(BusinessFileLabelStatusEnum.INVALID);
            assertThat(label.restricted()).isFalse();
            assertThat(label.text()).isEqualTo("记录 1").doesNotContain("999999");
        }
        verify(calculations)
                .enrich(
                        isNull(),
                        same(definition),
                        argThat(records -> records.size() == 1),
                        eq(10001L));
    }
}
