package com.lingan.ucp.nocode.runtime.service.task;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.*;

import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.application.service.authorization.ApplicationAuthorizationService;
import com.lingan.ucp.nocode.application.service.resource.ApplicationResourceValidator;
import com.lingan.ucp.nocode.application.service.sharing.ObjectGrantValidator;
import com.lingan.ucp.nocode.application.service.sharing.ObjectSharingService;
import com.lingan.ucp.nocode.application.service.task.TaskEntryPolicyService;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.runtime.dal.mapper.RecordHistoryMapper;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.function.Function;

/** 任务入口的固定版本、授权收敛与可信同步作用域；保持原事务和来源校验。 */
@Component
public class TaskEntryRuntimeInvocation {
    @Resource private ApplicationService applications;
    @Resource private ApplicationResourceValidator resources;
    @Resource private ApplicationAuthorizationService authorization;
    @Resource private TaskEntryPolicyService entryPolicies;
    @Resource private ObjectGrantValidator grantValidator;
    @Resource private ObjectSharingService sharing;
    @Resource private DataObjectApi objects;
    @Resource private TaskEntryRuntimeScope scope;
    @Resource private PlatformTransactionManager transactionManager;
    @Resource private RecordHistoryMapper history;

    void requireRelatedContext(Resolved r, RelatedForms.Query context, long actor) {
        if (context == null) throw invalid("缺少任务关联表单上下文");
        requireTarget(r, context.applicationId(), context.objectId());
        if (!Objects.equals(r.config().formId(), context.formId())) throw invalid("任务关联表单已变化");
        if (context.recordId() != null
                && TaskEntryModeEnum.FORM.matches(r.config().mode())
                && !history.createdFromTask(
                        r.invocation().applicationId(),
                        r.config().objectId(),
                        r.resource().id(),
                        context.recordId(),
                        Long.toString(actor))) throw invalid("只能查看本人通过此入口提交的主记录及关联信息");
    }

    record Resolved(
            ApplicationCenter.Published release,
            ApplicationCenter.Resource resource,
            TaskEntries.Config config,
            TaskEntryRuntimeScope.Invocation invocation) {}

    Resolved resolve(TaskEntries.Locator locator, long actor, boolean versionRequired) {
        if (locator == null || actor <= 0 || locator.entryId() == null) throw invalid("请登录并选择任务入口");
        // 仅服务器保存的资源引用可建立固定版本作用域；普通请求仍取当前发布并拒绝陈旧版本。
        ApplicationCenter.Published release = applications.published(locator.applicationId());
        if (versionRequired && locator.version() == null) throw invalid("缺少办理版本，请重新打开入口");
        if (locator.version() != null && locator.version() != release.versionNo())
            throw new ServiceException(CONFLICT, "应用配置已更新，请保留输入并重新打开入口");
        var resource =
                release.definition().resources().stream()
                        .filter(
                                r ->
                                        r.id().equals(locator.entryId())
                                                && ApplicationResourceKindEnum.TASK_ENTRY.matches(
                                                        r.kind()))
                        .findFirst()
                        .orElseThrow(() -> invalid("任务入口不存在或已撤下"));
        var config = resources.decode(resource.config(), TaskEntries.Config.class);
        var policy = entryPolicies.get(locator.applicationId(), locator.entryId());
        if (!policy.enabled()) throw invalid("任务入口已停用或尚未开放");
        Map<String, List<ApplicationAuthorization.ObjectGrant>> effective = new LinkedHashMap<>();
        for (var limit : config.limits()) {
            var ceiling = sharing.storedPermission(limit.objectId(), locator.applicationId());
            if (ceiling == null) continue;
            var candidates =
                    new ArrayList<>(
                            authorization.grants(policy.members(), limit.objectId(), actor));
            // 已有应用权限可在本入口使用，但不能超出入口范围；入口授权永不写回普通成员。
            if (applications.isOwner(locator.applicationId(), actor)) candidates.add(ceiling);
            else
                candidates.addAll(
                        authorization.grants(locator.applicationId(), limit.objectId(), actor));
            var allowed =
                    candidates.stream()
                            .map(
                                    g ->
                                            grantValidator.intersect(
                                                    grantValidator.intersect(g, limit), ceiling))
                            .filter(g -> g.actions().contains(ApplicationActionEnum.READ.getCode()))
                            .toList();
            if (!allowed.isEmpty())
                effective.put(limit.objectId(), resolved(release, limit.objectId(), allowed));
        }
        if (!effective.containsKey(config.objectId())) throw invalid("没有此任务入口的办理权限");
        if (TaskEntryModeEnum.FORM.matches(config.mode())
                && effective.get(config.objectId()).stream()
                        .noneMatch(
                                g -> g.actions().contains(ApplicationActionEnum.CREATE.getCode())))
            throw invalid("没有此入口的新增权限");
        return new Resolved(
                release,
                resource,
                config,
                new TaskEntryRuntimeScope.Invocation(
                        locator.applicationId(),
                        locator.entryId(),
                        release.versionNo(),
                        resource.name(),
                        config.objectId(),
                        actor,
                        Map.copyOf(effective)));
    }

    /** 交集链对「全部」透明；最后对着本发布版本固定的对象版本展开成普通 ID 集合，入口作用域里不再出现哨兵。 */
    private List<ApplicationAuthorization.ObjectGrant> resolved(
            ApplicationCenter.Published release,
            String objectId,
            List<ApplicationAuthorization.ObjectGrant> grants) {
        ApplicationCenter.ObjectReference reference =
                release.definition().objects().stream()
                        .filter(r -> r.objectId().equals(objectId))
                        .findFirst()
                        .orElseThrow(() -> invalid("任务入口的授权对象不属于应用的已发布版本"));
        DataCenter.Definition pinned =
                objects.getVersion(objectId, reference.versionNo()).definition();
        return grants.stream().map(g -> grantValidator.resolve(g, pinned)).toList();
    }

    <T> T execute(
            TaskEntries.Locator entry,
            long actor,
            boolean versionRequired,
            Function<Resolved, T> action) {
        return new TransactionTemplate(transactionManager)
                .execute(
                        status -> {
                            var resolved = resolve(entry, actor, versionRequired);
                            return scope.execute(
                                    resolved.invocation(), () -> action.apply(resolved));
                        });
    }

    void requireList(Resolved r) {
        if (!TaskEntryModeEnum.LIST.matches(r.config().mode())) throw invalid("直接填写入口未开放历史记录查询或维护");
    }

    void requireTarget(Resolved r, String app, String object) {
        if (!r.invocation().applicationId().equals(app) || !r.config().objectId().equals(object))
            throw invalid("任务入口不能切换应用或业务对象");
    }
}
