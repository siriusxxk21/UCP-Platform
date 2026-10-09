package com.richuang.os.nocode.runtime.service.taskcenter;

import static com.richuang.os.nocode.api.NocodeErrorCodes.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.framework.common.biz.system.permission.PermissionCommonApi;
import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.module.msg.api.*;
import com.richuang.os.module.system.api.user.AdminUserApi;
import com.richuang.os.module.system.api.user.dto.AdminUserRespDTO;
import com.richuang.os.module.system.service.user.AdminUserService;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.TaskCenter.*;
import com.richuang.os.nocode.application.service.published.ApplicationPublishedService;
import com.richuang.os.nocode.runtime.dal.dataobject.*;
import com.richuang.os.nocode.runtime.dal.mapper.TaskCenterMapper;
import com.richuang.os.nocode.runtime.dal.query.TaskEntryCandidate;
import com.richuang.os.nocode.runtime.dal.query.TaskQueryScope;
import com.richuang.os.nocode.runtime.service.access.ApplicationRuntimePolicy;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.HtmlUtils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** 真实工作任务的事务编排：实例根锁串行变更，节点修订避免覆盖；业务记录沿用公共管线。 */
@Service
public class TaskCenterServiceImpl implements TaskCenterService {
    @Resource private TaskCenterMapper store;
    @Resource private com.richuang.os.nocode.runtime.dal.mapper.TaskWorkEntryMapper workStore;
    @Resource private TaskStandardWork standardWork;
    @Resource private ObjectMapper json;
    @Resource private PlatformTransactionManager transactionManager;
    @Resource private AdminUserApi users;
    @Resource private AdminUserService userDirectory;
    @Resource private PermissionCommonApi permissions;
    @Resource private ApplicationPublishedService published;
    @Resource private ApplicationRuntimePolicy applicationPolicy;
    @Resource private TaskBusiness business;
    @Resource private TaskWorkEntryService workEntries;
    @Resource private TaskDataPolicies dataPolicies;
    @Resource private com.richuang.os.nocode.runtime.service.task.TaskGroupRuntime groupRuntime;
    @Resource private TaskPageQueries pageQueries;
    @Resource private TaskAssignments assignments;
    @Resource private TaskClaimGroups claimGroups;
    @Resource private TaskScheduling scheduling;
    @Resource private TaskChecklists checklists;
    @Resource private TaskWorkflowProtection workflowProtection;
    @Resource private IMsgSendService messages;
    private TransactionTemplate tx;
    private TransactionTemplate projectProbe;

    @Override
    public TaskWorkTimes.Context workTimeContext(String id, long actor) {
        TaskInstanceDO task = require(id);
        List<TaskInstanceDO> nodes = store.instance(task.getRootId());
        if (!nodeVisible(task, nodes, actor)) throw invalid("没有查看此任务的权限");
        TaskInstanceDO root =
                nodes.stream()
                        .filter(n -> n.getId().equals(task.getRootId()))
                        .findFirst()
                        .orElseThrow(() -> invalid("总任务不存在"));
        String reason = workTimeDisabled(root, nodes, actor);
        NodeInput definition = config(root);
        return new TaskWorkTimes.Context(
                root.getId(),
                root.getTitle(),
                root.getLockVersion(),
                definition.workTotalMode() == null
                        ? TaskWorkTimes.TotalMode.MANUAL
                        : definition.workTotalMode(),
                definition.effectiveWorkMinutes(),
                // 员工可读总工时，但调整上下文不得泄露其他节点的完整资源授权配置。
                canManage(nodes, actor) ? workTimeEntries(root, nodes) : List.of(),
                reason == null,
                reason);
    }

    private String workTimeDisabled(TaskInstanceDO root, List<TaskInstanceDO> nodes, long actor) {
        if (!canManage(nodes, actor)) return "只有任务发起人或任务管理者可以调整工时";
        String workflowReason =
                workflowProtection.readOnlyReasons(List.of(root.getId())).get(root.getId());
        if (workflowReason != null) return workflowReason;
        if (!Set.of(State.PENDING.name(), State.RUNNING.name(), State.PAUSED.name())
                .contains(root.getStatus())) return "待验收或已结束任务不能调整工时";
        return null;
    }

    private List<TaskWorkTimes.Entry> workTimeEntries(
            TaskInstanceDO root, List<TaskInstanceDO> nodes) {
        List<TaskWorkTimes.Entry> result = new ArrayList<>();
        boolean unified = dataPolicies.decode(root.getAuthorizationJson()) != null;
        for (TaskInstanceDO node : nodes) {
            if (unified && !node.getId().equals(root.getId())) continue;
            for (TaskWorkEntries.Config entry :
                    config(node).entries() == null
                            ? List.<TaskWorkEntries.Config>of()
                            : config(node).entries()) {
                if (entry.workRule() != null)
                    result.add(new TaskWorkTimes.Entry(node.getId(), node.getTitle(), entry));
            }
        }
        return List.copyOf(result);
    }

    /** 只对白名单工时字段写入；不进入编排调整，不重授权资源，不重算历史事实。 */
    @Override
    public TaskWorkTimes.Context adjustWorkTime(TaskWorkTimes.Change command, long actor) {
        if (command == null || command.workTotalMode() == null || command.entries() == null)
            throw invalid("请填写完整工时安排");
        return tx.execute(
                transaction -> {
                    String key = requestKey(command.requestKey());
                    store.requestLock("task:" + actor + ":" + key);
                    TaskInstanceDO root = lock(command.rootId(), actor);
                    if (!root.getId().equals(root.getRootId())) throw invalid("请调整完整任务的工时");
                    List<TaskInstanceDO> nodes = store.instance(root.getId());
                    if (!canManage(nodes, actor)) throw invalid("只有任务发起人或任务管理者可以调整工时");
                    String fingerprint = hash(command);
                    TaskHistoryDO receipt = store.requested(Long.toString(actor), key);
                    if (receipt != null) {
                        same(receipt.getRequestHash(), fingerprint);
                        if (!EventType.ADJUSTED.name().equals(receipt.getEventType())
                                || !root.getId().equals(receipt.getTaskId()))
                            throw invalid("请求标识已被其他操作使用");
                        return workTimeContext(root.getId(), actor);
                    }
                    revision(root, command.expectedRevision());
                    String disabled = workTimeDisabled(root, nodes, actor);
                    if (disabled != null) throw invalid(disabled);
                    String reason = TaskGraph.text(command.reason(), "调整原因", 1000, true);
                    List<TaskWorkTimes.Entry> entries = workTimeEntries(root, nodes);
                    Map<String, TaskWorkTimes.EntryChange> changes = new LinkedHashMap<>();
                    for (TaskWorkTimes.EntryChange change : command.entries()) {
                        if (change == null
                                || change.taskId() == null
                                || change.entryKey() == null
                                || changes.put(change.taskId() + ":" + change.entryKey(), change)
                                        != null) throw invalid("工时办理项无效或重复");
                    }
                    if (changes.size() != entries.size()) throw invalid("办理项已变化，请刷新后重新调整");
                    Map<String, TaskWorkEntries.WorkRule> newRules = new LinkedHashMap<>();
                    List<TaskWorkEntries.Config> budgetEntries = new ArrayList<>();
                    for (TaskWorkTimes.Entry entry : entries) {
                        String identity = entry.taskId() + ":" + entry.config().key();
                        TaskWorkTimes.EntryChange change = changes.get(identity);
                        if (change == null) throw invalid("办理项已变化，请刷新后重新调整");
                        TaskWorkEntries.WorkRule old = entry.config().workRule();
                        if (change.minutes() < 0 || change.minutes() > 599999)
                            throw invalid("标准工时须为 0 至 599999 分钟");
                        TaskWorkEntries.WorkRule rule =
                                new TaskWorkEntries.WorkRule(
                                        old.mode(),
                                        old.minutes(),
                                        old.quantityFieldId(),
                                        old.conditionFieldId(),
                                        old.conditionValue(),
                                        change.minutes() - old.minutes(),
                                        change.plannedQuantity());
                        standardWork.validate(rule);
                        newRules.put(identity, rule);
                        budgetEntries.add(workConfig(entry.config(), rule));
                    }
                    Integer total =
                            command.workTotalMode() == TaskWorkTimes.TotalMode.AUTO
                                    ? TaskWorkBudgets.total(budgetEntries)
                                    : command.effectiveWorkMinutes();
                    if (total != null && (total < 0 || total > 599999))
                        throw invalid("任务标准总工时须为 0 至 599999 分钟");
                    if (Integer.valueOf(0).equals(total)) total = null;
                    TaskWorkTimes.Context before = workTimeContext(root.getId(), actor);
                    // 兼容升级期间旧服务写入的空快照，包含尚未审批完成的申请。
                    workStore.freezeRules(root.getId());
                    for (TaskWorkTimes.Entry entry : entries) {
                        TaskWorkEntryDO origin =
                                workStore.entry(entry.taskId(), entry.config().key());
                        if (origin == null) throw invalid("办理项已变化，请刷新后重新调整");
                        TaskWorkEntries.WorkRule rule =
                                newRules.get(entry.taskId() + ":" + entry.config().key());
                        for (TaskInstanceDO node : nodes) {
                            for (TaskWorkEntryDO binding : workStore.entries(node.getId())) {
                                if (binding.getId().equals(origin.getId())
                                        || Boolean.TRUE.equals(binding.getInherited())
                                                && binding.getDatasetId()
                                                        .equals(origin.getDatasetId())
                                                && Objects.equals(
                                                        read(
                                                                        binding.getConfigJson(),
                                                                        TaskWorkEntries.Config
                                                                                .class)
                                                                .workRule(),
                                                        entry.config().workRule()))
                                    workStore.changeWorkRule(
                                            binding.getId(), write(rule), Long.toString(actor));
                            }
                        }
                    }
                    for (TaskInstanceDO node : nodes) {
                        NodeInput old = config(node);
                        List<TaskWorkEntries.Config> configured =
                                old.entries() == null
                                        ? null
                                        : old.entries().stream()
                                                .map(
                                                        entry -> {
                                                            TaskWorkEntries.WorkRule rule =
                                                                    newRules.get(
                                                                            node.getId()
                                                                                    + ":"
                                                                                    + entry.key());
                                                            return rule == null
                                                                    ? entry
                                                                    : workConfig(entry, rule);
                                                        })
                                                .toList();
                        NodeInput next =
                                new NodeInput(
                                        old.id(),
                                        old.parentId(),
                                        old.title(),
                                        old.description(),
                                        old.assigneeId(),
                                        old.urgency(),
                                        old.priority(),
                                        old.schedule(),
                                        old.predecessorIds(),
                                        old.binding(),
                                        old.sharing(),
                                        configured,
                                        old.assignmentMode(),
                                        old.candidateUserIds(),
                                        old.dataPolicy(),
                                        old.acceptorId(),
                                        node.getId().equals(root.getId())
                                                ? total
                                                : old.effectiveWorkMinutes(),
                                        node.getId().equals(root.getId())
                                                ? command.workTotalMode()
                                                : old.workTotalMode());
                        if (node.getId().equals(root.getId())) {
                            TaskDataPolicies.FrozenRoot frozen =
                                    dataPolicies.decode(node.getAuthorizationJson());
                            if (frozen != null)
                                node.setAuthorizationJson(
                                        dataPolicies.encode(
                                                new TaskDataPolicies.FrozenRoot(
                                                        next, frozen.grants())));
                        }
                        if (!old.equals(next) || node.getId().equals(root.getId())) {
                            node.setConfigJson(
                                    TaskInstanceOrdering.config(
                                            json,
                                            next,
                                            TaskInstanceOrdering.displayOrder(json, node)));
                            update(node, actor);
                        }
                    }
                    TaskWorkTimes.Context after = workTimeContext(root.getId(), actor);
                    append(
                            root,
                            EventType.ADJUSTED,
                            "调整工时（已计工时保留）：" + reason,
                            Map.of("before", before, "after", after),
                            key,
                            fingerprint,
                            actor);
                    return after;
                });
    }

    private TaskWorkEntries.Config workConfig(
            TaskWorkEntries.Config entry, TaskWorkEntries.WorkRule rule) {
        return new TaskWorkEntries.Config(
                entry.key(),
                entry.name(),
                entry.binding(),
                entry.dataMode(),
                entry.sourceNodeId(),
                entry.sourceEntryKey(),
                entry.readableFieldIds(),
                entry.writableFieldIds(),
                entry.required(),
                entry.allowAll(),
                rule,
                entry.dataScope());
    }

    @PostConstruct
    void initialize() {
        json = json.copy().findAndRegisterModules();
        tx = new TransactionTemplate(transactionManager);
        projectProbe = new TransactionTemplate(transactionManager);
        projectProbe.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    @Override
    public PageResult<Row> page(Query query, long actor) {
        Query normalized = query(query, false);
        return page(normalized, actor, TaskQueryScope.empty());
    }

    @Override
    public PageResult<Row> managementPage(TaskManagement.Query input, long actor) {
        if (input == null) throw invalid("缺少任务管理查询条件");
        if (input.employeeId() != null && input.employeeId() <= 0) throw invalid("员工身份无效");
        if (input.employeeId() == null && input.employeeMetric() != null)
            throw invalid("员工指标需要先选择员工");
        Query normalized = managementQuery(input.query());
        TaskManagement.Query management =
                new TaskManagement.Query(
                        normalized,
                        input.focus() == null ? TaskManagement.Focus.ACTIVE : input.focus(),
                        input.employeeId(),
                        input.employeeMetric() == null
                                ? TaskManagement.EmployeeMetric.ALL
                                : input.employeeMetric(),
                        Boolean.TRUE.equals(input.groupByRoot()));
        // 总览状态是整组状态；搜索、负责人等由可见子节点命中后归根，不要求根本身也匹配。
        // 员工下钻先按本人节点状态匹配，归组只改变分页单位，不能再按根状态排除结果。
        Query seeds =
                input.employeeId() == null ? managementSeedQuery(normalized, null) : normalized;
        // 员工归组搜索允许匹配总任务名称；由 mapper 同时校验本人节点，不能被同事独有子任务误命中。
        if (input.employeeId() != null && Boolean.TRUE.equals(input.groupByRoot())) {
            seeds = managementSeedQuery(normalized, normalized.status(), null);
        }
        boolean admin = admin(actor);
        TaskQueryScope scope = TaskQueryScope.empty();
        List<TaskInstanceDO> rows =
                store.managementPage(
                        management,
                        seeds,
                        actor,
                        admin,
                        (normalized.pageNo() - 1) * normalized.pageSize(),
                        normalized.pageSize(),
                        scope);
        Map<String, List<TaskInstanceDO>> instances = new HashMap<>();
        Map<String, Map<Long, String>> names = new HashMap<>();
        Map<String, List<TaskHistoryDO>> events = new HashMap<>();
        Map<String, Map<String, TaskSchedules.Dates>> schedules = new HashMap<>();
        List<Row> result = new ArrayList<>();
        Map<String, String> workflowReasons = workflowReasons(rows);
        for (TaskInstanceDO task : rows) {
            List<TaskInstanceDO> nodes =
                    instances.computeIfAbsent(task.getRootId(), store::instance);
            result.add(
                    row(
                            task,
                            nodes,
                            actor,
                            names.computeIfAbsent(
                                    task.getRootId(), ignored -> ancestorNames(nodes)),
                            events.computeIfAbsent(task.getRootId(), store::events),
                            schedules.computeIfAbsent(
                                    task.getRootId(),
                                    ignored -> TaskSchedules.display(nodes, this::config)),
                            workflowReasons));
        }
        return new PageResult<>(
                result, store.managementCount(management, seeds, actor, admin, scope));
    }

    @Override
    public PageResult<TaskManagement.Employee> managementEmployees(
            TaskManagement.Employees input, long actor) {
        TaskManagement.Employees employees =
                new TaskManagement.Employees(
                        input == null ? null : TaskGraph.text(input.search(), "员工搜索", 200, false),
                        input == null || input.date() == null ? LocalDate.now() : input.date(),
                        input == null ? 1 : Math.max(1, input.pageNo()),
                        input == null || input.pageSize() <= 0
                                ? 10
                                : Math.min(200, input.pageSize()));
        Query query =
                managementQuery(
                        new Query(
                                "MANAGE",
                                "ALL",
                                employees.date(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                employees.pageNo(),
                                employees.pageSize()));
        boolean admin = admin(actor);
        TaskQueryScope scope = TaskQueryScope.empty();
        return new PageResult<>(
                store.managementEmployees(
                        employees,
                        query,
                        actor,
                        admin,
                        (employees.pageNo() - 1) * employees.pageSize(),
                        employees.pageSize(),
                        scope),
                store.managementEmployeeCount(employees, query, actor, admin, scope));
    }

    private Query managementQuery(Query input) {
        if (input == null)
            return managementQuery(
                    new Query(
                            "MANAGE", "ALL", null, null, null, null, null, null, null, null, null,
                            null, 1, 10));
        if (input.scope() != null && !"MANAGE".equals(input.scope())
                || input.tab() != null && !"ALL".equals(input.tab())
                || input.planFilter() != null
                || input.personalScope() != null) throw invalid("任务管理查询仅支持管理总览，个人清单请使用员工指标");
        return query(managementSeedQuery(input, input.status()), false);
    }

    private Query managementSeedQuery(Query input, String status) {
        return managementSeedQuery(input, status, input.search());
    }

    private Query managementSeedQuery(Query input, String status, String search) {
        return new Query(
                "MANAGE",
                "ALL",
                input.date(),
                search,
                input.category(),
                input.project(),
                status,
                input.urgency(),
                input.priority(),
                input.entryId(),
                input.from(),
                input.to(),
                input.pageNo(),
                input.pageSize() <= 0 ? 10 : input.pageSize(),
                input.recentPeriod(),
                input.kind(),
                input.assignmentMode(),
                false,
                input.assigneeId() == null ? null : TaskPlanning.Scope.TEAM,
                input.assigneeId(),
                null,
                null,
                null);
    }

    @Override
    public PageResult<PersonalTreeNode> personalTreePage(Query input, long actor) {
        Query query = personalTreeQuery(input);
        boolean admin = admin(actor);
        TaskQueryScope scope = TaskQueryScope.empty();
        List<TaskInstanceDO> rows =
                store.personalTreePage(
                        query,
                        actor,
                        admin,
                        (query.pageNo() - 1) * query.pageSize(),
                        query.pageSize(),
                        scope);
        return new PageResult<>(
                personalTreeRows(rows, actor, query),
                store.personalTreeCount(query, actor, admin, scope));
    }

    @Override
    public List<PersonalTreeNode> personalTreeChildren(PersonalTreeChildren input, long actor) {
        if (input == null) throw invalid("缺少个人任务展开条件");
        Query query = personalTreeQuery(input.query());
        String parentId = TaskGraph.text(input.parentId(), "上级任务", 200, true);
        boolean admin = admin(actor);
        TaskQueryScope scope = TaskQueryScope.empty();
        if (!store.personalTreeContains(query, parentId, actor, admin, scope))
            throw invalid("上级任务不在当前筛选的本人任务中，请刷新列表");
        List<TaskInstanceDO> children =
                store.personalTreeChildren(query, parentId, actor, admin, scope);
        if (!children.isEmpty()) {
            Map<String, Integer> positions = new HashMap<>();
            List<TaskInstanceDO> ordered =
                    TaskInstanceOrdering.instance(store, json, children.getFirst().getRootId());
            for (int index = 0; index < ordered.size(); index++)
                positions.put(ordered.get(index).getId(), index);
            children = new ArrayList<>(children);
            children.sort(
                    Comparator.comparingInt(
                            node -> positions.getOrDefault(node.getId(), Integer.MAX_VALUE)));
        }
        return personalTreeRows(children, actor, query);
    }

    private Query personalTreeQuery(Query input) {
        Query normalized = query(input, false);
        if (!"MINE".equals(normalized.scope())
                || !Set.of("TODO", "POOL", "ALL", "TODAY", "WEEK", "MONTH", "DONE", "ACCEPTANCE")
                        .contains(normalized.tab())
                || Boolean.TRUE.equals(normalized.rootsOnly())
                || normalized.scheduleScope() == TaskPlanning.Scope.TEAM
                || normalized.assigneeId() != null) throw invalid("个人任务树仅支持本人任务与个人计划");
        return normalized;
    }

    private List<PersonalTreeNode> personalTreeRows(
            List<TaskInstanceDO> rows, long actor, Query query) {
        Map<String, List<TaskInstanceDO>> instances = new HashMap<>();
        Map<String, Map<Long, String>> contextNames = new HashMap<>();
        Map<String, List<TaskHistoryDO>> instanceEvents = new HashMap<>();
        Map<String, List<StructureNode>> structures = new HashMap<>();
        Map<String, Map<String, TaskSchedules.Dates>> schedules = new HashMap<>();
        Map<String, String> anchors = new HashMap<>();
        List<PersonalTreeNode> result = new ArrayList<>();
        Map<String, String> workflowReasons = workflowReasons(rows);
        for (TaskInstanceDO task : rows) {
            List<TaskInstanceDO> nodes =
                    instances.computeIfAbsent(task.getRootId(), store::instance);
            List<StructureNode> summaries =
                    structures.computeIfAbsent(
                            task.getRootId(),
                            ignored ->
                                    structure(
                                            nodes,
                                            nodes.stream()
                                                    .filter(n -> nodeVisible(n, nodes, actor))
                                                    .map(TaskInstanceDO::getId)
                                                    .collect(java.util.stream.Collectors.toSet()),
                                            contextNames.computeIfAbsent(
                                                    task.getRootId(),
                                                    key -> structureNames(nodes))));
            String anchor =
                    anchors.computeIfAbsent(
                            task.getRootId(),
                            ignored ->
                                    summaries.stream()
                                            .filter(StructureNode::detailVisible)
                                            .sorted(
                                                    Comparator.comparing(
                                                            n -> !n.id().equals(n.rootId())))
                                            .map(StructureNode::id)
                                            .findFirst()
                                            .orElseThrow(() -> invalid("任务权限已变化，请刷新列表")));
            result.add(
                    new PersonalTreeNode(
                            row(
                                    task,
                                    nodes,
                                    actor,
                                    contextNames.computeIfAbsent(
                                            task.getRootId(), ignored -> ancestorNames(nodes)),
                                    instanceEvents.computeIfAbsent(task.getRootId(), store::events),
                                    schedules.computeIfAbsent(
                                            task.getRootId(),
                                            ignored -> TaskSchedules.display(nodes, this::config)),
                                    workflowReasons),
                            task.getMatchingChildCount() == null ? 0 : task.getMatchingChildCount(),
                            !store.matches(
                                    task.getId(),
                                    query,
                                    actor,
                                    admin(actor),
                                    TaskQueryScope.empty()),
                            nodeVisible(task, nodes, actor),
                            (int)
                                    nodes.stream()
                                            .filter(
                                                    n ->
                                                            canAccept(n, actor)
                                                                    || Objects.equals(
                                                                                    n
                                                                                            .getAssigneeId(),
                                                                                    actor)
                                                                            && executable(n)
                                                                            && personalAction(
                                                                                    n, nodes))
                                            .count(),
                            (int)
                                    nodes.stream()
                                            .filter(
                                                    n ->
                                                            Objects.equals(n.getAssigneeId(), actor)
                                                                    && (State.COMPLETED
                                                                                    .name()
                                                                                    .equals(
                                                                                            n
                                                                                                    .getStatus())
                                                                            || State
                                                                                    .PENDING_ACCEPTANCE
                                                                                    .name()
                                                                                    .equals(
                                                                                            n
                                                                                                    .getStatus())))
                                            .filter(
                                                    n ->
                                                            nodes.stream()
                                                                    .noneMatch(
                                                                            c ->
                                                                                    Objects.equals(
                                                                                            c
                                                                                                    .getParentId(),
                                                                                            n
                                                                                                    .getId())))
                                            .count(),
                            task.getCompletedChildCount() == null
                                    ? 0
                                    : task.getCompletedChildCount(),
                            summaries.stream()
                                    .filter(n -> n.id().equals(task.getId()))
                                    .findFirst()
                                    .orElseThrow(),
                            anchor));
        }
        return List.copyOf(result);
    }

    private boolean personalAction(TaskInstanceDO task, List<TaskInstanceDO> nodes) {
        return State.PENDING.name().equals(task.getStatus())
                || nodes.stream()
                        .noneMatch(n -> Objects.equals(n.getParentId(), task.getId()) && !ended(n));
    }

    @Override
    public List<EntryOption> entryOptions(long actor) {
        Map<String, EntryOption> result = new LinkedHashMap<>();
        for (TaskEntryCandidate option : store.entryOptions(actor, admin(actor))) {
            Binding binding = read(option.bindingJson(), Binding.class);
            BusinessRef ref = read(option.businessJson(), BusinessRef.class);
            if (binding == null || ref == null) continue;
            try {
                business.model(ref, binding, actor);
                String value =
                        binding.applicationId()
                                + (binding.entryId() == null
                                        ? ":FORM:" + binding.formId()
                                        : ":ENTRY:" + binding.entryId());
                String name = published.resolve(ref.resource()).name();
                result.putIfAbsent(value, new EntryOption(value, name + " · " + option.label()));
            } catch (ServiceException denied) {
                // 任务参与资格不能让被撤销的业务入口再次出现在筛选候选中。
            }
        }
        return List.copyOf(result.values());
    }

    private PageResult<Row> page(Query query, long actor, TaskQueryScope scope) {
        if ("CLAIMABLE".equals(query.tab())) assignments.requireActive(actor);
        boolean admin = admin(actor);
        List<TaskInstanceDO> rows =
                store.page(
                        query,
                        actor,
                        admin,
                        (query.pageNo() - 1) * query.pageSize(),
                        query.pageSize(),
                        scope);
        Map<String, List<TaskInstanceDO>> instances = new HashMap<>();
        Map<String, Map<Long, String>> contextNames = new HashMap<>();
        List<Row> result = new ArrayList<>();
        Map<String, Map<String, TaskSchedules.Dates>> schedules = new HashMap<>();
        Map<String, String> workflowReasons = workflowReasons(rows);
        for (TaskInstanceDO row : rows) {
            List<TaskInstanceDO> nodes =
                    instances.computeIfAbsent(row.getRootId(), store::instance);
            result.add(
                    row(
                            row,
                            nodes,
                            actor,
                            contextNames.computeIfAbsent(
                                    row.getRootId(), ignored -> ancestorNames(nodes)),
                            store.events(row.getRootId()),
                            schedules.computeIfAbsent(
                                    row.getRootId(),
                                    ignored -> TaskSchedules.display(nodes, this::config)),
                            workflowReasons));
        }
        return new PageResult<>(result, store.count(query, actor, admin, scope));
    }

    @Override
    public PageResult<Row> pageTasks(PageQuery command, long actor) {
        return pageTasks(command, actor, false);
    }

    @Override
    public PageResult<Row> linkCandidates(PageQuery command, long actor) {
        return pageTasks(command, actor, true);
    }

    private PageResult<Row> pageTasks(PageQuery command, long actor, boolean candidates) {
        TaskQueryScope scope = pageQueries.resolve(command, actor, candidates);
        Query input = query(command.query(), true);
        // 页面不能借 MINE 默认值遗漏其他有权查看的项目任务；当前记录上下文始终额外收窄。
        Query scoped =
                new Query(
                        "VISIBLE",
                        input.tab(),
                        input.date(),
                        input.search(),
                        input.category(),
                        input.project(),
                        input.status(),
                        input.urgency(),
                        input.priority(),
                        input.entryId(),
                        input.from(),
                        input.to(),
                        input.pageNo(),
                        input.pageSize(),
                        input.recentPeriod(),
                        input.kind(),
                        input.assignmentMode());
        return page(scoped, actor, scope);
    }

    @Override
    public Detail detail(String id, long actor) {
        TaskInstanceDO task = require(id);
        List<TaskInstanceDO> nodes = TaskInstanceOrdering.instance(store, json, task.getRootId());
        if (!nodeVisible(task, nodes, actor)) {
            assignments.requireActive(actor);
            if (!assignments.canClaim(task, config(task), actor)) throw invalid("没有查看此任务的权限");
            Row summary = row(task, nodes, actor);
            NodeInput config = config(task);
            // 详情预览采用独立白名单；不扩大列表投影、成员目录、业务记录和执行权限。
            DetailPreview preview =
                    new DetailPreview(
                            config.description(),
                            config.priority(),
                            config.schedule(),
                            task.getExpectedStart(),
                            task.getExpectedEnd(),
                            task.getActualStart(),
                            task.getActualEnd(),
                            task.getCreateTime(),
                            config.acceptorId(),
                            name(config.acceptorId()),
                            task.getId().equals(task.getRootId())
                                    ? config.effectiveWorkMinutes()
                                    : null,
                            task.getTemplateVersion());
            return new Detail(
                    summary,
                    List.of(summary),
                    List.of(),
                    List.of(),
                    List.of(),
                    preview,
                    structure(
                            nodes,
                            nodes.stream()
                                    .filter(n -> nodeVisible(n, nodes, actor))
                                    .map(TaskInstanceDO::getId)
                                    .collect(java.util.stream.Collectors.toSet()),
                            structureNames(nodes)));
        }
        List<TaskInstanceDO> visibleNodes =
                nodes.stream().filter(n -> nodeVisible(n, nodes, actor)).toList();
        Set<String> visibleIds =
                new HashSet<>(visibleNodes.stream().map(TaskInstanceDO::getId).toList());
        Map<Long, String> contextNames = structureNames(nodes);
        List<TaskHistoryDO> instanceEvents = store.events(task.getRootId());
        Map<String, TaskSchedules.Dates> schedules = TaskSchedules.display(nodes, this::config);
        Map<String, String> workflowReasons = workflowReasons(nodes);
        return new Detail(
                row(task, nodes, actor, contextNames, instanceEvents, schedules, workflowReasons),
                visibleNodes.stream()
                        .map(
                                n ->
                                        row(
                                                n,
                                                nodes,
                                                actor,
                                                contextNames,
                                                instanceEvents,
                                                schedules,
                                                workflowReasons))
                        .toList(),
                store.comments(task.getRootId()).stream()
                        .filter(c -> visibleIds.contains(c.getTaskId()))
                        .map(this::comment)
                        .toList(),
                instanceEvents.stream()
                        .filter(e -> visibleIds.contains(e.getTaskId()))
                        .map(this::event)
                        .toList(),
                links(task.getId(), actor),
                null,
                structure(nodes, visibleIds, contextNames));
    }

    /** 使用实例当前结构而非模板版本；白名单摘要与可查看内容的节点集合严格分离。 */
    private List<StructureNode> structure(
            List<TaskInstanceDO> nodes, Set<String> visibleIds, Map<Long, String> names) {
        Map<String, TaskSchedules.Dates> dates = TaskSchedules.display(nodes, this::config);
        return nodes.stream()
                .map(
                        node ->
                                new StructureNode(
                                        node.getId(),
                                        node.getRootId(),
                                        node.getParentId(),
                                        node.getTitle(),
                                        TaskPauses.pausedBy(node, nodes) == null
                                                ? node.getStatus()
                                                : State.PAUSED.name(),
                                        node.getAssigneeId() == null
                                                ? switch (TaskAssignments.mode(config(node))) {
                                                    case OPEN -> "待领取";
                                                    case FOLLOW_ROOT -> "随总负责人（待承接）";
                                                    default -> "待分配";
                                                }
                                                : names.getOrDefault(
                                                        node.getAssigneeId(),
                                                        Long.toString(node.getAssigneeId())),
                                        config(node).predecessorIds(),
                                        dates.get(node.getId()).start(),
                                        dates.get(node.getId()).end(),
                                        visibleIds.contains(node.getId()),
                                        dates.get(node.getId()).summary()))
                .toList();
    }

    /** 详情一次批量读取同组负责人名称，供结构摘要、上级和前置等待提示共用。 */
    private Map<Long, String> structureNames(List<TaskInstanceDO> nodes) {
        Set<Long> ids = new HashSet<>();
        nodes.forEach(
                node -> {
                    if (node.getAssigneeId() != null) ids.add(node.getAssigneeId());
                });
        Map<Long, String> names = new HashMap<>();
        if (!ids.isEmpty())
            users.getUserList(ids)
                    .forEach(
                            user -> {
                                String nickname = TaskGraph.blank(user.getNickname());
                                names.put(
                                        user.getId(),
                                        nickname == null ? Long.toString(user.getId()) : nickname);
                            });
        return names;
    }

    @Override
    public TaskGuidance.Readiness readiness(String id, long actor) {
        TaskInstanceDO task = require(id);
        List<TaskInstanceDO> nodes = store.instance(task.getRootId());
        // 开放领取的摘要不授予整树及业务材料预检权限。
        if (!nodeVisible(task, nodes, actor)) throw invalid("没有查看此任务的权限");
        AdminUserRespDTO user = users.getUser(actor);
        if (user == null || !Integer.valueOf(0).equals(user.getStatus()))
            throw invalid("当前账号已停用或不可用");
        String pauseReason = TaskPauses.reason(task, nodes);
        boolean running = State.RUNNING.name().equals(task.getStatus()) && pauseReason == null;
        boolean assignee = Objects.equals(task.getAssigneeId(), actor);
        boolean childrenEnded =
                nodes.stream().noneMatch(n -> Objects.equals(n.getParentId(), id) && !ended(n));
        List<TaskGuidance.Check> checks = new ArrayList<>();
        checks.add(
                new TaskGuidance.Check(
                        TaskGuidance.CheckCode.STATE,
                        "任务执行状态",
                        running,
                        running
                                ? null
                                : pauseReason != null
                                        ? pauseReason
                                        : ended(task)
                                                ? "任务已经结束，不能再次完成"
                                                : State.PENDING_ACCEPTANCE
                                                                .name()
                                                                .equals(task.getStatus())
                                                        ? "任务正在等待验收"
                                                        : "请先开始执行任务",
                        null));
        checks.add(
                new TaskGuidance.Check(
                        TaskGuidance.CheckCode.ASSIGNEE,
                        "当前负责人",
                        assignee,
                        assignee
                                ? null
                                : task.getAssigneeId() == null ? "请先领取或分配负责人" : "只有当前负责人可以完成任务",
                        null));
        checks.add(
                new TaskGuidance.Check(
                        TaskGuidance.CheckCode.CHILDREN,
                        "子任务已结束",
                        childrenEnded,
                        childrenEnded ? null : "还有未结束的子任务，请先完成或取消子任务",
                        null));
        List<TaskInstanceDO> cancelledChildren =
                nodes.stream()
                        .filter(
                                n ->
                                        Objects.equals(n.getParentId(), id)
                                                && State.CANCELLED.name().equals(n.getStatus()))
                        .toList();
        if (!cancelledChildren.isEmpty()) {
            List<String> visibleTitles =
                    cancelledChildren.stream()
                            .filter(n -> nodeVisible(n, nodes, actor))
                            .map(TaskInstanceDO::getTitle)
                            .toList();
            checks.add(
                    new TaskGuidance.Check(
                            TaskGuidance.CheckCode.CHILDREN_CANCELLED,
                            "确认取消后的交付范围",
                            false,
                            (visibleTitles.isEmpty()
                                            ? "存在已取消子任务"
                                            : "已取消：" + String.join("、", visibleTitles))
                                    + "。请确认剩余工作可以交付，并填写交付说明",
                            null));
        }
        if (!unifiedData(task, nodes)) {
            BusinessRef ref = binding(task, nodes);
            if (ref != null) {
                String reason = null;
                try {
                    ref = business.refresh(ref, actor);
                    if (ref.requestId() != null) reason = "业务申请尚未审批生效，请先处理业务申请";
                    else if (ref.recordId() == null) reason = "请先填写并保存任务业务数据";
                    else business.read(ref, effectiveConfig(task, nodes).binding(), actor);
                } catch (ServiceException denied) {
                    reason = "业务数据当前不可访问，请联系任务负责人核对权限和资源状态";
                }
                checks.add(
                        new TaskGuidance.Check(
                                TaskGuidance.CheckCode.BUSINESS,
                                "业务数据",
                                reason == null,
                                reason,
                                null));
            }
        }
        // 不开启跨入口事务；每次公共业务读取独立完成后，再转为只读检查结果。
        checks.addAll(workEntries.completionChecks(id, actor));
        String cancelBlocked =
                ended(task)
                        ? "任务已经结束，不能取消"
                        : !assignee && !canManage(nodes, actor)
                                ? "只有当前负责人、发起人或任务管理者可以取消任务"
                                : !childrenEnded ? "请先结束子任务再取消父任务" : null;
        Set<String> visibleIds =
                new HashSet<>(
                        nodes.stream()
                                .filter(n -> nodeVisible(n, nodes, actor))
                                .map(TaskInstanceDO::getId)
                                .toList());
        Set<String> unfinished =
                new HashSet<>(
                        nodes.stream().filter(n -> !ended(n)).map(TaskInstanceDO::getId).toList());
        List<TaskGuidance.CancellationImpact> impacts =
                ended(task)
                        ? List.of()
                        : TaskCancellationImpact.inspect(
                                        id, nodes.stream().map(this::config).toList(), unfinished)
                                .stream()
                                .filter(impact -> visibleIds.contains(impact.taskId()))
                                .toList();
        return new TaskGuidance.Readiness(
                id,
                task.getLockVersion(),
                checks.stream().allMatch(TaskGuidance.Check::passed),
                checks,
                cancelBlocked == null,
                cancelBlocked,
                impacts);
    }

    @Override
    public Detail claim(Claim command, long actor) {
        return detail(assignments.claim(command, actor), actor);
    }

    @Override
    public Detail assign(Assign command, long actor) {
        TaskInstanceDO task = require(assignments.assign(command, actor));
        List<TaskInstanceDO> nodes = store.instance(task.getRootId());
        // 受限转交后不再拥有子项详情权，返回仍有权查看的总任务，不能泄漏已转交资料。
        return detail(
                nodeVisible(task, nodes, actor)
                        ? task.getId()
                        : nodes.stream()
                                .filter(node -> nodeVisible(node, nodes, actor))
                                .map(TaskInstanceDO::getId)
                                .findFirst()
                                .orElseThrow(() -> invalid("任务权限已变化，请刷新列表")),
                actor);
    }

    @Override
    public PageResult<TaskClaims.Group> claimableGroups(TaskClaims.Query query, long actor) {
        return claimGroups.page(query, actor, this::nodeVisible);
    }

    @Override
    public List<TaskClaims.Item> claimableChildren(TaskClaims.Root query, long actor) {
        return claimGroups.children(query, actor, this::nodeVisible);
    }

    @Override
    public TaskClaims.Preview claimPreview(TaskClaims.Root query, long actor) {
        return claimGroups.preview(query, actor);
    }

    @Override
    public Detail claimGroup(TaskClaims.ClaimGroup command, long actor) {
        return detail(assignments.claimGroup(command, actor), actor);
    }

    @Override
    public Detail linkRecord(LinkTask command, long actor) {
        if (command == null) throw invalid("缺少任务关联内容");
        String key = requestKey(command.requestKey());
        String fingerprint = hash(command);
        return tx.execute(
                status -> {
                    RecordRef record = pageQueries.context(command.context(), actor);
                    TaskInstanceDO task = lock(command.taskId(), actor);
                    List<TaskInstanceDO> nodes = store.instance(task.getRootId());
                    if (!canLink(task, nodes, actor)) throw invalid("只有任务负责人或管理者可以关联业务记录");
                    TaskHistoryDO receipt = store.requested(Long.toString(actor), key);
                    if (receipt != null) {
                        same(receipt.getRequestHash(), fingerprint);
                        return detail(task.getId(), actor);
                    }
                    revision(task, command.expectedRevision());
                    TaskRecordLinkDO existing = store.recordLink(task.getId(), record);
                    if (command.include()) {
                        if (existing == null) {
                            if (inherentLink(task, nodes, record))
                                throw invalid("此记录已通过项目或办理材料关联，无需重复关联");
                            RecordContext context = command.context();
                            TaskQueryScope candidates =
                                    pageQueries.resolve(
                                            new PageQuery(
                                                    context.applicationId(),
                                                    context.pageId(),
                                                    context.nodeId(),
                                                    context.recordId(),
                                                    null),
                                            actor,
                                            true);
                            if (!store.matches(
                                    task.getId(),
                                    query(null, true),
                                    actor,
                                    admin(actor),
                                    candidates)) throw invalid("此任务不符合当前发布任务视图的可关联范围");
                            TaskRecordLinkDO link = new TaskRecordLinkDO();
                            link.setId(UUID.randomUUID().toString());
                            link.setTaskId(task.getId());
                            link.setApplicationId(record.applicationId());
                            link.setObjectId(record.objectId());
                            link.setRecordId(record.recordId());
                            link.setLabel(record.label());
                            store.saveRecordLink(link, Long.toString(actor));
                            update(task, actor);
                        }
                    } else {
                        if (existing == null && inherentLink(task, nodes, record))
                            throw invalid("项目或办理材料的自动关联不能在这里解除");
                        if (existing != null) {
                            store.removeRecordLink(existing.getId(), Long.toString(actor));
                            update(task, actor);
                        }
                    }
                    if (!task.getId().equals(task.getRootId())) touchRoot(task.getRootId(), actor);
                    append(
                            task,
                            command.include() ? EventType.LINKED : EventType.UNLINKED,
                            (command.include() ? "关联" : "解除关联") + "业务记录 " + record.recordId(),
                            Map.of("record", record),
                            key,
                            fingerprint,
                            actor);
                    return detail(task.getId(), actor);
                });
    }

    private boolean inherentLink(
            TaskInstanceDO task, List<TaskInstanceDO> nodes, RecordRef record) {
        RecordRef project = read(task.getProjectJson(), RecordRef.class);
        BusinessRef ref = binding(task, nodes);
        return sameRecord(project, record)
                || ref != null
                        && Objects.equals(ref.resource().applicationId(), record.applicationId())
                        && Objects.equals(ref.object().objectId(), record.objectId())
                        && Objects.equals(ref.recordId(), record.recordId());
    }

    private boolean sameRecord(RecordRef left, RecordRef right) {
        return left != null
                && Objects.equals(left.applicationId(), right.applicationId())
                && Objects.equals(left.objectId(), right.objectId())
                && Objects.equals(left.recordId(), right.recordId());
    }

    /**
     * 关联记录的读权限探测，与 visibleProject 同一做法：在保存点里读，读不到只跳过该记录。 公共读取在参与外层事务时失败会把整个任务命令标成
     * rollback-only（R8：验收人读不到任务挂的记录时验收 500、状态全回滚）； 保存点回滚只撤回这次探测。外层已因真实失败标记回滚时不再探测，避免保存点回滚清掉该标记。
     */
    private boolean linkReadable(RecordRef record, long actor) {
        return Boolean.TRUE.equals(
                tx.execute(
                        status -> {
                            if (status.isRollbackOnly()) return false;
                            try {
                                projectProbe.executeWithoutResult(
                                        ignored -> business.project(record, actor));
                                return true;
                            } catch (ServiceException revoked) {
                                return false;
                            }
                        }));
    }

    private List<TaskRecordLink> links(String task, long actor) {
        List<TaskRecordLink> result = new ArrayList<>();
        for (TaskRecordLinkDO link : store.links(task)) {
            RecordRef record =
                    new RecordRef(
                            link.getApplicationId(),
                            link.getObjectId(),
                            link.getRecordId(),
                            link.getLabel());
            // 任务可见不会恢复关联记录权限；不能泄露被撤权的记录身份及标题。
            if (!linkReadable(record, actor)) continue;
            long creator = Long.parseLong(link.getCreator());
            result.add(
                    new TaskRecordLink(
                            link.getId(), record, creator, name(creator), link.getCreateTime()));
        }
        return result;
    }

    /** 完成材料不可变，但每次读取重新验证当前记录权限，绝不借历史快照恢复已收回权限。 */
    @Override
    public CompletionMaterial material(MaterialRef command, long actor) {
        if (command == null) throw invalid("缺少完成材料定位");
        TaskInstanceDO task = require(command.taskId());
        List<TaskInstanceDO> nodes = store.instance(task.getRootId());
        if (!nodeVisible(task, nodes, actor)) throw invalid("没有查看此任务的权限");
        TaskHistoryDO event =
                store.events(task.getRootId()).stream()
                        .filter(
                                e ->
                                        e.getId().equals(command.eventId())
                                                && e.getTaskId().equals(task.getId())
                                                && Set.of(
                                                                EventType.COMPLETED.name(),
                                                                EventType.SUBMITTED_FOR_ACCEPTANCE
                                                                        .name(),
                                                                EventType.ACCEPTED.name())
                                                        .contains(e.getEventType()))
                        .findFirst()
                        .orElseThrow(() -> invalid("完成材料不存在"));
        if (event.getMaterialJson() == null) throw invalid("本次完成没有业务表单材料");
        CompletedSnapshot snapshot = read(event.getMaterialJson(), CompletedSnapshot.class);
        List<TaskWorkEntries.Material> entries =
                snapshot.entries() == null || snapshot.entries().isEmpty()
                        ? List.of()
                        : workEntries.materials(task.getId(), snapshot.entries(), actor);
        if (snapshot.binding() == null)
            return new CompletionMaterial(event.getId(), null, null, null, entries);
        requireLegacyBusiness(task, nodes);
        Binding binding = effectiveConfig(task, nodes).binding();
        ApplicationRecords.Aggregate current = business.read(snapshot.binding(), binding, actor);
        ApplicationRecords.Model model = business.model(snapshot.binding(), binding, actor);
        ApplicationRecords.Aggregate result =
                TaskMaterials.project(
                        snapshot.record(), current, business.currentDefinition(snapshot.binding()));
        ApplicationRecords.Model readOnly =
                new ApplicationRecords.Model(
                        model.object(),
                        false,
                        model.generatedKey(),
                        model.keyFieldId(),
                        model.keyType(),
                        model.details(),
                        result.record().permissions(),
                        model.managedFieldIds(),
                        model.orderedStates());
        return new CompletionMaterial(event.getId(), snapshot.binding(), readOnly, result, entries);
    }

    /** 每次提交单独封存，不依赖之后会被覆盖的入口 submitted_json。 */
    private record CompletedSnapshot(
            BusinessRef binding,
            ApplicationRecords.Aggregate record,
            List<TaskWorkEntries.Material> entries) {}

    @Override
    public Detail create(Create command, long actor) {
        return create(command, actor, false);
    }

    @Override
    public com.richuang.os.nocode.api.workflow.WorkflowTaskNodes.Definition prepareWorkflow(
            NodeInput task,
            List<NodeInput> children,
            String templateId,
            Integer version,
            long actor) {
        return tx.execute(
                status -> {
                    assignments.requireActive(actor);
                    TaskTemplateGraph.Definition graph =
                            TaskTemplateGraph.normalize(task, children, actor);
                    if (graph.task() == null) throw invalid("任务节点必须配置总任务");
                    TaskDataPolicies.FrozenRoot authorization;
                    Integer frozenVersion = null;
                    if (templateId != null) {
                        TaskTemplateDO head = requireTemplate(templateId, false);
                        frozenVersion = version == null ? requiredVersion(head) : version;
                        TaskTemplateVersionDO template = store.version(templateId, frozenVersion);
                        if (template == null) throw invalid("模板发布版本不存在");
                        authorization = dataPolicies.decode(template.getAuthorizationJson());
                        if (authorization != null)
                            authorization = dataPolicies.forLaunch(graph.task(), authorization);
                        templateLaunchNodes(
                                graph.task(),
                                graph.nodes(),
                                read(template.getRootJson(), NodeInput.class),
                                nodes(template.getNodesJson()),
                                graph.task().id(),
                                actor);
                    } else {
                        authorization = dataPolicies.freeze(graph.task(), actor);
                        if (authorization != null)
                            graph =
                                    TaskTemplateGraph.normalize(
                                            authorization.task(), graph.nodes(), actor);
                    }
                    validateUsers(graph.all());
                    workEntries.validate(graph.all(), actor);
                    // 原任务数据权限仍由既有编译器批准，流程模型不能借动态人员选择扩大数据授权。
                    if (authorization == null)
                        for (NodeInput node : graph.all()) business.resolve(node.binding(), actor);
                    return new com.richuang.os.nocode.api.workflow.WorkflowTaskNodes.Definition(
                            graph.task(),
                            graph.nodes(),
                            templateId,
                            frozenVersion,
                            dataPolicies.encode(authorization));
                });
    }

    @Override
    public Detail createWorkflow(
            com.richuang.os.nocode.api.workflow.WorkflowTaskNodes.Definition definition,
            NodeInput task,
            List<NodeInput> nodes,
            long initiator,
            String requestKey,
            LocalDateTime activatedAt) {
        assignments.requireActive(initiator);
        Create command =
                new Create(
                        task,
                        null,
                        definition.templateId(),
                        definition.templateVersion(),
                        null,
                        null,
                        null,
                        requestKey,
                        nodes,
                        Kind.ORDINARY,
                        null,
                        activatedAt);
        return create(command, initiator, false, definition);
    }

    @Override
    public Detail split(Create command, long actor) {
        if (command == null
                || command.task() == null
                || TaskGraph.blank(command.parentId()) == null) throw invalid("请选择要拆分的本人任务");
        NodeInput node = command.task();
        if (command.templateId() != null
                || command.templateVersion() != null
                || command.nodes() != null && !command.nodes().isEmpty()
                || command.business() != null
                || command.existingRecord() != null
                || command.project() != null
                || command.applicationId() != null
                || command.plannedStart() != null) throw invalid("拆分只新增当前任务的子任务，不能另行发起模板或业务数据");
        if (node.assigneeId() != null && !Objects.equals(node.assigneeId(), actor)
                || node.assignmentMode() != null && node.assignmentMode() != AssignmentMode.ASSIGNED
                || node.candidateUserIds() != null && !node.candidateUserIds().isEmpty()
                || node.acceptorId() != null) throw invalid("个人拆分的子任务由本人负责，其他分工请使用任务人员安排");
        if (node.effectiveWorkMinutes() != null && node.effectiveWorkMinutes() != 0)
            throw invalid("有效工作时长只能配置在总任务，不能分配给子任务");
        if (node.binding() != null
                || node.dataPolicy() != null
                || node.entries() != null && !node.entries().isEmpty()
                || node.sharing() != null
                        && (node.sharing().mode() != DataMode.INDEPENDENT
                                || node.sharing().sourceNodeId() != null
                                || node.sharing().writableFieldIds() != null
                                        && !node.sharing().writableFieldIds().isEmpty())
                || node.predecessorIds() != null && !node.predecessorIds().isEmpty())
            throw invalid("个人拆分继承原任务关系与数据权限，不能另行配置依赖或数据资源");
        NodeInput child =
                new NodeInput(
                        node.id(),
                        command.parentId(),
                        node.title(),
                        node.description(),
                        actor,
                        node.urgency(),
                        node.priority(),
                        node.schedule(),
                        List.of(),
                        null,
                        null,
                        null,
                        AssignmentMode.ASSIGNED,
                        List.of());
        return create(
                new Create(
                        child,
                        command.parentId(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        command.requestKey()),
                actor,
                true);
    }

    private Detail create(Create command, long actor, boolean personalSplit) {
        return create(command, actor, personalSplit, null);
    }

    private Detail create(
            Create command,
            long actor,
            boolean personalSplit,
            com.richuang.os.nocode.api.workflow.WorkflowTaskNodes.Definition workflow) {
        if (command == null || command.task() == null) throw invalid("缺少任务内容");
        String key = requestKey(command.requestKey());
        String hash = hash(command);
        return tx.execute(
                status -> {
                    store.requestLock("task-create:" + actor + ":" + key);
                    TaskInstanceDO previous = store.created(Long.toString(actor), key);
                    if (previous != null) {
                        same(previous.getRequestHash(), hash);
                        return detail(previous.getId(), actor);
                    }
                    if (command.business() != null) {
                        String businessKey = requestKey(command.business().requestKey());
                        store.requestLock("task-business:" + actor + ":" + businessKey);
                        if (store.requested(Long.toString(actor), businessKey) != null)
                            throw invalid("该业务提交已属于已有任务，请恢复原任务提交回执");
                    }
                    TaskInstanceDO parent =
                            command.parentId() == null
                                    ? null
                                    : personalSplit
                                            ? lockPersonalSplit(command.parentId(), actor)
                                            : lock(command.parentId(), actor);
                    Kind kind = parent == null ? command.kind() : kind(parent.getKind());
                    List<TaskInstanceDO> existing =
                            parent == null
                                    ? new ArrayList<>()
                                    : new ArrayList<>(
                                            TaskInstanceOrdering.instance(
                                                    store, json, parent.getRootId()));
                    if (parent != null) {
                        if (ended(parent)) throw invalid("已完成或已取消任务不能拆分");
                        // 必须在原实例根锁内校验负责人，不能借任务查看权拆分他人任务或绕过并发转派。
                        if (personalSplit && !Objects.equals(parent.getAssigneeId(), actor))
                            throw invalid("只能拆分当前由本人负责的任务");
                        if (!canEdit(parent, existing, actor)) throw invalid("没有拆分此任务的权限");
                        if (command.templateId() != null
                                || command.nodes() != null && !command.nodes().isEmpty())
                            throw invalid("子任务拆分不能重复启动模板或编排");
                    }
                    RecordRef project =
                            parent == null
                                    ? command.project()
                                    : read(parent.getProjectJson(), RecordRef.class);
                    TaskInstanceDO parentRoot =
                            parent == null
                                    ? null
                                    : existing.stream()
                                            .filter(node -> node.getId().equals(parent.getRootId()))
                                            .findFirst()
                                            .orElse(parent);
                    TaskTemplateVersionDO approvedTemplate = null;
                    TaskDataPolicies.FrozenRoot rootAuthorization =
                            parentRoot == null
                                    ? null
                                    : dataPolicies.decode(parentRoot.getAuthorizationJson());
                    if (command.templateId() != null) {
                        TaskTemplateDO head = requireTemplate(command.templateId(), false);
                        approvedTemplate =
                                store.version(
                                        head.getId(),
                                        command.templateVersion() == null
                                                ? requiredVersion(head)
                                                : command.templateVersion());
                        if (approvedTemplate == null) throw invalid("模板发布版本不存在");
                        rootAuthorization =
                                dataPolicies.decode(approvedTemplate.getAuthorizationJson());
                        if (rootAuthorization != null)
                            rootAuthorization =
                                    dataPolicies.forLaunch(command.task(), rootAuthorization);
                        else if (command.task().dataPolicy() != null)
                            throw invalid("历史模板没有批准的总任务数据权限，请重新配置并发布模板");
                    } else if (parent == null) {
                        rootAuthorization =
                                workflow == null
                                        ? dataPolicies.freeze(command.task(), actor)
                                        : dataPolicies.decode(workflow.authorizationJson());
                    } else if (command.task().dataPolicy() != null) {
                        throw invalid("子任务统一继承总任务的数据权限，不能单独配置");
                    }
                    if (parentRoot != null && rootAuthorization != null)
                        dataPolicies.requireSame(config(parentRoot), rootAuthorization);
                    // 原页面已经明确引用的资料才可加入本组；继承父任务时不重新要求个人应用成员权限。
                    if (parent == null || rootAuthorization == null)
                        business.project(project, actor);
                    if (rootAuthorization != null && command.business() != null)
                        throw invalid("统一授权任务请先加入任务池，开始执行后再登记业务数据");
                    if (parent != null
                            && rootAuthorization != null
                            && command.existingRecord() != null)
                        throw invalid("子任务继承总任务数据，请在执行时关联已有记录");
                    // 仅显式归属要求完整应用权限；旧主业务入口可以只授予办理权限，不能借归属字段扩大其授权要求。
                    String applicationId =
                            parentRoot == null
                                    ? TaskGraph.blank(command.applicationId())
                                    : parentRoot.getApplicationId();
                    if (parent != null
                            && TaskGraph.blank(command.applicationId()) != null
                            && !Objects.equals(application(parentRoot), command.applicationId()))
                        throw invalid("子任务的所属应用必须与上级任务一致");
                    if (applicationId != null) {
                        published.getCurrent(applicationId);
                        if (!approvedApplication(rootAuthorization, applicationId))
                            applicationPolicy.requireEntry(applicationId, actor);
                    }
                    String firstId = UUID.randomUUID().toString();
                    String root = parent == null ? firstId : parent.getRootId();
                    LocalDateTime t0 = parent == null ? LocalDateTime.now() : parent.getT0();
                    LocalDateTime plannedStart =
                            parent == null ? command.plannedStart() : parent.getPlannedStart();
                    List<NodeInput> input;
                    Map<String, BusinessRef> frozen = new HashMap<>();
                    Map<String, String> templateNodeIds = new HashMap<>();
                    if (command.templateId() != null
                            || command.nodes() != null && !command.nodes().isEmpty()) {
                        List<NodeInput> templateNodes;
                        Set<String> sourceNodeIds = Set.of();
                        Map<String, BusinessRef> refs;
                        NodeInput templateRoot = null;
                        if (command.templateId() != null) {
                            TaskTemplateVersionDO template = approvedTemplate;
                            // 保留发布快照的旧标签，不把客户端标签差异变成发起限制。
                            kind = kind(template.getKind());
                            templateNodes = nodes(template.getNodesJson());
                            sourceNodeIds = TaskGraph.index(templateNodes).keySet();
                            templateRoot = read(template.getRootJson(), NodeInput.class);
                            if (command.nodes() != null)
                                templateNodes =
                                        templateLaunchNodes(
                                                command.task(),
                                                command.nodes(),
                                                templateRoot,
                                                templateNodes,
                                                firstId,
                                                actor);
                            refs =
                                    read(
                                            template.getBindingsJson(),
                                            new TypeReference<Map<String, BusinessRef>>() {});
                        } else {
                            templateNodes =
                                    TaskTemplateGraph.normalize(
                                                    creationGraphRoot(command.task(), firstId),
                                                    command.nodes(),
                                                    actor)
                                            .nodes();
                            refs = Map.of();
                        }
                        Map<String, String> ids = new LinkedHashMap<>();
                        for (NodeInput n : templateNodes)
                            ids.put(n.id(), UUID.randomUUID().toString());
                        if (templateRoot != null) {
                            ids.put(templateRoot.id(), firstId);
                            templateNodeIds.put(firstId, templateRoot.id());
                            if (refs.containsKey(templateRoot.id()))
                                frozen.put(firstId, refs.get(templateRoot.id()));
                        }
                        input = new ArrayList<>();
                        NodeInput rootInput = command.task();
                        input.add(
                                new NodeInput(
                                        firstId,
                                        null,
                                        rootInput.title(),
                                        rootInput.description(),
                                        rootInput.assigneeId(),
                                        rootInput.urgency(),
                                        rootInput.priority(),
                                        rootInput.schedule(),
                                        List.of(),
                                        rootInput.binding(),
                                        rootInput.sharing(),
                                        rootInput.entries(),
                                        rootInput.assignmentMode(),
                                        rootInput.candidateUserIds(),
                                        rootInput.dataPolicy(),
                                        rootInput.acceptorId(),
                                        // 模板工时只取本次选定的发布快照，客户端不能用当前草稿覆盖历史版本。
                                        command.templateId() == null
                                                ? rootInput.effectiveWorkMinutes()
                                                : templateRoot == null
                                                        ? null
                                                        : rootInput.workTotalMode() == null
                                                                ? templateRoot
                                                                        .effectiveWorkMinutes()
                                                                : rootInput.effectiveWorkMinutes(),
                                        rootInput.workTotalMode() == null && templateRoot != null
                                                ? templateRoot.workTotalMode()
                                                : rootInput.workTotalMode()));
                        for (NodeInput n : templateNodes) {
                            NodeInput mapped = remap(n, ids, n.id());
                            NodeInput remapped =
                                    new NodeInput(
                                            mapped.id(),
                                            mapped.parentId() == null ? firstId : mapped.parentId(),
                                            mapped.title(),
                                            mapped.description(),
                                            mapped.assigneeId(),
                                            mapped.urgency(),
                                            mapped.priority(),
                                            mapped.schedule(),
                                            mapped.predecessorIds(),
                                            mapped.binding(),
                                            mapped.sharing(),
                                            mapped.entries(),
                                            mapped.assignmentMode(),
                                            mapped.candidateUserIds(),
                                            mapped.dataPolicy(),
                                            mapped.acceptorId(),
                                            mapped.effectiveWorkMinutes(),
                                            mapped.workTotalMode());
                            input.add(remapped);
                            if (sourceNodeIds.contains(n.id()))
                                templateNodeIds.put(remapped.id(), n.id());
                            if (refs.containsKey(n.id()))
                                frozen.put(remapped.id(), refs.get(n.id()));
                        }
                    } else {
                        NodeInput n = command.task();
                        input =
                                List.of(
                                        new NodeInput(
                                                firstId,
                                                parent == null ? null : parent.getId(),
                                                n.title(),
                                                n.description(),
                                                n.assigneeId(),
                                                n.urgency(),
                                                n.priority(),
                                                n.schedule(),
                                                n.predecessorIds(),
                                                n.binding(),
                                                n.sharing(),
                                                n.entries(),
                                                n.assignmentMode(),
                                                n.candidateUserIds(),
                                                n.dataPolicy(),
                                                n.acceptorId(),
                                                n.effectiveWorkMinutes(),
                                                n.workTotalMode()));
                    }
                    List<NodeInput> combined =
                            new ArrayList<>(existing.stream().map(this::config).toList());
                    combined.addAll(input);
                    if (input.stream()
                            .anyMatch(
                                    n ->
                                            n.assignmentMode() == AssignmentMode.FOLLOW_ROOT
                                                    && n.assigneeId() != null))
                        throw invalid("新建随总任务分工不能预设已承接负责人");
                    List<NodeInput> valid = TaskGraph.normalize(combined, actor);
                    valid =
                            TaskAssignments.resolveFollowers(
                                    valid,
                                    input.stream()
                                            .map(NodeInput::id)
                                            .collect(java.util.stream.Collectors.toSet()));
                    valid = TaskGraph.normalize(valid, actor);
                    TaskAssignments.requireResolvedFollowers(
                            valid,
                            input.stream()
                                    .map(NodeInput::id)
                                    .collect(java.util.stream.Collectors.toSet()));
                    validateUnifiedData(valid, root);
                    if (kind == null) kind = Kind.ORDINARY;
                    validateUsers(valid);
                    workEntries.validate(valid, actor);
                    Map<String, NodeInput> normalized = TaskGraph.index(valid);
                    List<TaskInstanceDO> added = new ArrayList<>();
                    int nextDisplayOrder =
                            existing.stream()
                                    .map(n -> TaskInstanceOrdering.displayOrder(json, n))
                                    .filter(Objects::nonNull)
                                    .mapToInt(order -> order + 1)
                                    .max()
                                    .orElse(0);
                    nextDisplayOrder = Math.max(nextDisplayOrder, existing.size());
                    for (NodeInput raw : input) {
                        NodeInput node = normalized.get(raw.id());
                        TaskInstanceDO task = new TaskInstanceDO();
                        task.setId(node.id());
                        task.setRootId(root);
                        task.setParentId(node.parentId());
                        task.setTitle(node.title());
                        task.setAssigneeId(node.assigneeId());
                        task.setStatus(State.PENDING.name());
                        task.setKind(kind.name());
                        task.setLockVersion(0);
                        task.setConfigJson(
                                TaskInstanceOrdering.config(json, node, nextDisplayOrder++));
                        if (task.getId().equals(root))
                            task.setAuthorizationJson(dataPolicies.encode(rootAuthorization));
                        task.setProjectJson(project == null ? null : write(project));
                        task.setApplicationId(applicationId);
                        task.setT0(t0);
                        task.setPlannedStart(plannedStart);
                        task.setTemplateId(command.templateId());
                        task.setTemplateNodeId(templateNodeIds.get(node.id()));
                        task.setTemplateVersion(
                                command.templateId() == null
                                        ? null
                                        : approvedTemplate.getVersionNo());
                        task.setCreator(Long.toString(actor));
                        task.setCreateTime(LocalDateTime.now());
                        BusinessRef ref = frozen.getOrDefault(node.id(), null);
                        if (rootAuthorization != null && node.id().equals(root))
                            ref =
                                    rootAuthorization.grants().resources().stream()
                                            .filter(
                                                    g ->
                                                            TaskDataPolicies.BUSINESS.equals(
                                                                    g.entryKey()))
                                            .map(TaskDataPolicyCompiler.ResourceGrant::ref)
                                            .findFirst()
                                            .orElse(null);
                        if (ref == null && node.binding() != null)
                            ref = business.resolve(node.binding(), actor);
                        if (ref != null) {
                            if (rootAuthorization == null)
                                business.model(ref, node.binding(), actor);
                            task.setBusinessJson(write(ref));
                        }
                        if (task.getId().equals(firstId)) {
                            task.setRequestKey(key);
                            task.setRequestHash(hash);
                        }
                        added.add(task);
                    }
                    List<TaskInstanceDO> all = new ArrayList<>(existing);
                    all.addAll(added);
                    validateShared(all, actor);
                    TaskInstanceDO first =
                            added.stream()
                                    .filter(n -> n.getId().equals(firstId))
                                    .findFirst()
                                    .orElseThrow();
                    if (command.business() != null && command.existingRecord() != null)
                        throw invalid("新建业务内容与关联已有记录不能同时选择");
                    BusinessRef ref = binding(first, all);
                    if (rootAuthorization != null
                            && firstId.equals(root)
                            && command.existingRecord() == null
                            && ref != null
                            && project != null
                            && Objects.equals(
                                    ref.resource().applicationId(), project.applicationId())
                            && Objects.equals(ref.object().objectId(), project.objectId()))
                        ref =
                                business.existing(
                                        ref, effectiveConfig(first, all).binding(), project, actor);
                    if (command.existingRecord() != null)
                        ref =
                                business.existing(
                                        ref,
                                        effectiveConfig(first, all).binding(),
                                        command.existingRecord(),
                                        actor);
                    if (command.business() != null)
                        ref =
                                business.save(
                                        ref,
                                        effectiveConfig(first, all).binding(),
                                        config(first).sharing(),
                                        command.business(),
                                        actor);
                    if (ref != null) first.setBusinessJson(write(ref));
                    Map<String, List<LocalDateTime>> previousDates = new HashMap<>();
                    for (TaskInstanceDO node : existing)
                        previousDates.put(
                                node.getId(),
                                Arrays.asList(node.getExpectedStart(), node.getExpectedEnd()));
                    calculate(all);
                    // 拆分新下级也会改变尚未开始的 AUTO 父汇总；根锁内同步保存，不改执行中的计划。
                    for (TaskInstanceDO node : existing) {
                        List<LocalDateTime> previousSchedule = previousDates.get(node.getId());
                        if (!Objects.equals(previousSchedule.get(0), node.getExpectedStart())
                                || !Objects.equals(previousSchedule.get(1), node.getExpectedEnd()))
                            update(node, actor);
                    }
                    for (TaskInstanceDO task : added) {
                        task.setBaselineStart(task.getExpectedStart());
                        task.setBaselineEnd(task.getExpectedEnd());
                        store.insertTask(task, Long.toString(actor));
                        append(
                                task,
                                EventType.CREATED,
                                "创建任务",
                                new CreationEvidence(
                                        personalSplit
                                                ? CreationOrigin.PERSONAL_SPLIT
                                                : CreationOrigin.ARRANGED,
                                        task.getParentId()),
                                null,
                                null,
                                actor);
                    }
                    if (command.business() != null)
                        append(
                                first,
                                EventType.BUSINESS_SAVED,
                                "发起任务并提交业务内容",
                                new BusinessReceiptSnapshot(ref, true),
                                command.business().requestKey(),
                                hash(command.business()),
                                actor);
                    workEntries.synchronize(root, actor);
                    if (parent != null) touchRoot(root, actor);
                    return detail(firstId, actor);
                });
    }

    @Override
    public void deleteSubtask(DeleteSubtask command, long actor) {
        if (command == null || command.id() == null) throw invalid("请选择要删除的子任务");
        String key = requestKey(command.requestKey());
        String fingerprint = hash(command);
        tx.executeWithoutResult(
                status -> {
                    // 与创建、执行、转派、编排共用根锁；重试只恢复本人原请求，不重新操作节点。
                    store.requestLock("task-delete-subtask:" + actor + ":" + key);
                    TaskHistoryDO receipt = store.requested(Long.toString(actor), key);
                    if (receipt != null) {
                        same(receipt.getRequestHash(), fingerprint);
                        if (!EventType.SUBTASK_DELETED.name().equals(receipt.getEventType()))
                            throw invalid("该请求标识已用于其他任务操作");
                        return;
                    }
                    TaskInstanceDO initial = require(command.id());
                    store.get(initial.getRootId(), true);
                    TaskInstanceDO task = store.planningTask(command.id());
                    if (task == null) throw invalid("任务不存在");
                    List<TaskInstanceDO> nodes = store.instance(task.getRootId());
                    revision(task, command.expectedRevision());
                    String reason =
                            subtaskDeletionReason(
                                    task, nodes, actor, store.events(task.getRootId()));
                    if (reason != null) throw invalid(reason);
                    TaskInstanceDO parent = require(task.getParentId());
                    store.archiveDeletedSubtaskPlans(task.getId(), Long.toString(actor));
                    store.removeTask(task.getId(), Long.toString(actor));
                    // 删除只移除实例身份，不级联业务记录、历史材料、评论或其他节点。
                    recalculate(store.instance(task.getRootId()), actor);
                    touchRoot(task.getRootId(), actor);
                    append(
                            parent,
                            EventType.SUBTASK_DELETED,
                            "删除子任务：" + task.getTitle(),
                            new DeletedSubtask(task.getId(), task.getTitle(), task.getParentId()),
                            key,
                            fingerprint,
                            actor);
                    assignments.notifyDeleted(task, actor);
                });
    }

    /** 个人删除不借用整组编排授权；展示原因与写入检查共用同一规则，写入时仍在根锁内重查。 */
    private String subtaskDeletionReason(
            TaskInstanceDO task,
            List<TaskInstanceDO> nodes,
            long actor,
            List<TaskHistoryDO> events) {
        if (task.getParentId() == null || task.getId().equals(task.getRootId()))
            return "总任务不能通过删除子任务移除";
        TaskInstanceDO root =
                nodes.stream()
                        .filter(node -> node.getId().equals(task.getRootId()))
                        .findFirst()
                        .orElse(task);
        if (!assignments.canCoordinate(task, root, nodes, events, actor))
            return "仅总负责人或仍负责上级的原拆分人可以删除个人拆分的子任务";
        if (task.getTemplateNodeId() != null) return "模板下发的原任务不能删除";
        CreationEvidence origin = assignments.creationEvidence(task, events);
        if (origin == null) return "旧任务来源无法确认，请联系管理者调整";
        if (origin.origin() != CreationOrigin.PERSONAL_SPLIT) return "原始安排的任务不能删除";
        if (!Objects.equals(origin.parentId(), task.getParentId())) return "任务位置已调整，请联系管理者处理";
        if (!State.PENDING.name().equals(task.getStatus())
                || task.getActualStart() != null
                || task.getActualEnd() != null) return "已开始或已结束，不能删除，请保留执行记录";
        if (TaskPauses.pausedBy(task, nodes) != null) return "任务或上级已暂停，暂不能删除";
        Map<String, TaskInstanceDO> index = taskIndex(task, nodes);
        String parentId = task.getParentId();
        Set<String> visited = new HashSet<>();
        while (parentId != null && visited.add(parentId)) {
            TaskInstanceDO parent = index.get(parentId);
            if (parent == null || !executable(parent)) return "上级任务已结束或待验收，不能删除";
            parentId = parent.getParentId();
        }
        if (parentId != null) return "任务层级异常，请联系管理者处理";
        if (nodes.stream().anyMatch(node -> Objects.equals(node.getParentId(), task.getId())))
            return "还有下级任务，请先逐项处理下级任务";
        for (TaskInstanceDO other : nodes) {
            if (other.getId().equals(task.getId())) continue;
            NodeInput node = config(other);
            if (node.predecessorIds() != null && node.predecessorIds().contains(task.getId()))
                return "已被后续任务依赖，请联系管理者调整";
            if (node.sharing() != null && task.getId().equals(node.sharing().sourceNodeId())
                    || node.entries() != null
                            && node.entries().stream()
                                    .anyMatch(entry -> task.getId().equals(entry.sourceNodeId())))
                return "已被其他任务引用为数据来源，不能删除";
        }
        if (store.hasSubtaskDataReferences(task.getId())) return "已被其他任务引用为数据来源，不能删除";
        if (task.getExplicitLinkId() != null
                || store.hasRemovalEvidence(List.of(task.getId()))
                || store.hasSubtaskRemovalEvidence(task.getId()))
            return "已有业务记录、关联或工时事实，请保留任务并按权限取消";
        try {
            workflowProtection.requireActive(task.getRootId());
        } catch (ServiceException blocked) {
            return blocked.getMessage();
        }
        return null;
    }

    @Override
    public TransitionRecovery transitionRecovery(Transition command, long actor) {
        if (command == null || command.action() == null) throw invalid("请选择任务操作");
        String key = requestKey(command.requestKey());
        String fingerprint = hash(command);
        return tx.execute(
                status -> {
                    TaskInstanceDO task = lock(command.id(), actor);
                    // 等待根锁期间可能转派；确认回执前重新检查锁后的节点可见性。
                    if (!nodeVisible(task, store.instance(task.getRootId()), actor))
                        throw invalid("没有查看此任务的权限");
                    TaskHistoryDO previous = store.requested(Long.toString(actor), key);
                    if (previous != null) {
                        same(previous.getRequestHash(), fingerprint);
                        return new TransitionRecovery(detail(command.id(), actor), false);
                    }
                    // 同修订仍可能有迟到请求；未来修订也不能当作已失效的旧请求。
                    return new TransitionRecovery(
                            null, command.expectedRevision() < task.getLockVersion());
                });
    }

    @Override
    public Detail transition(Transition command, long actor) {
        if (command == null || command.action() == null) throw invalid("请选择任务操作");
        String key = requestKey(command.requestKey());
        String fingerprint = hash(command);
        return tx.execute(
                status -> {
                    TaskInstanceDO task = lock(command.id(), actor);
                    TaskHistoryDO previous = store.requested(Long.toString(actor), key);
                    if (previous != null) {
                        same(previous.getRequestHash(), fingerprint);
                        return detail(command.id(), actor);
                    }
                    revision(task, command.expectedRevision());
                    List<TaskInstanceDO> nodes = store.instance(task.getRootId());
                    boolean accepting =
                            command.action() == Action.APPROVE || command.action() == Action.REJECT;
                    boolean managing =
                            command.action() == Action.CANCEL
                                    || command.action() == Action.PAUSE
                                    || command.action() == Action.RESUME;
                    if (accepting && !canAccept(task, actor)) throw invalid("仅当前指定验收人可以处理待验收任务");
                    if (!accepting
                            && !Objects.equals(task.getAssigneeId(), actor)
                            && (!managing || !canManage(nodes, actor)))
                        throw invalid("只有任务负责人可以开始或完成任务");
                    if (command.action() == Action.PAUSE || command.action() == Action.RESUME)
                        assignments.requireActive(actor);
                    String note = TaskGraph.text(command.note(), "办理备注", 4000, false);
                    EventType type;
                    Object material = null;
                    switch (command.action()) {
                        case START -> {
                            String blocked = blocked(task, nodes);
                            if (blocked != null) throw invalid(blocked);
                            task.setStatus(State.RUNNING.name());
                            task.setActualStart(LocalDateTime.now());
                            type = EventType.STARTED;
                        }
                        case COMPLETE -> {
                            TaskPauses.requireActive(task, nodes);
                            if (!State.RUNNING.name().equals(task.getStatus()))
                                throw invalid("请先开始任务再完成");
                            if (nodes.stream()
                                    .anyMatch(
                                            n ->
                                                    Objects.equals(n.getParentId(), task.getId())
                                                            && !ended(n)))
                                throw invalid("还有未完成的子任务");
                            if (nodes.stream()
                                    .anyMatch(
                                            n ->
                                                    Objects.equals(n.getParentId(), task.getId())
                                                            && State.CANCELLED
                                                                    .name()
                                                                    .equals(n.getStatus()))) {
                                if (!Boolean.TRUE.equals(command.confirmCancelledChildren()))
                                    throw invalid("存在已取消子任务，请确认剩余工作范围可以交付后再完成");
                                note = TaskGraph.text(note, "取消子任务后的交付说明", 4000, true);
                            }
                            material = completeMaterial(task, nodes, actor);
                            type = completionType(task);
                        }
                        case PAUSE -> {
                            TaskPauses.requireActive(task, nodes);
                            if (!State.RUNNING.name().equals(task.getStatus()))
                                throw invalid("只有进行中的任务可以暂停");
                            task.setStatus(State.PAUSED.name());
                            type = EventType.PAUSED;
                        }
                        case RESUME -> {
                            if (!State.PAUSED.name().equals(task.getStatus()))
                                throw invalid("只有直接暂停的任务可以恢复");
                            if (!task.getId().equals(TaskPauses.pausedBy(task, nodes)))
                                throw invalid("上级任务已暂停，请先恢复上级任务");
                            // 原开始时间、排期与下级真实状态全部保留，恢复不是重新开始。
                            task.setStatus(State.RUNNING.name());
                            type = EventType.RESUMED;
                        }
                        case APPROVE -> {
                            // 通过沿用最近一次提交的材料；验收人不重新执行负责人的业务提交。
                            TaskHistoryDO submission =
                                    store.events(task.getRootId()).stream()
                                            .filter(
                                                    e ->
                                                            e.getTaskId().equals(task.getId())
                                                                    && EventType
                                                                            .SUBMITTED_FOR_ACCEPTANCE
                                                                            .name()
                                                                            .equals(
                                                                                    e
                                                                                            .getEventType()))
                                            .reduce((first, last) -> last)
                                            .orElseThrow(() -> invalid("验收提交记录不存在"));
                            material = read(submission.getMaterialJson(), CompletedSnapshot.class);
                            task.setStatus(State.COMPLETED.name());
                            task.setActualEnd(LocalDateTime.now());
                            type = EventType.ACCEPTED;
                        }
                        case REJECT -> {
                            note = TaskGraph.text(command.note(), "退回原因", 4000, true);
                            task.setStatus(State.RUNNING.name());
                            task.setActualEnd(null);
                            type = EventType.REJECTED;
                        }
                        case CANCEL -> {
                            if (ended(task)) throw invalid("任务已经结束");
                            if (nodes.stream()
                                    .anyMatch(
                                            n ->
                                                    Objects.equals(n.getParentId(), task.getId())
                                                            && !ended(n)))
                                throw invalid("请先结束子任务再取消父任务");
                            task.setStatus(State.CANCELLED.name());
                            task.setActualEnd(LocalDateTime.now());
                            type = EventType.CANCELLED;
                        }
                        default -> throw invalid("不支持的任务操作");
                    }
                    update(task, actor);
                    replace(nodes, task);
                    append(task, type, note, material, key, fingerprint, actor);
                    notifyAcceptance(task, type, actor);
                    if (command.action() == Action.COMPLETE || command.action() == Action.APPROVE)
                        rollupParents(task, nodes, actor);
                    recalculate(nodes, actor);
                    if (!task.getId().equals(task.getRootId())) touchRoot(task.getRootId(), actor);
                    return detail(task.getId(), actor);
                });
    }

    /** 手动与自动收尾共用同一材料封存路径；自动汇总不得绕过业务生效、必填及当前权限。 */
    private CompletedSnapshot completeMaterial(
            TaskInstanceDO task, List<TaskInstanceDO> nodes, long actor) {
        Long acceptor = config(task).acceptorId();
        if (acceptor != null) assignments.requireActive(acceptor);
        workEntries.complete(task.getId(), actor);
        CompletedSnapshot snapshot = null;
        BusinessRef ref = binding(task, nodes);
        if (ref != null && !unifiedData(task, nodes)) {
            ref = business.refresh(ref, actor);
            if (ref.requestId() != null) throw invalid("业务申请尚未审批生效，请先完成审批办理");
            if (ref.recordId() == null) throw invalid("请先填写并保存任务业务表单");
            ApplicationRecords.Aggregate record =
                    business.read(ref, effectiveConfig(task, nodes).binding(), actor);
            snapshot = new CompletedSnapshot(ref, record, List.of());
            task.setBusinessJson(write(ref));
        }
        List<TaskWorkEntries.Material> entries = workEntries.snapshot(task.getId(), actor);
        task.setStatus(acceptor == null ? State.COMPLETED.name() : State.PENDING_ACCEPTANCE.name());
        task.setActualEnd(acceptor == null ? LocalDateTime.now() : null);
        return snapshot != null || acceptor != null || !entries.isEmpty()
                ? new CompletedSnapshot(
                        snapshot == null ? null : snapshot.binding(),
                        snapshot == null ? null : snapshot.record(),
                        entries)
                : null;
    }

    private EventType completionType(TaskInstanceDO task) {
        return State.PENDING_ACCEPTANCE.name().equals(task.getStatus())
                ? EventType.SUBMITTED_FOR_ACCEPTANCE
                : EventType.COMPLETED;
    }

    private record RollupBlock(String reason) {}

    /** 根锁已持有：逐层从内向外结转，每层保存点失败只保留该层待收尾，不撤销员工已完成的工作。 */
    private void rollupParents(
            TaskInstanceDO completed, List<TaskInstanceDO> nodes, long triggerActor) {
        String parentId = completed.getParentId();
        Set<String> visited = new HashSet<>();
        while (parentId != null && visited.add(parentId)) {
            String id = parentId;
            TaskInstanceDO parent =
                    nodes.stream().filter(n -> n.getId().equals(id)).findFirst().orElse(null);
            if (parent == null || !executable(parent) || TaskPauses.pausedBy(parent, nodes) != null)
                break;
            List<TaskInstanceDO> children =
                    nodes.stream().filter(n -> Objects.equals(n.getParentId(), id)).toList();
            if (children.isEmpty()
                    || children.stream()
                            .anyMatch(n -> !State.COMPLETED.name().equals(n.getStatus()))) break;
            // 验收退回要求负责人显式重提，后续子项/业务变化不能触发自动重复提交。
            if (latestCompletionEvent(parent) == EventType.REJECTED) break;
            TaskInstanceDO candidate = read(write(parent), TaskInstanceDO.class);
            try {
                projectProbe.execute(
                        ignored -> {
                            if (candidate.getAssigneeId() == null)
                                throw invalid("下级已完成，请安排总负责人确认交付");
                            long owner = candidate.getAssigneeId();
                            assignments.requireActive(owner);
                            if (candidate.getActualStart() == null)
                                candidate.setActualStart(LocalDateTime.now());
                            CompletedSnapshot material = completeMaterial(candidate, nodes, owner);
                            update(candidate, triggerActor);
                            EventType type = completionType(candidate);
                            append(
                                    candidate,
                                    type,
                                    "下级任务全部完成，系统自动汇总",
                                    material,
                                    null,
                                    null,
                                    triggerActor);
                            notifyAcceptance(candidate, type, triggerActor);
                            return null;
                        });
            } catch (ServiceException blocked) {
                // 仅负责人可见此提示；不在子项响应中扩大父项业务资料读取权限。
                append(
                        parent,
                        EventType.ROLLUP_BLOCKED,
                        "下级已完成，需负责人完成交付检查",
                        new RollupBlock(blocked.getMessage()),
                        null,
                        null,
                        triggerActor);
                break;
            }
            replace(nodes, candidate);
            if (!State.COMPLETED.name().equals(candidate.getStatus())) break;
            parentId = candidate.getParentId();
        }
    }

    private EventType latestCompletionEvent(TaskInstanceDO task) {
        return latestCompletionEvent(task, store.events(task.getRootId()));
    }

    private EventType latestCompletionEvent(TaskInstanceDO task, List<TaskHistoryDO> events) {
        return events.stream()
                .filter(e -> e.getTaskId().equals(task.getId()))
                .map(e -> EventType.valueOf(e.getEventType()))
                .filter(
                        type ->
                                Set.of(
                                                EventType.REJECTED,
                                                EventType.COMPLETED,
                                                EventType.ACCEPTED,
                                                EventType.SUBMITTED_FOR_ACCEPTANCE)
                                        .contains(type))
                .reduce((previous, current) -> current)
                .orElse(null);
    }

    private String completionReason(
            TaskInstanceDO task, List<TaskInstanceDO> nodes, List<TaskHistoryDO> events) {
        if (!executable(task)) return null;
        if (latestCompletionEvent(task, events) == EventType.REJECTED) return "验收已退回，请整改后重新提交";
        List<TaskInstanceDO> children =
                nodes.stream().filter(n -> Objects.equals(n.getParentId(), task.getId())).toList();
        if (children.isEmpty() || children.stream().anyMatch(n -> !ended(n))) return null;
        if (children.stream().anyMatch(n -> State.CANCELLED.name().equals(n.getStatus())))
            return "存在已取消子任务，请确认剩余工作范围可以交付";
        return events.stream()
                .filter(
                        e ->
                                e.getTaskId().equals(task.getId())
                                        && EventType.ROLLUP_BLOCKED.name().equals(e.getEventType()))
                .reduce((previous, current) -> current)
                .map(e -> read(e.getMaterialJson(), RollupBlock.class).reason())
                .orElse("子任务已完成，请确认交付资料并完成任务");
    }

    /** 消息随状态事务保存；消息失败时回滚，幂等重试不重复生成通知。 */
    private void notifyAcceptance(TaskInstanceDO task, EventType type, long actor) {
        if (type != EventType.SUBMITTED_FOR_ACCEPTANCE
                && type != EventType.ACCEPTED
                && type != EventType.REJECTED) return;
        Long recipient =
                type == EventType.SUBMITTED_FOR_ACCEPTANCE
                        ? config(task).acceptorId()
                        : task.getAssigneeId();
        if (recipient == null || recipient == actor) return;
        String title =
                switch (type) {
                    case SUBMITTED_FOR_ACCEPTANCE -> "任务待验收：";
                    case ACCEPTED -> "任务验收通过：";
                    default -> "任务验收退回：";
                };
        MsgSendParam message =
                new MsgSendParam()
                        .setMsgCode("nocode-task-acceptance")
                        .setTitle(title + task.getTitle())
                        .setContent(HtmlUtils.htmlEscape(title + task.getTitle()))
                        .setMsgData(Map.of("taskId", task.getId()))
                        .setSourceType("NOCODE_TASK")
                        .setSourceId(task.getId())
                        .setOwnerId(Long.toString(actor))
                        .setTargets(
                                List.of(
                                        new MsgTarget()
                                                .setTargetType(MsgTargetType.USER)
                                                .setTargetId(Long.toString(recipient))));
        if (messages.send(message) == null) throw invalid("任务验收提醒未成功保存，请重试");
    }

    @Override
    public void plan(SavePlan command, long actor) {
        scheduling.legacy(command, actor, scheduleAccess());
    }

    @Override
    public TaskPlanning.Context planContext(TaskPlanning.ContextQuery query, long actor) {
        return scheduling.context(query, actor, scheduleAccess());
    }

    @Override
    public TaskPlanning.Result schedule(TaskPlanning.Change command, long actor) {
        return scheduling.change(command, actor, scheduleAccess());
    }

    @Override
    public TaskPlanning.ChecklistContext checklistContext(
            TaskPlanning.ContextQuery query, long actor) {
        return checklists.context(query, actor, scheduleAccess());
    }

    @Override
    public TaskPlanning.Result checklist(TaskPlanning.ChecklistChange command, long actor) {
        return checklists.change(command, actor, scheduleAccess());
    }

    private TaskScheduling.Access scheduleAccess() {
        return new TaskScheduling.Access() {
            @Override
            public boolean visible(TaskInstanceDO task, List<TaskInstanceDO> nodes, long actor) {
                return nodeVisible(task, nodes, actor);
            }

            @Override
            public boolean arrange(TaskInstanceDO task, List<TaskInstanceDO> nodes, long actor) {
                return canArrange(task, nodes, actor);
            }

            @Override
            public boolean readPlans(TaskInstanceDO task, List<TaskInstanceDO> nodes, long actor) {
                return canViewPlans(task, nodes, actor);
            }

            @Override
            public boolean manage(List<TaskInstanceDO> nodes, long actor) {
                return canManage(nodes, actor);
            }

            @Override
            public String name(Long actor) {
                return TaskCenterServiceImpl.this.name(actor);
            }

            @Override
            public String blocked(TaskInstanceDO task, List<TaskInstanceDO> nodes) {
                return TaskCenterServiceImpl.this.blocked(task, nodes);
            }
        };
    }

    @Override
    public Comment comment(AddComment command, long actor) {
        if (command == null) throw invalid("缺少评论内容");
        String content = TaskGraph.text(command.content(), "评论", 8000, true);
        String key = requestKey(command.requestKey());
        String fingerprint = hash(command);
        return tx.execute(
                status -> {
                    TaskInstanceDO task = lock(command.taskId(), actor);
                    TaskCommentDO previous = store.commented(Long.toString(actor), key);
                    if (previous != null) {
                        same(previous.getRequestHash(), fingerprint);
                        return comment(previous);
                    }
                    List<TaskInstanceDO> nodes = store.instance(task.getRootId());
                    Set<Long> allowed = participants(nodes);
                    // 安排人可能是非实例参与者的管理员；仅补入本任务当前有效且仍可见的主管安排人。
                    if (task.getAssigneeId() != null)
                        store.plans(task.getAssigneeId(), List.of(task.getId())).stream()
                                .filter(p -> "MANAGER".equals(p.getSource()))
                                .map(TaskPlanDO::getArrangedById)
                                .forEach(allowed::add);
                    // 领取子节点不授予兄弟节点资料权，也不能把当前评论推送给无权查看它的成员。
                    allowed.removeIf(member -> !nodeVisible(task, nodes, member));
                    Set<Long> recipients =
                            new LinkedHashSet<>(
                                    command.mentionedUserIds() == null
                                            ? List.of()
                                            : command.mentionedUserIds());
                    if (recipients.size() > 50 || !allowed.containsAll(recipients))
                        throw invalid("@只能选择有权查看当前任务的成员");
                    if (command.parentId() != null) {
                        TaskCommentDO parent = store.comment(command.parentId());
                        if (parent == null || !parent.getTaskId().equals(task.getId()))
                            throw invalid("回复评论不属于此任务");
                        long author = Long.parseLong(parent.getCreator());
                        AdminUserRespDTO authorUser = users.getUser(author);
                        if (allowed.contains(author)
                                && authorUser != null
                                && Integer.valueOf(0).equals(authorUser.getStatus()))
                            recipients.add(author);
                    }
                    users.validateUserList(recipients);
                    TaskCommentDO comment = new TaskCommentDO();
                    comment.setId(UUID.randomUUID().toString());
                    comment.setTaskId(task.getId());
                    comment.setParentId(command.parentId());
                    comment.setContent(content);
                    comment.setMentionedJson(
                            write(
                                    command.mentionedUserIds() == null
                                            ? List.of()
                                            : command.mentionedUserIds()));
                    comment.setRequestKey(key);
                    comment.setRequestHash(fingerprint);
                    comment.setCreator(Long.toString(actor));
                    comment.setCreateTime(LocalDateTime.now());
                    store.insertComment(comment, Long.toString(actor));
                    append(task, EventType.COMMENTED, content, null, null, null, actor);
                    recipients.remove(actor);
                    if (!recipients.isEmpty()) {
                        // 复用真实消息中心的事务写入与提交后投递；失败使评论一起回滚，原请求键可安全重试。
                        MsgSendParam message =
                                new MsgSendParam()
                                        .setMsgCode("nocode-task-comment")
                                        .setTitle("任务评论：" + task.getTitle())
                                        .setContent(
                                                HtmlUtils.htmlEscape(name(actor) + "：" + content))
                                        .setMsgData(
                                                Map.of(
                                                        "taskId",
                                                        task.getId(),
                                                        "commentId",
                                                        comment.getId()))
                                        .setSourceType("NOCODE_TASK")
                                        .setSourceId(task.getId())
                                        .setOwnerId(Long.toString(actor))
                                        .setOwnerName(name(actor))
                                        .setTargets(
                                                recipients.stream()
                                                        .map(
                                                                id ->
                                                                        new MsgTarget()
                                                                                .setTargetType(
                                                                                        MsgTargetType
                                                                                                .USER)
                                                                                .setTargetId(
                                                                                        Long
                                                                                                .toString(
                                                                                                        id))
                                                                                .setTargetName(
                                                                                        name(id)))
                                                        .toList());
                        if (messages.send(message) == null) throw invalid("评论提醒未成功保存，请重试");
                    }
                    return comment(comment);
                });
    }

    @Override
    public List<Member> members(String taskId, long actor) {
        if (taskId != null) {
            TaskInstanceDO task = require(taskId);
            List<TaskInstanceDO> nodes = store.instance(task.getRootId());
            if (!nodeVisible(task, nodes, actor)) throw invalid("没有查看此任务的权限");
            return users
                    .getUserList(
                            participants(
                                    nodes.stream()
                                            .filter(n -> nodeVisible(n, nodes, actor))
                                            .toList()))
                    .stream()
                    .filter(u -> Integer.valueOf(0).equals(u.getStatus()))
                    .map(u -> new Member(u.getId(), u.getNickname()))
                    .toList();
        }
        return userDirectory.getUserListByStatus(0).stream()
                .map(u -> new Member(u.getId(), u.getNickname()))
                .toList();
    }

    @Override
    public FormContext formPreview(Binding binding, long actor) {
        BusinessRef ref = business.resolve(binding, actor);
        if (ref == null) throw invalid("请选择业务表单");
        Sharing sharing = new Sharing(DataMode.INDEPENDENT, null, List.of());
        return new FormContext(
                ref,
                business.model(ref, binding, actor),
                business.definition(ref),
                null,
                business.writable(ref, binding, sharing, actor),
                null);
    }

    @Override
    public FormContext form(String id, long actor) {
        TaskInstanceDO task = require(id);
        List<TaskInstanceDO> nodes = store.instance(task.getRootId());
        if (!nodeVisible(task, nodes, actor)) throw invalid("没有查看此任务的权限");
        return form(task, nodes, actor);
    }

    private FormContext form(TaskInstanceDO task, List<TaskInstanceDO> nodes, long actor) {
        requireLegacyBusiness(task, nodes);
        BusinessRef ref = binding(task, nodes);
        if (ref == null) throw invalid("此任务没有业务表单");
        Binding configBinding = effectiveConfig(task, nodes).binding();
        ref = business.refresh(ref, actor);
        ApplicationRecords.Aggregate record = business.formRecord(ref, configBinding, actor);
        List<String> writable =
                Objects.equals(task.getAssigneeId(), actor)
                                && State.RUNNING.name().equals(task.getStatus())
                                && TaskPauses.pausedBy(task, nodes) == null
                        ? business.writable(ref, configBinding, config(task).sharing(), actor)
                        : List.of();
        return new FormContext(
                ref,
                business.model(ref, configBinding, actor),
                business.definition(ref),
                record,
                writable,
                business.result(ref, configBinding, actor));
    }

    @Override
    public FormContext saveBusiness(SaveBusiness command, long actor) {
        if (command == null || command.record() == null) throw invalid("缺少业务输入");
        String key = requestKey(command.record().requestKey());
        String fingerprint = hash(command);
        return tx.execute(
                status -> {
                    store.requestLock("task-business:" + actor + ":" + key);
                    TaskInstanceDO task = lock(command.taskId(), actor);
                    requireLegacyBusiness(task, store.instance(task.getRootId()));
                    TaskHistoryDO receipt = store.requested(Long.toString(actor), key);
                    if (receipt != null) {
                        same(receipt.getRequestHash(), fingerprint);
                        return form(task.getId(), actor);
                    }
                    revision(task, command.expectedRevision());
                    if (!Objects.equals(task.getAssigneeId(), actor)
                            || !State.RUNNING.name().equals(task.getStatus()))
                        throw invalid("只有进行中任务的负责人可以办理");
                    List<TaskInstanceDO> nodes = store.instance(task.getRootId());
                    TaskPauses.requireActive(task, nodes);
                    BusinessRef ref = business.refresh(binding(task, nodes), actor);
                    if (ref == null) throw invalid("任务没有配置业务表单");
                    ref =
                            business.save(
                                    ref,
                                    effectiveConfig(task, nodes).binding(),
                                    config(task).sharing(),
                                    command.record(),
                                    actor);
                    task.setBusinessJson(write(ref));
                    update(task, actor);
                    replace(nodes, task);
                    if (!task.getId().equals(task.getRootId())) touchRoot(task.getRootId(), actor);
                    append(
                            task,
                            EventType.BUSINESS_SAVED,
                            ref.requestId() == null ? "保存业务内容" : "提交业务审批申请",
                            new BusinessReceiptSnapshot(ref, false),
                            key,
                            fingerprint,
                            actor);
                    return form(task, nodes, actor);
                });
    }

    private record BusinessReceiptSnapshot(BusinessRef binding, boolean creation) {}

    @Override
    public BusinessHandling.Result businessReceipt(TaskForms.Receipt query, long actor) {
        if (query == null) throw invalid("缺少任务提交回执身份");
        String key = requestKey(query.requestKey());
        TaskInstanceDO task = require(query.taskId());
        List<TaskInstanceDO> nodes = store.instance(task.getRootId());
        if (!nodeVisible(task, nodes, actor)) throw invalid("没有查看此任务的权限");
        requireLegacyBusiness(task, nodes);
        TaskHistoryDO event = store.requested(Long.toString(actor), key);
        if (event == null) return null;
        if (!task.getId().equals(event.getTaskId())
                || !EventType.BUSINESS_SAVED.name().equals(event.getEventType()))
            throw invalid("回执键不属于本人在当前任务的业务提交");
        BusinessReceiptSnapshot snapshot =
                read(event.getMaterialJson(), BusinessReceiptSnapshot.class);
        BusinessRef ref = snapshot == null ? binding(task, nodes) : snapshot.binding();
        if (ref == null) throw invalid("当前任务无法确认该业务提交");
        return business.receipt(ref, effectiveConfig(task, nodes).binding(), key, actor);
    }

    @Override
    public TaskForms.CreatedReceipt createReceipt(TaskForms.CreateReceipt query, long actor) {
        if (query == null) throw invalid("缺少发起任务的业务回执身份");
        String key = requestKey(query.requestKey());
        TaskHistoryDO event = store.requested(Long.toString(actor), key);
        if (event == null) throw invalid("尚无法确认本次任务是否已发起，请保留原提交并刷新确认，不要重复新建");
        if (!EventType.BUSINESS_SAVED.name().equals(event.getEventType()))
            throw invalid("回执键不属于任务发起时的业务提交");
        BusinessReceiptSnapshot snapshot =
                read(event.getMaterialJson(), BusinessReceiptSnapshot.class);
        if (snapshot == null || !snapshot.creation()) throw invalid("当前回执不能用于恢复新发起任务");
        return new TaskForms.CreatedReceipt(
                event.getTaskId(),
                businessReceipt(new TaskForms.Receipt(event.getTaskId(), key), actor));
    }

    @Override
    public List<Template> templates(long actor) {
        return store.templates(Long.toString(actor), admin(actor)).stream()
                .map(
                        t -> {
                            if (admin(actor) || t.getCreator().equals(Long.toString(actor)))
                                return template(t);
                            TaskTemplateVersionDO version =
                                    store.version(t.getId(), requiredVersion(t));
                            return new Template(
                                    t.getId(),
                                    version.getName(),
                                    version.getDescription(),
                                    t.getLockVersion(),
                                    t.getPublishedVersion(),
                                    nodes(version.getNodesJson()),
                                    Long.parseLong(t.getCreator()),
                                    version.getCreateTime(),
                                    kind(version.getKind()),
                                    read(version.getRootJson(), NodeInput.class),
                                    requiredVersion(t));
                        })
                .toList();
    }

    @Override
    public PageResult<TemplateInstance> templateInstances(TemplateInstances input, long actor) {
        if (input == null || actor <= 0) throw invalid("缺少模板实例查询条件");
        String id = TaskGraph.text(input.templateId(), "任务模板", 200, true);
        TaskTemplateDO template = requireTemplate(id, false);
        boolean admin = admin(actor);
        if (template.getPublishedVersion() == null
                && !admin
                && !template.getCreator().equals(Long.toString(actor)))
            throw invalid("没有查看此任务模板的权限");
        if (input.version() != null && input.version() < 1) throw invalid("模板版本必须为正整数");
        TemplateInstances query =
                new TemplateInstances(
                        id,
                        input.version(),
                        TaskGraph.text(input.search(), "任务名称", 200, false),
                        input.status(),
                        Math.max(1, input.pageNo()),
                        input.pageSize() <= 0 ? 10 : Math.min(100, input.pageSize()));
        List<TemplateInstance> result = new ArrayList<>();
        for (TaskInstanceDO root :
                store.templateInstances(
                        query,
                        actor,
                        admin,
                        (query.pageNo() - 1) * query.pageSize(),
                        query.pageSize())) {
            List<TaskInstanceDO> nodes = TaskInstanceOrdering.instance(store, json, root.getId());
            Map<Long, String> names = ancestorNames(nodes);
            Map<String, TaskSchedules.Dates> schedules = TaskSchedules.display(nodes, this::config);
            List<TaskHistoryDO> events = store.events(root.getId());
            Map<String, String> workflowReasons = workflowReasons(nodes);
            // 与详情共用实时节点可见性；不可见根只返回原上级摘要允许的标题和状态。
            List<Row> visible =
                    nodes.stream()
                            .filter(node -> nodeVisible(node, nodes, actor))
                            .map(
                                    node ->
                                            row(
                                                    node,
                                                    nodes,
                                                    actor,
                                                    names,
                                                    events,
                                                    schedules,
                                                    workflowReasons))
                            .toList();
            if (visible.isEmpty()) continue;
            result.add(
                    new TemplateInstance(
                            root.getId(),
                            root.getTitle(),
                            root.getStatus(),
                            root.getTemplateVersion(),
                            visible.stream()
                                    .filter(node -> node.id().equals(root.getId()))
                                    .findFirst()
                                    .orElse(null),
                            visible.stream()
                                    .filter(node -> !node.id().equals(root.getId()))
                                    .toList()));
        }
        return new PageResult<>(result, store.templateInstanceCount(query, actor, admin));
    }

    @Override
    public Template saveTemplate(SaveTemplate command, long actor) {
        if (command == null) throw invalid("缺少模板");
        return tx.execute(
                status -> {
                    TaskTemplateGraph.Definition definition =
                            TaskTemplateGraph.normalize(command.task(), command.nodes(), actor);
                    dataPolicies.requireTemplateWorkRules(definition.all());
                    TaskDataPolicies.FrozenRoot authorization =
                            dataPolicies.freeze(definition.task(), actor);
                    if (authorization != null)
                        definition =
                                TaskTemplateGraph.normalize(
                                        authorization.task(), definition.nodes(), actor);
                    List<NodeInput> nodes = definition.nodes();
                    validateUsers(definition.all());
                    workEntries.validate(definition.all(), actor);
                    String rootJson = definition.task() == null ? null : write(definition.task());
                    String authorizationJson = dataPolicies.encode(authorization);
                    String name = TaskGraph.text(command.name(), "模板名称", 200, true);
                    String description = TaskGraph.text(command.description(), "模板说明", 4000, false);
                    TaskTemplateDO template;
                    if (command.id() == null) {
                        template = new TaskTemplateDO();
                        template.setId(UUID.randomUUID().toString());
                        template.setLockVersion(0);
                        template.setCreator(Long.toString(actor));
                        template.setName(name);
                        template.setDescription(description);
                        template.setNodesJson(write(nodes));
                        template.setRootJson(rootJson);
                        template.setAuthorizationJson(authorizationJson);
                        template.setKind(
                                (command.kind() == null ? Kind.ORDINARY : command.kind()).name());
                        store.insertTemplate(template, Long.toString(actor));
                    } else {
                        template = requireTemplate(command.id(), true);
                        templateOwner(template, actor);
                        if (!Objects.equals(template.getLockVersion(), command.expectedRevision()))
                            throw conflict();
                        template.setName(name);
                        template.setDescription(description);
                        template.setNodesJson(write(nodes));
                        template.setRootJson(rootJson);
                        template.setAuthorizationJson(authorizationJson);
                        if (command.kind() != null) template.setKind(command.kind().name());
                        if (store.updateTemplate(template, Long.toString(actor)) != 1)
                            throw conflict();
                    }
                    return template(requireTemplate(template.getId(), false));
                });
    }

    @Override
    public TemplateVersion publish(PublishTemplate command, long actor) {
        if (command == null) throw invalid("缺少模板发布参数");
        return tx.execute(
                status -> {
                    TaskTemplateDO head = requireTemplate(command.id(), true);
                    templateOwner(head, actor);
                    if (head.getLockVersion() != command.expectedRevision()) throw conflict();
                    TaskTemplateGraph.Definition definition =
                            TaskTemplateGraph.normalize(
                                    read(head.getRootJson(), NodeInput.class),
                                    nodes(head.getNodesJson()),
                                    actor);
                    dataPolicies.requireTemplateWorkRules(definition.all());
                    TaskDataPolicies.FrozenRoot authorization =
                            dataPolicies.freeze(definition.task(), actor);
                    if (authorization != null)
                        definition =
                                TaskTemplateGraph.normalize(
                                        authorization.task(), definition.nodes(), actor);
                    List<NodeInput> nodes = definition.nodes();
                    List<NodeInput> allNodes = definition.all();
                    validateUsers(allNodes);
                    workEntries.validate(allNodes, actor);
                    Map<String, BusinessRef> refs = new LinkedHashMap<>();
                    for (NodeInput node : allNodes) {
                        BusinessRef ref =
                                authorization != null
                                        ? authorization.grants().resources().stream()
                                                .filter(
                                                        g ->
                                                                TaskDataPolicies.BUSINESS.equals(
                                                                                g.entryKey())
                                                                        && node.parentId() == null)
                                                .map(TaskDataPolicyCompiler.ResourceGrant::ref)
                                                .findFirst()
                                                .orElse(null)
                                        : business.resolve(node.binding(), actor);
                        if (ref != null) refs.put(node.id(), ref);
                    }
                    for (NodeInput node : allNodes)
                        if (node.sharing().mode() == DataMode.SHARED) {
                            BusinessRef ref =
                                    templateBinding(
                                            node, TaskGraph.index(allNodes), refs, new HashSet<>());
                            if (ref == null) throw invalid("共享来源没有业务表单");
                            if (!business
                                    .model(
                                            ref,
                                            bindingSource(node, TaskGraph.index(allNodes))
                                                    .binding(),
                                            actor)
                                    .object()
                                    .fields()
                                    .stream()
                                    .map(FieldDefinition::id)
                                    .toList()
                                    .containsAll(node.sharing().writableFieldIds()))
                                throw invalid("共享补充字段不存在");
                        }
                    int next =
                            head.getPublishedVersion() == null ? 1 : head.getPublishedVersion() + 1;
                    TaskTemplateVersionDO version = new TaskTemplateVersionDO();
                    version.setId(UUID.randomUUID().toString());
                    version.setTemplateId(head.getId());
                    version.setVersionNo(next);
                    version.setName(head.getName());
                    version.setDescription(head.getDescription());
                    version.setKind(head.getKind());
                    version.setNodesJson(write(nodes));
                    version.setBindingsJson(write(refs));
                    version.setRootJson(
                            definition.task() == null ? null : write(definition.task()));
                    version.setAuthorizationJson(dataPolicies.encode(authorization));
                    store.insertVersion(version, Long.toString(actor));
                    if (authorization == null)
                        workEntries.publish(head.getId(), next, allNodes, actor);
                    head.setRootJson(version.getRootJson());
                    head.setAuthorizationJson(version.getAuthorizationJson());
                    // 发布序号始终单调递增；主版本可停留在历史快照，不能据此复用旧序号。
                    if (head.getPublishedVersion() == null
                            || !Boolean.FALSE.equals(command.setAsPrimary())) {
                        head.setPrimaryVersion(next);
                    } else if (head.getPrimaryVersion() == null) {
                        head.setPrimaryVersion(head.getPublishedVersion());
                    }
                    head.setPublishedVersion(next);
                    if (store.updateTemplate(head, Long.toString(actor)) != 1) throw conflict();
                    return version(head.getId(), next, actor);
                });
    }

    @Override
    public TemplateVersion version(String id, Integer version, long actor) {
        TaskTemplateDO head = requireTemplate(id, false);
        if (version != null && version < 1) throw invalid("模板版本必须为正整数");
        TaskTemplateVersionDO snapshot =
                store.version(id, version == null ? requiredVersion(head) : version);
        if (snapshot == null) throw invalid("模板发布版本不存在");
        return new TemplateVersion(
                id,
                snapshot.getVersionNo(),
                snapshot.getName(),
                snapshot.getDescription(),
                nodes(snapshot.getNodesJson()),
                snapshot.getCreateTime(),
                kind(snapshot.getKind()),
                read(snapshot.getRootJson(), NodeInput.class));
    }

    @Override
    public List<TemplateVersionSummary> versions(String id, long actor) {
        TaskTemplateDO head = requireTemplate(id, false);
        if (head.getPublishedVersion() == null) {
            templateOwner(head, actor);
            return List.of();
        }
        int primary = requiredVersion(head);
        return store.versions(id).stream()
                .map(
                        snapshot ->
                                new TemplateVersionSummary(
                                        snapshot.getVersionNo(),
                                        snapshot.getName(),
                                        snapshot.getDescription(),
                                        snapshot.getCreateTime(),
                                        nodes(snapshot.getNodesJson()).size(),
                                        snapshot.getVersionNo() == primary))
                .toList();
    }

    @Override
    public Template setPrimaryVersion(SetPrimaryTemplateVersion command, long actor) {
        if (command == null) throw invalid("缺少主版本参数");
        if (command.version() < 1) throw invalid("模板版本必须为正整数");
        return tx.execute(
                status -> {
                    // 与保存、发布共用模板头锁；即便重复选择主版本，也先拒绝过期修订号。
                    TaskTemplateDO head = requireTemplate(command.id(), true);
                    templateOwner(head, actor);
                    if (head.getLockVersion() != command.expectedRevision()) throw conflict();
                    if (store.version(head.getId(), command.version()) == null)
                        throw invalid("模板发布版本不存在");
                    if (!Objects.equals(head.getPrimaryVersion(), command.version())) {
                        if (store.updatePrimaryVersion(
                                        head.getId(),
                                        command.version(),
                                        command.expectedRevision(),
                                        Long.toString(actor))
                                != 1) throw conflict();
                        head = requireTemplate(head.getId(), false);
                    }
                    return template(head);
                });
    }

    @Override
    public SchedulePreview schedulePreview(SchedulePreviewQuery command, long actor) {
        if (command == null) throw invalid("缺少排期草稿");
        List<NodeInput> normalized = TaskGraph.normalize(command.nodes(), actor);
        String rootId =
                normalized.stream()
                        .filter(n -> n.parentId() == null)
                        .findFirst()
                        .orElseThrow()
                        .id();
        Map<String, NodeInput> configs = TaskGraph.index(normalized);
        LocalDateTime now = LocalDateTime.now();
        List<TaskInstanceDO> nodes = new ArrayList<>();
        // 客户端 ID 只参与本次内存图计算，不可借预览接口查询任意现存实例。
        for (NodeInput node : normalized) {
            TaskInstanceDO task = new TaskInstanceDO();
            task.setId(node.id());
            task.setRootId(rootId);
            task.setParentId(node.parentId());
            task.setTitle(node.title());
            task.setStatus(State.PENDING.name());
            task.setPlannedStart(command.plannedStart());
            task.setT0(now);
            nodes.add(task);
        }
        return TaskSchedules.preview(nodes, task -> configs.get(task.getId()));
    }

    @Override
    public AdjustmentPreview preview(Adjust command, long actor) {
        return tx.execute(status -> previewLocked(command, actor, false));
    }

    private AdjustmentPreview previewLocked(Adjust command, long actor, boolean apply) {
        if (command == null) throw invalid("缺少实例调整");
        TaskInstanceDO root = lock(command.rootId(), actor);
        if (!root.getId().equals(root.getRootId())) throw invalid("请选择完整任务实例");
        List<TaskInstanceDO> before = store.instance(root.getId());
        List<NodeInput> beforeSnapshot = before.stream().map(this::config).toList();
        if (!canManage(before, actor)) throw invalid("只有发起人或任务管理者可以调整实例");
        revision(root, command.expectedRevision());
        TaskPauses.requireActive(root, before);
        if (!executable(root)) throw invalid("待验收或已结束实例不能调整");
        String reason = TaskGraph.text(command.reason(), "调整原因", 1000, apply);
        List<NodeInput> adjustedNodes = new ArrayList<>();
        for (NodeInput node : command.nodes() == null ? List.<NodeInput>of() : command.nodes()) {
            if (node != null && root.getId().equals(node.id())) {
                Integer frozenMinutes = config(root).effectiveWorkMinutes();
                if (node.effectiveWorkMinutes() != null
                        && !Objects.equals(node.effectiveWorkMinutes(), frozenMinutes))
                    throw invalid("有效工作时长已按发起时的安排固定，不能在实例调整中修改");
                // 旧客户端省略新增字段时不能清掉已经冻结的工时。
                if (node.workTotalMode() != null
                        && !Objects.equals(
                                node.workTotalMode(),
                                config(root).workTotalMode() == null
                                        ? TaskWorkTimes.TotalMode.MANUAL
                                        : config(root).workTotalMode()))
                    throw invalid("请通过调整工时修改总工时计算方式");
                node = withEffectiveWorkMinutes(node, frozenMinutes, config(root).workTotalMode());
            }
            adjustedNodes.add(node);
        }
        List<NodeInput> normalized = TaskGraph.normalize(adjustedNodes, actor);
        Set<String> eligibleFollowers = new HashSet<>();
        Set<String> submittedFollowers = new HashSet<>();
        for (NodeInput n : normalized) {
            TaskInstanceDO previous =
                    before.stream().filter(p -> p.getId().equals(n.id())).findFirst().orElse(null);
            if (previous == null || State.PENDING.name().equals(previous.getStatus()))
                eligibleFollowers.add(n.id());
            if (previous == null
                    || config(previous).assignmentMode() != AssignmentMode.FOLLOW_ROOT
                    || !Objects.equals(previous.getParentId(), n.parentId())
                    || previous.getAssigneeId() != null && n.assigneeId() == null)
                submittedFollowers.add(n.id());
        }
        normalized = TaskAssignments.resolveFollowers(normalized, eligibleFollowers);
        normalized = TaskGraph.normalize(normalized, actor);
        TaskAssignments.requireResolvedFollowers(normalized, submittedFollowers);
        validateUsers(normalized);
        workEntries.validate(normalized, actor);
        Map<String, NodeInput> after = TaskGraph.index(normalized);
        if (!after.containsKey(root.getId()) || after.get(root.getId()).parentId() != null)
            throw invalid("不能替换实例根任务");
        validateUnifiedData(normalized, root.getId());
        NodeInput nextRoot = after.get(root.getId());
        TaskDataPolicies.FrozenRoot rootAuthorization =
                dataPolicies.decode(root.getAuthorizationJson());
        if (rootAuthorization == null && nextRoot.dataPolicy() != null)
            throw invalid("历史任务不能直接更换授权协议，请新建任务或使用新版模板");
        if (rootAuthorization != null) {
            if (nextRoot.dataPolicy() == null) throw invalid("不能移除总任务统一数据权限");
            NodeInput previousRoot = config(root);
            boolean resourceChanged =
                    !Objects.equals(previousRoot.dataPolicy(), nextRoot.dataPolicy())
                            || !Objects.equals(previousRoot.binding(), nextRoot.binding())
                            || !Objects.equals(previousRoot.entries(), nextRoot.entries());
            if (resourceChanged) {
                if (before.stream().anyMatch(n -> !State.PENDING.name().equals(n.getStatus())))
                    throw invalid("开始执行后不能更换总任务的业务资源或权限范围");
                rootAuthorization = dataPolicies.freeze(nextRoot, actor, rootAuthorization);
            } else {
                dataPolicies.requireSame(nextRoot, rootAuthorization);
            }
        }
        List<String> changed = new ArrayList<>(),
                added = new ArrayList<>(),
                removed = new ArrayList<>();
        Map<String, TaskInstanceDO> old = new HashMap<>();
        for (TaskInstanceDO task : before) {
            old.put(task.getId(), task);
            NodeInput next = after.get(task.getId());
            if (next == null) {
                TaskPauses.requireActive(task, before);
                if (!State.PENDING.name().equals(task.getStatus())) throw invalid("不能删除已执行节点");
                removed.add(task.getId());
            } else if (!Objects.equals(config(task), next)) {
                TaskPauses.requireActive(task, before);
                if (!State.PENDING.name().equals(task.getStatus()))
                    throw invalid("已开始节点配置已冻结；可使用列表拆分添加子任务");
                changed.add(task.getId());
            }
        }
        // 未开始不代表从未关联业务；预览与保存都在根锁内检查全部待删节点，不能只检查分支父节点。
        if (!removed.isEmpty() && store.hasRemovalEvidence(removed))
            throw invalid("待删除任务已有业务记录、关联或提交材料，请改为取消任务以保留历史");
        for (NodeInput node : normalized)
            if (!old.containsKey(node.id())) {
                if (store.get(node.id(), false) != null) throw invalid("新增节点标识已被其他实例使用");
                added.add(node.id());
            }
        // 任务管理权不授予已撤销的应用访问权；新增应用子任务与单独拆分共用授权边界。
        if (!added.isEmpty() && root.getApplicationId() != null) {
            published.getCurrent(root.getApplicationId());
            if (!approvedApplication(rootAuthorization, root.getApplicationId()))
                applicationPolicy.requireEntry(root.getApplicationId(), actor);
        }
        for (NodeInput node : normalized)
            if (added.contains(node.id())
                    || old.containsKey(node.id())
                            && !Objects.equals(old.get(node.id()).getParentId(), node.parentId())) {
                TaskInstanceDO parent = old.get(node.parentId());
                if (parent != null) TaskPauses.requireActive(parent, before);
                if (parent != null && ended(parent)) throw invalid("不能向已结束任务添加或移动子任务");
            }
        List<TaskInstanceDO> result = new ArrayList<>();
        Set<String> reordered = new HashSet<>();
        LocalDateTime plannedStart =
                command.plannedStart() == null ? root.getPlannedStart() : command.plannedStart();
        int displayOrder = 0;
        for (NodeInput node : normalized) {
            TaskInstanceDO task = old.get(node.id());
            if (task == null) {
                task = new TaskInstanceDO();
                task.setId(node.id());
                task.setRootId(root.getId());
                task.setStatus(State.PENDING.name());
                task.setKind(root.getKind());
                task.setLockVersion(0);
                task.setCreator(Long.toString(actor));
                task.setT0(root.getT0());
                task.setProjectJson(root.getProjectJson());
                task.setApplicationId(root.getApplicationId());
                task.setTemplateId(root.getTemplateId());
                task.setTemplateVersion(root.getTemplateVersion());
            }
            if (!Objects.equals(TaskInstanceOrdering.displayOrder(json, task), displayOrder))
                reordered.add(node.id());
            if (changed.contains(node.id()) || added.contains(node.id())) {
                NodeInput previous = old.containsKey(node.id()) ? config(task) : null;
                BusinessRef ref = read(task.getBusinessJson(), BusinessRef.class);
                if (previous != null
                        && !Objects.equals(previous.sharing(), node.sharing())
                        && ref != null
                        && (ref.recordId() != null || ref.requestId() != null))
                    throw invalid("已有业务内容的节点不能更换数据共享模式");
                if (previous == null || !Objects.equals(previous.binding(), node.binding())) {
                    if (ref != null && (ref.recordId() != null || ref.requestId() != null))
                        throw invalid("已有业务内容的节点不能更换表单");
                    if (rootAuthorization != null && node.id().equals(root.getId()))
                        ref =
                                rootAuthorization.grants().resources().stream()
                                        .filter(g -> TaskDataPolicies.BUSINESS.equals(g.entryKey()))
                                        .map(TaskDataPolicyCompiler.ResourceGrant::ref)
                                        .findFirst()
                                        .orElse(null);
                    else ref = business.resolve(node.binding(), actor);
                }
                task.setBusinessJson(ref == null ? null : write(ref));
                task.setTitle(node.title());
                task.setParentId(node.parentId());
                task.setAssigneeId(node.assigneeId());
            }
            // 只调整展示顺序时也需保存，不能依赖业务字段变化触发持久化。
            task.setConfigJson(TaskInstanceOrdering.config(json, node, displayOrder++));
            task.setPlannedStart(plannedStart);
            if (task.getId().equals(root.getId()))
                task.setAuthorizationJson(dataPolicies.encode(rootAuthorization));
            result.add(task);
        }
        validateShared(result, actor);
        calculate(result);
        List<String> affected = new ArrayList<>();
        for (TaskInstanceDO task : result) {
            TaskInstanceDO original = store.get(task.getId(), false);
            if (original == null
                    || !Objects.equals(task.getExpectedStart(), original.getExpectedStart())
                    || !Objects.equals(task.getExpectedEnd(), original.getExpectedEnd())
                    || !Objects.equals(task.getPlannedStart(), original.getPlannedStart()))
                affected.add(task.getId());
        }
        List<String> modified = new ArrayList<>(changed);
        normalized.stream()
                .map(NodeInput::id)
                .filter(reordered::contains)
                .filter(id -> !added.contains(id) && !modified.contains(id))
                .forEach(modified::add);
        AdjustmentPreview preview =
                new AdjustmentPreview(
                        modified,
                        added,
                        removed,
                        affected,
                        TaskSchedules.preview(result, this::config));
        if (apply) {
            for (String id : removed) store.removeTask(id, Long.toString(actor));
            for (TaskInstanceDO task : result) {
                if (added.contains(task.getId())) {
                    task.setBaselineStart(task.getExpectedStart());
                    task.setBaselineEnd(task.getExpectedEnd());
                    store.insertTask(task, Long.toString(actor));
                } else if (changed.contains(task.getId())
                        || reordered.contains(task.getId())
                        || affected.contains(task.getId())) {
                    TaskInstanceDO previous = store.get(task.getId(), false);
                    if (!Objects.equals(previous.getAssigneeId(), task.getAssigneeId()))
                        store.removeTaskPlans(task.getId(), Long.toString(actor));
                    update(task, actor);
                }
            }
            workEntries.synchronize(root.getId(), actor);
            touchRoot(root.getId(), actor);
            append(
                    root,
                    EventType.ADJUSTED,
                    reason,
                    Map.of("before", beforeSnapshot, "after", normalized, "impact", preview),
                    null,
                    null,
                    actor);
        }
        return preview;
    }

    @Override
    public Detail adjust(Adjust command, long actor) {
        return tx.execute(
                status -> {
                    previewLocked(command, actor, true);
                    return detail(command.rootId(), actor);
                });
    }

    /** 新协议只允许总任务配置资源，兼容请求不能通过子节点单独追加授权。 */
    private void validateUnifiedData(List<NodeInput> nodes, String rootId) {
        NodeInput root =
                nodes.stream().filter(n -> rootId.equals(n.id())).findFirst().orElseThrow();
        for (NodeInput node : nodes) {
            if (node.id().equals(rootId)) continue;
            if (node.dataPolicy() != null) throw invalid("子任务统一继承总任务数据权限，不能单独配置");
            if (root.dataPolicy() != null
                    && (node.binding() != null
                            || node.entries() != null && !node.entries().isEmpty()
                            || node.sharing().mode() != DataMode.INDEPENDENT))
                throw invalid("子任务统一使用总任务的业务数据和反馈资源，不能单独配置");
        }
    }

    private boolean approvedApplication(TaskDataPolicies.FrozenRoot authorization, String app) {
        return authorization != null
                && authorization.grants().resources().stream()
                        .anyMatch(g -> app.equals(g.binding().applicationId()));
    }

    private boolean unifiedData(TaskInstanceDO task, List<TaskInstanceDO> nodes) {
        return nodes.stream()
                .anyMatch(
                        n ->
                                n.getId().equals(task.getRootId())
                                        && n.getAuthorizationJson() != null);
    }

    private void requireLegacyBusiness(TaskInstanceDO task, List<TaskInstanceDO> nodes) {
        if (unifiedData(task, nodes)) throw invalid("此任务使用总任务统一数据授权，请在业务数据或过程反馈列表中办理");
    }

    private Query query(Query q, boolean page) {
        if (q == null)
            q =
                    new Query(
                            page ? "VISIBLE" : "MINE",
                            page ? "ALL" : "TODAY",
                            LocalDate.now(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            1,
                            20);
        String scope = q.scope() == null ? "MINE" : q.scope();
        if (!Set.of("MINE", "MANAGE", "VISIBLE").contains(scope)) throw invalid("任务范围无效");
        String tab = q.tab() == null ? (page ? "ALL" : "TODAY") : q.tab();
        if (!Set.of(
                        "TODO",
                        "TODAY",
                        "WEEK",
                        "MONTH",
                        "POOL",
                        "RECENT",
                        "ALL",
                        "CLAIMABLE",
                        "ACCEPTANCE",
                        "DONE")
                .contains(tab)) throw invalid("任务视图无效");
        if (Boolean.TRUE.equals(q.rootsOnly())
                && (page || !"MANAGE".equals(scope) || !"ALL".equals(tab)))
            throw invalid("整体任务分页仅支持任务管理的全部视图");
        if ("CLAIMABLE".equals(tab)
                && (page || q.project() != null || TaskGraph.blank(q.entryId()) != null))
            throw invalid("可领取视图不支持业务记录或入口筛选，请在领取后使用业务任务视图");
        if ("ACCEPTANCE".equals(tab) && (page || !"MINE".equals(scope)))
            throw invalid("待我验收仅支持个人任务视图");
        if ("TODO".equals(tab) && (page || !"MINE".equals(scope))) throw invalid("我的待办仅支持个人任务视图");
        if ("DONE".equals(tab) && (page || !"MINE".equals(scope))) throw invalid("已办记录仅支持个人任务视图");
        if (q.personalScope() != null && (page || !"MINE".equals(scope) || !"TODO".equals(tab)))
            throw invalid("执行与跟进筛选仅支持我的待办");
        boolean calendar = Set.of("TODAY", "WEEK", "MONTH").contains(tab);
        if (page
                && (q.scheduleScope() != null
                        || q.assigneeId() != null
                        || q.planFilter() != null
                        || q.planMode() != null)) throw invalid("应用嵌入任务不支持个人或团队计划筛选");
        if (q.scheduleScope() == TaskPlanning.Scope.TEAM
                && (!"MANAGE".equals(scope) || Boolean.TRUE.equals(q.rootsOnly())))
            throw invalid("团队计划仅支持有权管理的逐任务范围");
        if (q.assigneeId() != null
                && (q.assigneeId() <= 0 || q.scheduleScope() != TaskPlanning.Scope.TEAM))
            throw invalid("仅团队计划可以按负责人筛选");
        if ((q.scheduleScope() != null || q.planFilter() != null || q.planMode() != null)
                && (!calendar && !Set.of("TODO", "POOL", "ALL").contains(tab)
                        || Boolean.TRUE.equals(q.rootsOnly()))) throw invalid("当前视图不支持统一计划筛选");
        if (q.planFilter() == TaskPlanning.Filter.COARSE && !calendar)
            throw invalid("待细化计划需要选择日、周或月视图");
        if (q.planMode() == TaskPlanning.Mode.CHECKLIST) {
            if ("MONTH".equals(tab) || q.planFilter() == TaskPlanning.Filter.COARSE)
                throw invalid("清单仅支持今日和本周、下周，不使用月计划或待细化区间");
            if (q.planFilter() == TaskPlanning.Filter.CARRYOVER
                    && !Set.of("TODAY", "WEEK").contains(tab)) throw invalid("过往未完成清单需要选择今日或周计划");
        }
        if (q.category() != null
                && !q.category().isBlank()
                && !Set.of("PROJECT", "DAILY").contains(q.category())) throw invalid("任务分类无效");
        if (q.status() != null && !q.status().isBlank()) {
            try {
                State.valueOf(q.status());
            } catch (IllegalArgumentException e) {
                throw invalid("执行状态无效");
            }
        }
        if (q.from() != null && q.to() != null && q.from().isAfter(q.to()))
            throw invalid("筛选开始日期不能晚于结束日期");
        LocalDate date = q.date() == null ? LocalDate.now() : q.date();
        if (q.planMode() == TaskPlanning.Mode.CHECKLIST
                && q.planFilter() == TaskPlanning.Filter.UNPLANNED) date = LocalDate.now();
        if ("WEEK".equals(tab)) date = periodDate(Period.WEEK, date);
        if ("MONTH".equals(tab)) date = periodDate(Period.MONTH, date);
        Period recentPeriod = q.recentPeriod() == null ? Period.DAY : q.recentPeriod();
        LocalDate from = q.from(), to = q.to();
        if ("RECENT".equals(tab) && from == null && to == null) {
            from = periodDate(recentPeriod, date);
            to =
                    switch (recentPeriod) {
                        case DAY -> from;
                        case WEEK -> from.plusDays(6);
                        case MONTH -> from.plusMonths(1).minusDays(1);
                    };
        }
        return new Query(
                scope,
                tab,
                date,
                TaskGraph.text(q.search(), "搜索", 200, false),
                q.category(),
                q.project(),
                q.status(),
                q.urgency(),
                q.priority(),
                q.entryId(),
                from,
                to,
                Math.max(1, q.pageNo()),
                q.pageSize() <= 0 ? 20 : Math.min(q.pageSize(), 200),
                recentPeriod,
                q.kind(),
                q.assignmentMode(),
                q.rootsOnly(),
                q.scheduleScope(),
                q.assigneeId(),
                q.planFilter(),
                q.planMode(),
                q.personalScope());
    }

    private Map<String, String> workflowReasons(List<TaskInstanceDO> nodes) {
        return workflowProtection.readOnlyReasons(
                nodes.stream().map(TaskInstanceDO::getRootId).distinct().toList());
    }

    private Row row(TaskInstanceDO task, List<TaskInstanceDO> nodes, long actor) {
        return row(
                task,
                nodes,
                actor,
                nodeVisible(task, nodes, actor) ? ancestorNames(nodes) : Map.of());
    }

    private Row row(
            TaskInstanceDO task,
            List<TaskInstanceDO> nodes,
            long actor,
            Map<Long, String> contextNames) {
        return row(task, nodes, actor, contextNames, store.events(task.getRootId()));
    }

    private Row row(
            TaskInstanceDO task,
            List<TaskInstanceDO> nodes,
            long actor,
            Map<Long, String> contextNames,
            List<TaskHistoryDO> instanceEvents) {
        return row(
                task,
                nodes,
                actor,
                contextNames,
                instanceEvents,
                TaskSchedules.display(nodes, this::config));
    }

    private Row row(
            TaskInstanceDO task,
            List<TaskInstanceDO> nodes,
            long actor,
            Map<Long, String> contextNames,
            List<TaskHistoryDO> instanceEvents,
            Map<String, TaskSchedules.Dates> schedules) {
        return row(
                task,
                nodes,
                actor,
                contextNames,
                instanceEvents,
                schedules,
                workflowReasons(nodes));
    }

    private Row row(
            TaskInstanceDO task,
            List<TaskInstanceDO> nodes,
            long actor,
            Map<Long, String> contextNames,
            List<TaskHistoryDO> instanceEvents,
            Map<String, TaskSchedules.Dates> schedules,
            Map<String, String> workflowReasons) {
        NodeInput config = config(task);
        boolean summary = !nodeVisible(task, nodes, actor);
        String workflowReason = workflowReasons.get(task.getRootId());
        boolean workflowActive = workflowReason == null;
        TaskInstanceDO root =
                nodes.stream()
                        .filter(n -> n.getId().equals(task.getRootId()))
                        .findFirst()
                        .orElse(task);
        List<Plan> plans =
                task.getAssigneeId() == null || summary
                        ? List.of()
                        : scheduling
                                .effectivePlans(
                                        task,
                                        nodes,
                                        canViewPlans(task, nodes, actor)
                                                ? task.getAssigneeId()
                                                : actor,
                                        actor,
                                        scheduleAccess())
                                .stream()
                                .map(
                                        p ->
                                                scheduling.view(
                                                        p,
                                                        workflowActive
                                                                && plannable(task)
                                                                && nodes.stream()
                                                                        .noneMatch(
                                                                                n ->
                                                                                        State
                                                                                                .PENDING_ACCEPTANCE
                                                                                                .name()
                                                                                                .equals(
                                                                                                        n
                                                                                                                .getStatus()))
                                                                && TaskScheduling.mode(p)
                                                                        == TaskPlanning.Mode
                                                                                .CHECKLIST
                                                                && TaskChecklists.current(
                                                                        p, LocalDate.now())
                                                                && canArrange(task, nodes, actor)
                                                                && Objects.equals(
                                                                        p.getUserId(), actor),
                                                        scheduleAccess()))
                                .toList();
        String blocked = workflowActive ? blocked(task, nodes, contextNames) : workflowReason;
        String pausedBy = TaskPauses.pausedBy(task, nodes);
        boolean pauseManager =
                !summary
                        && workflowActive
                        && (Objects.equals(task.getAssigneeId(), actor) || canManage(nodes, actor));
        String deleteReason =
                !workflowActive
                        ? workflowReason
                        : subtaskDeletionReason(task, nodes, actor, instanceEvents);
        TaskSchedules.Dates dates = schedules.get(task.getId());
        return new Row(
                task.getId(),
                task.getRootId(),
                summary ? null : task.getParentId(),
                task.getTitle(),
                summary ? null : config.description(),
                task.getStatus(),
                Long.parseLong(task.getCreator()),
                name(Long.parseLong(task.getCreator())),
                task.getAssigneeId(),
                name(task.getAssigneeId()),
                summary ? null : visibleProject(task, actor),
                summary ? null : config.urgency(),
                summary ? null : config.priority(),
                summary ? null : config.schedule(),
                summary ? List.of() : config.predecessorIds(),
                summary ? null : effectiveConfig(task, nodes).binding(),
                summary ? new Sharing(DataMode.INDEPENDENT, null, List.of()) : config.sharing(),
                summary ? null : binding(task, nodes),
                summary ? null : task.getBaselineStart(),
                summary ? null : task.getBaselineEnd(),
                summary ? null : dates.start(),
                summary ? null : dates.end(),
                summary ? null : task.getActualStart(),
                summary ? null : task.getActualEnd(),
                summary ? null : task.getCreateTime(),
                task.getLockVersion(),
                summary ? task.getLockVersion() : root.getLockVersion(),
                summary
                        ? 0
                        : (int)
                                nodes.stream()
                                        .filter(
                                                n ->
                                                        Objects.equals(
                                                                        n.getParentId(),
                                                                        task.getId())
                                                                && nodeVisible(n, nodes, actor))
                                        .count(),
                plans,
                !summary && blocked == null && Objects.equals(task.getAssigneeId(), actor),
                !summary
                        && workflowActive
                        && Objects.equals(task.getAssigneeId(), actor)
                        && executable(task)
                        && pausedBy == null,
                !summary && workflowActive && canEdit(task, nodes, actor),
                summary ? null : blocked,
                summary ? null : task.getTemplateId(),
                summary ? null : task.getTemplateVersion(),
                !summary
                        && workflowActive
                        && task.getAssigneeId() != null
                        && canArrange(task, nodes, actor)
                        && plannable(task),
                summary ? null : task.getExplicitLinkId(),
                workflowActive && task.getExplicitLinkId() != null && canLink(task, nodes, actor),
                task.getLastHandledAt(),
                kind(task.getKind()),
                task.getId().equals(task.getRootId())
                        ? NodeRole.ROOT
                        : Objects.equals(task.getParentId(), task.getRootId())
                                ? NodeRole.NODE
                                : NodeRole.SUBTASK,
                summary ? List.of() : config.entries(),
                summary ? null : application(root),
                TaskAssignments.mode(config),
                summary ? List.of() : TaskAssignments.candidates(config),
                !summary
                        && workflowActive
                        && assignments.canClaim(task, config, root, nodes, actor),
                !summary
                        && workflowActive
                        && pausedBy == null
                        && State.PENDING.name().equals(task.getStatus())
                        && canManage(nodes, actor),
                summary ? null : root.getPlannedStart(),
                config.assignmentMode() == null,
                summary ? null : config(root).dataPolicy(),
                task.getId().equals(task.getRootId())
                        ? TaskStatuses.groupStatus(task, nodes)
                        : null,
                summary ? List.of() : ancestorContext(task, nodes, actor, contextNames),
                summary ? null : config.acceptorId(),
                summary ? null : name(config.acceptorId()),
                !summary && workflowActive && canAccept(task, actor),
                workflowActive
                        && pausedBy == null
                        && assignments.canDelegate(task, root, nodes, instanceEvents, actor),
                summary ? null : completionReason(task, nodes, instanceEvents),
                summary || !task.getId().equals(task.getRootId())
                        ? null
                        : config.effectiveWorkMinutes(),
                pauseManager && State.RUNNING.name().equals(task.getStatus()) && pausedBy == null,
                pauseManager
                        && State.PAUSED.name().equals(task.getStatus())
                        && task.getId().equals(pausedBy),
                summary ? null : pausedBy,
                summary ? null : TaskPauses.reason(task, nodes),
                deleteReason == null,
                summary ? null : deleteReason,
                summary
                        ? null
                        : (int)
                                nodes.stream()
                                        .filter(
                                                n ->
                                                        Objects.equals(
                                                                        n.getParentId(),
                                                                        task.getId())
                                                                && nodeVisible(n, nodes, actor)
                                                                && State.COMPLETED
                                                                        .name()
                                                                        .equals(n.getStatus()))
                                        .count(),
                summary ? null : dates.summary(),
                workflowActive
                        && assignments.canTransfer(task, root, nodes, instanceEvents, actor));
    }

    /** 仅投影同一实例内真实上级的白名单字段；不把摘要混入有详情权限的节点集合。 */
    private List<AncestorContext> ancestorContext(
            TaskInstanceDO task, List<TaskInstanceDO> nodes, long actor, Map<Long, String> names) {
        Map<String, TaskInstanceDO> index = taskIndex(task, nodes);
        List<TaskInstanceDO> ancestors = new ArrayList<>();
        Set<String> visited = new HashSet<>(Set.of(task.getId()));
        String parentId = task.getParentId();
        while (parentId != null && visited.add(parentId)) {
            TaskInstanceDO parent = index.get(parentId);
            if (parent == null) break;
            ancestors.addFirst(parent);
            parentId = parent.getParentId();
        }
        if (ancestors.isEmpty()) return List.of();
        return ancestors.stream()
                .map(
                        parent ->
                                new AncestorContext(
                                        parent.getId(),
                                        parent.getParentId(),
                                        parent.getTitle(),
                                        parent.getAssigneeId() == null
                                                ? unassignedName(parent)
                                                : names.getOrDefault(
                                                        parent.getAssigneeId(),
                                                        Long.toString(parent.getAssigneeId())),
                                        parent.getStatus(),
                                        nodeVisible(parent, nodes, actor)))
                .toList();
    }

    /** 同一响应缓存上级及前置等待人员名称；树已一次加载，不逐级或逐行追加名称查询。 */
    private Map<Long, String> ancestorNames(List<TaskInstanceDO> nodes) {
        Set<String> parentIds = new HashSet<>();
        nodes.forEach(
                node -> {
                    if (node.getParentId() != null) parentIds.add(node.getParentId());
                    parentIds.addAll(config(node).predecessorIds());
                });
        Set<Long> userIds = new HashSet<>();
        nodes.forEach(
                node -> {
                    if (parentIds.contains(node.getId()) && node.getAssigneeId() != null)
                        userIds.add(node.getAssigneeId());
                });
        Map<Long, String> names = new HashMap<>();
        if (!userIds.isEmpty())
            users.getUserList(userIds)
                    .forEach(
                            user -> {
                                String nickname = TaskGraph.blank(user.getNickname());
                                names.put(
                                        user.getId(),
                                        nickname == null ? Long.toString(user.getId()) : nickname);
                            });
        return names;
    }

    private Map<String, TaskInstanceDO> taskIndex(TaskInstanceDO task, List<TaskInstanceDO> nodes) {
        Map<String, TaskInstanceDO> index = new HashMap<>();
        nodes.stream()
                .filter(node -> Objects.equals(node.getRootId(), task.getRootId()))
                .forEach(node -> index.put(node.getId(), node));
        return index;
    }

    private String unassignedName(TaskInstanceDO task) {
        return TaskAssignments.mode(config(task)) == AssignmentMode.OPEN ? "待领取" : "未分配负责人";
    }

    private RecordRef visibleProject(TaskInstanceDO task, long actor) {
        RecordRef project = read(task.getProjectJson(), RecordRef.class);
        if (project == null) return null;
        // 项目仅是可选展示。保存点隔离内部公共读取的权限失败，不污染已经成功的任务命令。
        // 同一连接保留新任务/关联种子的可见性，也不会因外层根锁与新事务互相等待。
        return tx.execute(
                status -> {
                    // 不能让保存点回滚清掉此前真实业务失败的 rollback-only 标记。
                    if (status.isRollbackOnly()) return null;
                    try {
                        return projectProbe.execute(
                                ignored -> readableProject(task, project, actor));
                    } catch (ServiceException denied) {
                        return null;
                    }
                });
    }

    private RecordRef readableProject(TaskInstanceDO task, RecordRef project, long actor) {
        TaskDataPolicies.FrozenRoot approved = groupRuntime.policy(task.getId());
        if (approved != null
                && approved.grants().resources().stream()
                        .anyMatch(
                                g ->
                                        TaskDataPolicies.BUSINESS.equals(g.entryKey())
                                                && Objects.equals(
                                                        g.ref().resource().applicationId(),
                                                        project.applicationId())
                                                && Objects.equals(
                                                        g.ref().object().objectId(),
                                                        project.objectId())))
            return groupRuntime.execute(
                    task.getId(),
                    TaskDataPolicies.BUSINESS,
                    actor,
                    false,
                    () -> {
                        business.project(project, actor);
                        return project;
                    });
        business.project(project, actor);
        return project;
    }

    /** 历史任务只读推导归属，避免迁移改写存量实例；不从过程反馈反推所属应用。 */
    private String application(TaskInstanceDO root) {
        if (root.getApplicationId() != null) return root.getApplicationId();
        RecordRef project = read(root.getProjectJson(), RecordRef.class);
        if (project != null) return project.applicationId();
        Binding binding = config(root).binding();
        return binding == null ? null : binding.applicationId();
    }

    private String blocked(TaskInstanceDO task, List<TaskInstanceDO> nodes) {
        return blocked(task, nodes, null);
    }

    private String blocked(
            TaskInstanceDO task, List<TaskInstanceDO> nodes, Map<Long, String> names) {
        String pauseReason = TaskPauses.reason(task, nodes);
        if (pauseReason != null) return pauseReason;
        if (!State.PENDING.name().equals(task.getStatus()))
            return State.RUNNING.name().equals(task.getStatus())
                    ? "任务已开始"
                    : State.PENDING_ACCEPTANCE.name().equals(task.getStatus())
                            ? "任务正在等待验收"
                            : "任务已结束";
        Map<String, TaskInstanceDO> index = taskIndex(task, nodes);
        Set<String> visited = new HashSet<>(Set.of(task.getId()));
        List<TaskInstanceDO> ancestors = new ArrayList<>();
        String parentId = task.getParentId();
        while (parentId != null) {
            if (!visited.add(parentId)) return "任务层级存在循环，请联系任务管理者调整";
            TaskInstanceDO parent = index.get(parentId);
            if (parent == null) return "上级任务不存在，请联系任务管理者调整";
            ancestors.addFirst(parent);
            parentId = parent.getParentId();
        }
        // 从总任务向下说明最先等待的一层；条件与原整条祖先链门控保持一致。
        for (TaskInstanceDO parent : ancestors) {
            if (ended(parent)) return "上级任务" + waitingTask(parent, names) + "已结束，不能开始下级任务";
            boolean pendingSummary =
                    parent.getAssigneeId() == null
                            && config(parent).assignmentMode() != null
                            && State.PENDING.name().equals(parent.getStatus());
            if (!State.RUNNING.name().equals(parent.getStatus()) && !pendingSummary)
                return "等待上级任务" + waitingTask(parent, names) + "开始";
            // 汇总父项可不先安排负责人，但下级仍须继承整条父链的前置依赖。
            String blocked = prerequisitesBlocked(parent, index, true, names);
            if (blocked != null) return blocked;
        }
        return prerequisitesBlocked(task, index, false, names);
    }

    /** 等待说明仅给出真正阻塞节点的名称与人员，不输出私有前置任务内容。 */
    private String prerequisitesBlocked(
            TaskInstanceDO task,
            Map<String, TaskInstanceDO> index,
            boolean ancestor,
            Map<Long, String> names) {
        NodeInput node = config(task);
        String prefix = ancestor ? "上级任务「" + task.getTitle() + "」的" : "";
        for (String id : node.predecessorIds()) {
            TaskInstanceDO predecessor = index.get(id);
            if (predecessor == null) return prefix + "前置任务不存在，请联系任务管理者调整";
            if (!State.COMPLETED.name().equals(predecessor.getStatus()))
                return prefix + "前置任务" + waitingTask(predecessor, names) + "尚未完成";
        }
        // 预计日期仅用于排期参考；可提前开始，实际日期另存，不改写原预计日期。
        return null;
    }

    private String waitingTask(TaskInstanceDO task, Map<Long, String> names) {
        String assignee =
                task.getAssigneeId() == null
                        ? unassignedName(task)
                        : names == null
                                ? name(task.getAssigneeId())
                                : names.getOrDefault(
                                        task.getAssigneeId(), Long.toString(task.getAssigneeId()));
        return "「" + task.getTitle() + "」（负责人：" + assignee + "）";
    }

    private void calculate(List<TaskInstanceDO> nodes) {
        TaskSchedules.calculate(nodes, this::config);
    }

    private void recalculate(List<TaskInstanceDO> nodes, long actor) {
        Map<String, LocalDateTime> oldStart = new HashMap<>(), oldEnd = new HashMap<>();
        nodes.forEach(
                n -> {
                    oldStart.put(n.getId(), n.getExpectedStart());
                    oldEnd.put(n.getId(), n.getExpectedEnd());
                });
        calculate(nodes);
        for (TaskInstanceDO node : nodes)
            if (!Objects.equals(oldStart.get(node.getId()), node.getExpectedStart())
                    || !Objects.equals(oldEnd.get(node.getId()), node.getExpectedEnd()))
                update(node, actor);
    }

    private BusinessRef binding(TaskInstanceDO task, List<TaskInstanceDO> nodes) {
        NodeInput node = config(task);
        if (node.sharing().mode() != DataMode.SHARED)
            return read(task.getBusinessJson(), BusinessRef.class);
        TaskInstanceDO source =
                nodes.stream()
                        .filter(n -> n.getId().equals(node.sharing().sourceNodeId()))
                        .findFirst()
                        .orElseThrow(() -> invalid("共享来源不存在"));
        BusinessRef sourceRef = binding(source, nodes);
        BusinessRef own = read(task.getBusinessJson(), BusinessRef.class);
        if (own != null && own.requestId() != null) {
            if (sourceRef == null
                    || !Objects.equals(own.recordId(), sourceRef.recordId())
                    || !own.object().equals(sourceRef.object())) throw invalid("共享记录身份已变化");
            return own;
        }
        return sourceRef;
    }

    private NodeInput effectiveConfig(TaskInstanceDO task, List<TaskInstanceDO> nodes) {
        NodeInput node = config(task);
        if (node.sharing().mode() != DataMode.SHARED) return node;
        return effectiveConfig(
                nodes.stream()
                        .filter(n -> n.getId().equals(node.sharing().sourceNodeId()))
                        .findFirst()
                        .orElseThrow(() -> invalid("共享来源不存在")),
                nodes);
    }

    private void validateShared(List<TaskInstanceDO> nodes, long actor) {
        for (TaskInstanceDO node : nodes)
            if (config(node).sharing().mode() == DataMode.SHARED) {
                BusinessRef ref = binding(node, nodes);
                if (ref == null) throw invalid("共享来源没有业务表单");
                List<String> fields =
                        business
                                .model(ref, effectiveConfig(node, nodes).binding(), actor)
                                .object()
                                .fields()
                                .stream()
                                .map(FieldDefinition::id)
                                .toList();
                if (!fields.containsAll(config(node).sharing().writableFieldIds()))
                    throw invalid("共享补充字段不存在");
            }
    }

    /**
     * 显式子图是本次实例的完整编排，不回写模板。原节点业务资源仍使用批准快照；新增节点只能继承总任务， 不能借新增设计 ID 绕过模板资源约束。省略 nodes 的历史调用不经过本次调整分支。
     */
    private List<NodeInput> templateLaunchNodes(
            NodeInput root,
            List<NodeInput> requested,
            NodeInput templateRoot,
            List<NodeInput> templateNodes,
            String generatedRootId,
            long actor) {
        NodeInput validationRoot =
                creationGraphRoot(root, templateRoot == null ? generatedRootId : templateRoot.id());
        TaskTemplateGraph.Definition graph =
                TaskTemplateGraph.normalize(validationRoot, requested, actor);
        if (templateRoot != null) requireTemplateResources(graph.task(), templateRoot);
        Map<String, NodeInput> approved = TaskGraph.index(templateNodes);
        for (NodeInput node : graph.nodes()) {
            NodeInput original = approved.get(node.id());
            if (original != null) {
                requireTemplateResources(node, original);
            } else if (node.binding() != null
                    || node.entries() != null && !node.entries().isEmpty()
                    || node.dataPolicy() != null
                    || node.sharing().mode() != DataMode.INDEPENDENT) {
                throw invalid("模板发起时新增子任务只能继承总任务的业务数据和反馈资源");
            }
        }
        return graph.nodes();
    }

    /** 空集合与省略按原配置默认值比较；固定资源、共享来源及字段范围不得伪装为普通节点编辑。 */
    private void requireTemplateResources(NodeInput input, NodeInput approved) {
        Sharing defaultSharing = new Sharing(DataMode.INDEPENDENT, null, List.of());
        if (!Objects.equals(input.binding(), approved.binding())
                || !Objects.equals(input.dataPolicy(), approved.dataPolicy())
                || !Objects.equals(
                        input.sharing() == null ? defaultSharing : input.sharing(),
                        approved.sharing() == null ? defaultSharing : approved.sharing()))
            throw invalid("模板的业务资源和数据权限已经固定，请由模板发布者修改后重新发布");
        dataPolicies.requireLaunchEntries(input.entries(), approved.entries());
    }

    /** 新建外部总任务允许省略设计 ID；使用服务端新 ID 校验子图，避免与旧子节点 ID 冲突。 */
    private NodeInput creationGraphRoot(NodeInput node, String id) {
        return new NodeInput(
                id,
                null,
                node.title(),
                node.description(),
                node.assigneeId(),
                node.urgency(),
                node.priority(),
                node.schedule(),
                List.of(),
                node.binding(),
                node.sharing(),
                node.entries(),
                node.assignmentMode(),
                node.candidateUserIds(),
                node.dataPolicy(),
                node.acceptorId(),
                node.effectiveWorkMinutes(),
                node.workTotalMode());
    }

    private NodeInput remap(NodeInput node, Map<String, String> ids, String original) {
        Sharing sharing = node.sharing();
        return new NodeInput(
                ids.get(original),
                node.parentId() == null ? null : ids.get(node.parentId()),
                node.title(),
                node.description(),
                node.assigneeId(),
                node.urgency(),
                node.priority(),
                node.schedule(),
                node.predecessorIds().stream().map(ids::get).toList(),
                node.binding(),
                new Sharing(
                        sharing.mode(),
                        sharing.sourceNodeId() == null ? null : ids.get(sharing.sourceNodeId()),
                        sharing.writableFieldIds()),
                node.entries() == null
                        ? null
                        : node.entries().stream()
                                .map(
                                        entry ->
                                                new com.richuang.os.nocode.api.TaskWorkEntries
                                                        .Config(
                                                        entry.key(),
                                                        entry.name(),
                                                        entry.binding(),
                                                        entry.dataMode(),
                                                        entry.sourceNodeId() == null
                                                                ? null
                                                                : ids.get(entry.sourceNodeId()),
                                                        entry.sourceEntryKey(),
                                                        entry.readableFieldIds(),
                                                        entry.writableFieldIds(),
                                                        entry.required(),
                                                        entry.allowAll(),
                                                        entry.workRule(),
                                                        entry.dataScope()))
                                .toList(),
                node.assignmentMode(),
                node.candidateUserIds(),
                node.dataPolicy(),
                node.acceptorId(),
                node.effectiveWorkMinutes(),
                node.workTotalMode());
    }

    /** 编排兼容旧请求时只补回冻结工时，其余字段仍由原调整权限和状态校验负责。 */
    private NodeInput withEffectiveWorkMinutes(
            NodeInput node, Integer minutes, TaskWorkTimes.TotalMode mode) {
        return new NodeInput(
                node.id(),
                node.parentId(),
                node.title(),
                node.description(),
                node.assigneeId(),
                node.urgency(),
                node.priority(),
                node.schedule(),
                node.predecessorIds(),
                node.binding(),
                node.sharing(),
                node.entries(),
                node.assignmentMode(),
                node.candidateUserIds(),
                node.dataPolicy(),
                node.acceptorId(),
                minutes,
                mode);
    }

    private BusinessRef templateBinding(
            NodeInput node,
            Map<String, NodeInput> nodes,
            Map<String, BusinessRef> refs,
            Set<String> seen) {
        if (!seen.add(node.id())) throw invalid("共享关系存在循环");
        return node.sharing().mode() == DataMode.SHARED
                ? templateBinding(nodes.get(node.sharing().sourceNodeId()), nodes, refs, seen)
                : refs.get(node.id());
    }

    private NodeInput bindingSource(NodeInput node, Map<String, NodeInput> nodes) {
        return node.sharing().mode() == DataMode.SHARED
                ? bindingSource(nodes.get(node.sharing().sourceNodeId()), nodes)
                : node;
    }

    private TaskInstanceDO require(String id) {
        TaskInstanceDO task = id == null ? null : store.get(id, false);
        if (task == null) throw invalid("任务不存在");
        return task;
    }

    private TaskInstanceDO lock(String id, long actor) {
        TaskInstanceDO task = require(id);
        store.get(task.getRootId(), true);
        List<TaskInstanceDO> nodes = store.instance(task.getRootId());
        if (!nodeVisible(task, nodes, actor)) throw invalid("没有查看此任务的权限");
        workflowProtection.requireActive(task.getRootId());
        return require(id);
    }

    /** 拆分等待根锁期间可能被转派，清除首次查询缓存后再使用当前负责人作判断。 */
    private TaskInstanceDO lockPersonalSplit(String id, long actor) {
        lock(id, actor);
        TaskInstanceDO current = store.planningTask(id);
        if (current == null) throw invalid("任务不存在");
        return current;
    }

    /** 新安排协议仅赋予当前节点可见性；旧实例保留原参与者整树语义。 */
    private boolean nodeVisible(TaskInstanceDO task, List<TaskInstanceDO> nodes, long actor) {
        if (actor <= 0) return false;
        if (canManage(nodes, actor)
                || rootAcceptor(nodes, actor)
                || Objects.equals(task.getAssigneeId(), actor)
                || task.getCreator().equals(Long.toString(actor))) return true;
        boolean legacyRoot =
                nodes.stream()
                        .anyMatch(
                                n ->
                                        n.getId().equals(n.getRootId())
                                                && config(n).assignmentMode() == null);
        return legacyRoot
                && participants(
                                nodes.stream()
                                        .filter(n -> config(n).assignmentMode() == null)
                                        .toList())
                        .contains(actor);
    }

    private void visible(List<TaskInstanceDO> nodes, long actor) {
        if (actor <= 0 || !admin(actor) && !participants(nodes).contains(actor))
            throw invalid("没有查看此任务的权限");
    }

    private Set<Long> participants(List<TaskInstanceDO> nodes) {
        Set<Long> result = new HashSet<>();
        nodes.forEach(
                n -> {
                    if (n.getAssigneeId() != null) result.add(n.getAssigneeId());
                    if (n.getId().equals(n.getRootId()) && config(n).acceptorId() != null)
                        result.add(config(n).acceptorId());
                    result.add(Long.parseLong(n.getCreator()));
                });
        return result;
    }

    private boolean canManage(List<TaskInstanceDO> nodes, long actor) {
        return admin(actor)
                || nodes.stream()
                        .anyMatch(
                                n ->
                                        n.getId().equals(n.getRootId())
                                                && n.getCreator().equals(Long.toString(actor)));
    }

    private boolean canEdit(TaskInstanceDO task, List<TaskInstanceDO> nodes, long actor) {
        return executable(task)
                && TaskPauses.pausedBy(task, nodes) == null
                && (Objects.equals(task.getAssigneeId(), actor) || canManage(nodes, actor));
    }

    private boolean canLink(TaskInstanceDO task, List<TaskInstanceDO> nodes, long actor) {
        return !nodes.stream().anyMatch(n -> State.PENDING_ACCEPTANCE.name().equals(n.getStatus()))
                && (Objects.equals(task.getAssigneeId(), actor) || canManage(nodes, actor));
    }

    private boolean canArrange(TaskInstanceDO task, List<TaskInstanceDO> nodes, long actor) {
        return Objects.equals(task.getAssigneeId(), actor);
    }

    /** 保留原有管理查看范围；是否可维护计划独立检查当前负责人身份。 */
    private boolean canViewPlans(TaskInstanceDO task, List<TaskInstanceDO> nodes, long actor) {
        if (Objects.equals(task.getAssigneeId(), actor) || canManage(nodes, actor)) return true;
        Map<String, TaskInstanceDO> index = new HashMap<>();
        nodes.forEach(node -> index.put(node.getId(), node));
        Set<String> visited = new HashSet<>();
        String parentId = task.getParentId();
        while (parentId != null && visited.add(parentId)) {
            TaskInstanceDO parent = index.get(parentId);
            if (parent == null) break;
            if (Objects.equals(parent.getAssigneeId(), actor)) return true;
            parentId = parent.getParentId();
        }
        return false;
    }

    private boolean admin(long actor) {
        return permissions.hasAnyRoles(actor, "super_admin")
                || permissions.hasAnyPermissions(actor, "nocode:task:manage-all");
    }

    private boolean executable(TaskInstanceDO task) {
        return State.PENDING.name().equals(task.getStatus())
                || State.RUNNING.name().equals(task.getStatus());
    }

    /** 暂停不移出个人计划，也不禁止本人重新安排清单；执行入口仍由 executable 与祖先门控控制。 */
    private boolean plannable(TaskInstanceDO task) {
        return executable(task) || State.PAUSED.name().equals(task.getStatus());
    }

    private boolean rootAcceptor(List<TaskInstanceDO> nodes, long actor) {
        return nodes.stream()
                .anyMatch(
                        n ->
                                n.getId().equals(n.getRootId())
                                        && Objects.equals(config(n).acceptorId(), actor));
    }

    private boolean canAccept(TaskInstanceDO task, long actor) {
        if (!task.getId().equals(task.getRootId())
                || !State.PENDING_ACCEPTANCE.name().equals(task.getStatus())
                || !Objects.equals(config(task).acceptorId(), actor)) return false;
        AdminUserRespDTO user = users.getUser(actor);
        return user != null && Integer.valueOf(0).equals(user.getStatus());
    }

    private boolean ended(TaskInstanceDO task) {
        return State.COMPLETED.name().equals(task.getStatus())
                || State.CANCELLED.name().equals(task.getStatus());
    }

    private void revision(TaskInstanceDO task, int revision) {
        if (task.getLockVersion() != revision) throw conflict();
    }

    private ServiceException conflict() {
        return new ServiceException(CONFLICT, "任务已被其他人修改，请刷新后重试");
    }

    private void update(TaskInstanceDO task, long actor) {
        if (store.updateTask(task, Long.toString(actor)) != 1) throw conflict();
        task.setLockVersion(task.getLockVersion() + 1);
    }

    private void touchRoot(String root, long actor) {
        update(require(root), actor);
    }

    private void replace(List<TaskInstanceDO> nodes, TaskInstanceDO task) {
        nodes.replaceAll(n -> n.getId().equals(task.getId()) ? task : n);
    }

    private void validateUsers(List<NodeInput> nodes) {
        for (NodeInput node : nodes)
            if (node.acceptorId() != null) assignments.requireActive(node.acceptorId());
        users.validateUserList(
                nodes.stream()
                        .map(NodeInput::assigneeId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList());
        users.validateUserList(
                nodes.stream()
                        .flatMap(n -> TaskAssignments.candidates(n).stream())
                        .distinct()
                        .toList());
    }

    private NodeInput config(TaskInstanceDO task) {
        return read(task.getConfigJson(), NodeInput.class);
    }

    private Kind kind(String value) {
        return value == null ? Kind.ORDINARY : Kind.valueOf(value);
    }

    private List<NodeInput> nodes(String data) {
        return read(data, new TypeReference<List<NodeInput>>() {});
    }

    private String name(Long user) {
        if (user == null) return null;
        AdminUserRespDTO value = users.getUser(user);
        return value == null ? Long.toString(user) : value.getNickname();
    }

    private LocalDate periodDate(Period period, LocalDate date) {
        return switch (period) {
            case DAY -> date;
            case WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> date.withDayOfMonth(1);
        };
    }

    private String requestKey(String key) {
        return TaskGraph.text(key, "请求标识", 120, true);
    }

    private String hash(Object value) {
        return cn.hutool.crypto.digest.DigestUtil.sha256Hex(write(value));
    }

    private void same(String existing, String request) {
        if (!Objects.equals(existing, request)) throw invalid("同一请求标识不能用于不同内容");
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw invalid("任务内容无法保存");
        }
    }

    private <T> T read(String value, Class<T> type) {
        if (value == null) return null;
        try {
            return json.readValue(value, type);
        } catch (java.io.IOException e) {
            throw invalid("任务内容无法读取");
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (java.io.IOException e) {
            throw invalid("任务内容无法读取");
        }
    }

    private void append(
            TaskInstanceDO task,
            EventType type,
            String note,
            Object material,
            String key,
            String hash,
            long actor) {
        TaskHistoryDO event = new TaskHistoryDO();
        event.setId(UUID.randomUUID().toString());
        event.setTaskId(task.getId());
        event.setRootId(task.getRootId());
        event.setEventType(type.name());
        event.setNote(note);
        event.setMaterialJson(material == null ? null : write(material));
        event.setRequestKey(key);
        event.setRequestHash(hash);
        store.appendEvent(event, Long.toString(actor));
    }

    private Comment comment(TaskCommentDO comment) {
        return new Comment(
                comment.getId(),
                comment.getTaskId(),
                comment.getParentId(),
                Long.parseLong(comment.getCreator()),
                name(Long.parseLong(comment.getCreator())),
                comment.getContent(),
                read(comment.getMentionedJson(), new TypeReference<List<Long>>() {}),
                comment.getCreateTime());
    }

    private Event event(TaskHistoryDO event) {
        return new Event(
                event.getId(),
                event.getTaskId(),
                event.getEventType(),
                Long.parseLong(event.getCreator()),
                name(Long.parseLong(event.getCreator())),
                event.getNote(),
                event.getCreateTime());
    }

    private TaskTemplateDO requireTemplate(String id, boolean lock) {
        TaskTemplateDO template = store.template(id, lock);
        if (template == null) throw invalid("模板不存在");
        return template;
    }

    private int requiredVersion(TaskTemplateDO template) {
        if (template.getPublishedVersion() == null) throw invalid("模板尚未发布");
        // 兼容历史空主指针；显式版本发起不经过此默认值解析。
        return template.getPrimaryVersion() == null
                ? template.getPublishedVersion()
                : template.getPrimaryVersion();
    }

    private void templateOwner(TaskTemplateDO template, long actor) {
        if (!admin(actor) && !template.getCreator().equals(Long.toString(actor)))
            throw invalid("只能维护自己创建的模板");
    }

    private Template template(TaskTemplateDO template) {
        return new Template(
                template.getId(),
                template.getName(),
                template.getDescription(),
                template.getLockVersion(),
                template.getPublishedVersion(),
                nodes(template.getNodesJson()),
                Long.parseLong(template.getCreator()),
                template.getUpdateTime(),
                kind(template.getKind()),
                read(template.getRootJson(), NodeInput.class),
                template.getPublishedVersion() == null ? null : requiredVersion(template));
    }
}
