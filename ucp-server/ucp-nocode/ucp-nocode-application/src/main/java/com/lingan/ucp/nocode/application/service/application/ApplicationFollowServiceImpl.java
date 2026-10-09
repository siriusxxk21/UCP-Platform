package com.lingan.ucp.nocode.application.service.application;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.module.system.api.permission.RoleApi;
import com.lingan.ucp.module.system.api.permission.dto.RoleRespDTO;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.module.system.api.user.dto.AdminUserRespDTO;
import com.lingan.ucp.nocode.api.ApplicationAuthorization;
import com.lingan.ucp.nocode.api.ApplicationCenter;
import com.lingan.ucp.nocode.api.ApplicationFollows;
import com.lingan.ucp.nocode.api.DataCenter;
import com.lingan.ucp.nocode.api.DataObjectApi;
import com.lingan.ucp.nocode.api.FieldDefinition;
import com.lingan.ucp.nocode.api.ObjectApplicationUpgrade;
import com.lingan.ucp.nocode.api.Selections;
import com.lingan.ucp.nocode.application.dal.dataobject.ApplicationObjectFollowDO;
import com.lingan.ucp.nocode.application.dal.dataobject.ApplicationObjectFollowLogDO;
import com.lingan.ucp.nocode.application.dal.dataobject.NocodeApplicationDO;
import com.lingan.ucp.nocode.application.dal.dataobject.NocodeApplicationVersionDO;
import com.lingan.ucp.nocode.application.dal.dataobject.NocodeRecordProcessDO;
import com.lingan.ucp.nocode.application.dal.mapper.ApplicationMapper;
import com.lingan.ucp.nocode.application.dal.mapper.ApplicationObjectFollowMapper;
import com.lingan.ucp.nocode.application.dal.mapper.RecordProcessMapper;
import com.lingan.ucp.nocode.application.service.authorization.ApplicationAuthorizationService;
import com.lingan.ucp.nocode.application.service.resource.ApplicationAutomationCatalog;
import com.lingan.ucp.nocode.application.service.sharing.ObjectGrantValidator;
import com.lingan.ucp.nocode.application.service.sharing.ObjectSharingService;
import com.lingan.ucp.nocode.application.service.task.TaskEntryPolicyService;
import com.lingan.ucp.nocode.enums.ApplicationFollowOutcomeEnum;
import com.lingan.ucp.nocode.enums.ApplicationFollowPendingEnum;
import com.lingan.ucp.nocode.enums.ApplicationFollowPolicyEnum;
import com.lingan.ucp.nocode.enums.ApplicationFollowStateEnum;
import com.lingan.ucp.nocode.enums.ApplicationFollowTriggerEnum;
import com.lingan.ucp.nocode.enums.ApplicationPrincipalEnum;
import com.lingan.ucp.nocode.enums.ApplicationResourceKindEnum;
import com.lingan.ucp.nocode.enums.ApplicationStatusEnum;
import com.lingan.ucp.nocode.enums.HandlingStateEnum;
import com.lingan.ucp.nocode.enums.ObjectStatusEnum;
import com.lingan.ucp.nocode.enums.PublishCheckEnum;
import com.lingan.ucp.nocode.metadata.dal.dataobject.NocodeObjectDO;
import com.lingan.ucp.nocode.metadata.dal.mapper.ObjectDraftMapper;
import com.lingan.ucp.nocode.metadata.service.object.DraftValidator;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 自动跟随的编排：谁该跟、能不能跟、跟的结果记到哪。真正的「同步 + 发布」在 {@link ApplicationService#followObject}，与人工发布走同一条路。
 *
 * <p>事务边界：对象发布事务里逐个应用各打一个保存点，应用的失败回滚到保存点，对象发布与其它应用不受影响。 凡是「试一下、失败也无妨」的步骤都必须让异常从保存点模板里抛出来再接住——
 * 被调用的服务自带参与事务，在里面抛错会把外层事务标成只能回滚，只有回滚保存点才清得掉这个标记；在保存点外面接异常，外层提交时照样失败。
 */
@Service
@Slf4j
public class ApplicationFollowServiceImpl implements ApplicationFollowService {
    private static final List<String> PENDING_HANDLING =
            List.of(
                    HandlingStateEnum.PENDING.getCode(),
                    HandlingStateEnum.APPLY_PENDING.getCode(),
                    HandlingStateEnum.APPLY_FAILED.getCode());
    private static final String SYSTEM_ERROR = "系统错误，请重试；详见日志";
    private static final String STATE_CHANGED = "应用状态或发布版本在跟随过程中已变化，请重试";
    private static final String SUSPENDED_HINT =
            "应用已停用，自动跟随不处理已停用的应用。请点这一行的“同步最新版本”，调整配置并保存，然后“发布并启用”。";
    private static final String DESIGN_LOCK = "nocode-design-write";
    private static final int REASON_LIMIT = 1000;

    @Resource private ApplicationMapper store;
    @Resource private ApplicationObjectFollowMapper follows;
    @Resource private RecordProcessMapper processes;
    @Resource private ApplicationService applications;
    @Resource private ApplicationAutomationCatalog automations;
    @Resource private ApplicationAuthorizationService authorization;
    @Resource private TaskEntryPolicyService entryPolicies;
    @Resource private ObjectSharingService sharing;
    @Resource private ObjectDraftMapper objectHeads;
    @Resource private DataObjectApi objects;
    @Resource private DraftValidator validator;
    @Resource private RoleApi roles;
    @Resource private AdminUserApi users;
    @Resource private ObjectMapper json;
    @Resource private PlatformTransactionManager manager;

    /** 总开关：关着时对象发布、人工发布、定时重试都不做任何跟随，行为与没有这个功能时一致（不用回滚代码就能停）。 */
    @Value("${nocode.follow.enabled:true}")
    private boolean enabled;

    /** 有审批在途的应用怎么办：strict 不跟并记待处理（过渡做法）；relaxed 不做预检（对方放宽两道在途保护之后才能用）。 */
    @Value("${nocode.follow.in-flight-policy:strict}")
    private String inFlightPolicy;

    private TransactionTemplate required;
    private TransactionTemplate savepoint;

    /** 本线程正在替应用跟随：这时发布入口不再提版别的对象，也不重复记状态。 */
    private final ThreadLocal<Boolean> following = new ThreadLocal<>();

    @PostConstruct
    void initialize() {
        required = new TransactionTemplate(manager);
        savepoint = new TransactionTemplate(manager);
        savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    /** 单个应用一次跟随的结果；outcome 为 null 表示没有可做的（开关关着、应用不在范围内）。 */
    private record One(
            String outcome,
            boolean draftSynced,
            String draftReason,
            String pendingCode,
            String reason,
            Integer fromVersion,
            Integer applicationVersion,
            String applicationName) {
        static One none() {
            return new One(null, true, null, null, null, null, null, null);
        }
    }

    private static final class StateChanged extends RuntimeException {
        StateChanged() {
            super(STATE_CHANGED);
        }
    }

    // ── 人工发布时自动提版 ──

    @Override
    public Lift lift(NocodeApplicationDO app, ApplicationCenter.Snapshot snapshot) {
        Lift untouched = new Lift(snapshot, null, List.of());
        if (!enabled
                || Boolean.TRUE.equals(following.get())
                || app.getId() == null
                || snapshot == null
                || snapshot.definition() == null
                || snapshot.definition().objects() == null) return untouched;
        ApplicationCenter.Definition definition = snapshot.definition();
        List<Lifted> lifted = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (ApplicationCenter.ObjectReference reference : snapshot.definition().objects()) {
            if (reference == null) continue;
            Integer latest = latestVersion(reference.objectId());
            if (latest == null || reference.versionNo() >= latest) continue;
            if (!switchedOn(app.getId(), Long.parseLong(reference.objectId()))) continue;
            if (inFlight(app.getId(), reference.objectId()).blocked()) continue;
            DataObjectApi.PublishedObject to = objects.getVersion(reference.objectId(), null);
            definition =
                    ApplicationUpgrader.apply(
                            definition,
                            reference.objectId(),
                            to.versionNo(),
                            to.checksum(),
                            objects.getVersion(reference.objectId(), reference.versionNo())
                                    .definition(),
                            to.definition());
            lifted.add(new Lifted(reference.objectId(), reference.versionNo(), to.versionNo()));
            notes.add("对象「" + to.definition().objectName() + "」已有新版本 V" + to.versionNo());
        }
        if (lifted.isEmpty()) return untouched;
        return new Lift(
                new ApplicationCenter.Snapshot(
                        snapshot.code(),
                        snapshot.name(),
                        snapshot.description(),
                        snapshot.icon(),
                        definition),
                String.join("、", notes) + "，本应用开着自动跟随，按新版本校验未通过：",
                List.copyOf(lifted));
    }

    @Override
    public void published(
            NocodeApplicationDO app,
            ApplicationCenter.Definition definition,
            Lift lift,
            int applicationVersion,
            long actor) {
        if (!enabled || Boolean.TRUE.equals(following.get())) return;
        for (Lifted item : lift.lifted()) {
            long object = Long.parseLong(item.objectId());
            follows.followed(app.getId(), object, item.toVersion(), Long.toString(actor));
            journal(
                    app.getId(),
                    object,
                    item.fromVersion(),
                    item.toVersion(),
                    app.getPublishedVersion(),
                    applicationVersion,
                    ApplicationFollowOutcomeEnum.FOLLOWED,
                    null,
                    null,
                    ApplicationFollowTriggerEnum.APPLICATION_PUBLISH,
                    null,
                    actor);
        }
        // 草稿里落后于刚发布版本的引用同步过去；通不过草稿校验的保持原样。
        Map<String, ApplicationCenter.ObjectReference> released = new LinkedHashMap<>();
        definition.objects().forEach(reference -> released.put(reference.objectId(), reference));
        for (ApplicationCenter.ObjectReference reference :
                applications.read(app.getDesignJson()).objects()) {
            ApplicationCenter.ObjectReference target = released.get(reference.objectId());
            if (target == null
                    || reference.versionNo() >= target.versionNo()
                    || !switchedOn(app.getId(), Long.parseLong(reference.objectId()))) continue;
            applications.syncDraft(
                    app.getId().toString(),
                    reference.objectId(),
                    target.versionNo(),
                    target.checksum(),
                    actor);
        }
    }

    // ── 对象发布：计划阶段的提示 ──

    @Override
    public List<DataCenter.Check> followChecks(
            DataCenter.Definition previous,
            DataCenter.Definition proposed,
            List<ObjectApplicationUpgrade.Impact> impacts,
            long actor) {
        if (!enabled || previous == null || proposed == null) return List.of();
        String objectId = proposed.objectId();
        long object = Long.parseLong(objectId);
        Set<String> suspended = new LinkedHashSet<>();
        if (impacts != null) impacts.forEach(impact -> suspended.add(impact.applicationId()));
        List<NocodeApplicationDO> followers = new ArrayList<>();
        List<DataCenter.Check> waiting = new ArrayList<>();
        for (long id : candidates(object)) {
            if (suspended.contains(Long.toString(id))) continue;
            NocodeApplicationDO app = store.selectById(id);
            if (!runnable(app) || !references(snapshot(app), objectId)) continue;
            if (!switchedOn(id, object)) continue;
            InFlight inFlight = inFlight(id, objectId);
            if (inFlight.blocked()) {
                waiting.add(
                        hint(
                                PublishCheckEnum.APPLICATION_FOLLOW_PENDING,
                                "应用“"
                                        + app.getAppName()
                                        + "”暂时跟不上：还有 "
                                        + inFlight.processes()
                                        + " 条流程审批、"
                                        + inFlight.handlings()
                                        + " 条办理申请没有完结。它继续按当前版本运行；这些审批完结（通过并生效，或由申请人放弃）后自动跟上。"));
                continue;
            }
            followers.add(app);
        }
        List<DataCenter.Check> checks = new ArrayList<>();
        if (!followers.isEmpty()) {
            List<String> names = followers.stream().map(NocodeApplicationDO::getAppName).toList();
            checks.add(
                    hint(
                            PublishCheckEnum.APPLICATION_FOLLOW,
                            "发布后将自动跟随并生效的应用（"
                                    + names.size()
                                    + " 个）："
                                    + String.join("、", names.subList(0, Math.min(names.size(), 10)))
                                    + (names.size() > 10 ? "等" : "")
                                    + "。不需要再到应用里同步、保存、发布。"));
        }
        checks.addAll(waiting);
        List<String> added = addedFields(previous, proposed);
        if (!added.isEmpty() && !followers.isEmpty()) {
            List<String> exposure = new ArrayList<>();
            for (NocodeApplicationDO app : followers)
                exposure.add(app.getAppName() + "：" + exposed(app, objectId));
            checks.add(
                    hint(
                            PublishCheckEnum.FIELD_EXPOSURE,
                            "本次新增字段："
                                    + String.join("、", added)
                                    + "。应用跟随后，这些字段会自动对“可查看字段”选了“全部”的成员可见——"
                                    + String.join("；", exposure)
                                    + "。"));
        }
        long handlings = follows.unresolvedHandlingOfObject(object, PENDING_HANDLING);
        if (handlings > 0)
            checks.add(
                    hint(
                            PublishCheckEnum.HANDLING_IN_FLIGHT,
                            "本对象还有 "
                                    + handlings
                                    + " 条“新增 / 修改须审批”的申请在审批中。对象发布后，这些申请通过时将无法生效，需要申请人重新提交。"));
        return List.copyOf(checks);
    }

    private static DataCenter.Check hint(PublishCheckEnum code, String message) {
        return new DataCenter.Check(code.getCode(), message, false);
    }

    private static List<String> addedFields(
            DataCenter.Definition previous, DataCenter.Definition proposed) {
        Set<String> before =
                new LinkedHashSet<>(ObjectGrantValidator.Universe.of(previous).fields());
        Set<String> after =
                new LinkedHashSet<>(ObjectGrantValidator.Universe.of(proposed).fields());
        List<String> names = new ArrayList<>();
        for (FieldDefinition field : proposed.fields())
            if (after.contains(field.id()) && !before.contains(field.id())) names.add(field.name());
        return names;
    }

    /** 这个应用里谁会自动看到新增字段：对象授予应用的可查看字段不是「全部」时谁都不会；否则是应用创建人，加上成员授权、入口成员授权里可查看字段选了「全部」的角色与用户。 */
    private String exposed(NocodeApplicationDO app, String objectId) {
        String applicationId = app.getId().toString();
        ApplicationAuthorization.ObjectGrant ceiling =
                sharing.storedPermission(objectId, applicationId);
        if (ceiling == null || !Selections.isAll(ceiling.readFields())) return "需在数据权限里勾选后才可见";
        Set<String> names = new LinkedHashSet<>();
        names.add("应用创建人");
        collect(names, authorization.get(applicationId).members(), objectId);
        ApplicationCenter.Snapshot snapshot = snapshot(app);
        if (snapshot != null)
            for (ApplicationCenter.Resource resource : snapshot.definition().resources())
                if (ApplicationResourceKindEnum.TASK_ENTRY.matches(resource.kind()))
                    collect(
                            names,
                            entryPolicies.get(applicationId, resource.id()).members(),
                            objectId);
        return String.join("、", names);
    }

    private void collect(
            Set<String> names, List<ApplicationAuthorization.Member> members, String objectId) {
        for (ApplicationAuthorization.Member member : members) {
            boolean all =
                    member.objects().stream()
                            .anyMatch(
                                    grant ->
                                            objectId.equals(grant.objectId())
                                                    && Selections.isAll(grant.readFields()));
            if (!all || !member.principalId().matches("[1-9][0-9]{0,18}")) continue;
            long principal = Long.parseLong(member.principalId());
            if (ApplicationPrincipalEnum.USER.matches(member.principalKind())) {
                AdminUserRespDTO user = users.getUser(principal);
                names.add(user == null ? "已不存在的用户" : user.getNickname());
            } else {
                RoleRespDTO role = roles.getRole(principal);
                names.add(role == null ? "已不存在的角色" : role.getName());
            }
        }
    }

    // ── 对象发布：执行阶段 ──

    @Override
    public void follow(
            DataCenter.Definition previous,
            DataCenter.Definition published,
            String planId,
            long actor,
            String objectReason) {
        if (!enabled || previous == null || published == null) return;
        String objectId = published.objectId();
        long object = Long.parseLong(objectId);
        DataObjectApi.PublishedObject target;
        List<Long> ids;
        try {
            target = savepoint.execute(status -> objects.getVersion(objectId, null));
            ids = savepoint.execute(status -> candidates(object));
        } catch (RuntimeException failure) {
            log.error("[follow][对象 {} 发布后无法确定要跟随的应用，本次不跟随]", objectId, failure);
            return;
        }
        for (long id : ids) {
            try {
                savepoint.executeWithoutResult(
                        status ->
                                followOne(
                                        id,
                                        objectId,
                                        target,
                                        planId,
                                        actor,
                                        objectReason,
                                        ApplicationFollowTriggerEnum.OBJECT_PUBLISH));
            } catch (RuntimeException failure) {
                // 单个应用的任何意外都回滚到它自己的保存点，对象发布与其它应用照常。
                log.error("[follow][应用 {} 跟随对象 {} 时出错]", id, objectId, failure);
                try {
                    savepoint.executeWithoutResult(
                            status ->
                                    pending(
                                            id,
                                            object,
                                            null,
                                            target.versionNo(),
                                            null,
                                            ApplicationFollowPendingEnum.ERROR,
                                            SYSTEM_ERROR,
                                            ApplicationFollowTriggerEnum.OBJECT_PUBLISH,
                                            planId,
                                            actor));
                } catch (RuntimeException unrecorded) {
                    log.error("[follow][应用 {} 的待处理状态未能写入]", id, unrecorded);
                }
            }
        }
    }

    /** 启用中且已发布的应用，加上草稿引用了这个对象的启用应用；按 ID 升序，顺序确定。 */
    private List<Long> candidates(long object) {
        Set<Long> ids = new TreeSet<>();
        for (String id : store.runnableIds()) ids.add(Long.parseLong(id));
        ids.addAll(follows.draftReferencing(Long.toString(object)));
        return List.copyOf(ids);
    }

    /**
     * 单个应用的跟随。调用方已持有目录独占锁与设计写锁。
     *
     * <p>S0 读已发布快照（快照没引用这个对象 ⇒ 只同步草稿）；S1 已在目标版本或更高 ⇒ 跳过；S2 开关关着 ⇒ 跳过、不记日志；S3 在途审批预检不过 ⇒ 记待处理； S4
     * 打保存点替应用发布，失败回滚保存点并记待处理。
     */
    private One followOne(
            long id,
            String objectId,
            DataObjectApi.PublishedObject target,
            String planId,
            long actor,
            String objectReason,
            ApplicationFollowTriggerEnum trigger) {
        long object = Long.parseLong(objectId);
        NocodeApplicationDO app = store.selectById(id);
        if (app == null || !ApplicationStatusEnum.ACTIVE.matches(app.getStatus()))
            return One.none();
        ApplicationObjectFollowDO row = follows.find(id, object);
        boolean on = row == null || Boolean.TRUE.equals(row.getEnabled());
        ApplicationCenter.Snapshot snapshot =
                app.getPublishedVersion() == null ? null : snapshot(app);
        ApplicationCenter.ObjectReference pinned =
                snapshot == null ? null : reference(snapshot, objectId);
        if (pinned == null) return draftOnly(app, objectId, target, on, actor);
        if (pinned.versionNo() >= target.versionNo()) {
            if (row != null && ApplicationFollowStateEnum.PENDING.matches(row.getState()))
                follows.followed(id, object, pinned.versionNo(), Long.toString(actor));
            DraftSync draft =
                    on ? syncDraft(app, objectId, target, actor) : new DraftSync(true, null);
            return new One(
                    ApplicationFollowOutcomeEnum.UP_TO_DATE.getCode(),
                    draft.synced(),
                    draft.reason(),
                    null,
                    null,
                    pinned.versionNo(),
                    app.getPublishedVersion(),
                    app.getAppName());
        }
        if (!on) return One.none();
        InFlight inFlight = inFlight(id, objectId);
        if (inFlight.blocked()) {
            String reason =
                    "应用里还有 "
                            + inFlight.processes()
                            + " 条流程审批、"
                            + inFlight.handlings()
                            + " 条办理申请没有完结（审批中、审批通过后还没生效、或生效失败还没放弃的都算）。"
                            + "全部完结后系统会自动跟上，也可以点“重试”；生效失败的申请要由申请人放弃或重新提交。";
            return pending(
                    id,
                    object,
                    row,
                    target.versionNo(),
                    pinned.versionNo(),
                    ApplicationFollowPendingEnum.IN_FLIGHT,
                    reason,
                    trigger,
                    planId,
                    actor);
        }
        int before = app.getPublishedVersion();
        String reason =
                limited(
                        "系统跟随：对象「"
                                + target.definition().objectName()
                                + "」V"
                                + pinned.versionNo()
                                + " → V"
                                + target.versionNo()
                                + "。"
                                + Objects.toString(objectReason, ""));
        ApplicationCenter.Detail detail;
        try {
            detail =
                    savepoint.execute(
                            status -> {
                                // 锁应用头并重读：状态或发布版本已变就当作本次没跟上。
                                NocodeApplicationDO locked = store.lock(id, true);
                                if (locked == null
                                        || !ApplicationStatusEnum.ACTIVE.matches(locked.getStatus())
                                        || !Objects.equals(locked.getPublishedVersion(), before))
                                    throw new StateChanged();
                                return scoped(
                                        () ->
                                                applications.followObject(
                                                        Long.toString(id),
                                                        objectId,
                                                        target.versionNo(),
                                                        target.checksum(),
                                                        reason,
                                                        actor));
                            });
        } catch (StateChanged changed) {
            return pending(
                    id,
                    object,
                    row,
                    target.versionNo(),
                    pinned.versionNo(),
                    ApplicationFollowPendingEnum.ERROR,
                    STATE_CHANGED,
                    trigger,
                    planId,
                    actor);
        } catch (ServiceException rejected) {
            return pending(
                    id,
                    object,
                    row,
                    target.versionNo(),
                    pinned.versionNo(),
                    ApplicationFollowPendingEnum.VALIDATION,
                    limited(rejected.getMessage()),
                    trigger,
                    planId,
                    actor);
        } catch (RuntimeException failure) {
            log.error(
                    "[follow][应用 {} 跟随对象 {} 到 V{} 时出错]", id, objectId, target.versionNo(), failure);
            return pending(
                    id,
                    object,
                    row,
                    target.versionNo(),
                    pinned.versionNo(),
                    ApplicationFollowPendingEnum.ERROR,
                    SYSTEM_ERROR,
                    trigger,
                    planId,
                    actor);
        }
        Integer after = detail.application().publishedVersion();
        follows.followed(id, object, target.versionNo(), Long.toString(actor));
        journal(
                id,
                object,
                pinned.versionNo(),
                target.versionNo(),
                before,
                after,
                ApplicationFollowOutcomeEnum.FOLLOWED,
                null,
                null,
                trigger,
                planId,
                actor);
        DraftSync draft = syncDraft(app, objectId, target, actor);
        return new One(
                ApplicationFollowOutcomeEnum.FOLLOWED.getCode(),
                draft.synced(),
                draft.reason(),
                null,
                null,
                pinned.versionNo(),
                after,
                app.getAppName());
    }

    private record DraftSync(boolean synced, String reason) {}

    private DraftSync syncDraft(
            NocodeApplicationDO app,
            String objectId,
            DataObjectApi.PublishedObject target,
            long actor) {
        ApplicationService.DraftSync result =
                applications.syncDraft(
                        app.getId().toString(),
                        objectId,
                        target.versionNo(),
                        target.checksum(),
                        actor);
        return new DraftSync(result.synced(), result.reason());
    }

    /** 已发布快照没有引用这个对象（含从没发布过）：只同步草稿，不建版本，不记待处理。 */
    private One draftOnly(
            NocodeApplicationDO app,
            String objectId,
            DataObjectApi.PublishedObject target,
            boolean on,
            long actor) {
        if (!on) return One.none();
        ApplicationCenter.ObjectReference drafted =
                applications.read(app.getDesignJson()).objects().stream()
                        .filter(reference -> reference.objectId().equals(objectId))
                        .findFirst()
                        .orElse(null);
        if (drafted == null) return One.none();
        if (drafted.versionNo() >= target.versionNo())
            return new One(
                    ApplicationFollowOutcomeEnum.UP_TO_DATE.getCode(),
                    true,
                    null,
                    null,
                    null,
                    drafted.versionNo(),
                    app.getPublishedVersion(),
                    app.getAppName());
        DraftSync draft = syncDraft(app, objectId, target, actor);
        return new One(
                ApplicationFollowOutcomeEnum.DRAFT_ONLY.getCode(),
                draft.synced(),
                draft.reason(),
                null,
                null,
                drafted.versionNo(),
                app.getPublishedVersion(),
                app.getAppName());
    }

    /** 记为待处理并写日志。定时重试时原因没变就不重复写日志。写状态行同时清掉回滚保存点后本会话残留的一级缓存。 */
    private One pending(
            long id,
            long object,
            ApplicationObjectFollowDO previous,
            int toVersion,
            Integer fromVersion,
            ApplicationFollowPendingEnum code,
            String reason,
            ApplicationFollowTriggerEnum trigger,
            String planId,
            long actor) {
        follows.pending(id, object, toVersion, code.getCode(), reason, Long.toString(actor));
        NocodeApplicationDO app = store.selectById(id);
        boolean repeated =
                trigger == ApplicationFollowTriggerEnum.RETRY_JOB
                        && previous != null
                        && ApplicationFollowStateEnum.PENDING.matches(previous.getState())
                        && Objects.equals(previous.getPendingVersion(), toVersion)
                        && code.matches(previous.getPendingCode())
                        && Objects.equals(previous.getPendingReason(), reason);
        Integer pinned = fromVersion;
        if (pinned == null && app != null && app.getPublishedVersion() != null) {
            ApplicationCenter.Snapshot snapshot = snapshot(app);
            ApplicationCenter.ObjectReference reference =
                    snapshot == null ? null : reference(snapshot, Long.toString(object));
            pinned = reference == null ? null : reference.versionNo();
        }
        if (!repeated)
            journal(
                    id,
                    object,
                    pinned == null ? 0 : pinned,
                    toVersion,
                    app == null ? null : app.getPublishedVersion(),
                    null,
                    ApplicationFollowOutcomeEnum.PENDING,
                    code,
                    reason,
                    trigger,
                    planId,
                    actor);
        return new One(
                ApplicationFollowOutcomeEnum.PENDING.getCode(),
                true,
                null,
                code.getCode(),
                reason,
                pinned,
                app == null ? null : app.getPublishedVersion(),
                app == null ? null : app.getAppName());
    }

    private void journal(
            long id,
            long object,
            int fromVersion,
            int toVersion,
            Integer versionBefore,
            Integer versionAfter,
            ApplicationFollowOutcomeEnum outcome,
            ApplicationFollowPendingEnum code,
            String reason,
            ApplicationFollowTriggerEnum trigger,
            String planId,
            long actor) {
        ApplicationObjectFollowLogDO row = new ApplicationObjectFollowLogDO();
        row.setApplicationId(id);
        row.setObjectId(object);
        row.setFromVersion(fromVersion);
        row.setToVersion(toVersion);
        row.setApplicationVersionBefore(versionBefore);
        row.setApplicationVersionAfter(versionAfter);
        row.setOutcome(outcome.getCode());
        row.setPendingCode(code == null ? null : code.getCode());
        row.setReason(reason);
        row.setTriggerKind(trigger.getCode());
        row.setPlanId(planId);
        follows.log(row, Long.toString(actor));
    }

    private <T> T scoped(Supplier<T> action) {
        Boolean previous = following.get();
        following.set(true);
        try {
            return action.get();
        } finally {
            if (previous == null) following.remove();
            else following.set(previous);
        }
    }

    // ── 在途审批 ──

    /** blocked：过渡做法下有在途就不跟。流程审批只看本对象（保护只涉及被切换版本的对象），办理申请看整个应用（应用版本一变它们全部作废）。 */
    private record InFlight(long processes, long handlings, boolean blocked) {}

    private InFlight inFlight(long id, String objectId) {
        if (ApplicationFollowPolicyEnum.RELAXED.matches(inFlightPolicy))
            return new InFlight(0, 0, false);
        long running = 0;
        for (NocodeRecordProcessDO process : processes.active(id))
            if (objectId.equals(Objects.toString(process.getObjectId(), null))) running++;
        long handlings = store.unresolvedHandling(id, PENDING_HANDLING);
        return new InFlight(running, handlings, running + handlings > 0);
    }

    // ── 开关、状态与手工触发 ──

    @Override
    public List<ApplicationFollows.ObjectFollow> list(String applicationId, long actor) {
        applications.requireDesigner(applicationId, actor);
        long id = validator.id(applicationId, "应用 ID");
        return required.execute(status -> rows(id));
    }

    private List<ApplicationFollows.ObjectFollow> rows(long id) {
        NocodeApplicationDO app = store.selectById(id);
        if (app == null) throw new ServiceException(NOT_FOUND, "应用不存在");
        Map<String, Integer> pinned = new LinkedHashMap<>();
        ApplicationCenter.Snapshot snapshot =
                app.getPublishedVersion() == null ? null : snapshot(app);
        if (snapshot != null)
            snapshot.definition()
                    .objects()
                    .forEach(reference -> pinned.put(reference.objectId(), reference.versionNo()));
        applications
                .read(app.getDesignJson())
                .objects()
                .forEach(
                        reference ->
                                pinned.putIfAbsent(reference.objectId(), reference.versionNo()));
        Map<Long, ApplicationObjectFollowDO> states = new LinkedHashMap<>();
        follows.forApplication(id).forEach(row -> states.put(row.getObjectId(), row));
        List<ApplicationFollows.ObjectFollow> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : pinned.entrySet())
            result.add(
                    view(
                            entry.getKey(),
                            entry.getValue(),
                            states.get(Long.parseLong(entry.getKey()))));
        return List.copyOf(result);
    }

    private ApplicationFollows.ObjectFollow view(
            String objectId, Integer pinned, ApplicationObjectFollowDO row) {
        return new ApplicationFollows.ObjectFollow(
                objectId,
                row == null || Boolean.TRUE.equals(row.getEnabled()),
                row == null ? ApplicationFollowStateEnum.FOLLOWING.getCode() : row.getState(),
                pinned,
                latestVersion(objectId),
                row == null ? null : row.getPendingVersion(),
                row == null ? null : row.getPendingCode(),
                row == null ? null : row.getPendingReason(),
                row == null ? null : row.getFollowedAt(),
                row == null ? 0 : row.getLockVersion());
    }

    @Override
    public ApplicationFollows.FollowRun toggle(ApplicationFollows.Switch command, long actor) {
        if (command == null || command.enabled() == null) throw invalid("缺少自动跟随开关");
        applications.requireDesigner(command.applicationId(), actor);
        long id = validator.id(command.applicationId(), "应用 ID");
        long object = validator.id(command.objectId(), "对象");
        int expected = command.expectedRevision() == null ? 0 : command.expectedRevision();
        return required.execute(
                status -> {
                    lockDesign();
                    requireReferenced(id, command.objectId());
                    ApplicationObjectFollowDO row = follows.find(id, object);
                    if ((row == null ? 0 : row.getLockVersion()) != expected)
                        throw new ServiceException(CONFLICT, "自动跟随开关已被修改，请刷新后重试");
                    int written =
                            row == null
                                    ? follows.insertSwitch(
                                            id, object, command.enabled(), Long.toString(actor))
                                    : follows.updateSwitch(
                                            id,
                                            object,
                                            command.enabled(),
                                            expected,
                                            Long.toString(actor));
                    if (written != 1) throw new ServiceException(CONFLICT, "自动跟随开关已被修改，请刷新后重试");
                    One one =
                            enabled && command.enabled()
                                    ? followOne(
                                            id,
                                            command.objectId(),
                                            objects.getVersion(command.objectId(), null),
                                            null,
                                            actor,
                                            null,
                                            ApplicationFollowTriggerEnum.SWITCH_ON)
                                    : One.none();
                    return run(id, command.objectId(), one);
                });
    }

    @Override
    public ApplicationFollows.FollowRun run(ApplicationFollows.Run command, long actor) {
        if (command == null) throw invalid("缺少应用与对象");
        applications.requireDesigner(command.applicationId(), actor);
        long id = validator.id(command.applicationId(), "应用 ID");
        long object = validator.id(command.objectId(), "对象");
        if (!enabled) throw invalid("自动跟随已被系统管理员停用");
        return required.execute(
                status -> {
                    lockDesign();
                    requireReferenced(id, command.objectId());
                    if (!switchedOn(id, object)) throw invalid("请先打开自动跟随");
                    requireActive(id);
                    return run(
                            id,
                            command.objectId(),
                            followOne(
                                    id,
                                    command.objectId(),
                                    objects.getVersion(command.objectId(), null),
                                    null,
                                    actor,
                                    null,
                                    ApplicationFollowTriggerEnum.MANUAL));
                });
    }

    /** 已停用的应用不跟（契约 5.1：进了暂停名单的应用照旧走「同步 → 调整 → 发布并启用」）。人点了「立即跟随」就明确拒绝并说清下一步， 不答一句「已是最新」。 */
    private void requireActive(long id) {
        NocodeApplicationDO app = store.selectById(id);
        if (app != null && !ApplicationStatusEnum.ACTIVE.matches(app.getStatus()))
            throw invalid(SUSPENDED_HINT);
    }

    /** 与应用发布相同的拿锁顺序：目录独占锁 → 设计写锁。 */
    private void lockDesign() {
        automations.lock(true);
        objectHeads.lockTableName(DESIGN_LOCK);
    }

    private void requireReferenced(long id, String objectId) {
        NocodeApplicationDO app = store.lock(id, true);
        if (app == null) throw new ServiceException(NOT_FOUND, "应用不存在");
        boolean drafted =
                applications.read(app.getDesignJson()).objects().stream()
                        .anyMatch(reference -> reference.objectId().equals(objectId));
        boolean released = app.getPublishedVersion() != null && references(snapshot(app), objectId);
        if (!drafted && !released) throw invalid("应用没有引用这个对象");
    }

    private ApplicationFollows.FollowRun run(long id, String objectId, One one) {
        ApplicationFollows.ObjectFollow follow =
                rows(id).stream()
                        .filter(row -> row.objectId().equals(objectId))
                        .findFirst()
                        .orElse(null);
        String outcome = one.outcome();
        // 什么都没做（应用已停用、总开关关着）且确实落后时不谎报「已是最新」：结果留空，界面按状态行显示。
        if (outcome == null && follow != null && follow.enabled() && !behind(follow))
            outcome = ApplicationFollowOutcomeEnum.UP_TO_DATE.getCode();
        return new ApplicationFollows.FollowRun(
                outcome,
                follow,
                applications.get(Long.toString(id)),
                one.draftSynced(),
                one.draftReason());
    }

    private static boolean behind(ApplicationFollows.ObjectFollow follow) {
        return follow.pinnedVersion() != null
                && follow.latestVersion() != null
                && follow.pinnedVersion() < follow.latestVersion();
    }

    @Override
    public List<ApplicationFollows.FollowResult> result(String planId) {
        UUID.fromString(planId);
        return required.execute(
                status ->
                        follows.byPlan(planId).stream()
                                .map(
                                        row ->
                                                new ApplicationFollows.FollowResult(
                                                        row.getApplicationId().toString(),
                                                        row.getApplicationName(),
                                                        row.getOutcome(),
                                                        row.getFromVersion(),
                                                        row.getToVersion(),
                                                        row.getApplicationVersionAfter() != null
                                                                ? row.getApplicationVersionAfter()
                                                                : row.getApplicationVersionBefore(),
                                                        row.getReason()))
                                .toList());
    }

    // ── 定时重试与迁移工具 ──

    @Override
    public Retried retryPending(int limit) {
        if (!enabled) return new Retried(0, 0);
        List<ApplicationObjectFollowDO> rows =
                required.execute(status -> follows.pendingRows(limit));
        int followed = 0;
        for (ApplicationObjectFollowDO row : rows) {
            String objectId = row.getObjectId().toString();
            String updater = Objects.toString(row.getUpdater(), "");
            if (!updater.matches("[1-9][0-9]{0,18}")) continue;
            long actor = Long.parseLong(updater);
            // 先不拿锁看在途：仍在途就不去占独占锁，业务写入不受打扰。
            InFlight inFlight =
                    required.execute(status -> inFlight(row.getApplicationId(), objectId));
            if (inFlight.blocked()) continue;
            try {
                One one =
                        required.execute(
                                status -> {
                                    lockDesign();
                                    if (latestVersion(objectId) == null) return One.none();
                                    return followOne(
                                            row.getApplicationId(),
                                            objectId,
                                            objects.getVersion(objectId, null),
                                            null,
                                            actor,
                                            null,
                                            ApplicationFollowTriggerEnum.RETRY_JOB);
                                });
                if (ApplicationFollowOutcomeEnum.FOLLOWED.matches(one.outcome())) followed++;
            } catch (RuntimeException failure) {
                log.warn(
                        "[retryPending][应用 {} 重试跟随对象 {} 失败]",
                        row.getApplicationId(),
                        objectId,
                        failure);
            }
        }
        return new Retried(rows.size(), followed);
    }

    @Override
    public List<ApplicationFollows.FollowResult> followBehind(long actor, boolean dryRun) {
        if (!enabled) return List.of();
        List<long[]> behind = new ArrayList<>();
        List<ApplicationFollows.FollowResult> result = new ArrayList<>();
        required.executeWithoutResult(
                status -> {
                    for (String id : store.runnableIds()) {
                        NocodeApplicationDO app = store.selectById(Long.parseLong(id));
                        ApplicationCenter.Snapshot snapshot = runnable(app) ? snapshot(app) : null;
                        if (snapshot == null) continue;
                        for (ApplicationCenter.ObjectReference reference :
                                snapshot.definition().objects()) {
                            Integer latest = latestVersion(reference.objectId());
                            long object = Long.parseLong(reference.objectId());
                            if (latest == null
                                    || reference.versionNo() >= latest
                                    || !switchedOn(app.getId(), object)) continue;
                            behind.add(new long[] {app.getId(), object});
                            if (dryRun)
                                result.add(
                                        new ApplicationFollows.FollowResult(
                                                id,
                                                app.getAppName(),
                                                null,
                                                reference.versionNo(),
                                                latest,
                                                app.getPublishedVersion(),
                                                null));
                        }
                    }
                });
        if (dryRun) return List.copyOf(result);
        for (long[] pair : behind) {
            String objectId = Long.toString(pair[1]);
            One one =
                    required.execute(
                            status -> {
                                lockDesign();
                                return followOne(
                                        pair[0],
                                        objectId,
                                        objects.getVersion(objectId, null),
                                        null,
                                        actor,
                                        "迁移工具：对落后的应用补一次跟随",
                                        ApplicationFollowTriggerEnum.MIGRATION);
                            });
            if (one.outcome() != null)
                result.add(
                        new ApplicationFollows.FollowResult(
                                Long.toString(pair[0]),
                                one.applicationName(),
                                one.outcome(),
                                one.fromVersion(),
                                latestVersion(objectId),
                                one.applicationVersion(),
                                one.reason()));
        }
        return List.copyOf(result);
    }

    // ── 工具 ──

    private boolean runnable(NocodeApplicationDO app) {
        return app != null
                && ApplicationStatusEnum.ACTIVE.matches(app.getStatus())
                && app.getPublishedVersion() != null;
    }

    /** 没有行就是默认开。 */
    private boolean switchedOn(long id, long object) {
        ApplicationObjectFollowDO row = follows.find(id, object);
        return row == null || Boolean.TRUE.equals(row.getEnabled());
    }

    /** 对象当前发布版本号；未发布、已停用或不存在时为 null。只看对象头，不调用会抛错的取定义服务。 */
    private Integer latestVersion(String objectId) {
        if (objectId == null || !objectId.matches("[1-9][0-9]{0,18}")) return null;
        NocodeObjectDO head = objectHeads.selectById(Long.parseLong(objectId));
        return head == null || !ObjectStatusEnum.ACTIVE.matches(head.getStatus())
                ? null
                : head.getCurrentPublishedVersionNo();
    }

    private ApplicationCenter.Snapshot snapshot(NocodeApplicationDO app) {
        NocodeApplicationVersionDO version = store.version(app.getId(), app.getPublishedVersion());
        if (version == null) return null;
        try {
            return json.readValue(version.getDefinitionJson(), ApplicationCenter.Snapshot.class);
        } catch (java.io.IOException unreadable) {
            throw invalid("应用发布快照无法读取：" + app.getAppName());
        }
    }

    private static boolean references(ApplicationCenter.Snapshot snapshot, String objectId) {
        return snapshot != null && reference(snapshot, objectId) != null;
    }

    private static ApplicationCenter.ObjectReference reference(
            ApplicationCenter.Snapshot snapshot, String objectId) {
        return snapshot.definition().objects().stream()
                .filter(item -> item.objectId().equals(objectId))
                .findFirst()
                .orElse(null);
    }

    private static String limited(String text) {
        String value = Objects.toString(text, "");
        return value.length() <= REASON_LIMIT ? value : value.substring(0, REASON_LIMIT);
    }
}
