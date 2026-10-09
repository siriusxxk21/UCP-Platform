package com.richuang.os.nocode.runtime.service.taskcenter;

import static com.richuang.os.nocode.api.NocodeErrorCodes.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.framework.common.biz.system.permission.PermissionCommonApi;
import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.module.msg.api.*;
import com.richuang.os.module.system.api.user.AdminUserApi;
import com.richuang.os.module.system.api.user.dto.AdminUserRespDTO;
import com.richuang.os.nocode.api.TaskCenter.*;
import com.richuang.os.nocode.api.TaskClaims;
import com.richuang.os.nocode.runtime.dal.dataobject.*;
import com.richuang.os.nocode.runtime.dal.mapper.TaskCenterMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.HtmlUtils;

import java.util.*;

/** 人员安排独立于执行状态；根锁、节点修订和请求收据共同防止重复领取。 */
@Component
public class TaskAssignments {
    @Resource private TaskCenterMapper store;
    @Resource private TaskWorkflowProtection workflowProtection;
    @Resource private ObjectMapper json;
    @Resource private AdminUserApi users;
    @Resource private PermissionCommonApi permissions;
    @Resource private IMsgSendService messages;
    @Resource private PlatformTransactionManager transactionManager;
    private TransactionTemplate tx;

    @PostConstruct
    void initialize() {
        tx = new TransactionTemplate(transactionManager);
    }

    public static AssignmentMode mode(NodeInput node) {
        return node.assignmentMode() == null ? AssignmentMode.ASSIGNED : node.assignmentMode();
    }

    public static List<Long> candidates(NodeInput node) {
        return node.candidateUserIds() == null ? List.of() : node.candidateUserIds();
    }

    public void requireActive(long actor) {
        AdminUserRespDTO user = users.getUser(actor);
        if (actor <= 0 || user == null || !Integer.valueOf(0).equals(user.getStatus()))
            throw invalid("当前成员不存在或已停用，不能领取或分配任务");
    }

    public boolean canClaim(TaskInstanceDO task, NodeInput node, long actor) {
        TaskInstanceDO root = store.get(task.getRootId(), false);
        return canClaim(task, node, root, store.instance(task.getRootId()), actor);
    }

    /** 已读取整组时复用快照，避免可领取目录对每个默认分工重复读取全树。 */
    public boolean canClaim(
            TaskInstanceDO task,
            NodeInput node,
            TaskInstanceDO root,
            List<TaskInstanceDO> nodes,
            long actor) {
        if (!(actor > 0
                && task.getAssigneeId() == null
                && State.PENDING.name().equals(task.getStatus())
                && !Objects.equals(node.acceptorId(), actor)
                && (candidates(node).isEmpty() || candidates(node).contains(actor)))) return false;
        if (root == null || !active(root)) return false;
        if (TaskPauses.pausedBy(task, nodes) != null) return false;
        if (mode(node) == AssignmentMode.OPEN) return true;
        if (mode(node) != AssignmentMode.FOLLOW_ROOT || task.getId().equals(task.getRootId()))
            return false;
        return canClaim(root, node(root), root, nodes, actor) && followsUnassignedRoot(task, nodes);
    }

    /** 安排者与执行者分离；协调子项不赋予整组编排或业务资料权限。 */
    public boolean canCoordinate(
            TaskInstanceDO task,
            TaskInstanceDO root,
            List<TaskInstanceDO> nodes,
            List<TaskHistoryDO> events,
            long actor) {
        if (actor <= 0 || task.getId().equals(root.getId())) return false;
        if (Objects.equals(root.getAssigneeId(), actor)) return true;
        if (!Objects.equals(task.getCreator(), Long.toString(actor))) return false;
        CreationEvidence evidence = creationEvidence(task, events);
        return evidence != null
                && evidence.origin() == CreationOrigin.PERSONAL_SPLIT
                && Objects.equals(evidence.parentId(), task.getParentId())
                && nodes.stream()
                        .anyMatch(
                                parent ->
                                        parent.getId().equals(task.getParentId())
                                                && Objects.equals(parent.getAssigneeId(), actor));
    }

    /** 来源只能由可信创建事件证明，不能把模板或历史未知节点当成员工拆分。 */
    public CreationEvidence creationEvidence(TaskInstanceDO task, List<TaskHistoryDO> events) {
        for (TaskHistoryDO event : events) {
            if (!event.getTaskId().equals(task.getId())
                    || !EventType.CREATED.name().equals(event.getEventType())
                    || !Objects.equals(event.getCreator(), task.getCreator())
                    || event.getMaterialJson() == null) continue;
            try {
                CreationEvidence evidence =
                        json.readValue(event.getMaterialJson(), CreationEvidence.class);
                if (evidence.origin() != null) return evidence;
            } catch (java.io.IOException ignored) {
                // 未知历史材料不构成管理来源证明。
            }
        }
        return null;
    }

    public boolean canDelegate(TaskInstanceDO task, TaskInstanceDO root, long actor) {
        return canDelegate(
                task, root, store.instance(root.getId()), store.events(root.getId()), actor);
    }

    public boolean canDelegate(
            TaskInstanceDO task,
            TaskInstanceDO root,
            List<TaskInstanceDO> nodes,
            List<TaskHistoryDO> events,
            long actor) {
        return active(root)
                && State.PENDING.name().equals(task.getStatus())
                && TaskPauses.pausedBy(task, nodes) == null
                && canCoordinate(task, root, nodes, events, actor);
    }

    /** 在办交接只改变单个子任务人员，不重置进度、时间、暂停或后代分工。 */
    public boolean canTransfer(
            TaskInstanceDO task,
            TaskInstanceDO root,
            List<TaskInstanceDO> nodes,
            List<TaskHistoryDO> events,
            long actor) {
        if (task.getId().equals(root.getId())
                || !active(root)
                || task.getAssigneeId() == null
                || !(State.RUNNING.name().equals(task.getStatus())
                        || State.PAUSED.name().equals(task.getStatus()))) return false;
        String pausedBy = TaskPauses.pausedBy(task, nodes);
        return (pausedBy == null || pausedBy.equals(task.getId()))
                && (manager(root, actor) || canCoordinate(task, root, nodes, events, actor));
    }

    public boolean followsUnassignedRoot(TaskInstanceDO task, List<TaskInstanceDO> nodes) {
        if (task.getId().equals(task.getRootId())) return false;
        Map<String, TaskInstanceDO> index = new HashMap<>();
        nodes.forEach(n -> index.put(n.getId(), n));
        TaskInstanceDO current = task;
        Set<String> visited = new HashSet<>();
        while (current != null
                && !current.getId().equals(task.getRootId())
                && visited.add(current.getId())) {
            if (mode(node(current)) != AssignmentMode.FOLLOW_ROOT
                    || current.getAssigneeId() != null
                    || !State.PENDING.name().equals(current.getStatus())) return false;
            current = index.get(current.getParentId());
        }
        return current != null && current.getId().equals(task.getRootId());
    }

    /** 预览和原子领取使用相同资格；不允许预览承诺把验收人变成负责人。 */
    public boolean canClaimGroup(TaskInstanceDO root, List<TaskInstanceDO> nodes, long actor) {
        return canClaim(root, node(root), root, nodes, actor)
                && nodes.stream()
                        .filter(n -> followsUnassignedRoot(n, nodes))
                        .allMatch(n -> canClaim(n, node(n), root, nodes, actor));
    }

    /** 本人已负责的未结束总任务可补领开放子项，不重复领取总任务，也不扩大单项资格。 */
    public boolean canClaimRemaining(TaskInstanceDO root, long actor) {
        return root != null && active(root) && Objects.equals(root.getAssigneeId(), actor);
    }

    /** 整体领取显式包含本人可领的开放子项；预览和提交复用同一快照筛选，不扩大领取资格。 */
    public List<TaskInstanceDO> groupClaimItems(
            TaskInstanceDO root, List<TaskInstanceDO> nodes, long actor, boolean includeOpen) {
        boolean eligible =
                includeOpen
                        ? canClaim(root, node(root), root, nodes, actor)
                                || canClaimRemaining(root, actor)
                        : canClaimGroup(root, nodes, actor);
        if (!eligible) throw invalid("总任务当前不可整项领取，请展开选择可领取单项或调整分工");
        List<TaskInstanceDO> result =
                nodes.stream()
                        .filter(
                                n ->
                                        includeOpen
                                                ? canClaim(n, node(n), root, nodes, actor)
                                                : n.getId().equals(root.getId())
                                                        || followsUnassignedRoot(n, nodes))
                        .toList();
        if (result.isEmpty()) throw invalid("当前没有可领取的剩余子任务，请刷新列表");
        return result;
    }

    /** 仅沿连续默认分工解析；独立指定/待分配/开放领取分支是屏障，既有已承接人保持原值。 */
    public static List<NodeInput> resolveFollowers(List<NodeInput> graph, Set<String> eligible) {
        NodeInput root = graph.stream().filter(n -> n.parentId() == null).findFirst().orElseThrow();
        if (root.assigneeId() == null) return graph;
        Map<String, NodeInput> index = TaskGraph.index(graph);
        List<NodeInput> result = new ArrayList<>();
        for (NodeInput n : graph) {
            boolean follows =
                    eligible.contains(n.id())
                            && n.assigneeId() == null
                            && mode(n) == AssignmentMode.FOLLOW_ROOT;
            NodeInput parent = n.parentId() == null ? null : index.get(n.parentId());
            Set<String> visited = new HashSet<>();
            while (follows
                    && parent != null
                    && !parent.id().equals(root.id())
                    && visited.add(parent.id())) {
                follows =
                        mode(parent) == AssignmentMode.FOLLOW_ROOT
                                && (parent.assigneeId() == null
                                        || Objects.equals(parent.assigneeId(), root.assigneeId()));
                parent = parent.parentId() == null ? null : index.get(parent.parentId());
            }
            result.add(
                    follows && parent != null && parent.id().equals(root.id())
                            ? assigned(
                                    n,
                                    root.assigneeId(),
                                    AssignmentMode.FOLLOW_ROOT,
                                    List.of(),
                                    n.schedule(),
                                    n.acceptorId())
                            : n);
        }
        return List.copyOf(result);
    }

    /** 只验证本次新增或明确改为跟随的节点，不追溯拒绝既有独立分支下的历史待安排项。 */
    public static void requireResolvedFollowers(List<NodeInput> graph, Set<String> submitted) {
        NodeInput root = graph.stream().filter(n -> n.parentId() == null).findFirst().orElseThrow();
        if (root.assigneeId() != null
                && graph.stream()
                        .anyMatch(
                                n ->
                                        submitted.contains(n.id())
                                                && mode(n) == AssignmentMode.FOLLOW_ROOT
                                                && n.assigneeId() == null))
            throw invalid("此分工位于独立人员安排的分支下，不能自动随总任务；请明确指定负责人、开放领取或暂不分配");
    }

    public String claimGroup(TaskClaims.ClaimGroup command, long actor) {
        if (command == null) throw invalid("缺少整项领取内容");
        TaskInstanceDO root = require(command.rootId());
        if (!root.getId().equals(root.getRootId())) throw invalid("请选择真实总任务");
        return change(
                new Assign(
                        command.rootId(),
                        command.expectedInstanceRevision(),
                        AssignmentMode.ASSIGNED,
                        actor,
                        List.of(),
                        command.requestKey(),
                        null),
                actor,
                true,
                Boolean.TRUE.equals(command.includeOpen()));
    }

    public String claim(Claim command, long actor) {
        if (command == null) throw invalid("缺少领取任务内容");
        return change(
                new Assign(
                        command.id(),
                        command.expectedRevision(),
                        AssignmentMode.ASSIGNED,
                        actor,
                        List.of(),
                        command.requestKey(),
                        null),
                actor,
                true);
    }

    public String assign(Assign command, long actor) {
        if (command == null || command.assignmentMode() == null) throw invalid("请选择人员安排方式");
        if (command.assignmentMode() == AssignmentMode.FOLLOW_ROOT && command.assigneeId() != null)
            throw invalid("随总任务的负责人由总任务解析，请勿另行指定成员");
        return change(command, actor, false);
    }

    private String change(Assign command, long actor, boolean claiming) {
        return change(command, actor, claiming, false);
    }

    private String change(Assign command, long actor, boolean claiming, boolean includeOpen) {
        requireActive(actor);
        String key = TaskGraph.text(command.requestKey(), "请求标识", 120, true);
        String fingerprint =
                cn.hutool.crypto.digest.DigestUtil.sha256Hex(
                        encode(
                                includeOpen
                                        ? Map.of(
                                                "claim",
                                                claiming,
                                                "command",
                                                command,
                                                "includeOpen",
                                                true)
                                        : Map.of("claim", claiming, "command", command)));
        return tx.execute(
                status -> {
                    store.requestLock("task-assignment:" + actor + ":" + key);
                    TaskInstanceDO initial = require(command.id());
                    TaskInstanceDO root = store.get(initial.getRootId(), true);
                    if (root == null) throw invalid("任务实例不存在");
                    workflowProtection.requireActive(root.getId());
                    // 锁前身份读取不可复用 MyBatis 一级缓存，否则并发领取可看见旧负责人/修订。
                    TaskInstanceDO task = store.planningTask(command.id());
                    root = store.get(initial.getRootId(), false);
                    if (task == null || root == null) throw invalid("任务实例不存在");
                    TaskHistoryDO receipt = store.requested(Long.toString(actor), key);
                    if (receipt != null) {
                        if (!Objects.equals(receipt.getRequestHash(), fingerprint)
                                || !task.getId().equals(receipt.getTaskId()))
                            throw invalid("同一请求标识不能用于不同内容");
                        return task.getId();
                    }
                    if (!Objects.equals(task.getLockVersion(), command.expectedRevision()))
                        throw conflict("任务已被其他人修改，请刷新后重试");
                    boolean remainingOnly =
                            claiming
                                    && includeOpen
                                    && task.getId().equals(root.getId())
                                    && canClaimRemaining(root, actor);
                    boolean transferring = !claiming && Boolean.TRUE.equals(command.transfer());
                    if (!State.PENDING.name().equals(task.getStatus())
                            && !remainingOnly
                            && !transferring) throw invalid("仅未开始任务可以领取或重新分配");
                    if (!active(root)) throw invalid("待验收或已结束实例不能领取或重新分配");
                    NodeInput before = node(task);
                    List<TaskInstanceDO> instance = store.instance(task.getRootId());
                    List<TaskHistoryDO> events = store.events(task.getRootId());
                    if (transferring) {
                        if (!canTransfer(task, root, instance, events, actor))
                            throw invalid("仅总负责人、任务管理者或仍负责上级的原拆分人可以转交在办子任务");
                        if (command.assignmentMode() != AssignmentMode.ASSIGNED
                                || command.assigneeId() == null
                                || command.acceptance() != null
                                || command.candidateUserIds() != null
                                        && !command.candidateUserIds().isEmpty())
                            throw invalid("转交在办任务时请指定新负责人，不能改变领取范围或验收配置");
                        if (Objects.equals(task.getAssigneeId(), command.assigneeId()))
                            throw invalid("请选择不同的负责人");
                        TaskGraph.text(command.note(), "交接说明", 1000, true);
                    } else TaskPauses.requireActive(task, instance);
                    if (command.acceptance() != null && !task.getId().equals(root.getId()))
                        throw invalid("仅总任务可以配置验收人");
                    Long acceptor =
                            command.acceptance() == null
                                    ? before.acceptorId()
                                    : command.acceptance().acceptorId();
                    if (claiming) {
                        if (!remainingOnly && !canClaim(task, before, root, instance, actor))
                            throw invalid("任务已被领取、未开放领取或您不在可领取成员范围内");
                        if (task.getId().equals(root.getId())
                                && !includeOpen
                                && !canClaimGroup(root, instance, actor))
                            throw invalid("默认分工的负责人或验收安排存在冲突，请先调整分工");
                    } else if (!transferring && !manager(root, actor)) {
                        if (!canDelegate(task, root, instance, events, actor))
                            throw invalid("仅总负责人或仍负责上级的原拆分人可以调整此子任务的负责人");
                        if (command.acceptance() != null
                                || command.assignmentMode() != AssignmentMode.ASSIGNED
                                        && command.assignmentMode() != AssignmentMode.OPEN)
                            throw invalid("分工管理仅可指定同事或开放领取，不可更改其他人员规则");
                    }
                    Set<String> wholeClaimIds = new HashSet<>();
                    if (includeOpen) {
                        if (!claiming || !task.getId().equals(root.getId()))
                            throw invalid("请选择真实总任务领取");
                        groupClaimItems(root, instance, actor, true).stream()
                                .map(TaskInstanceDO::getId)
                                .forEach(wholeClaimIds::add);
                    }
                    NodeInput next =
                            remainingOnly
                                    ? before
                                    : new NodeInput(
                                            before.id(),
                                            before.parentId(),
                                            before.title(),
                                            before.description(),
                                            command.assigneeId(),
                                            before.urgency(),
                                            before.priority(),
                                            assignmentSchedule(before, task),
                                            before.predecessorIds(),
                                            before.binding(),
                                            before.sharing(),
                                            before.entries(),
                                            command.assignmentMode(),
                                            command.candidateUserIds(),
                                            before.dataPolicy(),
                                            acceptor,
                                            before.effectiveWorkMinutes(),
                                            before.workTotalMode());
                    // 复用节点规范化，但不能把单节点的父子/依赖误当成完整实例校验。
                    List<NodeInput> graph =
                            instance.stream()
                                    .map(
                                            n -> {
                                                NodeInput config = node(n);
                                                if (n.getId().equals(next.id())) return next;
                                                if (!wholeClaimIds.contains(n.getId()))
                                                    return config;
                                                return assigned(
                                                        config,
                                                        actor,
                                                        mode(config) == AssignmentMode.FOLLOW_ROOT
                                                                ? AssignmentMode.FOLLOW_ROOT
                                                                : AssignmentMode.ASSIGNED,
                                                        List.of(),
                                                        assignmentSchedule(config, n),
                                                        config.acceptorId());
                                            })
                                    .toList();
                    List<NodeInput> normalizedGraph = TaskGraph.normalize(graph, actor);
                    Set<String> followIds = new HashSet<>();
                    if (includeOpen) followIds.addAll(wholeClaimIds);
                    else if (task.getId().equals(root.getId())
                            && task.getAssigneeId() == null
                            && next.assigneeId() != null)
                        instance.stream()
                                .filter(n -> followsUnassignedRoot(n, instance))
                                .map(TaskInstanceDO::getId)
                                .forEach(followIds::add);
                    else if (mode(next) == AssignmentMode.FOLLOW_ROOT) followIds.add(task.getId());
                    if (!includeOpen)
                        normalizedGraph = resolveFollowers(normalizedGraph, followIds);
                    normalizedGraph = TaskGraph.normalize(normalizedGraph, actor);
                    if (mode(next) == AssignmentMode.FOLLOW_ROOT)
                        requireResolvedFollowers(normalizedGraph, Set.of(task.getId()));
                    Map<String, NodeInput> normalizedIndex = TaskGraph.index(normalizedGraph);
                    NodeInput normalized =
                            normalizedGraph.stream()
                                    .filter(n -> n.id().equals(next.id()))
                                    .findFirst()
                                    .orElseThrow();
                    if (normalized.assigneeId() != null) requireActive(normalized.assigneeId());
                    if (normalized.acceptorId() != null) requireActive(normalized.acceptorId());
                    users.validateUserList(candidates(normalized));
                    Long previousAssignee = task.getAssigneeId();
                    task.setAssigneeId(normalized.assigneeId());
                    task.setConfigJson(
                            TaskInstanceOrdering.config(
                                    json,
                                    normalized,
                                    TaskInstanceOrdering.displayOrder(json, task)));
                    if (store.updateTask(task, Long.toString(actor)) != 1)
                        throw conflict("任务已被其他人修改，请刷新后重试");
                    if (!Objects.equals(previousAssignee, task.getAssigneeId()))
                        store.removeTaskPlans(task.getId(), Long.toString(actor));
                    for (TaskInstanceDO follower : instance) {
                        if (follower.getId().equals(task.getId())
                                || !followIds.contains(follower.getId())) continue;
                        NodeInput resolved = normalizedIndex.get(follower.getId());
                        if (resolved.assigneeId() == null) continue;
                        follower.setAssigneeId(resolved.assigneeId());
                        follower.setConfigJson(
                                TaskInstanceOrdering.config(
                                        json,
                                        resolved,
                                        TaskInstanceOrdering.displayOrder(json, follower)));
                        if (store.updateTask(follower, Long.toString(actor)) != 1)
                            throw conflict("任务人员安排已变化，请刷新后重试");
                        TaskHistoryDO inherited = new TaskHistoryDO();
                        inherited.setId(UUID.randomUUID().toString());
                        inherited.setTaskId(follower.getId());
                        inherited.setRootId(root.getId());
                        inherited.setEventType(
                                includeOpen ? EventType.CLAIMED.name() : EventType.ASSIGNED.name());
                        inherited.setNote(
                                remainingOnly
                                        ? "总负责人领取剩余子任务"
                                        : includeOpen ? "领取整个任务时一起领取" : "随总任务负责人承接");
                        inherited.setMaterialJson(
                                encode(
                                        Map.of(
                                                "source",
                                                remainingOnly
                                                        ? "REMAINING_TASK_CLAIM"
                                                        : includeOpen
                                                                ? "WHOLE_TASK_CLAIM"
                                                                : AssignmentMode.FOLLOW_ROOT,
                                                "rootId",
                                                root.getId(),
                                                "assigneeId",
                                                resolved.assigneeId())));
                        store.appendEvent(inherited, Long.toString(actor));
                    }
                    if (!root.getId().equals(task.getId())
                            && store.updateTask(root, Long.toString(actor)) != 1)
                        throw conflict("任务实例已被其他人修改，请刷新后重试");
                    TaskHistoryDO event = new TaskHistoryDO();
                    event.setId(UUID.randomUUID().toString());
                    event.setTaskId(task.getId());
                    event.setRootId(task.getRootId());
                    event.setEventType(
                            claiming ? EventType.CLAIMED.name() : EventType.ASSIGNED.name());
                    event.setNote(
                            remainingOnly
                                    ? "领取剩余子任务（" + wholeClaimIds.size() + " 项）"
                                    : claiming
                                            ? "领取任务"
                                            : transferring
                                                    ? "转交在办任务："
                                                            + TaskGraph.text(
                                                                    command.note(),
                                                                    "交接说明",
                                                                    1000,
                                                                    true)
                                                    : TaskGraph.text(
                                                            command.note(), "分配说明", 1000, false));
                    event.setMaterialJson(encode(Map.of("before", before, "after", normalized)));
                    event.setRequestKey(key);
                    event.setRequestHash(fingerprint);
                    store.appendEvent(event, Long.toString(actor));
                    notifyChange(
                            task,
                            root,
                            previousAssignee,
                            actor,
                            claiming,
                            remainingOnly,
                            transferring);
                    return task.getId();
                });
    }

    private static NodeInput assigned(
            NodeInput before,
            Long assignee,
            AssignmentMode mode,
            List<Long> candidates,
            Schedule schedule,
            Long acceptor) {
        return new NodeInput(
                before.id(),
                before.parentId(),
                before.title(),
                before.description(),
                assignee,
                before.urgency(),
                before.priority(),
                schedule,
                before.predecessorIds(),
                before.binding(),
                before.sharing(),
                before.entries(),
                mode,
                candidates,
                before.dataPolicy(),
                acceptor,
                before.effectiveWorkMinutes(),
                before.workTotalMode());
    }

    private boolean manager(TaskInstanceDO root, long actor) {
        return root.getCreator().equals(Long.toString(actor))
                || permissions.hasAnyRoles(actor, "super_admin")
                || permissions.hasAnyPermissions(actor, "nocode:task:manage-all");
    }

    private static boolean active(TaskInstanceDO task) {
        return State.PENDING.name().equals(task.getStatus())
                || State.RUNNING.name().equals(task.getStatus());
    }

    // 领取仅确定负责人。业务和反馈的查看/办理仍由各自实时权限入口校验，不在这里授予权限。

    /** 旧 FIXED 工期在人员安排转为显式协议时封存原截止，不能在后续重算中丢失。 */
    private Schedule assignmentSchedule(NodeInput before, TaskInstanceDO task) {
        Schedule schedule = before.schedule();
        if (before.assignmentMode() == null
                && schedule.mode() == TimeMode.FIXED
                && schedule.fixedEnd() == null
                && task.getExpectedEnd() != null) {
            return new Schedule(
                    schedule.mode(),
                    schedule.fixedStart(),
                    schedule.offsetDays(),
                    schedule.durationDays(),
                    task.getExpectedEnd());
        }
        return schedule;
    }

    private void notifyChange(
            TaskInstanceDO task,
            TaskInstanceDO root,
            Long previous,
            long actor,
            boolean claiming,
            boolean remainingOnly,
            boolean transferring) {
        Set<Long> recipients = new LinkedHashSet<>();
        recipients.add(Long.parseLong(root.getCreator()));
        if (previous != null) recipients.add(previous);
        if (task.getAssigneeId() != null) recipients.add(task.getAssigneeId());
        recipients.remove(actor);
        recipients.removeIf(
                id -> {
                    AdminUserRespDTO user = users.getUser(id);
                    return user == null || !Integer.valueOf(0).equals(user.getStatus());
                });
        if (recipients.isEmpty()) return;
        MsgSendParam message =
                new MsgSendParam()
                        .setMsgCode("nocode-task-assignment")
                        .setTitle(
                                (remainingOnly
                                                ? "剩余子任务已领取："
                                                : claiming
                                                        ? "任务已领取："
                                                        : transferring ? "在办任务已转交：" : "任务人员安排：")
                                        + task.getTitle())
                        .setContent(HtmlUtils.htmlEscape("任务“" + task.getTitle() + "”的人员安排已更新"))
                        .setMsgData(Map.of("taskId", task.getId()))
                        .setSourceType("NOCODE_TASK")
                        .setSourceId(task.getId())
                        .setOwnerId(Long.toString(actor))
                        .setTargets(
                                recipients.stream()
                                        .map(
                                                id ->
                                                        new MsgTarget()
                                                                .setTargetType(MsgTargetType.USER)
                                                                .setTargetId(Long.toString(id)))
                                        .toList());
        if (messages.send(message) == null) throw invalid("任务人员安排提醒未成功保存，请重试");
    }

    /** 删除通知和逻辑删除同事务，避免接收人仍以为有待执行分工。 */
    public void notifyDeleted(TaskInstanceDO task, long actor) {
        Long recipient = task.getAssigneeId();
        if (recipient == null || Objects.equals(recipient, actor)) return;
        AdminUserRespDTO user = users.getUser(recipient);
        if (user == null || !Integer.valueOf(0).equals(user.getStatus())) return;
        MsgSendParam message =
                new MsgSendParam()
                        .setMsgCode("nocode-task-assignment")
                        .setTitle("子任务已删除：" + task.getTitle())
                        .setContent(
                                HtmlUtils.htmlEscape(
                                        "您负责的子任务“" + task.getTitle() + "”已由安排者删除，无需继续办理"))
                        .setMsgData(Map.of("taskId", task.getRootId()))
                        .setSourceType("NOCODE_TASK")
                        .setSourceId(task.getRootId())
                        .setOwnerId(Long.toString(actor))
                        .setTargets(
                                List.of(
                                        new MsgTarget()
                                                .setTargetType(MsgTargetType.USER)
                                                .setTargetId(Long.toString(recipient))));
        if (messages.send(message) == null) throw invalid("子任务删除提醒未成功保存，请重试");
    }

    private TaskInstanceDO require(String id) {
        TaskInstanceDO task = id == null ? null : store.get(id, false);
        if (task == null) throw invalid("任务不存在");
        return task;
    }

    private ServiceException conflict(String message) {
        return new ServiceException(CONFLICT, message);
    }

    private NodeInput node(TaskInstanceDO task) {
        return decode(task.getConfigJson(), NodeInput.class);
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw invalid("任务人员安排无法保存");
        }
    }

    private <T> T decode(String value, Class<T> type) {
        if (value == null) return null;
        try {
            return json.readValue(value, type);
        } catch (Exception e) {
            throw invalid("任务人员安排无法读取");
        }
    }
}
