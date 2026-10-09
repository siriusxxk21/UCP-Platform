package com.lingan.ucp.nocode.tools;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 存量授权清单转「全部」的报告（契约 7.3）。dry-run 产出计划，apply / rollback / expand-all 按报告逐行执行并把每行的结果写回 status。
 *
 * <p>报告同时是回滚依据：每一行都带转换前、转换后的原文（对象→应用授权、应用成员授权、任务入口成员授权是整份 JSON；应用草稿只带各任务入口的允许范围）。
 */
public record SelectionAllMigrationReport(
        String tool,
        int formatVersion,
        String command,
        /** 只处理应用编码以它开头的应用；空串为全部。apply 按报告里的这个范围重新核对。 */
        String prefix,
        String executedAt,
        /** 数据库会话时区与 JVM 时区：授权表的保存时间是不带时区的墙上时间，按这个会话时区换算后与对象版本的发布时间比较。 */
        String sessionTimeZone,
        String jvmTimeZone,
        Summary summary,
        List<Row> rows,
        List<String> violations,
        List<String> skipped) {
    public static final String TOOL = "SelectionAllMigrationTool";
    public static final int FORMAT_VERSION = 1;

    /** 行的存放处。 */
    public static final String OBJECT_GRANT = "OBJECT_GRANT";

    public static final String APPLICATION_ACCESS = "APPLICATION_ACCESS";
    public static final String ENTRY_ACCESS = "ENTRY_ACCESS";
    public static final String ENTRY_LIMIT = "ENTRY_LIMIT";

    /** 行的执行状态：计划中、已写入、库里现状与报告不一致而跳过。 */
    public static final String PLANNED = "PLANNED";

    public static final String WRITTEN = "WRITTEN";
    public static final String CONFLICT = "CONFLICT";

    /** expand-all 的清单结果：「全部」被展开成了显式清单。 */
    public static final String EXPANDED = "EXPANDED";

    public record Summary(
            int rows,
            int exact,
            int laterOnly,
            int kept,
            int undetermined,
            int expanded,
            int written,
            int conflicts) {}

    public record Item(String id, String name) {}

    /** 一个清单的转换结果。before、after 是这个清单的原文；gained 是因此新获得权限的项。 */
    public record Change(
            String objectId,
            String objectName,
            String holder,
            String dimension,
            String dimensionName,
            String outcome,
            int removedDead,
            List<Item> gained,
            List<String> before,
            List<String> after) {}

    /**
     * 库里的一行（或应用草稿里的全部入口范围）。store 是存放处；objectId 只在对象→应用授权上有，entryId 只在任务入口成员授权上有。 before、after
     * 是整份原文，执行时逐行核对「库里现状 == before（rollback 时 == after）」。savedAt 是转换前这一行的保存时间
     * （数据库里的墙上时间原文），回滚时一并恢复：「后加」的判据靠它。
     */
    public record Row(
            String store,
            String applicationId,
            String applicationName,
            String objectId,
            String entryId,
            String savedAt,
            JsonNode before,
            JsonNode after,
            List<Change> changes,
            String status) {
        Row withStatus(String value) {
            return new Row(
                    store,
                    applicationId,
                    applicationName,
                    objectId,
                    entryId,
                    savedAt,
                    before,
                    after,
                    changes,
                    value);
        }

        /** 这一行在库里的身份：存放处 + 应用 + 对象 + 入口。 */
        String key() {
            return store + ":" + applicationId + ":" + objectId + ":" + entryId;
        }

        String describe() {
            return store
                    + " 应用 "
                    + applicationName
                    + (objectId == null ? "" : " 对象 " + objectId)
                    + (entryId == null ? "" : " 入口 " + entryId);
        }
    }

    static Summary summarize(List<Row> rows) {
        int exact = 0, later = 0, kept = 0, undetermined = 0, expanded = 0;
        int written = 0, conflicts = 0;
        for (Row row : rows) {
            if (WRITTEN.equals(row.status())) written++;
            if (CONFLICT.equals(row.status())) conflicts++;
            for (Change change : row.changes())
                switch (change.outcome()) {
                    case SelectionAllMigrationPlanner.EXACT -> exact++;
                    case SelectionAllMigrationPlanner.LATER_ONLY -> later++;
                    case SelectionAllMigrationPlanner.UNDETERMINED -> undetermined++;
                    case EXPANDED -> expanded++;
                    default -> kept++;
                }
        }
        return new Summary(
                rows.size(), exact, later, kept, undetermined, expanded, written, conflicts);
    }

    /** 给业务方看的留底清单：只列转成「全部」的清单（恰好等于全部 / 只差后加的项），点名因此新看到或新可改的项。 */
    String retained() {
        List<String> lines = new ArrayList<>();
        lines.add("# 授权清单转「全部」留底清单");
        lines.add("");
        lines.add("执行时间：" + executedAt + "　数据库会话时区：" + sessionTimeZone);
        lines.add("");
        lines.add("| 应用 | 角色 / 用户 / 入口 | 对象 | 哪一项 | 怎么转的 | 因此新看到或新可改的 |");
        lines.add("|---|---|---|---|---|---|");
        for (Row row : rows) {
            if (!WRITTEN.equals(row.status())) continue;
            for (Change change : row.changes()) {
                boolean exact = SelectionAllMigrationPlanner.EXACT.equals(change.outcome());
                if (!exact && !SelectionAllMigrationPlanner.LATER_ONLY.equals(change.outcome()))
                    continue;
                lines.add(
                        "| "
                                + cell(row.applicationName())
                                + " | "
                                + cell(change.holder())
                                + " | "
                                + cell(change.objectName())
                                + " | "
                                + change.dimensionName()
                                + " | "
                                + (exact ? "恰好等于全部" : "只差后加的项")
                                + " | "
                                + (change.gained().isEmpty()
                                        ? "（无）"
                                        : cell(
                                                String.join(
                                                        "、",
                                                        change.gained().stream()
                                                                .map(Item::name)
                                                                .toList())))
                                + " |");
            }
        }
        return String.join("\n", lines) + "\n";
    }

    private static String cell(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }
}
