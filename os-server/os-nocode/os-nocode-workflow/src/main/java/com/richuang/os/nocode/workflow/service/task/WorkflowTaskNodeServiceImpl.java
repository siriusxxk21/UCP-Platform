package com.richuang.os.nocode.workflow.service.task;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.framework.common.biz.system.permission.PermissionCommonApi;
import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.framework.tenant.core.context.TenantContextHolder;
import com.richuang.os.framework.tenant.core.util.TenantUtils;
import com.richuang.os.module.bpm.api.task.BpmTaskCenterNodeApi;
import com.richuang.os.module.bpm.api.task.dto.BpmTaskCenterNodeArrivalDTO;
import com.richuang.os.module.bpm.api.task.dto.BpmTaskCenterNodeExecutionDTO;
import com.richuang.os.nocode.api.TaskCenter;
import com.richuang.os.nocode.api.TaskCenter.NodeInput;
import com.richuang.os.nocode.api.workflow.WorkflowTaskNodes.*;
import com.richuang.os.nocode.runtime.dal.dataobject.TaskInstanceDO;
import com.richuang.os.nocode.runtime.dal.mapper.TaskCenterMapper;
import com.richuang.os.nocode.runtime.service.taskcenter.TaskCenterService;
import com.richuang.os.nocode.workflow.dal.dataobject.task.WorkflowTaskNodeDO;
import com.richuang.os.nocode.workflow.dal.mapper.task.WorkflowTaskNodeMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;

/** 到达事实与引擎同事务登记；创建任务和推进各自在独立可重试事务中完成。 不从任务完成事务内同步调用引擎，避免父子汇总、引擎回调形成反向锁和丢通知。 */
@Service
@Slf4j
public class WorkflowTaskNodeServiceImpl implements WorkflowTaskNodeService {
    @Resource private WorkflowTaskNodeMapper store;
    @Resource private TaskCenterMapper taskStore;
    @Resource private TaskCenterService tasks;
    @Resource private BpmTaskCenterNodeApi engine;
    @Resource private PermissionCommonApi permissions;
    @Resource private ObjectMapper json;
    @Resource private PlatformTransactionManager transactionManager;
    private TransactionTemplate tx;
    private TransactionTemplate isolated;

    @PostConstruct
    void initialize() {
        json = json.copy().findAndRegisterModules();
        tx = new TransactionTemplate(transactionManager);
        isolated = new TransactionTemplate(transactionManager);
        isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public String prepare(String nodeId, String value, long actor) {
        return tx.execute(
                status -> {
                    Configuration input = configuration(value);
                    if (input.version() != 1 || input.source() == null || input.task() == null)
                        throw invalid("任务节点配置不完整，请选择模板或配置任务");
                    if (input.source() == Source.TEMPLATE && blank(input.templateId()))
                        throw invalid("请选择任务模板及版本");
                    List<NodeInput> children = input.nodes() == null ? List.of() : input.nodes();
                    List<Person> people = people(input, children);
                    // 动态身份未解析前不伪造人员；仍验证固定身份，运行时再次检查人员及执行/验收分离。
                    Definition definition =
                            tasks.prepareWorkflow(
                                    resolve(input.task(), people, Map.of()),
                                    children.stream()
                                            .map(n -> resolve(n, people, Map.of()))
                                            .toList(),
                                    input.source() == Source.TEMPLATE ? input.templateId() : null,
                                    input.source() == Source.TEMPLATE
                                            ? input.templateVersion()
                                            : null,
                                    actor);
                    return write(
                            new Configuration(
                                    1,
                                    input.source(),
                                    definition.templateId(),
                                    definition.templateVersion(),
                                    input.task(),
                                    children,
                                    people,
                                    actor,
                                    definition));
                });
    }

    private List<Person> people(Configuration input, List<NodeInput> children) {
        List<Person> people = input.people() == null ? List.of() : input.people();
        Set<String> ids = new HashSet<>();
        ids.add(input.task().id());
        for (NodeInput node : children) {
            if (node == null) throw invalid("任务节点不能为空");
            ids.add(node.id());
        }
        Set<String> keys = new HashSet<>();
        for (Person person : people) {
            if (person == null
                    || person.role() == null
                    || person.source() == null
                    || !ids.contains(person.nodeId())
                    || !keys.add(personKey(person))) throw invalid("流程人员来源重复或引用了不存在的任务");
            if (person.role() == Role.ACCEPTOR
                    && !Objects.equals(person.nodeId(), input.task().id()))
                throw invalid("验收人员只能设置在总任务");
            if (person.source() == PersonSource.FORM_FIELD
                    && (blank(person.field()) || person.field().length() > 200))
                throw invalid("请选择流程表单人员字段");
        }
        return List.copyOf(people);
    }

    @Override
    public Set<String> neededFields(String value) {
        Configuration input = configuration(value);
        Set<String> fields = new LinkedHashSet<>();
        for (Person person : input.people() == null ? List.<Person>of() : input.people())
            if (person.source() == PersonSource.FORM_FIELD && !blank(person.field()))
                fields.add(person.field());
        return fields;
    }

    @Override
    public String arrive(BpmTaskCenterNodeArrivalDTO arrival) {
        return tx.execute(
                status -> {
                    Configuration config = configuration(arrival.configurationJson());
                    if (config.publisherId() == null
                            || config.definition() == null
                            || arrival.initiatorId() == null)
                        throw invalid("任务节点缺少发布快照或流程发起人，请重新发布流程");
                    long tenant = arrival.tenantId() == null ? 0 : arrival.tenantId();
                    store.lock("workflow-task:" + tenant + ":" + arrival.executionId());
                    WorkflowTaskNodeDO prior =
                            store.arrival(
                                    arrival.executionId(),
                                    arrival.processInstanceId(),
                                    arrival.nodeId(),
                                    tenant);
                    if (prior != null) {
                        if (!Objects.equals(
                                        prior.getProcessInstanceId(), arrival.processInstanceId())
                                || !Objects.equals(prior.getNodeId(), arrival.nodeId()))
                            throw invalid("流程执行已关联其他任务节点");
                        return prior.getId();
                    }
                    Map<String, Object> personValues = new LinkedHashMap<>();
                    for (String field : neededFields(arrival.configurationJson())) {
                        Object value =
                                arrival.variables() == null ? null : arrival.variables().get(field);
                        personValues.put(field, value);
                    }
                    WorkflowTaskNodeDO row = new WorkflowTaskNodeDO();
                    row.setId(UUID.randomUUID().toString());
                    row.setTenantId(tenant);
                    row.setExecutionId(arrival.executionId());
                    row.setProcessInstanceId(arrival.processInstanceId());
                    row.setProcessDefinitionId(arrival.processDefinitionId());
                    row.setNodeId(arrival.nodeId());
                    row.setNodeName(blank(arrival.nodeName()) ? "任务节点" : arrival.nodeName());
                    row.setInitiatorId(arrival.initiatorId());
                    row.setPublisherId(config.publisherId());
                    row.setConfigurationJson(arrival.configurationJson());
                    row.setPeopleJson(write(personValues));
                    row.setState(State.CREATING.name());
                    store.create(row);
                    return row.getId();
                });
    }

    @Override
    @Scheduled(fixedDelayString = "${nocode.workflow-task.reconcile-delay:3000}")
    public void reconcile() {
        // 只扫描交接表，不扫描整个任务中心；每条记录隔离失败，多实例靠事务锁去重。
        for (WorkflowTaskNodeDO candidate : store.pending())
            TenantUtils.execute(candidate.getTenantId(), () -> attempt(candidate.getId()));
    }

    private void attempt(String bindingId) {
        try {
            isolated.executeWithoutResult(
                    status -> {
                        store.lock("workflow-task-binding:" + tenant() + ":" + bindingId);
                        WorkflowTaskNodeDO row = store.binding(bindingId, tenant());
                        if (row == null || terminal(row)) return;
                        BpmTaskCenterNodeApi.State active = engine.inspect(execution(row));
                        if (active == BpmTaskCenterNodeApi.State.INACTIVE) {
                            row.setState(State.INVALIDATED.name());
                            row.setLastError("所属流程已结束或已离开此节点，任务仅保留历史，不再推进流程");
                        } else if (active == BpmTaskCenterNodeApi.State.WAITING) {
                            if (row.getTaskId() == null) {
                                Configuration config = configuration(row.getConfigurationJson());
                                Map<String, Long> people = resolvePeople(config, row);
                                Definition definition = config.definition();
                                TaskCenter.Detail task =
                                        tasks.createWorkflow(
                                                definition,
                                                resolve(definition.task(), config.people(), people),
                                                definition.nodes().stream()
                                                        .map(
                                                                n ->
                                                                        resolve(
                                                                                n,
                                                                                config.people(),
                                                                                people))
                                                        .toList(),
                                                row.getInitiatorId(),
                                                "workflow:" + row.getId(),
                                                row.getCreateTime());
                                row.setTaskId(task.task().id());
                                row.setState(State.WAITING.name());
                            }
                            TaskInstanceDO root = taskStore.get(row.getTaskId(), true);
                            if (root == null) throw invalid("关联总任务不存在，请联系管理员");
                            if (TaskCenter.State.COMPLETED.name().equals(root.getStatus())) {
                                if (engine.advance(execution(row)))
                                    row.setState(State.COMPLETED.name());
                            }
                            row.setLastError(
                                    TaskCenter.State.CANCELLED.name().equals(root.getStatus())
                                            ? "关联任务已取消，流程不会自动跳过此节点；请由流程管理员处理流程"
                                            : null);
                        }
                        row.setNextAttemptTime(LocalDateTime.now().plusSeconds(3));
                        store.save(row);
                    });
        } catch (RuntimeException failure) {
            log.warn("工作流任务节点交接失败，等待重试 bindingId={}", bindingId, failure);
            isolated.executeWithoutResult(
                    status -> {
                        store.lock("workflow-task-binding:" + tenant() + ":" + bindingId);
                        WorkflowTaskNodeDO row = store.binding(bindingId, tenant());
                        if (row == null || terminal(row)) return;
                        row.setAttempts(row.getAttempts() + 1);
                        String error =
                                failure instanceof ServiceException
                                        ? failure.getMessage()
                                        : "任务交接暂时失败，系统将自动重试，也可手动重试";
                        row.setLastError(
                                error == null
                                        ? "任务交接失败"
                                        : error.substring(0, Math.min(1000, error.length())));
                        row.setNextAttemptTime(
                                LocalDateTime.now()
                                        .plusSeconds(Math.min(300, 10L * row.getAttempts())));
                        store.save(row);
                    });
        }
    }

    private Map<String, Long> resolvePeople(Configuration config, WorkflowTaskNodeDO row) {
        Map<String, Object> fields;
        try {
            fields =
                    json.readValue(
                            row.getPeopleJson(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception failure) {
            throw invalid("流程人员来源快照无法读取");
        }
        Map<String, Long> result = new HashMap<>();
        for (Person person : config.people() == null ? List.<Person>of() : config.people()) {
            Long id =
                    person.source() == PersonSource.INITIATOR
                            ? row.getInitiatorId()
                            : personId(fields.get(person.field()));
            if (id == null || id <= 0) throw invalid("流程人员字段“" + person.field() + "”必须提供一个有效人员ID");
            result.put(personKey(person), id);
        }
        return result;
    }

    private Long personId(Object value) {
        if (value instanceof List<?> list)
            return list.size() == 1 ? personId(list.getFirst()) : null;
        if (value == null || !value.toString().matches("[1-9][0-9]{0,18}")) return null;
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    private NodeInput resolve(NodeInput node, List<Person> people, Map<String, Long> values) {
        Long assignee = node.assigneeId();
        Long acceptor = node.acceptorId();
        TaskCenter.AssignmentMode mode = node.assignmentMode();
        List<Long> candidates = node.candidateUserIds();
        for (Person person : people == null ? List.<Person>of() : people) {
            if (!Objects.equals(person.nodeId(), node.id())) continue;
            if (person.role() == Role.ACCEPTOR) acceptor = values.get(personKey(person));
            else {
                assignee = values.get(personKey(person));
                mode =
                        assignee == null
                                ? TaskCenter.AssignmentMode.OPEN
                                : TaskCenter.AssignmentMode.ASSIGNED;
                candidates = List.of();
            }
        }
        return new NodeInput(
                node.id(),
                node.parentId(),
                node.title(),
                node.description(),
                assignee,
                node.urgency(),
                node.priority(),
                node.schedule(),
                node.predecessorIds(),
                node.binding(),
                node.sharing(),
                node.entries(),
                mode,
                candidates,
                node.dataPolicy(),
                acceptor,
                node.effectiveWorkMinutes(),
                node.workTotalMode());
    }

    @Override
    public View retry(String executionId, long actor) {
        WorkflowTaskNodeDO row = store.execution(executionId, tenant());
        if (row == null || !canRetry(row, actor)) throw invalid("没有重试此任务节点的权限或节点已结束");
        attempt(row.getId());
        return view(store.binding(row.getId(), tenant()), actor);
    }

    @Override
    public List<View> process(String id, long actor) {
        if (!engine.canViewProcess(actor, id)) throw invalid("没有查看此流程的权限");
        return store.process(id, tenant()).stream().map(row -> view(row, actor)).toList();
    }

    @Override
    public View source(String taskId, long actor) {
        TaskCenter.Detail task = tasks.detail(taskId, actor);
        WorkflowTaskNodeDO row = store.byTask(task.task().rootId(), tenant());
        return row == null ? null : view(row, actor);
    }

    @Override
    public void requireActive(String rootId) {
        WorkflowTaskNodeDO row = store.byTask(rootId, tenant());
        String reason = readOnlyReason(row);
        if (reason != null) throw invalid(reason);
    }

    @Override
    public Map<String, String> readOnlyReasons(Collection<String> rootIds) {
        if (rootIds.isEmpty()) return Map.of();
        Map<String, String> reasons = new HashMap<>();
        // 每个响应仅查一次来源绑定，同组所有节点共用来源状态，不按任务行重复查询引擎。
        for (WorkflowTaskNodeDO row :
                store.byTasks(rootIds.stream().distinct().toList(), tenant())) {
            String reason = readOnlyReason(row);
            if (reason != null) reasons.put(row.getTaskId(), reason);
        }
        return reasons;
    }

    private String readOnlyReason(WorkflowTaskNodeDO row) {
        if (row == null || State.COMPLETED.name().equals(row.getState())) return null;
        if (State.INVALIDATED.name().equals(row.getState())) return "所属流程已结束或已离开此节点，任务仅供查看";
        BpmTaskCenterNodeApi.State active = engine.inspect(execution(row));
        if (active == BpmTaskCenterNodeApi.State.INACTIVE) return "所属流程已结束或已离开此节点，任务仅供查看";
        if (active == BpmTaskCenterNodeApi.State.SUSPENDED) return "所属流程已挂起，请恢复流程后再操作任务";
        return null;
    }

    private View view(WorkflowTaskNodeDO row, long actor) {
        boolean canView = false;
        TaskCenter.State state = null;
        if (row.getTaskId() != null) {
            // 流程可见只提供关联入口；具体任务数据仍须通过原任务权限，不借流程参与身份扩大权限。
            try {
                state =
                        TaskCenter.State.valueOf(
                                tasks.detail(row.getTaskId(), actor).task().status());
                canView = true;
            } catch (ServiceException denied) {
                /* 无任务查看资格时不泄露任务内容。 */
            }
        }
        String readOnlyReason = readOnlyReason(row);
        return new View(
                row.getExecutionId(),
                row.getProcessInstanceId(),
                row.getNodeId(),
                row.getNodeName(),
                canView ? row.getTaskId() : null,
                State.valueOf(row.getState()),
                state,
                canRetry(row, actor) || canView ? row.getLastError() : null,
                canView,
                engine.canViewProcess(actor, row.getProcessInstanceId()),
                canRetry(row, actor),
                row.getCreateTime(),
                readOnlyReason);
    }

    private boolean canRetry(WorkflowTaskNodeDO row, long actor) {
        return actor > 0
                && !terminal(row)
                && (Objects.equals(actor, row.getInitiatorId())
                        || Objects.equals(actor, row.getPublisherId())
                        || permissions.hasAnyRoles(actor, "super_admin"));
    }

    private boolean terminal(WorkflowTaskNodeDO row) {
        return State.COMPLETED.name().equals(row.getState())
                || State.INVALIDATED.name().equals(row.getState());
    }

    private BpmTaskCenterNodeExecutionDTO execution(WorkflowTaskNodeDO row) {
        return new BpmTaskCenterNodeExecutionDTO(
                row.getExecutionId(), row.getProcessInstanceId(), row.getNodeId(), row.getId());
    }

    private long tenant() {
        Long id = TenantContextHolder.getTenantId();
        return id == null ? 0 : id;
    }

    private String personKey(Person person) {
        return person.nodeId() + ":" + person.role();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private Configuration configuration(String value) {
        if (blank(value) || value.length() > 2_000_000) throw invalid("任务节点配置为空或过大");
        try {
            return json.readValue(value, Configuration.class);
        } catch (Exception failure) {
            throw invalid("任务节点配置格式不正确");
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception failure) {
            throw invalid("任务节点快照无法保存");
        }
    }
}
