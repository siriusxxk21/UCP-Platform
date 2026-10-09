package com.richuang.os.nocode.runtime.service.taskcenter;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.module.system.api.user.AdminUserApi;
import com.richuang.os.module.system.api.user.dto.AdminUserRespDTO;
import com.richuang.os.nocode.api.TaskCenter.*;
import com.richuang.os.nocode.api.TaskClaims;
import com.richuang.os.nocode.runtime.dal.dataobject.TaskInstanceDO;
import com.richuang.os.nocode.runtime.dal.mapper.TaskCenterMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** 可领取列表按真实总任务分页；同组公开编排摘要不授予内容、业务数据和领取权限。 */
@Component
public class TaskClaimGroups {
    public interface Access {
        boolean visible(TaskInstanceDO task, List<TaskInstanceDO> nodes, long actor);
    }

    @Resource private TaskCenterMapper store;
    @Resource private TaskAssignments assignments;
    @Resource private TaskWorkflowProtection workflowProtection;
    @Resource private ObjectMapper json;
    @Resource private AdminUserApi users;
    @Resource private PlatformTransactionManager transactionManager;
    private TransactionTemplate tx;

    @PostConstruct
    void initialize() {
        tx = new TransactionTemplate(transactionManager);
    }

    public PageResult<TaskClaims.Group> page(TaskClaims.Query input, long actor, Access access) {
        assignments.requireActive(actor);
        TaskClaims.Query q = input == null ? new TaskClaims.Query(null, null, null, 1, 20) : input;
        q =
                new TaskClaims.Query(
                        TaskGraph.text(q.search(), "搜索", 200, false),
                        q.urgency(),
                        q.priority(),
                        Math.max(1, q.pageNo()),
                        q.pageSize() <= 0 ? 20 : Math.min(200, q.pageSize()));
        List<TaskClaims.Group> rows = new ArrayList<>();
        List<String> roots =
                store.claimableRoots(q, actor, (q.pageNo() - 1) * q.pageSize(), q.pageSize());
        Map<String, String> workflowReasons = workflowProtection.readOnlyReasons(roots);
        for (String id : roots) {
            List<TaskInstanceDO> nodes = TaskInstanceOrdering.instance(store, json, id);
            TaskInstanceDO root =
                    nodes.stream().filter(n -> n.getId().equals(id)).findFirst().orElse(null);
            if (root == null) continue;
            List<TaskInstanceDO> eligible = eligible(nodes, actor);
            if (eligible.isEmpty()) continue;
            boolean visible = access.visible(root, nodes, actor),
                    canClaim =
                            !workflowReasons.containsKey(id)
                                    && assignments.canClaimGroup(root, nodes, actor);
            boolean rootClaimable =
                    !workflowReasons.containsKey(id)
                            && assignments.canClaim(root, node(root), root, nodes, actor);
            int childCount = (int) eligible.stream().filter(n -> !n.getId().equals(id)).count();
            TaskClaims.Ownership ownership =
                    rootClaimable
                            ? TaskClaims.Ownership.UNCLAIMED
                            : !visible
                                    ? TaskClaims.Ownership.RESTRICTED
                                    : Objects.equals(root.getAssigneeId(), actor)
                                            ? TaskClaims.Ownership.MINE
                                            : root.getAssigneeId() != null
                                                    ? TaskClaims.Ownership.ASSIGNED
                                                    : TaskClaims.Ownership.UNAVAILABLE;
            Map<Long, String> names = new HashMap<>();
            TaskSchedules.Dates dates = TaskSchedules.display(nodes, this::node).get(id);
            rows.add(
                    new TaskClaims.Group(
                            id,
                            root.getTitle(),
                            visible,
                            canClaim,
                            eligible.size(),
                            (int)
                                    eligible.stream()
                                            .filter(
                                                    n ->
                                                            TaskAssignments.mode(node(n))
                                                                    == AssignmentMode.FOLLOW_ROOT)
                                            .count(),
                            rootClaimable ? eligible.size() : 0,
                            ownership,
                            assigneeName(root, names),
                            childCount,
                            !workflowReasons.containsKey(id)
                                            && assignments.canClaimRemaining(root, actor)
                                    ? childCount
                                    : 0,
                            TaskStatuses.groupStatus(root, nodes),
                            dates.start(),
                            dates.end(),
                            node(root).priority(),
                            childrenCount(root, nodes, false),
                            childrenCount(root, nodes, true),
                            anchor(nodes, eligible, actor, access),
                            dates.summary()));
        }
        return new PageResult<>(rows, store.claimableRootCount(q, actor));
    }

    public List<TaskClaims.Item> children(TaskClaims.Root query, long actor, Access access) {
        assignments.requireActive(actor);
        String rootId = rootId(query);
        List<TaskInstanceDO> nodes = TaskInstanceOrdering.instance(store, json, rootId);
        List<TaskInstanceDO> eligible = eligible(nodes, actor);
        if (eligible.isEmpty()) throw invalid("该总任务没有当前可领取项，请刷新列表");
        Set<String> claimableIds =
                new HashSet<>(eligible.stream().map(TaskInstanceDO::getId).toList());
        String anchor = anchor(nodes, eligible, actor, access);
        boolean workflowActive =
                !workflowProtection.readOnlyReasons(List.of(rootId)).containsKey(rootId);
        Map<Long, String> names = new HashMap<>();
        Map<String, TaskSchedules.Dates> dates = TaskSchedules.display(nodes, this::node);
        return nodes.stream()
                .filter(n -> !n.getId().equals(rootId))
                .map(
                        n -> {
                            NodeInput config = node(n);
                            return new TaskClaims.Item(
                                    n.getId(),
                                    rootId,
                                    n.getParentId(),
                                    n.getTitle(),
                                    displayStatus(n, nodes),
                                    n.getAssigneeId(),
                                    TaskAssignments.mode(config),
                                    n.getLockVersion(),
                                    config.urgency(),
                                    config.priority(),
                                    dates.get(n.getId()).start(),
                                    dates.get(n.getId()).end(),
                                    workflowActive && claimableIds.contains(n.getId()),
                                    assigneeName(n, names),
                                    access.visible(n, nodes, actor),
                                    childrenCount(n, nodes, false),
                                    childrenCount(n, nodes, true),
                                    anchor,
                                    dates.get(n.getId()).summary());
                        })
                .toList();
    }

    /** 先选有详情权限的根/节点，再选可领取预览；从不将摘要节点本身升级为详情入口。 */
    private String anchor(
            List<TaskInstanceDO> nodes, List<TaskInstanceDO> eligible, long actor, Access access) {
        return nodes.stream()
                .filter(n -> access.visible(n, nodes, actor))
                .sorted(Comparator.comparing(n -> !n.getId().equals(n.getRootId())))
                .map(TaskInstanceDO::getId)
                .findFirst()
                .orElse(eligible.getFirst().getId());
    }

    private int childrenCount(
            TaskInstanceDO parent, List<TaskInstanceDO> nodes, boolean completed) {
        return (int)
                nodes.stream()
                        .filter(n -> Objects.equals(parent.getId(), n.getParentId()))
                        .filter(n -> !completed || State.COMPLETED.name().equals(n.getStatus()))
                        .count();
    }

    private String displayStatus(TaskInstanceDO task, List<TaskInstanceDO> nodes) {
        return TaskPauses.pausedBy(task, nodes) == null ? task.getStatus() : State.PAUSED.name();
    }

    private String assigneeName(TaskInstanceDO task, Map<Long, String> names) {
        if (task.getAssigneeId() == null)
            return switch (TaskAssignments.mode(node(task))) {
                case OPEN -> "待领取";
                case FOLLOW_ROOT -> "随总负责人（待承接）";
                default -> "待分配";
            };
        return names.computeIfAbsent(
                task.getAssigneeId(),
                id -> {
                    AdminUserRespDTO user = users.getUser(id);
                    return user == null || TaskGraph.blank(user.getNickname()) == null
                            ? Long.toString(id)
                            : user.getNickname();
                });
    }

    public TaskClaims.Preview preview(TaskClaims.Root query, long actor) {
        assignments.requireActive(actor);
        String id = rootId(query);
        return tx.execute(
                status -> {
                    TaskInstanceDO root = store.get(id, true);
                    if (root == null || !root.getId().equals(root.getRootId()))
                        throw invalid("请选择真实总任务");
                    // 根锁后刷新缓存，预览修订与自动承接集合来自同一时刻。
                    root = store.planningTask(id);
                    workflowProtection.requireActive(id);
                    List<TaskInstanceDO> nodes = TaskInstanceOrdering.instance(store, json, id);
                    List<TaskInstanceDO> accepted =
                            assignments.groupClaimItems(
                                    root, nodes, actor, Boolean.TRUE.equals(query.includeOpen()));
                    return new TaskClaims.Preview(
                            id,
                            root.getTitle(),
                            root.getLockVersion(),
                            items(accepted, id, nodes),
                            Objects.equals(root.getAssigneeId(), actor));
                });
    }

    private String rootId(TaskClaims.Root query) {
        return TaskGraph.text(query == null ? null : query.rootId(), "总任务", 64, true);
    }

    private List<TaskInstanceDO> eligible(List<TaskInstanceDO> nodes, long actor) {
        TaskInstanceDO root =
                nodes.stream()
                        .filter(n -> n.getId().equals(n.getRootId()))
                        .findFirst()
                        .orElse(null);
        return nodes.stream()
                .filter(n -> assignments.canClaim(n, node(n), root, nodes, actor))
                .toList();
    }

    private List<TaskClaims.Item> items(
            List<TaskInstanceDO> eligible, String rootId, List<TaskInstanceDO> nodes) {
        Map<String, TaskSchedules.Dates> dates = TaskSchedules.display(nodes, this::node);
        Set<String> visible = new HashSet<>();
        visible.add(rootId);
        eligible.forEach(n -> visible.add(n.getId()));
        return eligible.stream()
                .map(
                        n -> {
                            NodeInput config = node(n);
                            return new TaskClaims.Item(
                                    n.getId(),
                                    rootId,
                                    visible.contains(n.getParentId()) ? n.getParentId() : null,
                                    n.getTitle(),
                                    n.getStatus(),
                                    n.getAssigneeId(),
                                    TaskAssignments.mode(config),
                                    n.getLockVersion(),
                                    config.urgency(),
                                    config.priority(),
                                    dates.get(n.getId()).start(),
                                    dates.get(n.getId()).end(),
                                    true,
                                    null,
                                    false,
                                    0,
                                    0,
                                    n.getId(),
                                    dates.get(n.getId()).summary());
                        })
                .toList();
    }

    private NodeInput node(TaskInstanceDO task) {
        try {
            return json.readValue(task.getConfigJson(), NodeInput.class);
        } catch (Exception ex) {
            throw invalid("任务人员配置无法读取");
        }
    }
}
