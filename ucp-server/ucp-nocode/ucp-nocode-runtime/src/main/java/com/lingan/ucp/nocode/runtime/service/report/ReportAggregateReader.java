package com.lingan.ucp.nocode.runtime.service.report;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.*;
import com.lingan.ucp.nocode.api.ApplicationReports;
import com.lingan.ucp.nocode.runtime.dal.mapper.ReportMapper;
import com.lingan.ucp.nocode.runtime.dal.query.ReportStatement;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.*;

/** 应用报表与独立数据集共用同一 SQL 结果读取，数值保持字符串避免精度丢失。 */
@Component
public class ReportAggregateReader {
    @Resource private ReportMapper mapper;
    @Resource private ObjectMapper json;

    public ApplicationReports.Result read(
            ReportStatement statement,
            List<String> names,
            List<ApplicationReports.Metric> metrics,
            String timeZone,
            Supplier<Boolean> export,
            Function<List<List<String>>, List<List<String>>> labels) {
        try {
            JsonNode raw = json.readTree(mapper.result(statement));
            List<List<String>> keys = new ArrayList<>();
            for (JsonNode row : raw.path("groups"))
                keys.add(ReportDimensionLabels.keys(row.path("keys")));
            List<List<String>> display = labels.apply(keys);
            List<ApplicationReports.Group> groups = new ArrayList<>();
            int index = 0;
            for (JsonNode row : raw.path("groups")) {
                groups.add(
                        new ApplicationReports.Group(
                                keys.get(index), display.get(index), strings(row.path("values"))));
                index++;
            }
            return new ApplicationReports.Result(
                    names,
                    metrics,
                    groups,
                    strings(raw.path("totals")),
                    raw.path("totalGroups").asLong(),
                    raw.path("recordCount").asLong(),
                    export.get(),
                    timeZone);
        } catch (java.io.IOException error) {
            throw invalid("统计结果无法读取");
        }
    }

    private Map<String, String> strings(JsonNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        node.fields()
                .forEachRemaining(
                        entry ->
                                result.put(
                                        entry.getKey(),
                                        entry.getValue().isNull()
                                                ? null
                                                : entry.getValue().asText()));
        return result;
    }
}
