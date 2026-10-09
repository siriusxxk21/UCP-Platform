package com.richuang.os.nocode.api;

import java.util.List;

/** 业务办理入口：复用应用发布资源和授权结构，不创建常规任务实例。 */
public final class TaskEntries {
    private TaskEntries() {}

    /** limits 是入口允许范围；成员授权与对象共享授权还需在每次请求取交集。 */
    public record Config(
            String objectId,
            String viewId,
            String formId,
            String mode,
            String category,
            String description,
            String icon,
            int sortOrder,
            List<ApplicationAuthorization.ObjectGrant> limits) {}

    /** 授权与启停单独修订，应用回退不能恢复已经收回的授权。 */
    public record Policy(
            int revision, boolean enabled, List<ApplicationAuthorization.Member> members) {}

    public record SavePolicy(
            String applicationId,
            String entryId,
            int expectedRevision,
            boolean enabled,
            List<ApplicationAuthorization.Member> members) {}

    public record Locator(String applicationId, String entryId, Integer version) {}

    public record Card(
            String applicationId,
            String applicationName,
            String entryId,
            String name,
            String category,
            String description,
            String icon,
            String mode,
            int sortOrder,
            int version) {}

    /** 只返回入口使用的资源，不携带完整应用导航与无关对象。 */
    public record Context(
            Card entry,
            Config config,
            ApplicationRecords.Model model,
            List<ApplicationCenter.Resource> resources) {}

    public record Query(Locator entry, ApplicationRecords.Query query) {}

    public record DraftRef(String id, int revision) {}

    public record Save(Locator entry, ApplicationRecords.Save record, DraftRef draft) {
        public Save(Locator entry, ApplicationRecords.Save record) {
            this(entry, record, null);
        }
    }

    public record ActivityQuery(Locator entry, String before) {}

    /** 本人成功办理摘要；只携带当前可读标题和业务定位，不复制另一份业务数据。 */
    public record Activity(
            String id,
            String time,
            String operation,
            String entryName,
            String applicationName,
            String applicationId,
            String entryId,
            String objectId,
            String recordId,
            String recordName) {}

    public record Activities(List<Activity> items, String next) {}

    public record DraftQuery(
            com.richuang.os.nocode.api.work.WorkDraftViews.Cursor before, int limit) {}

    /** 只展示本人草稿的入口与可恢复状态，不在门户列表返回业务字段值。 */
    public record DraftItem(
            String id,
            Locator entry,
            String entryName,
            String applicationName,
            java.time.LocalDateTime updatedAt,
            boolean available,
            String blockedReason) {}

    public record DraftPage(
            List<DraftItem> items, com.richuang.os.nocode.api.work.WorkDraftViews.Cursor before) {}

    public record Delete(Locator entry, String recordId, String expectedRevision) {}

    public record Get(Locator entry, String recordId) {}

    public record Selection(Locator entry, SelectionFields.Query query) {}

    public record FormFill(Locator entry, FormFills.Query query) {}

    /** 任务入口表单的对象规则求值；配置只从入口固定的应用与对象版本读取。 */
    public record FieldRules(
            Locator entry, com.richuang.os.nocode.api.FieldRules.EvaluateQuery query) {}

    public record ViewModel(Locator entry, String objectId, String viewId) {}

    public record ViewChildren(Locator entry, DataViews.ChildQuery query) {}

    public record Receipt(Locator entry, String requestKey) {}
}
