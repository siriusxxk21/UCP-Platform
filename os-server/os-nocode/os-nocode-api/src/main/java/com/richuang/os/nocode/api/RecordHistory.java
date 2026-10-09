package com.richuang.os.nocode.api;

import java.util.List;
import java.util.Map;

/** 检索范围采用 ISO 带偏移时间，起止均包含；所有下钻复用同一服务端截止时间。 */
public final class RecordHistory {
    private RecordHistory() {}

    public record Query(String start, String end, String applicationId, String employeeId) {}

    /** 下钻复用统计返回的截止时间及数据库可见性；每次仍重新校验当前权限。 */
    public record PageQuery(
            Query query,
            String visibility,
            String objectId,
            boolean changesOnly,
            int pageNo,
            int pageSize) {}

    public record DetailQuery(Query query, String visibility, String objectId, String recordId) {}

    public record Counts(
            long records, long operations, long create, long update, long delete, long employees) {}

    public record Employee(String id, String name, Counts counts) {}

    public record TableSummary(
            String objectId,
            String name,
            List<String> applicationIds,
            List<String> applicationNames,
            String coveredFrom,
            boolean complete,
            Counts counts,
            List<Employee> employees) {}

    /** 首屏只返回聚合结果，不包含记录值、表结构和修改过程。 */
    public record Summary(String start, String end, String visibility, List<TableSummary> tables) {}

    public record Page(Table table, long total, int pageNo, int pageSize) {}

    public record Detail(Row row, List<Field> fields) {}

    public record Field(String id, String name) {}

    /** 以持久化行 ID 对比明细；未采集的历史与已知空集合分开，不能把旧快照解释成新增。 */
    public record DetailChange(
            String id,
            String name,
            List<Field> fields,
            Map<String, Map<String, Object>> before,
            Map<String, Map<String, Object>> after,
            List<String> beforeOrder,
            List<String> afterOrder,
            boolean beforeKnown,
            boolean afterKnown) {}

    public record Change(
            String id,
            String operation,
            String time,
            String employeeId,
            String employeeName,
            Map<String, Object> before,
            Map<String, Object> after,
            List<Field> fields,
            Map<String, Object> source,
            List<DetailChange> details) {
        public Change {
            details = details == null ? List.of() : details;
        }

        public Change(
                String id,
                String operation,
                String time,
                String employeeId,
                String employeeName,
                Map<String, Object> before,
                Map<String, Object> after,
                List<Field> fields,
                Map<String, Object> source) {
            this(
                    id,
                    operation,
                    time,
                    employeeId,
                    employeeName,
                    before,
                    after,
                    fields,
                    source,
                    List.of());
        }
    }

    public record Row(
            String id,
            Map<String, Object> startValues,
            Map<String, Object> endValues,
            Map<String, Object> values,
            List<String> changedFields,
            List<Change> changes,
            long changeCount,
            boolean deleted,
            boolean createdInRange,
            boolean restored) {}

    public record Table(
            String objectId,
            String name,
            List<String> applicationIds,
            List<String> applicationNames,
            String coveredFrom,
            boolean complete,
            List<Field> fields,
            List<Row> rows) {}

    public record Result(String start, String end, List<Table> tables) {}
}
