package com.lingan.ucp.nocode.tools;

import java.util.*;

/** DAL 目录调整的显式类名对照；不改写捕获的原始 SQL 或参数值基线。 */
final class SqlBaselineNames {
    private SqlBaselineNames() {}

    private static final Map<String, String> RENAMED =
            Map.ofEntries(
                    Map.entry(
                            "com.lingan.ucp.nocode.dal.dataobject.DataCenterRows",
                            "com.lingan.ucp.nocode.metadata.dal.dataobject.DataCenterRows"),
                    Map.entry(
                            "com.lingan.ucp.nocode.dal.dataobject.NocodeObjectDO",
                            "com.lingan.ucp.nocode.metadata.dal.dataobject.NocodeObjectDO"),
                    Map.entry(
                            "com.lingan.ucp.nocode.dal.dataobject.NocodeObjectTableDO",
                            "com.lingan.ucp.nocode.metadata.dal.dataobject.NocodeObjectTableDO"),
                    Map.entry(
                            "com.lingan.ucp.nocode.dal.dataobject.NocodeObjectVersionDO",
                            "com.lingan.ucp.nocode.metadata.dal.dataobject.NocodeObjectVersionDO"),
                    Map.entry(
                            "com.lingan.ucp.nocode.dal.dataobject.ObjectDraftHeadDO",
                            "com.lingan.ucp.nocode.metadata.dal.dataobject.ObjectDraftHeadDO"),
                    Map.entry(
                            "com.lingan.ucp.nocode.dal.mapper.DataCenterMapper",
                            "com.lingan.ucp.nocode.metadata.dal.mapper.DataCenterMapper"),
                    Map.entry(
                            "com.lingan.ucp.nocode.dal.mapper.ObjectDraftMapper",
                            "com.lingan.ucp.nocode.metadata.dal.mapper.ObjectDraftMapper"),
                    Map.entry(
                            "com.lingan.ucp.nocode.dal.mapper.SelectionMigrationMapper",
                            "com.lingan.ucp.nocode.metadata.dal.mapper.SelectionMigrationMapper"),
                    Map.entry(
                            "com.lingan.ucp.nocode.application.dal.ObjectApplicationGrantMapper",
                            "com.lingan.ucp.nocode.application.dal.mapper.ObjectApplicationGrantMapper"),
                    Map.entry(
                            "com.lingan.ucp.nocode.application.dal.ApplicationAccessMapper",
                            "com.lingan.ucp.nocode.application.dal.mapper.ApplicationAccessMapper"),
                    Map.entry(
                            "com.lingan.ucp.nocode.application.dal.NocodeObjectApplicationGrantDO",
                            "com.lingan.ucp.nocode.application.dal.dataobject.NocodeObjectApplicationGrantDO"),
                    Map.entry(
                            "com.lingan.ucp.nocode.application.dal.NocodeApplicationAccessDO",
                            "com.lingan.ucp.nocode.application.dal.dataobject.NocodeApplicationAccessDO"),
                    Map.entry(
                            "com.lingan.ucp.nocode.application.dal.NocodeApplicationDO",
                            "com.lingan.ucp.nocode.application.dal.dataobject.NocodeApplicationDO"),
                    Map.entry(
                            "com.lingan.ucp.nocode.application.dal.ApplicationMapper",
                            "com.lingan.ucp.nocode.application.dal.mapper.ApplicationMapper"),
                    Map.entry(
                            "com.lingan.ucp.nocode.application.dal.RecordProcessMapper",
                            "com.lingan.ucp.nocode.application.dal.mapper.RecordProcessMapper"),
                    Map.entry(
                            "com.lingan.ucp.nocode.application.dal.NocodeApplicationVersionDO",
                            "com.lingan.ucp.nocode.application.dal.dataobject.NocodeApplicationVersionDO"),
                    Map.entry(
                            "com.lingan.ucp.nocode.application.dal.NocodeRecordProcessDO",
                            "com.lingan.ucp.nocode.application.dal.dataobject.NocodeRecordProcessDO"),
                    Map.entry(
                            "com.lingan.ucp.nocode.runtime.dal.DataViewChildStatement",
                            "com.lingan.ucp.nocode.runtime.dal.query.DataViewChildStatement"),
                    Map.entry(
                            "com.lingan.ucp.nocode.runtime.dal.NocodeBusinessCounterDO",
                            "com.lingan.ucp.nocode.runtime.dal.dataobject.NocodeBusinessCounterDO"),
                    Map.entry(
                            "com.lingan.ucp.nocode.runtime.dal.RuntimeSqlParameters",
                            "com.lingan.ucp.nocode.runtime.dal.support.RuntimeSqlParameters"),
                    Map.entry(
                            "com.lingan.ucp.nocode.runtime.dal.RuntimeConditionSql",
                            "com.lingan.ucp.nocode.runtime.dal.support.RuntimeConditionSql"),
                    Map.entry(
                            "com.lingan.ucp.nocode.runtime.dal.DataViewMapper",
                            "com.lingan.ucp.nocode.runtime.dal.mapper.DataViewMapper"),
                    Map.entry(
                            "com.lingan.ucp.nocode.runtime.dal.ReportStatement",
                            "com.lingan.ucp.nocode.runtime.dal.query.ReportStatement"),
                    Map.entry(
                            "com.lingan.ucp.nocode.runtime.dal.SummaryStatement",
                            "com.lingan.ucp.nocode.runtime.dal.query.SummaryStatement"),
                    Map.entry(
                            "com.lingan.ucp.nocode.runtime.dal.RecordStatement",
                            "com.lingan.ucp.nocode.runtime.dal.query.RecordStatement"),
                    Map.entry(
                            "com.lingan.ucp.nocode.runtime.dal.ReportMapper",
                            "com.lingan.ucp.nocode.runtime.dal.mapper.ReportMapper"),
                    Map.entry(
                            "com.lingan.ucp.nocode.runtime.dal.RelationScope",
                            "com.lingan.ucp.nocode.runtime.dal.query.RelationScope"),
                    Map.entry(
                            "com.lingan.ucp.nocode.runtime.dal.ReportMetricExpressions",
                            "com.lingan.ucp.nocode.runtime.dal.support.ReportMetricExpressions"),
                    Map.entry(
                            "com.lingan.ucp.nocode.runtime.dal.DataViewStatement",
                            "com.lingan.ucp.nocode.runtime.dal.query.DataViewStatement"),
                    Map.entry(
                            "com.lingan.ucp.nocode.runtime.dal.RecordMapper",
                            "com.lingan.ucp.nocode.runtime.dal.mapper.RecordMapper"),
                    Map.entry(
                            "com.lingan.ucp.nocode.runtime.dal.RelationStatement",
                            "com.lingan.ucp.nocode.runtime.dal.query.RelationStatement"),
                    Map.entry(
                            "com.lingan.ucp.nocode.runtime.dal.BusinessCounterMapper",
                            "com.lingan.ucp.nocode.runtime.dal.mapper.BusinessCounterMapper"));

    static String current(String original) {
        String value = original;
        for (var entry : RENAMED.entrySet())
            value = value.replace(entry.getKey(), entry.getValue());
        return value;
    }

    static String original(String current) {
        String value = current;
        for (var entry : RENAMED.entrySet())
            value = value.replace(entry.getValue(), entry.getKey());
        return value;
    }

    /** 仅持久化结果类型的类名改变，SQL 词元、实际绑定值及其他执行属性原样比较。 */
    static Map<String, Object> resultTypes(Map<String, Object> original) {
        var value = new LinkedHashMap<>(original);
        if (value.get("resultTypes") instanceof List<?> types)
            value.put("resultTypes", types.stream().map(type -> current((String) type)).toList());
        return value;
    }
}
