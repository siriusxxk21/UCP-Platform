package com.lingan.ucp.nocode.api;

import java.util.List;

/**
 * 数据联动「来源变化时自动更新」的总览、预告与回填契约（应用设计器用）。
 *
 * <p>基准：PUBLISHED = 应用当前发布版固定的对象版本；DRAFT = 应用草稿引用的对象版本（发布前预告用）。 回填恒按 PUBLISHED。服务端不记游标、
 * 不记「是否已回填」：是否需要回填的真相只有一个——预告的结果。
 */
public final class LinkageSync {
    public static final String BASIS_PUBLISHED = "PUBLISHED";
    public static final String BASIS_DRAFT = "DRAFT";

    /** 相对比较基准（PUBLISHED：上一个发布版；DRAFT：当前发布版）的变化。 */
    public static final String CHANGE_NEW = "NEW";

    public static final String CHANGE_CHANGED = "CHANGED";
    public static final String CHANGE_UNCHANGED = "UNCHANGED";

    /** 其它应用对同一目标字段口径不同的原因。 */
    public static final String REASON_DIFFERENT_RULE = "DIFFERENT_RULE";

    public static final String REASON_NO_RULE = "NO_RULE";
    public static final String REASON_TARGET_NOT_PINNED = "TARGET_NOT_PINNED";

    private LinkageSync() {}

    /**
     * @param applicationVersion 应用发布版本；DRAFT 基准时为 null
     * @param removed 比较基准里有、现在没有的字段（关闭或删掉了规则）：已落库的值保留不清，只是不再跟随
     */
    public record Overview(
            String applicationId,
            String basis,
            Integer applicationVersion,
            List<Field> fields,
            List<Removed> removed) {}

    /**
     * 一个开启了自动更新的目标字段。
     *
     * @param anchor CURRENT_RECORD（anchorField 在来源对象上）或 RECORD_KEY（anchorField 在目标对象上）
     * @param divergent 其它启用中的已发布应用里，也固定了这个来源对象、但对这个目标字段口径不同的
     */
    public record Field(
            String targetObjectId,
            String targetObjectName,
            int targetObjectVersion,
            String targetFieldId,
            String targetFieldName,
            String sourceObjectId,
            String sourceObjectName,
            String anchor,
            String anchorFieldId,
            String anchorFieldName,
            String signature,
            String change,
            List<Divergent> divergent) {}

    public record Divergent(String applicationId, String applicationName, String reason) {}

    public record Removed(
            String targetObjectId,
            String targetObjectName,
            String targetFieldId,
            String targetFieldName) {}

    /**
     * @param cursor 上一页返回的 nextCursor；第一页为 null
     * @param limit 1..500，缺省 200
     */
    public record PreviewRequest(
            String applicationId,
            String basis,
            String targetObjectId,
            String targetFieldId,
            String cursor,
            Integer limit) {}

    public record Failure(String recordId, String reason) {}

    /**
     * 一页预告：只读，不写任何东西。「将更新 N 条」= 各页 willFill + willClear + willChange 之和。
     *
     * @param total 目标记录总数；只在第一页（cursor 为 null）给，其余页为 null
     * @param failed 无法求值的记录，每页至多 20 条；failedCount 是本页全部失败数
     */
    public record Preview(
            String signature,
            Long total,
            int scanned,
            int unchanged,
            int willFill,
            int willClear,
            int willChange,
            int failedCount,
            List<Failure> failed,
            String nextCursor,
            boolean done) {}

    /**
     * @param signature 来自总览或预告；与当前发布版登记的签名不一致即拒绝（规则已变化，请重新预告）
     * @param limit 1..200，缺省 100
     */
    public record BackfillRequest(
            String applicationId,
            String targetObjectId,
            String targetFieldId,
            String signature,
            String cursor,
            Integer limit) {}

    /** 一页回填：每条记录一个独立事务，单条失败保持旧值、记入 failed，不影响其它记录。 */
    public record Backfill(
            int scanned,
            int updated,
            int unchanged,
            int failedCount,
            List<Failure> failed,
            String nextCursor,
            boolean done) {}
}
