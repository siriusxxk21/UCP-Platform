package com.richuang.os.nocode.api;

import java.time.LocalDateTime;

/**
 * 应用自动跟随对象最新版本的接口契约。
 *
 * <p>应用 × 对象一个开关，默认开、要关单独关；没有状态行等于默认开且正常跟随。开着时对象一发布（对旧应用兼容的改动），系统替应用做「同步 + 发布」。
 */
public final class ApplicationFollows {
    private ApplicationFollows() {}

    /**
     * 应用引用的一个对象的跟随情况。pinnedVersion 是已发布快照里固定的版本（从没发布过时是草稿里固定的版本）；latestVersion 读不到时为 null；revision
     * 是开关的修订号，没有行时为 0。state、pendingCode 取对应枚举的编码。
     */
    public record ObjectFollow(
            String objectId,
            boolean enabled,
            String state,
            Integer pinnedVersion,
            Integer latestVersion,
            Integer pendingVersion,
            String pendingCode,
            String pendingReason,
            LocalDateTime followedAt,
            int revision) {}

    /** 拨开关。 */
    public record Switch(
            String applicationId, String objectId, Boolean enabled, Integer expectedRevision) {}

    /** 立即跟随 / 重试。 */
    public record Run(String applicationId, String objectId) {}

    /** outcome 取 ApplicationFollowOutcomeEnum 的编码；application 的修订号已更新；draftReason 是草稿没同步上的原因。 */
    public record FollowRun(
            String outcome,
            ObjectFollow follow,
            ApplicationCenter.Detail application,
            boolean draftSynced,
            String draftReason) {}

    /** 一次对象发布里某个应用的跟随结果；outcome 只有 FOLLOWED 或 PENDING。 */
    public record FollowResult(
            String applicationId,
            String applicationName,
            String outcome,
            Integer fromVersion,
            Integer toVersion,
            Integer applicationVersion,
            String reason) {}
}
