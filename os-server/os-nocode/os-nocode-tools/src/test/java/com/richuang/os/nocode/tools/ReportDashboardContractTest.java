package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.nocode.api.ReportDashboards;
import com.richuang.os.nocode.enums.ReportDashboardEntryEnum;

import org.junit.jupiter.api.Test;

/** 仪表板交互扩展不添加空属性，历史固定版本的 JSON 和校验和输入必须逐字保持。 */
class ReportDashboardContractTest {
    @Test
    void legacyFilterRoundTripsWithoutChangingPublishedChecksumInput() throws Exception {
        ObjectMapper json = new ObjectMapper();
        String legacy =
                "{\"id\":\"names\",\"name\":\"名称\",\"kind\":\"SELECT\",\"mappings\":[{\"chartId\":\"chart\",\"fieldId\":\"name\"}]}";
        ReportDashboards.Filter decoded = json.readValue(legacy, ReportDashboards.Filter.class);
        assertThat(decoded.defaultValue()).isNull();
        assertThat(json.writeValueAsString(decoded)).isEqualTo(legacy);
        assertThat(
                        json.writeValueAsString(
                                new ReportDashboards.Filter(
                                        decoded.id(),
                                        decoded.name(),
                                        decoded.kind(),
                                        decoded.mappings())))
                .isEqualTo(legacy);
    }

    @Test
    void defaultValuesRetainNullAndTextOriginalKeys() throws Exception {
        ObjectMapper json = new ObjectMapper();
        String configured =
                "{\"id\":\"names\",\"name\":\"名称\",\"kind\":\"MULTISELECT\",\"mappings\":[{\"chartId\":\"chart\",\"fieldId\":\"name\"}],\"defaultValue\":{\"values\":[null,\"NULL\",\"\"],\"from\":null,\"to\":null}}";
        ReportDashboards.Filter decoded = json.readValue(configured, ReportDashboards.Filter.class);
        assertThat(decoded.defaultValue().values()).containsExactly(null, "NULL", "");
        assertThat(json.writeValueAsString(decoded)).isEqualTo(configured);
    }

    @Test
    void legacyContentAndQueryRoundTripWithoutNewNullProperties() throws Exception {
        ObjectMapper json = new ObjectMapper();
        String content =
                "{\"schemaVersion\":1,\"name\":\"旧看板\",\"description\":\"\",\"charts\":[{\"id\":\"chart\",\"title\":\"名称\",\"display\":\"TABLE\",\"dataset\":{\"id\":\"1\",\"versionNo\":1,\"checksum\":\"fixed\"},\"dimensions\":[{\"fieldId\":\"name\",\"bucket\":\"VALUE\"}],\"metricIds\":[\"count\"],\"x\":0,\"y\":0,\"w\":12,\"h\":6}]}";
        assertThat(json.writeValueAsString(json.readValue(content, ReportDashboards.Content.class)))
                .isEqualTo(content);
        ReportDashboards.Chart chart =
                json.readValue(content, ReportDashboards.Content.class).charts().getFirst();
        assertThat(
                        json.readTree(
                                        json.writeValueAsString(
                                                new ReportDashboards.Execution(
                                                        chart,
                                                        null,
                                                        java.util.List.of(),
                                                        java.util.List.of(),
                                                        java.util.List.of())))
                                .has("resolved"))
                .isFalse();
        String detail =
                "{\"id\":\"1\",\"ownerId\":\"10001\",\"revision\":1,\"publishedVersion\":1,\"checksum\":\"fixed\",\"modified\":false,\"draft\":"
                        + content
                        + "}";
        assertThat(json.writeValueAsString(json.readValue(detail, ReportDashboards.Detail.class)))
                .isEqualTo(detail);
        String query =
                "{\"id\":\"1\",\"chartId\":\"chart\",\"preview\":false,\"versionNo\":1,\"checksum\":\"fixed\"}";
        assertThat(json.writeValueAsString(json.readValue(query, ReportDashboards.Query.class)))
                .isEqualTo(query);
    }

    @Test
    void fixedHistoricalEntryIsInternalAndCannotBeAddedToHttpQuery() throws Exception {
        ObjectMapper json = new ObjectMapper();
        ReportDashboards.Query request =
                new ReportDashboards.Query("1", "chart", false, 1, "fixed");
        ReportDashboards.Resolved resolved =
                new ReportDashboards.Resolved(
                        request,
                        1,
                        "fixed",
                        null,
                        null,
                        ReportDashboardEntryEnum.APPLICATION_FIXED);
        assertThat(json.readTree(json.writeValueAsString(resolved)).has("entry")).isFalse();
        String forged =
                "{\"id\":\"1\",\"chartId\":\"chart\",\"preview\":false,\"versionNo\":1,\"checksum\":\"fixed\",\"entry\":\"APPLICATION_FIXED\"}";
        assertThatThrownBy(() -> json.readValue(forged, ReportDashboards.Query.class))
                .isInstanceOf(
                        com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class);
    }

    @Test
    void interactionRequestsRetainNullOriginalKeys() throws Exception {
        ObjectMapper json = new ObjectMapper();
        String query =
                "{\"id\":\"1\",\"chartId\":\"chart\",\"preview\":false,\"versionNo\":1,\"checksum\":\"fixed\",\"filterValues\":[{\"filterId\":\"names\",\"values\":[null,\"A\"],\"from\":null,\"to\":null}],\"selections\":[{\"chartId\":\"source\",\"group\":[null]}],\"drillPath\":[null,\"2026-01\"]}";
        ReportDashboards.Query decoded = json.readValue(query, ReportDashboards.Query.class);
        assertThat(decoded.filterValues().getFirst().values()).containsExactly(null, "A");
        assertThat(decoded.selections().getFirst().group()).containsExactly((String) null);
        assertThat(decoded.drillPath()).containsExactly(null, "2026-01");
        assertThat(json.writeValueAsString(decoded)).isEqualTo(query);
    }
}
