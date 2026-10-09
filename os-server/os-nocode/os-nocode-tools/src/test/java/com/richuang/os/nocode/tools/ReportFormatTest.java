package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.hutool.crypto.digest.DigestUtil;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.framework.mybatis.core.metadata.DatabaseMetadata;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.application.service.application.ApplicationService;
import com.richuang.os.nocode.application.service.resource.ApplicationReportValidator;
import com.richuang.os.nocode.application.service.resource.ApplicationResourceContext;
import com.richuang.os.nocode.application.service.resource.ApplicationResourceValidator;
import com.richuang.os.nocode.enums.ReportOperationEnum;
import com.richuang.os.nocode.runtime.dal.mapper.ReportMapper;
import com.richuang.os.nocode.runtime.dal.query.RecordStatement;
import com.richuang.os.nocode.runtime.dal.query.ReportStatement;
import com.richuang.os.nocode.runtime.service.access.ApplicationRuntimePolicy;
import com.richuang.os.nocode.runtime.service.record.FixedViewConditions;
import com.richuang.os.nocode.runtime.service.record.RecordQueryAccess;
import com.richuang.os.nocode.runtime.service.record.RuntimeSchema;
import com.richuang.os.nocode.runtime.service.report.ApplicationReportService;
import com.richuang.os.nocode.runtime.service.selection.SelectionCatalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

/** 格式归一与历史发布快照的只读投影回归；全部使用内存夹具，不连接或清理数据库。 */
class ReportFormatTest {
    private final ApplicationReportValidator validator = new ApplicationReportValidator();
    private final DataCenter.Definition definition = definition();

    @ParameterizedTest
    @EnumSource(
            value = ReportOperationEnum.class,
            names = {"SUM", "AVG", "MIN", "MAX"})
    void moneyAggregatesAutomaticallyEnableFinancialAndPreserveDisplayOptions(
            ReportOperationEnum operation) {
        for (Integer decimals : Arrays.asList(null, 0, 4, 8)) {
            ApplicationReports.Format original =
                    new ApplicationReports.Format("万元", decimals, false, "#12abEF", false);
            ApplicationReports.Config input = config(metric(operation, "money", original));
            ApplicationReports.Config normalized = normalize(input);
            assertThat(normalized.metrics().getFirst().format())
                    .isEqualTo(
                            new ApplicationReports.Format("万元", decimals, false, "#12abEF", true));
            assertThat(input.metrics().getFirst().format()).isSameAs(original);
            assertThat(normalize(normalized)).isEqualTo(normalized);
        }
        ApplicationReports.Config input = config(metric(operation, "money", null));
        assertThat(normalize(input).metrics().getFirst().format())
                .isEqualTo(new ApplicationReports.Format(null, null, false, null, true));
        assertThat(input.metrics().getFirst().format()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
        "COUNT,",
        "COUNT_FIELD,money",
        "COUNT_DISTINCT,money",
        "COUNT_FIELD,decimal",
        "COUNT_DISTINCT,decimal",
        "SUM,decimal",
        "AVG,decimal",
        "MIN,decimal",
        "MAX,decimal",
        "SUM,integer",
        "AVG,integer",
        "MIN,integer",
        "MAX,integer",
        "SUM,percent",
        "AVG,percent",
        "MIN,percent",
        "MAX,percent"
    })
    void countsAndNonMoneyAggregatesClearStaleFinancialFlags(
            ReportOperationEnum operation, String field) {
        ApplicationReports.Format stale =
                new ApplicationReports.Format("个", 4, false, "#123456", true);
        ApplicationReports.Config input = config(metric(operation, field, stale));
        assertThat(normalize(input).metrics().getFirst().format())
                .isEqualTo(new ApplicationReports.Format("个", 4, false, "#123456", false));
        assertThat(validator.normalizeFormats(input, definition).metrics())
                .isEqualTo(normalize(input).metrics());
        assertThat(normalize(config(metric(operation, field, null))).metrics().getFirst().format())
                .isNull();
        assertThat(stale.financial()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(
            value = ReportOperationEnum.class,
            names = {"SUM", "AVG", "MIN", "MAX"})
    void percentMoneyMetricsDoNotAutomaticallyEnableFinancial(ReportOperationEnum operation) {
        ApplicationReports.Format percent =
                new ApplicationReports.Format("占比", 3, true, "#456789", false);
        ApplicationReports.Config input = config(metric(operation, "money", percent));
        assertThat(normalize(input).metrics().getFirst().format()).isEqualTo(percent);
        assertThat(validator.normalizeFormats(input, definition).metrics().getFirst().format())
                .isEqualTo(percent);
    }

    @ParameterizedTest
    @EnumSource(ReportOperationEnum.class)
    void explicitFinancialAndPercentConflictIsRejectedBeforeNormalization(
            ReportOperationEnum operation) {
        ApplicationReports.Format invalid =
                new ApplicationReports.Format(null, 2, true, null, true);
        for (String field : List.of("money", "decimal")) {
            ApplicationReports.Metric metric = metric(operation, field, invalid);
            ApplicationReports.Config input =
                    operation == ReportOperationEnum.FORMULA
                            ? config(metric(ReportOperationEnum.SUM, "money", null), metric)
                            : config(metric);
            assertThatThrownBy(() -> normalize(input))
                    .isInstanceOf(ServiceException.class)
                    .hasMessageContaining("财务金额不能同时设为百分比");
        }
    }

    @Test
    void formulaRetainsExplicitFinancialAndNeverInfersItFromReferencedMetrics() {
        ApplicationReports.Metric base = metric(ReportOperationEnum.SUM, "money", null);
        for (ApplicationReports.Format format :
                Arrays.asList(
                        null,
                        new ApplicationReports.Format("元", 5, false, "#ABCDEF", true),
                        new ApplicationReports.Format("倍", 3, false, "#123456", false),
                        new ApplicationReports.Format(null, 2, true, null, false))) {
            ApplicationReports.Config input =
                    config(base, metric(ReportOperationEnum.FORMULA, null, format));
            ApplicationReports.Config normalized = normalize(input);
            assertThat(normalized.metrics().getFirst().format().financial()).isTrue();
            assertThat(normalized.metrics().get(1).format()).isSameAs(format);
            assertThat(validator.normalizeFormats(input, definition).metrics().get(1).format())
                    .isSameAs(format);
        }
    }

    @Test
    void formatProjectionDoesNotRevalidateOrChangeUnrelatedHistoricalConfiguration() {
        ApplicationReports.Config original =
                new ApplicationReports.Config(
                        definition.objectId(),
                        List.of(),
                        List.of(metric(ReportOperationEnum.SUM, "money", null)),
                        Map.of("legacy_filter", "保留"),
                        List.of("legacy_filter"),
                        "legacy_date",
                        "legacy_zone",
                        "legacy_display",
                        null,
                        true,
                        500,
                        "legacy_view");
        ApplicationReports.Config projected = validator.normalizeFormats(original, definition);
        assertThat(projected)
                .usingRecursiveComparison()
                .ignoringFields("metrics")
                .isEqualTo(original);
        assertThat(projected.metrics().getFirst().format().financial()).isTrue();
        assertThat(original.metrics().getFirst().format()).isNull();
        assertThat(validator.normalizeFormats(projected, definition)).isEqualTo(projected);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publishedLegacyReportQueryProjectsFinancialWithoutChangingSnapshotChecksum(
            boolean missingFormat) throws Exception {
        ObjectMapper json =
                new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        ApplicationReports.Format oldFormat =
                missingFormat
                        ? null
                        : new ApplicationReports.Format("万元", 4, false, "#123456", false);
        ApplicationReports.Config legacy =
                config(
                        metric(ReportOperationEnum.SUM, "money", oldFormat),
                        new ApplicationReports.Metric(
                                "count",
                                "金额计数",
                                "COUNT_FIELD",
                                "money",
                                null,
                                null,
                                new ApplicationReports.Format("个", 0, false, null, true)),
                        new ApplicationReports.Metric(
                                "plain",
                                "普通数值",
                                "SUM",
                                "decimal",
                                null,
                                null,
                                new ApplicationReports.Format(null, 3, false, null, true)));
        ObjectNode legacyJson = json.valueToTree(legacy);
        // 模拟 financial 字段引入前的序列化形状；仅构造内存夹具，绝不更新发布表。
        ObjectNode firstMetric = (ObjectNode) legacyJson.path("metrics").get(0);
        if (missingFormat) firstMetric.remove("format");
        else ((ObjectNode) firstMetric.path("format")).remove("financial");
        Map<String, Object> resourceConfig =
                json.convertValue(legacyJson, new TypeReference<Map<String, Object>>() {});
        ApplicationCenter.ObjectReference reference =
                new ApplicationCenter.ObjectReference(definition.objectId(), 7, "fixed-object-sum");
        ApplicationCenter.Definition applicationDefinition =
                new ApplicationCenter.Definition(
                        List.of(reference),
                        List.of(
                                new ApplicationCenter.Resource(
                                        "report", "REPORT", "report", "历史金额报表", resourceConfig)));
        ApplicationCenter.Snapshot snapshot =
                new ApplicationCenter.Snapshot(
                        "report_fixture", "只读报表夹具", null, null, applicationDefinition);
        String snapshotJson = json.writeValueAsString(snapshot);
        String checksum = DigestUtil.sha256Hex(snapshotJson);
        String objectJson = json.writeValueAsString(definition);
        ApplicationCenter.Published published =
                new ApplicationCenter.Published(
                        null, 3, checksum, applicationDefinition, List.of());

        ApplicationService applications = mock(ApplicationService.class);
        RecordQueryAccess records = mock(RecordQueryAccess.class);
        ApplicationRuntimePolicy policy = mock(ApplicationRuntimePolicy.class);
        RuntimeSchema schemas = mock(RuntimeSchema.class);
        ReportMapper mapper = mock(ReportMapper.class);
        ApplicationReportValidator runtimeValidator = spy(new ApplicationReportValidator());
        ApplicationResourceContext resourceContext = new ApplicationResourceContext();
        ReflectionTestUtils.setField(resourceContext, "json", json);
        ReflectionTestUtils.invokeMethod(resourceContext, "initialize");
        ApplicationResourceValidator resources = new ApplicationResourceValidator();
        ReflectionTestUtils.setField(resources, "resourceContext", resourceContext);
        ApplicationReportService reports = new ApplicationReportService();
        com.richuang.os.nocode.runtime.service.report.ReportAggregateReader aggregates =
                new com.richuang.os.nocode.runtime.service.report.ReportAggregateReader();
        ReflectionTestUtils.setField(aggregates, "json", json);
        ReflectionTestUtils.setField(aggregates, "mapper", mapper);
        ReflectionTestUtils.setField(reports, "aggregates", aggregates);
        ReflectionTestUtils.setField(reports, "applications", applications);
        ReflectionTestUtils.setField(reports, "records", records);
        ReflectionTestUtils.setField(reports, "policy", policy);
        ReflectionTestUtils.setField(reports, "schemas", schemas);
        ReflectionTestUtils.setField(reports, "mapper", mapper);
        ReflectionTestUtils.setField(reports, "validator", runtimeValidator);
        ReflectionTestUtils.setField(reports, "resources", resources);
        ReflectionTestUtils.setField(reports, "json", json);
        // 无分组的内存夹具无需候选来源，但运行报表仍经过真实的应用固定版本作用域。
        ReflectionTestUtils.setField(reports, "selections", new SelectionCatalog());
        ReflectionTestUtils.setField(reports, "fixedViewConditions", new FixedViewConditions());
        // 运行期报表在应用上下文里解析挑取值来源；这里手工装配服务，需补上选择目录。
        ReflectionTestUtils.setField(
                reports,
                "selections",
                new com.richuang.os.nocode.runtime.service.selection.SelectionCatalog());
        Set<String> fields = Set.of("money", "decimal", "integer", "percent");
        ApplicationRuntimePolicy.Access access =
                new ApplicationRuntimePolicy.Access(
                        10001,
                        definition,
                        List.of(
                                new ApplicationAuthorization.ObjectGrant(
                                        definition.objectId(),
                                        Set.of("READ", "EXPORT"),
                                        "ALL",
                                        fields,
                                        Set.of(),
                                        Set.of(),
                                        Set.of())));
        RuntimeSchema.Table table = table();
        when(applications.published("report_app")).thenReturn(published);
        when(records.definition("report_app", definition.objectId(), 10001)).thenReturn(definition);
        when(policy.access("report_app", definition, 10001)).thenReturn(access);
        when(schemas.main(definition)).thenReturn(table);
        when(records.reportScope(
                        any(RecordStatement.class),
                        eq("report_app"),
                        eq(definition.objectId()),
                        eq("report"),
                        isNull(),
                        same(access),
                        eq(10001L)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(policy.conditions(same(access), same(table), isNull(), any(), eq("t")))
                .thenReturn(new QueryWrapper<>());
        when(mapper.result(any(ReportStatement.class)))
                .thenReturn(
                        """
{"groups":[],"totals":{"base":"9007199254740993.1234","count":"2","plain":"3.125"},"totalGroups":0,"recordCount":2}
""");
        ApplicationReports.Query query =
                new ApplicationReports.Query(
                        "report_app", "report", null, null, null, null, null, 1, 20);
        ApplicationReports.Result result = reports.query(query, 10001);
        assertThat(result.metrics().getFirst().format())
                .isEqualTo(
                        missingFormat
                                ? new ApplicationReports.Format(null, null, false, null, true)
                                : new ApplicationReports.Format("万元", 4, false, "#123456", true));
        assertThat(result.metrics().get(1).format().financial()).isFalse();
        assertThat(result.metrics().get(2).format().financial()).isFalse();
        assertThat(result.totals()).containsEntry("base", "9007199254740993.1234");
        assertThat(reports.export(query, 10001).metrics()).isEqualTo(result.metrics());
        assertThat(json.writeValueAsString(snapshot)).isEqualTo(snapshotJson);
        assertThat(DigestUtil.sha256Hex(json.writeValueAsString(snapshot))).isEqualTo(checksum);
        assertThat(published.checksum()).isEqualTo(checksum);
        assertThat(published.versionNo()).isEqualTo(3);
        assertThat(published.definition().objects()).containsExactly(reference);
        assertThat(json.writeValueAsString(definition)).isEqualTo(objectJson);
        verify(runtimeValidator, never()).normalize(any(), anyMap());
        verify(applications, atLeastOnce()).published("report_app");
        verifyNoMoreInteractions(applications);
    }

    private ApplicationReports.Config normalize(ApplicationReports.Config config) {
        return validator.normalize(config, Map.of(definition.objectId(), definition));
    }

    private ApplicationReports.Metric metric(
            ReportOperationEnum operation, String field, ApplicationReports.Format format) {
        return new ApplicationReports.Metric(
                operation == ReportOperationEnum.FORMULA ? "derived" : "base",
                "测试指标",
                operation.getCode(),
                operation == ReportOperationEnum.COUNT || operation == ReportOperationEnum.FORMULA
                        ? null
                        : field,
                null,
                operation == ReportOperationEnum.FORMULA
                        ? new ApplicationReports.Formula("SUBTRACT", "base", "base")
                        : null,
                format);
    }

    private ApplicationReports.Config config(ApplicationReports.Metric... metrics) {
        return new ApplicationReports.Config(
                definition.objectId(),
                List.of(),
                List.of(metrics),
                Map.of(),
                List.of(),
                null,
                "Asia/Shanghai",
                "METRIC",
                null,
                false,
                30,
                null);
    }

    private static DataCenter.Definition definition() {
        List<FieldDefinition> fields =
                List.of(
                        field("money", "MONEY"),
                        field("decimal", "DECIMAL"),
                        field("integer", "INTEGER"),
                        field("percent", "PERCENT"));
        return new DataCenter.Definition(
                "report_object",
                "report_format",
                "报表格式夹具",
                null,
                "public",
                "biz_report_format",
                "GENERATED",
                false,
                "money",
                DataCenter.Settings.defaults(),
                fields,
                Map.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private static FieldDefinition field(String id, String type) {
        return new FieldDefinition(id, id, id, id, type, null, 30, 4, false, false, 0);
    }

    private RuntimeSchema.Table table() {
        DatabaseMetadata.Column key =
                new DatabaseMetadata.Column("id", 1, "bigint", false, null, null, null, null, 1);
        DatabaseMetadata.Table physical =
                new DatabaseMetadata.Table(
                        new DatabaseMetadata.Relation(
                                "public", definition.tableName(), "TABLE", null),
                        List.of(
                                key,
                                new DatabaseMetadata.Column(
                                        "money",
                                        2,
                                        "numeric(30,4)",
                                        true,
                                        null,
                                        null,
                                        null,
                                        null,
                                        0),
                                new DatabaseMetadata.Column(
                                        "decimal",
                                        3,
                                        "numeric(30,4)",
                                        true,
                                        null,
                                        null,
                                        null,
                                        null,
                                        0)),
                        List.of(),
                        List.of());
        return new RuntimeSchema.Table(
                "public",
                definition.tableName(),
                TableBinding.generated("public", false),
                physical,
                definition.fields(),
                definition.fieldOptions(),
                Map.of("money", "money", "decimal", "decimal"),
                key,
                false,
                definition);
    }
}
