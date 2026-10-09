package com.richuang.os.nocode.tools;

import java.util.*;

/** DAL 目录调整的显式类名对照；不改写捕获的原始 SQL 或参数值基线。 */
final class SqlBaselineNames {
    private SqlBaselineNames() {}

    private static final Map<String, String> RENAMED =
            Map.ofEntries(
                    Map.entry(
                            "com.richuang.os.nocode.dal.dataobject.DataCenterRows",
                            "com.richuang.os.nocode.metadata.dal.dataobject.DataCenterRows"),
                    Map.entry(
                            "com.richuang.os.nocode.dal.dataobject.NocodeObjectDO",
                            "com.richuang.os.nocode.metadata.dal.dataobject.NocodeObjectDO"),
                    Map.entry(
                            "com.richuang.os.nocode.dal.dataobject.NocodeObjectTableDO",
                            "com.richuang.os.nocode.metadata.dal.dataobject.NocodeObjectTableDO"),
                    Map.entry(
                            "com.richuang.os.nocode.dal.dataobject.NocodeObjectVersionDO",
                            "com.richuang.os.nocode.metadata.dal.dataobject.NocodeObjectVersionDO"),
                    Map.entry(
                            "com.richuang.os.nocode.dal.dataobject.ObjectDraftHeadDO",
                            "com.richuang.os.nocode.metadata.dal.dataobject.ObjectDraftHeadDO"),
                    Map.entry(
                            "com.richuang.os.nocode.dal.mapper.DataCenterMapper",
                            "com.richuang.os.nocode.metadata.dal.mapper.DataCenterMapper"),
                    Map.entry(
                            "com.richuang.os.nocode.dal.mapper.ObjectDraftMapper",
                            "com.richuang.os.nocode.metadata.dal.mapper.ObjectDraftMapper"),
                    Map.entry(
                            "com.richuang.os.nocode.dal.mapper.SelectionMigrationMapper",
                            "com.richuang.os.nocode.metadata.dal.mapper.SelectionMigrationMapper"),
                    Map.entry(
                            "com.richuang.os.nocode.application.dal.ObjectApplicationGrantMapper",
                            "com.richuang.os.nocode.application.dal.mapper.ObjectApplicationGrantMapper"),
                    Map.entry(
                            "com.richuang.os.nocode.application.dal.ApplicationAccessMapper",
                            "com.richuang.os.nocode.application.dal.mapper.ApplicationAccessMapper"),
                    Map.entry(
                            "com.richuang.os.nocode.application.dal.NocodeObjectApplicationGrantDO",
                            "com.richuang.os.nocode.application.dal.dataobject.NocodeObjectApplicationGrantDO"),
                    Map.entry(
                            "com.richuang.os.nocode.application.dal.NocodeApplicationAccessDO",
                            "com.richuang.os.nocode.application.dal.dataobject.NocodeApplicationAccessDO"),
                    Map.entry(
                            "com.richuang.os.nocode.application.dal.NocodeApplicationDO",
                            "com.richuang.os.nocode.application.dal.dataobject.NocodeApplicationDO"),
                    Map.entry(
                            "com.richuang.os.nocode.application.dal.ApplicationMapper",
                            "com.richuang.os.nocode.application.dal.mapper.ApplicationMapper"),
                    Map.entry(
                            "com.richuang.os.nocode.application.dal.RecordProcessMapper",
                            "com.richuang.os.nocode.application.dal.mapper.RecordProcessMapper"),
                    Map.entry(
                            "com.richuang.os.nocode.application.dal.NocodeApplicationVersionDO",
                            "com.richuang.os.nocode.application.dal.dataobject.NocodeApplicationVersionDO"),
                    Map.entry(
                            "com.richuang.os.nocode.application.dal.NocodeRecordProcessDO",
                            "com.richuang.os.nocode.application.dal.dataobject.NocodeRecordProcessDO"),
                    Map.entry(
                            "com.richuang.os.nocode.runtime.dal.DataViewChildStatement",
                            "com.richuang.os.nocode.runtime.dal.query.DataViewChildStatement"),
                    Map.entry(
                            "com.richuang.os.nocode.runtime.dal.NocodeBusinessCounterDO",
                            "com.richuang.os.nocode.runtime.dal.dataobject.NocodeBusinessCounterDO"),
                    Map.entry(
                            "com.richuang.os.nocode.runtime.dal.RuntimeSqlParameters",
                            "com.richuang.os.nocode.runtime.dal.support.RuntimeSqlParameters"),
                    Map.entry(
                            "com.richuang.os.nocode.runtime.dal.RuntimeConditionSql",
                            "com.richuang.os.nocode.runtime.dal.support.RuntimeConditionSql"),
                    Map.entry(
                            "com.richuang.os.nocode.runtime.dal.DataViewMapper",
                            "com.richuang.os.nocode.runtime.dal.mapper.DataViewMapper"),
                    Map.entry(
                            "com.richuang.os.nocode.runtime.dal.ReportStatement",
                            "com.richuang.os.nocode.runtime.dal.query.ReportStatement"),
                    Map.entry(
                            "com.richuang.os.nocode.runtime.dal.SummaryStatement",
                            "com.richuang.os.nocode.runtime.dal.query.SummaryStatement"),
                    Map.entry(
                            "com.richuang.os.nocode.runtime.dal.RecordStatement",
                            "com.richuang.os.nocode.runtime.dal.query.RecordStatement"),
                    Map.entry(
                            "com.richuang.os.nocode.runtime.dal.ReportMapper",
                            "com.richuang.os.nocode.runtime.dal.mapper.ReportMapper"),
                    Map.entry(
                            "com.richuang.os.nocode.runtime.dal.RelationScope",
                            "com.richuang.os.nocode.runtime.dal.query.RelationScope"),
                    Map.entry(
                            "com.richuang.os.nocode.runtime.dal.ReportMetricExpressions",
                            "com.richuang.os.nocode.runtime.dal.support.ReportMetricExpressions"),
                    Map.entry(
                            "com.richuang.os.nocode.runtime.dal.DataViewStatement",
                            "com.richuang.os.nocode.runtime.dal.query.DataViewStatement"),
                    Map.entry(
                            "com.richuang.os.nocode.runtime.dal.RecordMapper",
                            "com.richuang.os.nocode.runtime.dal.mapper.RecordMapper"),
                    Map.entry(
                            "com.richuang.os.nocode.runtime.dal.RelationStatement",
                            "com.richuang.os.nocode.runtime.dal.query.RelationStatement"),
                    Map.entry(
                            "com.richuang.os.nocode.runtime.dal.BusinessCounterMapper",
                            "com.richuang.os.nocode.runtime.dal.mapper.BusinessCounterMapper"));

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
