package com.lingan.ucp.nocode.application.service.application;

import com.lingan.ucp.nocode.api.ApplicationCenter;
import com.lingan.ucp.nocode.api.ApplicationFollows;
import com.lingan.ucp.nocode.api.DataCenter;
import com.lingan.ucp.nocode.api.ObjectApplicationUpgrade;
import com.lingan.ucp.nocode.application.dal.dataobject.NocodeApplicationDO;

import java.util.List;

/**
 * 应用自动跟随对象最新版本。
 *
 * <p>应用 × 对象一个开关，默认开、要关单独关。开着时，对象发布（对旧应用兼容的改动）在同一事务里替应用做「同步 + 发布」：
 * 单个应用的失败用保存点隔离，不影响对象发布，也不影响其它应用；没跟上的记为待处理，之后重试。进了暂停名单的应用（不兼容改动）不在这里处理。
 */
public interface ApplicationFollowService {
    /** 人工发布时的提版结果：snapshot 是提版后的快照（没提就是原快照）；prefix 非空时，提版后的校验失败要在报错前面加上它。 */
    record Lift(ApplicationCenter.Snapshot snapshot, String prefix, List<Lifted> lifted) {}

    record Lifted(String objectId, int fromVersion, int toVersion) {}

    /** 人工发布开头：把开着跟随、落后于最新发布版、且在途审批预检通过的对象引用提到最新版本。总开关关着、或本次发布本身就是跟随时原样返回。 */
    Lift lift(NocodeApplicationDO app, ApplicationCenter.Snapshot snapshot);

    /** 人工发布移动发布指针之后：提过版的记入跟随状态与日志，并把草稿里落后的引用同步到刚发布的版本。 */
    void published(
            NocodeApplicationDO app,
            ApplicationCenter.Definition definition,
            Lift lift,
            int applicationVersion,
            long actor);

    /** 对象发布计划阶段的提示项，全部不阻断。 */
    List<DataCenter.Check> followChecks(
            DataCenter.Definition previous,
            DataCenter.Definition proposed,
            List<ObjectApplicationUpgrade.Impact> impacts,
            long actor);

    /** 对象发布执行阶段：对象新版本已生效后调用。逐个应用独立成败，不向外抛任何异常。 */
    void follow(
            DataCenter.Definition previous,
            DataCenter.Definition published,
            String planId,
            long actor,
            String objectReason);

    /** 应用（草稿 ∪ 已发布）引用的每个对象一行。要求应用设计者。 */
    List<ApplicationFollows.ObjectFollow> list(String applicationId, long actor);

    /** 拨开关；从关拨到开且落后时立刻跟随一次。要求应用设计者。 */
    ApplicationFollows.FollowRun toggle(ApplicationFollows.Switch command, long actor);

    /** 立即跟随 / 重试。开关关着时拒绝。要求应用设计者。 */
    ApplicationFollows.FollowRun run(ApplicationFollows.Run command, long actor);

    /** 一次对象发布的跟随结果。 */
    List<ApplicationFollows.FollowResult> result(String planId);

    /** 定时重试待处理的行；返回（处理行数，跟上的行数）。 */
    Retried retryPending(int limit);

    record Retried(int examined, int followed) {}

    /** 迁移工具用：对所有开着跟随且落后的应用各跑一次；dryRun 为真时只列出会跟的，不写库。 */
    List<ApplicationFollows.FollowResult> followBehind(long actor, boolean dryRun);
}
