package com.richuang.os.nocode.api;

import java.util.List;
import java.util.Set;

/** 对象发布与应用维护的跨模块边界；不自动修复或发布应用配置。 */
public interface ObjectApplicationUpgrade {
    /** 必须先于建模锁获取，排空既有业务事务并与应用发布共用锁顺序。 */
    void lock();

    /** 仅列出本次变更会破坏的生效应用；阻断原因包含不可暂停的流程或权限限制。 */
    List<Impact> inspect(
            DataCenter.Definition previous,
            DataCenter.Definition proposed,
            Set<String> clearedFieldIds,
            long actor);

    /**
     * 同一字段只能由一处写入：proposed 中配了数据联动或公式默认值的字段，若同时是已发布应用里生效的自动更新或留存动作的目标字段，
     * 返回逐条可读的冲突说明（阻断对象发布）；没有冲突返回空列表。
     */
    default List<String> ruleWriteConflicts(DataCenter.Definition proposed) {
        return List.of();
    }

    /** 在对象发布事务内暂停已确认应用；失败与结构变更共同回滚。 */
    void suspend(List<Impact> impacts, long actor, String reason);

    /** 自动跟随：计划阶段的提示项（不阻断）——哪些应用会自动跟上、哪些暂时跟不上、新增字段会对谁可见。默认没有。 */
    default List<DataCenter.Check> followChecks(
            DataCenter.Definition previous,
            DataCenter.Definition proposed,
            List<Impact> impacts,
            long actor) {
        return List.of();
    }

    /** 自动跟随：执行阶段，对象新版本已生效后调用，替开着跟随的应用做「同步 + 发布」。逐个应用独立成败，不向外抛出——对象发布成不成功只取决于对象自己。默认什么都不做。 */
    default void follow(
            DataCenter.Definition previous,
            DataCenter.Definition published,
            String planId,
            long actor,
            String objectReason) {}

    /** 修订与发布版本绑定确认内容，不能把旧确认套用于新发布的应用。 */
    record Impact(
            String applicationId,
            String applicationName,
            int revision,
            int applicationVersion,
            int objectVersion,
            List<String> reasons,
            List<String> blockers,
            String route) {}
}
