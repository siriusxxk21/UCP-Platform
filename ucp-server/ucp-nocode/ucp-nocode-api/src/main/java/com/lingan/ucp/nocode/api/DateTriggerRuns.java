package com.lingan.ucp.nocode.api;

import java.util.List;

/** 按日期自动执行的运行情况与「立即按今天执行」：只给应用设计者看，日期均为 yyyy-MM-dd，时刻为系统时区的本地时间。 */
public final class DateTriggerRuns {
    private DateTriggerRuns() {}

    /** 一条处理失败的来源记录。 */
    public record Failure(String sourceRecordId, String message, String at) {}

    /**
     * 一条规则的运行情况：账本与最近一个处理过的业务日的结果。
     *
     * @param closedDate 已封账到哪一天（含）；之后的日期才会处理
     * @param businessDate 最近一次扫描的业务日；从没扫描过为 null
     * @param success 该业务日写了目标的来源记录数
     * @param unchanged 该业务日目标已是这个值（或没找到目标）的来源记录数
     * @param failed 该业务日失败的来源记录数
     * @param failures 该业务日的失败明细（最多 20 条）
     */
    public record Status(
            String resourceId,
            boolean armed,
            String closedDate,
            String lastScanAt,
            String lastTrigger,
            String lastError,
            String businessDate,
            int success,
            int unchanged,
            int failed,
            List<Failure> failures) {}

    public record Run(String id, String resourceId) {}

    /** 一次执行的结果（立即执行时只含今天）。 */
    public record Result(
            String businessDate, int success, int unchanged, int failed, int skipped) {}
}
