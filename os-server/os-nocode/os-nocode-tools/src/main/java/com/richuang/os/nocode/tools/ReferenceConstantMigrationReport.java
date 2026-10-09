package com.richuang.os.nocode.tools;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 引用字段条件固定值存量转换的报告（dry-run 产出，apply / rollback 读入并逐行回写结果）。
 *
 * @param objects 每个对象一步：apply / rollback 时的发布结果（版本前后、仍固定旧版本的应用）
 */
public record ReferenceConstantMigrationReport(
        String tool,
        int formatVersion,
        String command,
        String executedAt,
        Map<String, Integer> summary,
        List<Row> rows,
        List<ObjectStep> objects) {
    static final String TOOL = "reference-constant-migration";
    static final int FORMAT_VERSION = 1;

    /** apply / rollback 之后行的状态。 */
    static final String CONVERTED = "CONVERTED",
            RESTORED = "RESTORED",
            CONFLICT = "CONFLICT",
            FAILED = "FAILED";

    /**
     * 一个条件值。rule：REFERENCE_FILTER（引用筛选）或 LINKAGE（数据联动条件）；conditionIndex / valueIndex 从 0 起； before
     * 是存量值，after 是要改成的记录 ID（只有 CONVERT 才有）；matches 是显示名完全相同的记录条数。
     */
    public record Row(
            String objectId,
            String objectName,
            int versionNo,
            String detailId,
            String detailName,
            String fieldId,
            String fieldName,
            String rule,
            int conditionIndex,
            int valueIndex,
            String conditionFieldId,
            String conditionFieldName,
            String targetObjectId,
            String targetObjectName,
            String before,
            String after,
            Integer matches,
            String status,
            String note) {
        String key() {
            return String.join(
                    "|",
                    objectId,
                    detailId == null ? "" : detailId,
                    fieldId,
                    rule,
                    Integer.toString(conditionIndex),
                    Integer.toString(valueIndex));
        }

        Row with(String nextStatus, String nextAfter, Integer nextMatches, String nextNote) {
            return new Row(
                    objectId,
                    objectName,
                    versionNo,
                    detailId,
                    detailName,
                    fieldId,
                    fieldName,
                    rule,
                    conditionIndex,
                    valueIndex,
                    conditionFieldId,
                    conditionFieldName,
                    targetObjectId,
                    targetObjectName,
                    before,
                    nextAfter,
                    nextMatches,
                    nextStatus,
                    nextNote);
        }

        Row status(String nextStatus, String nextNote) {
            return with(nextStatus, after, matches, nextNote);
        }

        /** 判定内容是否相同（忽略版本号、状态与说明）：apply 时用来比对报告与重算结果。 */
        boolean samePlan(Row other) {
            return other != null
                    && key().equals(other.key())
                    && java.util.Objects.equals(conditionFieldId, other.conditionFieldId)
                    && java.util.Objects.equals(targetObjectId, other.targetObjectId)
                    && java.util.Objects.equals(before, other.before)
                    && java.util.Objects.equals(after, other.after);
        }

        String describe() {
            return objectName
                    + (detailName == null ? "" : " · " + detailName)
                    + " · "
                    + fieldName
                    + " · "
                    + (ReferenceConstantMigrationPlanner.LINKAGE.equals(rule) ? "数据联动" : "引用筛选")
                    + " 第 "
                    + (conditionIndex + 1)
                    + " 条「"
                    + conditionFieldName
                    + "」";
        }
    }

    /**
     * 一个对象的发布结果。action：PUBLISHED / UNCHANGED / FAILED；behind 是发布后仍固定在旧版本的应用（关着自动跟随或跟随失败）， 需要人工同步。
     */
    public record ObjectStep(
            String objectId,
            String objectName,
            String action,
            Integer versionBefore,
            Integer versionAfter,
            List<String> details,
            List<String> behind) {}

    static Map<String, Integer> summarize(List<Row> rows) {
        Map<String, Integer> result = new TreeMap<>();
        for (var row : rows) result.merge(row.status(), 1, Integer::sum);
        return result;
    }
}
