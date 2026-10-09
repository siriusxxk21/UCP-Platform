package com.richuang.os.nocode.runtime.service.taskcenter;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.framework.common.biz.system.permission.PermissionCommonApi;
import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.module.system.api.user.AdminUserApi;
import com.richuang.os.module.system.api.user.dto.AdminUserRespDTO;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.TaskCenter.BusinessRef;
import com.richuang.os.nocode.api.TaskWorkEntries.*;
import com.richuang.os.nocode.application.service.published.ApplicationPublishedService;
import com.richuang.os.nocode.runtime.dal.dataobject.*;
import com.richuang.os.nocode.runtime.dal.mapper.*;
import com.richuang.os.nocode.runtime.service.record.RecordService;
import com.richuang.os.nocode.runtime.service.record.RelatedFormService;
import com.richuang.os.nocode.runtime.service.task.TaskEntryRuntimeService;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;

/** 每次操作同时核验任务、入口、行和字段权限；根锁使调整和旧编辑窗口的写入串行。 */
@Service
public class TaskWorkEntryServiceImpl implements TaskWorkEntryService {
    @Resource private TaskWorkflowProtection workflowProtection;
    @Resource private TaskWorkEntryMapper store;
    @Resource private TaskCenterMapper tasks;
    @Resource private TaskBusiness business;
    @Resource private ObjectMapper json;
    @Resource private PermissionCommonApi permissions;
    @Resource private AdminUserApi users;
    @Resource private ApplicationPublishedService published;
    @Resource private TaskEntryRuntimeService entries;
    @Resource private RelatedFormService relatedForms;
    @Resource private com.richuang.os.nocode.runtime.service.task.TaskGroupRuntime groupRuntime;
    @Resource private TaskDataPolicies dataPolicies;
    @Resource private RecordHistoryMapper recordHistory;
    @Resource private TaskStandardWork standardWork;

    @Resource
    private com.richuang.os.nocode.runtime.service.handling.BusinessHandlingService handling;

    @Resource(name = "nocodeRecordService")
    private RecordService records;

    private record Frozen(
            String nodeId, String entryKey, TaskCenter.Binding binding, BusinessRef ref) {}

    private record Context(
            TaskInstanceDO task, TaskWorkEntryDO row, Config config, BusinessRef ref) {}

    @Override
    public HandlingLocation handlingLocation(String requestId, long actor) {
        if (requestId == null || requestId.isBlank()) throw invalid("缺少审批申请身份");
        TaskWorkRecordDO contribution = store.handlingContribution(requestId, Long.toString(actor));
        if (contribution == null) return null;
        Set<String> seen = new HashSet<>();
        while (contribution.getSupersededBy() != null && seen.add(contribution.getId())) {
            TaskWorkRecordDO next = store.record(contribution.getSupersededBy());
            if (next == null
                    || !Objects.equals(next.getTaskId(), contribution.getTaskId())
                    || !Objects.equals(next.getCreator(), Long.toString(actor)))
                throw invalid("申请的后续办理记录已失效");
            contribution = next;
        }
        Context c = context(contribution.getTaskId(), contribution.getEntryKey(), actor, false);
        formRef(
                c,
                new Form(
                        contribution.getTaskId(),
                        contribution.getEntryKey(),
                        null,
                        contribution.getId()),
                actor);
        return new HandlingLocation(
                contribution.getTaskId(), contribution.getEntryKey(), contribution.getId());
    }

    @Override
    public Saved receipt(Receipt query, long actor) {
        if (!groupRuntime.active() && groupRuntime.enabled(query.taskId()))
            return groupRuntime.execute(
                    query.taskId(), query.entryKey(), actor, false, () -> receipt(query, actor));
        Context c = context(query.taskId(), query.entryKey(), actor, false);
        TaskWorkRecordDO row = store.receipt(Long.toString(actor), key(query.requestKey()));
        if (row == null) return null;
        if (!row.getTaskId().equals(query.taskId()) || !row.getEntryKey().equals(query.entryKey()))
            throw invalid("提交收据不属于当前任务入口");
        return receiptResult(c, row, actor);
    }

    @Override
    public void validate(List<TaskCenter.NodeInput> nodes, long actor) {
        Map<String, TaskCenter.NodeInput> index = new HashMap<>();
        nodes.forEach(n -> index.put(n.id(), n));
        boolean groupPolicy =
                nodes.stream().anyMatch(n -> n.parentId() == null && n.dataPolicy() != null);
        for (TaskCenter.NodeInput node : nodes) {
            Set<String> keys = new HashSet<>();
            if (node.binding() != null && node.binding().viewId() != null && !groupPolicy)
                throw invalid("视图办理需要总任务统一数据授权，请重新配置业务关联");
            if (node.entries() == null) continue;
            if (node.entries().size() > 20) throw invalid("每个任务最多配置20个办理入口");
            for (Config c : node.entries()) {
                if (c == null
                        || c.key() == null
                        || !c.key().matches("[A-Za-z0-9_-]{1,80}")
                        || !keys.add(c.key())) throw invalid("办理入口标识为空、重复或不合法");
                if (c.name() == null || c.name().isBlank() || c.name().length() > 100)
                    throw invalid("请填写100字以内的入口名称");
                if (c.dataMode() == null) throw invalid("请选择入口共享方式");
                if (c.binding() != null && c.binding().viewId() != null && !groupPolicy)
                    throw invalid("视图办理需要总任务统一数据授权，请重新配置业务关联");
                standardWork.validate(c.workRule());
                if (c.dataMode() == DataMode.SOURCE_SHARED) {
                    TaskCenter.NodeInput source = index.get(c.sourceNodeId());
                    if (source == null
                            || source.id().equals(node.id())
                            || !legalSource(node, source.id(), index, new HashSet<>()))
                        throw invalid("共享来源必须是同一任务的祖先或前序节点");
                    if (c.sourceEntryKey() == null) throw invalid("请选择共享的来源入口");
                    if (configuredEntries(source, index, new HashSet<>()).stream()
                            .noneMatch(e -> e.key().equals(c.sourceEntryKey())))
                        throw invalid("共享来源入口不存在");
                } else if (c.binding() == null) throw invalid("请选择入口的业务表单");
            }
        }
    }

    private List<Config> configuredEntries(
            TaskCenter.NodeInput node, Map<String, TaskCenter.NodeInput> index, Set<String> seen) {
        if (node == null || !seen.add(node.id())) return List.of();
        if (node.entries() != null && !node.entries().isEmpty()) return node.entries();
        return configuredEntries(index.get(node.parentId()), index, seen);
    }

    private boolean legalSource(
            TaskCenter.NodeInput node,
            String source,
            Map<String, TaskCenter.NodeInput> index,
            Set<String> seen) {
        if (node == null || !seen.add(node.id())) return false;
        if (Objects.equals(node.parentId(), source)
                || node.predecessorIds() != null && node.predecessorIds().contains(source))
            return true;
        if (legalSource(index.get(node.parentId()), source, index, seen)) return true;
        if (node.predecessorIds() != null)
            for (String predecessor : node.predecessorIds())
                if (legalSource(index.get(predecessor), source, index, seen)) return true;
        return false;
    }

    private void validateFields(Config config, BusinessRef ref, long actor) {
        ApplicationRecords.Model model = business.model(ref, config.binding(), actor);
        standardWork.validate(config.workRule(), model, config);
        if (config.binding().entryId() != null) {
            TaskEntries.Context entry =
                    published.withVersion(
                            ref.resource(),
                            () ->
                                    entries.context(
                                            new TaskEntries.Locator(
                                                    ref.resource().applicationId(),
                                                    config.binding().entryId(),
                                                    ref.resource().applicationVersion()),
                                            actor));
            if (!"LIST".equals(entry.config().mode())) throw invalid("多条业务数据请选择列表模式的办理入口");
        }
        Set<String> ids = new HashSet<>();
        model.object().fields().forEach(f -> ids.add(f.id()));
        if (config.readableFieldIds() != null && !ids.containsAll(config.readableFieldIds())
                || config.writableFieldIds() != null && !ids.containsAll(config.writableFieldIds()))
            throw invalid("入口字段配置包含不存在的字段");
        if (config.readableFieldIds() != null
                && config.writableFieldIds() != null
                && !config.readableFieldIds().containsAll(config.writableFieldIds()))
            throw invalid("允许修改的字段必须同时允许查看");
    }

    @Override
    public void publish(String id, int version, List<TaskCenter.NodeInput> nodes, long actor) {
        List<Frozen> frozen = new ArrayList<>();
        for (TaskCenter.NodeInput node : nodes) {
            if (node.entries() == null) continue;
            for (Config c : node.entries()) {
                if (c.binding() != null) {
                    BusinessRef ref = business.resolve(c.binding(), actor);
                    validateFields(c, ref, actor);
                    frozen.add(new Frozen(node.id(), c.key(), c.binding(), ref));
                }
            }
        }
        if (frozen.isEmpty()) return;
        TaskWorkEntryTemplateDO row = new TaskWorkEntryTemplateDO();
        row.setId(UUID.randomUUID().toString());
        row.setTemplateId(id);
        row.setVersionNo(version);
        row.setBindingsJson(write(frozen));
        store.publish(row, Long.toString(actor));
    }

    @Override
    public void synchronize(String rootId, long actor) {
        List<TaskInstanceDO> instance = tasks.instance(rootId);
        Map<String, TaskInstanceDO> index = new LinkedHashMap<>();
        instance.forEach(n -> index.put(n.getId(), n));
        TaskInstanceDO root = index.get(rootId);
        if (root == null) throw invalid("任务不存在");
        TaskDataPolicies.FrozenRoot approved = dataPolicies.decode(root.getAuthorizationJson());
        if (approved != null) {
            synchronizeGroup(instance, root, approved, actor);
            return;
        }
        List<Frozen> frozen = List.of();
        if (root.getTemplateId() != null && root.getTemplateVersion() != null) {
            TaskWorkEntryTemplateDO template =
                    store.template(root.getTemplateId(), root.getTemplateVersion());
            if (template != null)
                frozen = read(template.getBindingsJson(), new TypeReference<List<Frozen>>() {});
        }
        Map<String, TaskWorkEntryDO> resolved = new HashMap<>();
        for (TaskInstanceDO task : instance) {
            List<Config> configs = configs(task, index);
            Set<String> keys = new HashSet<>();
            for (Config c : configs) {
                keys.add(c.key());
                resolve(task, c, index, frozen, resolved, new HashSet<>(), actor);
            }
            for (TaskWorkEntryDO old : store.entries(task.getId()))
                if (!keys.contains(old.getEntryKey())) {
                    if (!"PENDING".equals(task.getStatus())) throw invalid("已开始任务不能移除办理入口");
                    store.removeEntry(old.getId(), Long.toString(actor));
                }
        }
    }

    private List<Config> configs(TaskInstanceDO task, Map<String, TaskInstanceDO> index) {
        TaskInstanceDO root = index.get(task.getRootId());
        TaskDataPolicies.FrozenRoot approved =
                root == null ? null : dataPolicies.decode(root.getAuthorizationJson());
        if (approved != null) return groupConfigs(approved);
        TaskCenter.NodeInput node = read(task.getConfigJson(), TaskCenter.NodeInput.class);
        if (node.entries() != null && !node.entries().isEmpty()) return node.entries();
        TaskInstanceDO parent = index.get(task.getParentId());
        if (parent == null) return List.of();
        return configs(parent, index).stream()
                .map(
                        c ->
                                new Config(
                                        c.key(),
                                        c.name(),
                                        c.binding(),
                                        DataMode.ROOT_SHARED,
                                        null,
                                        null,
                                        c.readableFieldIds(),
                                        c.writableFieldIds(),
                                        c.required(),
                                        c.allowAll(),
                                        c.workRule(),
                                        c.dataScope()))
                .toList();
    }

    /** 新协议每个节点只有总任务批准的目录，数据集固定到根而非临近父节点。 */
    private List<Config> groupConfigs(TaskDataPolicies.FrozenRoot approved) {
        List<Config> result = new ArrayList<>();
        TaskCenter.NodeInput root = approved.task();
        Config primary =
                root.entries() == null
                        ? null
                        : root.entries().stream()
                                .filter(c -> TaskDataPolicies.BUSINESS.equals(c.key()))
                                .findFirst()
                                .orElse(null);
        if (root.binding() != null)
            result.add(
                    new Config(
                            TaskDataPolicies.BUSINESS,
                            primary == null ? "业务数据" : primary.name(),
                            root.binding(),
                            DataMode.ROOT_SHARED,
                            null,
                            null,
                            primary == null ? null : primary.readableFieldIds(),
                            primary == null ? null : primary.writableFieldIds(),
                            primary != null && primary.required(),
                            TaskEntryScopes.root(root, TaskDataPolicies.BUSINESS)
                                    == TaskCenter.DataAccessMode.ALL,
                            primary == null ? null : primary.workRule(),
                            TaskEntryScopes.root(root, TaskDataPolicies.BUSINESS)));
        if (root.entries() != null)
            for (Config entry : root.entries()) {
                if (TaskDataPolicies.BUSINESS.equals(entry.key())) continue;
                result.add(
                        new Config(
                                entry.key(),
                                entry.name(),
                                entry.binding(),
                                DataMode.ROOT_SHARED,
                                null,
                                null,
                                entry.readableFieldIds(),
                                entry.writableFieldIds(),
                                entry.required(),
                                TaskEntryScopes.effective(entry, root.dataPolicy())
                                        == TaskCenter.DataAccessMode.ALL,
                                entry.workRule(),
                                TaskEntryScopes.effective(entry, root.dataPolicy())));
            }
        return result;
    }

    private void synchronizeGroup(
            List<TaskInstanceDO> instance,
            TaskInstanceDO root,
            TaskDataPolicies.FrozenRoot approved,
            long actor) {
        for (TaskInstanceDO task : instance) {
            for (Config config : groupConfigs(approved)) {
                TaskDataPolicyCompiler.ResourceGrant grant =
                        approved.grants().resources().stream()
                                .filter(g -> config.key().equals(g.entryKey()))
                                .findFirst()
                                .orElseThrow(() -> invalid("任务数据授权缺少资源快照"));
                TaskWorkEntryDO row = store.entry(task.getId(), config.key());
                requireFrozenWorkRule(row, config);
                if (row != null
                        && !Objects.equals(
                                read(row.getConfigJson(), Config.class).binding(), config.binding())
                        && !store.records(row.getDatasetId()).isEmpty())
                    throw invalid("已有业务记录的任务资源不能更换表单，请新增独立资源");
                if (row == null) {
                    row = new TaskWorkEntryDO();
                    row.setId(UUID.randomUUID().toString());
                }
                row.setTaskId(task.getId());
                row.setEntryKey(config.key());
                row.setDatasetId(root.getId() + ":" + config.key());
                row.setConfigJson(write(config));
                row.setBusinessJson(write(grant.ref()));
                row.setInherited(!task.getId().equals(root.getId()));
                store.saveEntry(row, Long.toString(actor));
            }
            Set<String> activeKeys =
                    groupConfigs(approved).stream()
                            .map(Config::key)
                            .collect(java.util.stream.Collectors.toSet());
            for (TaskWorkEntryDO stale : store.entries(task.getId()))
                if (!activeKeys.contains(stale.getEntryKey()))
                    store.removeEntry(stale.getId(), Long.toString(actor));
        }
        // 现有资料保持 LINKED 来源，创建业务数据保持 CREATED，不把归属上下文伪造成任务新增。
        TaskWorkEntryDO primary = store.entry(root.getId(), TaskDataPolicies.BUSINESS);
        if (primary == null || !store.records(primary.getDatasetId()).isEmpty()) return;
        BusinessRef ref = read(root.getBusinessJson(), BusinessRef.class);
        if (ref == null || ref.recordId() == null && ref.requestId() == null) return;
        TaskWorkRecordDO contribution = new TaskWorkRecordDO();
        contribution.setId(UUID.randomUUID().toString());
        contribution.setTaskId(root.getId());
        contribution.setEntryKey(TaskDataPolicies.BUSINESS);
        contribution.setDatasetId(primary.getDatasetId());
        contribution.setBusinessJson(write(ref));
        contribution.setOperation(Operation.LINKED.name());
        contribution.setRequestKey("task-seed:" + root.getId());
        contribution.setRequestHash(hash(ref));
        store.saveRecord(contribution, Long.toString(actor));
    }

    private TaskInstanceDO ancestorEntry(
            TaskInstanceDO task, String key, Map<String, TaskInstanceDO> index) {
        Set<String> seen = new HashSet<>();
        TaskInstanceDO ancestor = index.get(task.getParentId());
        while (ancestor != null && seen.add(ancestor.getId())) {
            if (configs(ancestor, index).stream().anyMatch(c -> c.key().equals(key)))
                return ancestor;
            ancestor = index.get(ancestor.getParentId());
        }
        return null;
    }

    private TaskWorkEntryDO resolve(
            TaskInstanceDO task,
            Config config,
            Map<String, TaskInstanceDO> index,
            List<Frozen> frozen,
            Map<String, TaskWorkEntryDO> resolved,
            Set<String> visiting,
            long actor) {
        String identity = task.getId() + ":" + config.key();
        if (resolved.containsKey(identity)) return resolved.get(identity);
        if (!visiting.add(identity)) throw invalid("入口共享关系不能循环");
        TaskWorkEntryDO old = store.entry(task.getId(), config.key());
        Config oldConfig = old == null ? null : read(old.getConfigJson(), Config.class);
        BusinessRef oldRef = old == null ? null : read(old.getBusinessJson(), BusinessRef.class);
        boolean inherited =
                read(task.getConfigJson(), TaskCenter.NodeInput.class).entries() == null
                        || read(task.getConfigJson(), TaskCenter.NodeInput.class)
                                .entries()
                                .isEmpty();
        TaskWorkEntryDO source = null;
        TaskInstanceDO ancestor =
                config.dataMode() == DataMode.ROOT_SHARED
                        ? ancestorEntry(task, config.key(), index)
                        : null;
        if (config.dataMode() == DataMode.SOURCE_SHARED || ancestor != null) {
            String sourceId =
                    config.dataMode() == DataMode.SOURCE_SHARED
                            ? config.sourceNodeId()
                            : ancestor.getId();
            String key =
                    config.dataMode() == DataMode.SOURCE_SHARED
                            ? config.sourceEntryKey()
                            : config.key();
            TaskInstanceDO sourceTask = index.get(sourceId);
            if (sourceTask == null) throw invalid("共享来源任务不存在");
            Map<String, TaskCenter.NodeInput> graph = new HashMap<>();
            index.values()
                    .forEach(
                            n ->
                                    graph.put(
                                            n.getId(),
                                            read(n.getConfigJson(), TaskCenter.NodeInput.class)));
            if (!legalSource(graph.get(task.getId()), sourceId, graph, new HashSet<>()))
                throw invalid("不能共享无关节点的入口");
            Config sourceConfig =
                    configs(sourceTask, index).stream()
                            .filter(c -> c.key().equals(key))
                            .findFirst()
                            .orElseThrow(() -> invalid("共享来源入口不存在"));
            source = resolve(sourceTask, sourceConfig, index, frozen, resolved, visiting, actor);
            Config effective = read(source.getConfigJson(), Config.class);
            if (config.binding() != null && !Objects.equals(config.binding(), effective.binding()))
                throw invalid("共享入口必须使用来源入口的同一业务定义");
            config =
                    new Config(
                            config.key(),
                            config.name(),
                            effective.binding(),
                            config.dataMode(),
                            sourceId,
                            key,
                            intersect(config.readableFieldIds(), effective.readableFieldIds()),
                            intersect(
                                    config.dataMode() == DataMode.SOURCE_SHARED
                                                    && config.writableFieldIds() == null
                                            ? List.of()
                                            : config.writableFieldIds(),
                                    effective.writableFieldIds()),
                            config.required(),
                            TaskEntryScopes.shared(config, effective)
                                    == TaskCenter.DataAccessMode.ALL,
                            config.workRule(),
                            config.dataScope() == null && effective.dataScope() == null
                                    ? null
                                    : TaskEntryScopes.shared(config, effective));
        }
        if (old != null && Objects.equals(oldConfig, config)) {
            resolved.put(identity, old);
            visiting.remove(identity);
            return old;
        }
        if (old != null && !"PENDING".equals(task.getStatus()))
            throw invalid("已开始任务的办理入口不能变更；请调整未开始部分");
        requireFrozenWorkRule(old, config);
        if (old != null
                && !store.records(old.getDatasetId()).isEmpty()
                && (!Objects.equals(oldConfig.binding(), config.binding())
                        || oldConfig.dataMode() != config.dataMode()
                        || !Objects.equals(oldConfig.sourceNodeId(), config.sourceNodeId())
                        || !Objects.equals(oldConfig.sourceEntryKey(), config.sourceEntryKey())))
            throw invalid("已有贡献记录的入口不能更换业务定义或共享来源");
        TaskWorkEntryDO row = old == null ? new TaskWorkEntryDO() : old;
        row.setId(old == null ? UUID.randomUUID().toString() : old.getId());
        row.setTaskId(task.getId());
        row.setEntryKey(config.key());
        row.setDatasetId(source == null ? identity : source.getDatasetId());
        row.setConfigJson(write(config));
        row.setInherited(inherited);
        BusinessRef ref = source == null ? null : read(source.getBusinessJson(), BusinessRef.class);
        if (ref == null && old != null && Objects.equals(oldConfig.binding(), config.binding()))
            ref = oldRef;
        if (ref == null) {
            TaskCenter.Binding binding = config.binding();
            String configKey = config.key();
            ref =
                    frozen.stream()
                            .filter(
                                    f ->
                                            Objects.equals(f.nodeId(), task.getTemplateNodeId())
                                                    && f.entryKey().equals(configKey)
                                                    && f.binding().equals(binding))
                            .map(Frozen::ref)
                            .findFirst()
                            .orElseGet(() -> business.resolve(binding, actor));
        }
        validateFields(config, ref, actor);
        row.setBusinessJson(write(ref));
        store.saveEntry(row, Long.toString(actor));
        resolved.put(identity, row);
        visiting.remove(identity);
        return row;
    }

    private List<String> intersect(List<String> a, List<String> b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.stream().filter(b::contains).toList();
    }

    /** 已有办理事实不能补填或替换计量口径，防止新规则倒算历史。 */
    private void requireFrozenWorkRule(TaskWorkEntryDO old, Config next) {
        if (old == null || store.records(old.getDatasetId()).isEmpty()) return;
        Config previous = read(old.getConfigJson(), Config.class);
        if (!Objects.equals(previous.workRule(), next.workRule()))
            throw invalid("已有办理记录，标准工时规则已锁定；请发布新模板版本用于后续任务");
    }

    private boolean canViewTotalWork(TaskInstanceDO task, long actor) {
        TaskInstanceDO root = tasks.get(task.getRootId(), false);
        return Long.toString(actor).equals(task.getCreator())
                || root != null && Long.toString(actor).equals(root.getCreator())
                || permissions.hasAnyRoles(actor, "super_admin")
                || permissions.hasAnyPermissions(actor, "nocode:task:manage-all");
    }

    /** 展示顺序来自保存的节点配置；继承入口沿用最近上级顺序，不依赖同事务时间或随机主键。 */
    private List<TaskWorkEntryDO> orderedEntries(TaskInstanceDO task) {
        List<TaskWorkEntryDO> rows = store.entries(task.getId());
        if (rows.size() < 2) return rows;
        Map<String, TaskInstanceDO> index = new LinkedHashMap<>();
        tasks.instance(task.getRootId()).forEach(node -> index.put(node.getId(), node));
        Map<String, Integer> order = new HashMap<>();
        List<Config> configured = configs(task, index);
        for (int i = 0; i < configured.size(); i++) order.put(configured.get(i).key(), i);
        return rows.stream()
                .sorted(
                        Comparator.comparingInt(
                                row -> order.getOrDefault(row.getEntryKey(), Integer.MAX_VALUE)))
                .toList();
    }

    @Override
    public List<Config> effectiveConfigs(String taskId) {
        TaskInstanceDO task = tasks.get(taskId, false);
        if (task == null) return List.of();
        return orderedEntries(task).stream()
                .map(e -> read(e.getConfigJson(), Config.class))
                .toList();
    }

    private TaskInstanceDO visible(String id, long actor, boolean write) {
        TaskInstanceDO task = tasks.get(id, false);
        if (task == null) throw invalid("任务不存在");
        if (write) {
            tasks.get(task.getRootId(), true);
            workflowProtection.requireActive(task.getRootId());
            task = tasks.get(id, false);
            if (task == null) throw invalid("任务已删除，请刷新后重试");
        }
        boolean admin =
                permissions.hasAnyRoles(actor, "super_admin")
                        || permissions.hasAnyPermissions(actor, "nocode:task:manage-all");
        List<TaskInstanceDO> instance = tasks.instance(task.getRootId());
        boolean legacy =
                instance.stream()
                        .anyMatch(
                                n ->
                                        n.getId().equals(n.getRootId())
                                                && read(
                                                                        n.getConfigJson(),
                                                                        TaskCenter.NodeInput.class)
                                                                .assignmentMode()
                                                        == null);
        String selected = task.getId();
        boolean acceptor =
                instance.stream()
                        .anyMatch(
                                n ->
                                        n.getId().equals(n.getRootId())
                                                && Objects.equals(
                                                        read(
                                                                        n.getConfigJson(),
                                                                        TaskCenter.NodeInput.class)
                                                                .acceptorId(),
                                                        actor));
        if (actor <= 0
                || !admin
                        && !acceptor
                        && instance.stream()
                                .noneMatch(
                                        n ->
                                                (Objects.equals(n.getAssigneeId(), actor)
                                                                || Long.toString(actor)
                                                                        .equals(n.getCreator()))
                                                        && (legacy
                                                                        && read(
                                                                                                n
                                                                                                        .getConfigJson(),
                                                                                                TaskCenter
                                                                                                        .NodeInput
                                                                                                        .class)
                                                                                        .assignmentMode()
                                                                                == null
                                                                || n.getId().equals(selected)
                                                                || n.getId().equals(n.getRootId())
                                                                        && Long.toString(actor)
                                                                                .equals(
                                                                                        n
                                                                                                .getCreator()))))
            throw invalid("没有查看任务的权限");
        if (write
                && (!"RUNNING".equals(task.getStatus())
                        || !Objects.equals(task.getAssigneeId(), actor)))
            throw invalid("仅进行中任务的办理人可提交业务数据");
        if (write) TaskPauses.requireActive(task, instance);
        return task;
    }

    private Context context(String taskId, String entryKey, long actor, boolean write) {
        TaskInstanceDO task = visible(taskId, actor, write);
        TaskWorkEntryDO row = store.entry(taskId, entryKey);
        if (row == null) throw invalid("任务办理入口不存在");
        Config config = read(row.getConfigJson(), Config.class);
        BusinessRef ref = read(row.getBusinessJson(), BusinessRef.class);
        business.model(ref, config.binding(), actor);
        return new Context(task, row, config, ref);
    }

    @Override
    public List<Entry> entries(String taskId, long actor) {
        TaskInstanceDO task = visible(taskId, actor, false);
        List<TaskInstanceDO> instance = tasks.instance(task.getRootId());
        boolean active =
                TaskPauses.pausedBy(task, instance) == null
                        && workflowProtection.readOnlyReasons(List.of(task.getRootId())).isEmpty()
                        && instance.stream()
                                .noneMatch(
                                        node ->
                                                node.getId().equals(task.getRootId())
                                                        && Set.of(
                                                                        TaskCenter.State
                                                                                .PENDING_ACCEPTANCE
                                                                                .name(),
                                                                        TaskCenter.State.COMPLETED
                                                                                .name(),
                                                                        TaskCenter.State.CANCELLED
                                                                                .name())
                                                                .contains(node.getStatus()));
        boolean canHandle =
                active
                        && "RUNNING".equals(task.getStatus())
                        && Objects.equals(task.getAssigneeId(), actor);
        List<Entry> result = new ArrayList<>();
        for (TaskWorkEntryDO row : orderedEntries(task)) {
            Config config = read(row.getConfigJson(), Config.class);
            BusinessRef ref = read(row.getBusinessJson(), BusinessRef.class);
            ApplicationRecords.Model model;
            try {
                model =
                        TaskEntryProjection.model(
                                groupRuntime.execute(
                                        taskId,
                                        row.getEntryKey(),
                                        actor,
                                        false,
                                        () -> business.model(ref, config.binding(), actor)),
                                config);
            } catch (ServiceException
                    | org.springframework.security.access.AccessDeniedException denied) {
                // 保留已知办理项的位置，但失效授权不能返回业务数据或计量结果，也不回落到创建人身份。
                result.add(
                        new Entry(
                                config,
                                ref,
                                row.getDatasetId(),
                                Boolean.TRUE.equals(row.getInherited()),
                                false,
                                null,
                                row.getSubmittedJson() != null,
                                TaskDataPolicies.BUSINESS.equals(row.getEntryKey())
                                        ? Category.BUSINESS
                                        : Category.FEEDBACK,
                                TaskEntryScopes.effective(config, null),
                                false,
                                null,
                                groupRuntime.enabled(taskId)
                                        ? "业务授权已失效，请联系任务创建人检查视图、表单及共享权限"
                                        : "此历史任务未配置任务内业务授权，请联系任务创建人"));
                continue;
            }
            result.add(
                    new Entry(
                            config,
                            ref,
                            row.getDatasetId(),
                            Boolean.TRUE.equals(row.getInherited()),
                            canHandle
                                    && (model.permissions().actions().contains("CREATE")
                                            || model.permissions().actions().contains("UPDATE")),
                            null,
                            row.getSubmittedJson() != null,
                            TaskDataPolicies.BUSINESS.equals(row.getEntryKey())
                                    ? Category.BUSINESS
                                    : Category.FEEDBACK,
                            TaskEntryScopes.effective(config, null),
                            groupRuntime.enabled(taskId)
                                    && canHandle
                                    && model.permissions().actions().contains("DELETE"),
                            standardWork.summarize(
                                    taskId,
                                    config,
                                    store.records(row.getDatasetId()),
                                    actor,
                                    canViewTotalWork(task, actor)),
                            null,
                            canHandle && model.permissions().actions().contains("READ")));
        }
        return result;
    }

    private BusinessRef withRecord(BusinessRef ref, String id) {
        return new BusinessRef(ref.resource(), ref.object(), id, null);
    }

    private BusinessRef refreshed(TaskWorkRecordDO contribution, long actor) {
        BusinessRef old = read(contribution.getBusinessJson(), BusinessRef.class);
        String effective =
                old.requestId() == null ? null : store.approvedRecord(contribution.getId());
        BusinessRef value =
                effective == null
                        ? old
                        : new BusinessRef(old.resource(), old.object(), effective, null);
        return value;
    }

    private List<TaskWorkRecordDO> dataset(Context c) {
        return store.records(c.row().getDatasetId()).stream()
                .filter(r -> r.getSupersededBy() == null)
                .filter(r -> !Operation.UNCHANGED.name().equals(r.getOperation()))
                .toList();
    }

    private void scope(Context c, String record, long actor) {
        if (record == null) return;
        if (TaskEntryScopes.allowsAll(c.config())) return;
        for (TaskWorkRecordDO contribution : dataset(c)) {
            BusinessRef ref = refreshed(contribution, actor);
            if (ref.requestId() == null && Objects.equals(ref.recordId(), record)) return;
        }
        throw invalid("此记录不在当前入口共享范围内");
    }

    private TaskEntries.Locator locator(Context c) {
        return groupRuntime.active() || c.config().binding().entryId() == null
                ? null
                : new TaskEntries.Locator(
                        c.ref().resource().applicationId(),
                        c.config().binding().entryId(),
                        c.ref().resource().applicationVersion());
    }

    @Override
    public PageResult<Item> page(Query query, long actor) {
        if (!groupRuntime.active() && groupRuntime.enabled(query.taskId()))
            return groupRuntime.execute(
                    query.taskId(), query.entryKey(), actor, false, () -> page(query, actor));
        Context c = context(query.taskId(), query.entryKey(), actor, false);
        if (query.all() && !TaskEntryScopes.allowsAll(c.config())) throw invalid("当前入口只允许任务关联数据");
        int page = Math.max(1, query.pageNo()), size = Math.max(1, Math.min(100, query.pageSize()));
        List<TaskWorkRecordDO> contributions = dataset(c);
        if (query.all() && !query.onlyMine()) {
            if (c.config().readableFieldIds() != null
                    && query.search() != null
                    && !query.search().isBlank()) throw invalid("当前入口限制了可读字段；请切换任务数据范围后搜索");
            ApplicationRecords.Query q =
                    new ApplicationRecords.Query(
                            c.ref().resource().applicationId(),
                            c.ref().object().objectId(),
                            page,
                            size,
                            query.search(),
                            Map.of(),
                            null,
                            true,
                            c.config().binding().viewId());
            PageResult<ApplicationRecords.Row> found =
                    published.withVersion(
                            c.ref().resource(),
                            () ->
                                    locator(c) == null
                                            ? records.page(q, actor)
                                            : entries.page(
                                                    new TaskEntries.Query(locator(c), q), actor));
            return new PageResult<>(
                    found.getList().stream()
                            .map(
                                    r ->
                                            new Item(
                                                    r.id(),
                                                    TaskEntryProjection.row(
                                                            business.project(
                                                                    c.ref(),
                                                                    c.config().binding(),
                                                                    r),
                                                            c.config()),
                                                    null,
                                                    "EFFECTIVE",
                                                    sources(contributions, r.id(), actor)))
                            .toList(),
                    found.getTotal());
        }
        Map<String, Item> found = new LinkedHashMap<>();
        for (TaskWorkRecordDO contribution : contributions) {
            if (query.onlyMine() && !contribution.getTaskId().equals(query.taskId())) continue;
            try {
                BusinessRef ref = refreshed(contribution, actor);
                String key = ref.recordId() == null ? contribution.getId() : ref.recordId();
                if (found.containsKey(key)) continue;
                if (ref.requestId() != null
                        && !contribution.getCreator().equals(Long.toString(actor))) continue;
                ApplicationRecords.Aggregate aggregate = feedbackRecord(ref, c.config(), actor);
                BusinessHandling.Result state = business.result(ref, c.config().binding(), actor);
                ApplicationRecords.Row row =
                        aggregate == null
                                ? null
                                : TaskEntryProjection.row(aggregate.record(), c.config());
                if (query.search() != null
                        && !query.search().isBlank()
                        && (row == null
                                || row.values().values().stream()
                                        .noneMatch(
                                                v ->
                                                        String.valueOf(v)
                                                                .toLowerCase(Locale.ROOT)
                                                                .contains(
                                                                        query.search()
                                                                                .toLowerCase(
                                                                                        Locale
                                                                                                .ROOT)))))
                    continue;
                found.put(
                        key,
                        new Item(
                                contribution.getId(),
                                row,
                                ref.requestId(),
                                state.outcome(),
                                sources(contributions, ref.recordId(), actor)));
            } catch (ServiceException unavailable) {
                /* 撤权、删除后的记录不因任务关联重新暴露。 */
            }
        }
        List<Item> values = new ArrayList<>(found.values());
        int start = Math.min(values.size(), (page - 1) * size);
        return new PageResult<>(
                values.subList(start, Math.min(values.size(), start + size)), (long) values.size());
    }

    private List<Source> sources(List<TaskWorkRecordDO> rows, String record, long actor) {
        if (record == null) return List.of();
        List<Source> result = new ArrayList<>();
        for (TaskWorkRecordDO row : rows) {
            BusinessRef ref = refreshed(row, actor);
            if (ref.requestId() != null || !record.equals(ref.recordId())) continue;
            TaskInstanceDO task = tasks.get(row.getTaskId(), false);
            long owner = Long.parseLong(row.getCreator());
            AdminUserRespDTO user = users.getUser(owner);
            result.add(
                    new Source(
                            row.getTaskId(),
                            task == null ? "历史任务" : task.getTitle(),
                            owner,
                            user == null ? row.getCreator() : user.getNickname(),
                            row.getEntryKey(),
                            row.getOperation(),
                            row.getRecordRevision(),
                            row.getCreateTime()));
        }
        return result;
    }

    private BusinessRef formRef(Context c, Form query, long actor) {
        if (query.contributionId() != null) {
            TaskWorkRecordDO row = store.record(query.contributionId());
            if (row == null
                    || row.getSupersededBy() != null
                    || !row.getDatasetId().equals(c.row().getDatasetId()))
                throw invalid("办理记录不属于当前入口");
            BusinessRef ref = refreshed(row, actor);
            if (ref.requestId() != null && !row.getCreator().equals(Long.toString(actor)))
                throw invalid("只能恢复本人尚未生效的申请");
            if (query.recordId() != null && !Objects.equals(query.recordId(), ref.recordId()))
                throw invalid("业务记录身份不一致");
            return ref;
        }
        scope(c, query.recordId(), actor);
        return withRecord(c.ref(), query.recordId());
    }

    /** 未生效反馈仅向本人呈现封存填写意图；材料恢复仍裁剪当前行、字段、明细授权。 */
    private ApplicationRecords.Aggregate feedbackRecord(
            BusinessRef ref, Config config, long actor) {
        if (ref.requestId() == null) return business.read(ref, config.binding(), actor);
        BusinessHandling.Detail detail = handling.detail(ref.requestId(), null, actor);
        if (com.richuang.os.nocode.enums.HandlingStateEnum.REJECTED.matches(
                        detail.request().status())
                || com.richuang.os.nocode.enums.HandlingStateEnum.CANCELED.matches(
                        detail.request().status()))
            return business.formRecord(ref, config.binding(), actor);
        ApplicationRecords.Model model = business.model(ref, config.binding(), actor);
        ApplicationRecords.Aggregate current = business.read(ref, config.binding(), actor);
        ApplicationAuthorization.Capabilities rights =
                current == null ? model.permissions() : current.record().permissions();
        com.richuang.os.nocode.api.work.WorkDrafts.Submission material = detail.material();
        ApplicationRecords.Row row =
                new ApplicationRecords.Row(
                        ref.recordId(),
                        material.recordRevision(),
                        material.values(),
                        rights,
                        material.displayValues());
        ApplicationRecords.Aggregate submitted =
                new ApplicationRecords.Aggregate(
                        row,
                        material.details(),
                        List.of(),
                        material.handling() == null ? Map.of() : material.handling().relations());
        return com.richuang.os.nocode.runtime.service.handling.HandlingMaterials.restore(
                submitted, model.object(), business.currentDefinition(ref));
    }

    @Override
    public TaskCenter.FormContext form(Form query, long actor) {
        if (!groupRuntime.active() && groupRuntime.enabled(query.taskId()))
            return groupRuntime.execute(
                    query.taskId(), query.entryKey(), actor, false, () -> form(query, actor));
        Context c = context(query.taskId(), query.entryKey(), actor, false);
        BusinessRef ref = formRef(c, query, actor);
        ApplicationRecords.Model model =
                TaskEntryProjection.model(
                        business.model(ref, c.config().binding(), actor), c.config());
        ApplicationRecords.Aggregate record = feedbackRecord(ref, c.config(), actor);
        return new TaskCenter.FormContext(
                ref,
                model,
                business.definition(ref),
                TaskEntryProjection.aggregate(record, c.config()),
                new ArrayList<>(model.permissions().writeFields()),
                ref.recordId() == null && ref.requestId() == null
                        ? null
                        : TaskEntryProjection.handling(
                                business.result(ref, c.config().binding(), actor), c.config()));
    }

    @Override
    @Transactional
    public Saved save(Save command, long actor) {
        if (!groupRuntime.active() && groupRuntime.enabled(command.taskId()))
            return groupRuntime.execute(
                    command.taskId(), command.entryKey(), actor, true, () -> save(command, actor));
        Context c = context(command.taskId(), command.entryKey(), actor, true);
        if (command.record() == null) throw invalid("缺少业务数据内容");
        String key = key(command.record().requestKey());
        String fingerprint = hash(command);
        tasks.requestLock("task-work-entry:" + actor + ":" + key);
        TaskWorkRecordDO receipt = store.receipt(Long.toString(actor), key);
        if (receipt != null) return receipt(c, receipt, fingerprint, actor);
        BusinessRef ref =
                formRef(
                        c,
                        new Form(
                                command.taskId(),
                                command.entryKey(),
                                command.record().id(),
                                command.contributionId()),
                        actor);
        if (command.contributionId() != null && ref.requestId() != null) {
            TaskWorkRecordDO original = store.record(command.contributionId());
            if (original == null || original.getSupersededBy() != null)
                throw invalid("办理记录已失效，请刷新后重试");
            // 共享可见性只允许查看；原申请的重提必须留在原任务、原办理项，避免改写贡献归属。
            if (!Objects.equals(original.getTaskId(), c.task().getId())
                    || !Objects.equals(original.getEntryKey(), c.config().key()))
                throw invalid("请回到原任务的原办理项重新提交申请");
        }
        TaskEntryProjection.validateWrite(command.record(), c.config());
        long checkpoint = recordHistory.transactionCheckpoint();
        String contributionId = UUID.randomUUID().toString();
        BusinessRef saved =
                business.save(
                        ref,
                        c.config().binding(),
                        new TaskCenter.Sharing(TaskCenter.DataMode.INDEPENDENT, null, List.of()),
                        command.record(),
                        actor);
        int changes =
                saved.requestId() == null
                        ? recordHistory.bindTaskContribution(
                                checkpoint,
                                saved.resource().applicationId(),
                                saved.object().objectId(),
                                saved.recordId(),
                                Long.toString(actor),
                                contributionId)
                        : 0;
        Saved result =
                contribution(
                        c,
                        saved,
                        saved.requestId() == null && changes == 0
                                ? Operation.UNCHANGED
                                : command.record().id() == null
                                        ? Operation.CREATED
                                        : Operation.UPDATED,
                        key,
                        fingerprint,
                        actor,
                        contributionId);
        if (command.contributionId() != null && ref.requestId() != null)
            store.supersede(
                    command.contributionId(), result.contributionId(), Long.toString(actor));
        return result;
    }

    @Override
    @Transactional
    public Saved link(Link command, long actor) {
        if (!groupRuntime.active() && groupRuntime.enabled(command.taskId()))
            return groupRuntime.execute(
                    command.taskId(), command.entryKey(), actor, true, () -> link(command, actor));
        Context c = context(command.taskId(), command.entryKey(), actor, true);
        if (command.recordId() == null || command.recordId().isBlank())
            throw invalid("请选择要关联的业务记录");
        String key = key(command.requestKey());
        String fingerprint = hash(command);
        tasks.requestLock("task-work-entry:" + actor + ":" + key);
        TaskWorkRecordDO receipt = store.receipt(Long.toString(actor), key);
        if (receipt != null) return receipt(c, receipt, fingerprint, actor);
        if (!TaskEntryScopes.allowsAll(c.config())) scope(c, command.recordId(), actor);
        BusinessRef ref = withRecord(c.ref(), command.recordId());
        business.read(ref, c.config().binding(), actor);
        return contribution(
                c, ref, Operation.LINKED, key, fingerprint, actor, UUID.randomUUID().toString());
    }

    private Saved receipt(Context c, TaskWorkRecordDO receipt, String hash, long actor) {
        if (!hash.equals(receipt.getRequestHash())
                || !receipt.getTaskId().equals(c.task().getId())
                || !receipt.getEntryKey().equals(c.config().key()))
            throw invalid("同一请求标识不能重复用于不同内容");
        return receiptResult(c, receipt, actor);
    }

    /** 公共删除引擎负责修订、引用完整性、审批和单据保护；失败时贡献与历史一同回滚。 */
    @Override
    @Transactional
    public boolean delete(Delete command, long actor) {
        if (!groupRuntime.enabled(command.taskId())) throw invalid("旧版任务不支持此数据删除入口");
        if (!groupRuntime.active())
            return groupRuntime.execute(
                    command.taskId(),
                    command.entryKey(),
                    actor,
                    true,
                    () -> delete(command, actor));
        Context c = context(command.taskId(), command.entryKey(), actor, true);
        String key = key(command.requestKey());
        String fingerprint = hash(command);
        tasks.requestLock("task-work-entry:" + actor + ":" + key);
        TaskWorkRecordDO previous = store.receipt(Long.toString(actor), key);
        if (previous != null) {
            if (!fingerprint.equals(previous.getRequestHash())
                    || !c.task().getId().equals(previous.getTaskId())
                    || !c.config().key().equals(previous.getEntryKey())
                    || !Operation.DELETED.name().equals(previous.getOperation()))
                throw invalid("同一请求标识不能重复用于不同内容");
            return true;
        }
        scope(c, command.recordId(), actor);
        BusinessRef ref = withRecord(c.ref(), command.recordId());
        ApplicationRecords.Aggregate before = business.read(ref, c.config().binding(), actor);
        if (before == null) throw invalid("请选择要删除的业务记录");
        long checkpoint = recordHistory.transactionCheckpoint();
        String contributionId = UUID.randomUUID().toString();
        published.withVersion(
                ref.resource(),
                () -> {
                    records.delete(
                            new ApplicationRecords.Delete(
                                    ref.resource().applicationId(),
                                    ref.object().objectId(),
                                    command.recordId(),
                                    command.expectedRevision()),
                            actor);
                    return null;
                });
        recordHistory.bindTaskContribution(
                checkpoint,
                ref.resource().applicationId(),
                ref.object().objectId(),
                ref.recordId(),
                Long.toString(actor),
                contributionId);
        TaskWorkRecordDO receipt = new TaskWorkRecordDO();
        receipt.setId(contributionId);
        receipt.setTaskId(c.task().getId());
        receipt.setEntryKey(c.config().key());
        receipt.setDatasetId(c.row().getDatasetId());
        receipt.setBusinessJson(write(ref));
        receipt.setOperation(Operation.DELETED.name());
        receipt.setRecordRevision(before.record().revision());
        receipt.setSnapshotJson(write(before));
        receipt.setRequestKey(key);
        receipt.setRequestHash(fingerprint);
        store.saveRecord(receipt, Long.toString(actor));
        TaskHistoryDO event = new TaskHistoryDO();
        event.setId(UUID.randomUUID().toString());
        event.setTaskId(c.task().getId());
        event.setRootId(c.task().getRootId());
        event.setEventType(TaskCenter.EventType.BUSINESS_SAVED.name());
        event.setNote(
                "删除业务数据："
                        + c.config().name()
                        + "；入口="
                        + c.config().key()
                        + "；记录="
                        + command.recordId());
        event.setRequestKey(key);
        event.setRequestHash(fingerprint);
        tasks.appendEvent(event, Long.toString(actor));
        return true;
    }

    /** 重放提交返回原保存版本，而非把其他人的后续修改归给本次提交。 */
    private Saved receiptResult(Context c, TaskWorkRecordDO row, long actor) {
        BusinessRef ref = refreshed(row, actor);
        BusinessHandling.Result current = business.result(ref, c.config().binding(), actor);
        if (ref.requestId() == null && current.result() != null && row.getSnapshotJson() != null) {
            ApplicationRecords.Aggregate snapshot =
                    TaskMaterials.project(
                            read(row.getSnapshotJson(), ApplicationRecords.Aggregate.class),
                            current.result(),
                            business.currentDefinition(ref));
            current = new BusinessHandling.Result(current.outcome(), snapshot, null);
        }
        return new Saved(row.getId(), TaskEntryProjection.handling(current, c.config()));
    }

    private Saved contribution(
            Context c,
            BusinessRef ref,
            Operation operation,
            String key,
            String fingerprint,
            long actor,
            String contributionId) {
        TaskWorkRecordDO row = new TaskWorkRecordDO();
        row.setId(contributionId);
        row.setTaskId(c.task().getId());
        row.setEntryKey(c.config().key());
        row.setDatasetId(c.row().getDatasetId());
        row.setBusinessJson(write(ref));
        row.setOperation(operation.name());
        row.setRequestKey(key);
        row.setRequestHash(fingerprint);
        ApplicationRecords.Aggregate saved =
                ref.requestId() == null ? business.read(ref, c.config().binding(), actor) : null;
        row.setSnapshotJson(saved == null ? null : write(saved));
        row.setWorkRuleJson(write(c.config().workRule()));
        row.setRecordRevision(saved == null ? null : saved.record().revision());
        store.saveRecord(row, Long.toString(actor));
        TaskHistoryDO event = new TaskHistoryDO();
        event.setId(UUID.randomUUID().toString());
        event.setTaskId(c.task().getId());
        event.setRootId(c.task().getRootId());
        event.setEventType(TaskCenter.EventType.BUSINESS_SAVED.name());
        String subject = "业务数据";
        String action =
                switch (operation) {
                    case CREATED -> "新增";
                    case UPDATED -> "修改";
                    case DELETED -> "删除";
                    case LINKED -> "关联";
                    case UNCHANGED -> "保存内容未变化：";
                };
        event.setNote(
                (ref.requestId() == null ? action : "提交办理申请：") + subject + "：" + c.config().name());
        event.setRequestKey(key);
        event.setRequestHash(fingerprint);
        tasks.appendEvent(event, Long.toString(actor));
        return new Saved(
                row.getId(),
                TaskEntryProjection.handling(
                        business.result(ref, c.config().binding(), actor), c.config()));
    }

    @Override
    public void complete(String taskId, long actor) {
        TaskInstanceDO task = visible(taskId, actor, true);
        for (TaskWorkEntryDO entry : orderedEntries(task)) {
            groupRuntime.execute(
                    taskId,
                    entry.getEntryKey(),
                    actor,
                    true,
                    () -> {
                        completeEntry(taskId, entry, actor);
                        return null;
                    });
        }
    }

    /** 预检复用封存前的读取与有效性规则，但绝不调用材料持久化。 */
    @Override
    public List<TaskGuidance.Check> completionChecks(String taskId, long actor) {
        TaskInstanceDO task = visible(taskId, actor, false);
        List<TaskGuidance.Check> checks = new ArrayList<>();
        for (TaskWorkEntryDO entry : orderedEntries(task)) {
            TaskGuidance.CheckCode code =
                    TaskDataPolicies.BUSINESS.equals(entry.getEntryKey())
                            ? TaskGuidance.CheckCode.BUSINESS
                            : TaskGuidance.CheckCode.FEEDBACK;
            try {
                CompletionInspection inspection =
                        groupRuntime.execute(
                                taskId,
                                entry.getEntryKey(),
                                actor,
                                false,
                                () -> inspectCompletion(taskId, entry, actor));
                Config config = read(entry.getConfigJson(), Config.class);
                checks.add(
                        new TaskGuidance.Check(
                                code,
                                config.name(),
                                inspection.blockedReason() == null,
                                inspection.blockedReason(),
                                entry.getEntryKey()));
            } catch (ServiceException denied) {
                // 被撤权入口不能被当作“没有配置”，也不能从错误中泄漏资源名称或材料。
                checks.add(
                        new TaskGuidance.Check(
                                code, "业务数据", false, "该数据入口当前不可访问，请联系任务负责人核对权限和资源状态", null));
            }
        }
        return checks;
    }

    private record CompletionInspection(List<Submission> submissions, String blockedReason) {}

    private void completeEntry(String taskId, TaskWorkEntryDO entry, long actor) {
        CompletionInspection inspection = inspectCompletion(taskId, entry, actor);
        if (inspection.blockedReason() != null) throw invalid(inspection.blockedReason());
        entry.setSubmittedJson(write(inspection.submissions()));
        store.saveEntry(entry, Long.toString(actor));
    }

    private CompletionInspection inspectCompletion(
            String taskId, TaskWorkEntryDO entry, long actor) {
        Context c = context(taskId, entry.getEntryKey(), actor, false);
        List<Submission> result = new ArrayList<>();
        for (TaskWorkRecordDO contribution : dataset(c)) {
            BusinessRef ref = refreshed(contribution, actor);
            if (ref.requestId() != null && contribution.getTaskId().equals(taskId))
                return new CompletionInspection(
                        List.of(), "入口“" + c.config().name() + "”仍有未生效申请，请处理后再完成任务");
            if (ref.requestId() != null || ref.recordId() == null) continue;
            try {
                ApplicationRecords.Aggregate current =
                        groupRuntime.active()
                                ? business.readIfPresent(ref, c.config().binding(), actor)
                                : business.read(ref, c.config().binding(), actor);
                if (current == null) continue;
                ApplicationRecords.Aggregate snapshot =
                        contribution.getSnapshotJson() == null
                                ? current
                                : TaskMaterials.project(
                                        read(
                                                contribution.getSnapshotJson(),
                                                ApplicationRecords.Aggregate.class),
                                        current,
                                        business.currentDefinition(ref));
                result.add(
                        new Submission(
                                contribution.getId(),
                                ref,
                                TaskEntryProjection.aggregate(snapshot, c.config()),
                                sources(List.of(contribution), ref.recordId(), actor)));
            } catch (ServiceException unavailable) {
                if (groupRuntime.active()) throw unavailable;
                // 删除或撤权记录不能满足新交付要求。
            }
        }
        if (c.config().required() && result.isEmpty())
            return new CompletionInspection(result, "入口“" + c.config().name() + "”至少需要一条有效业务记录");
        return new CompletionInspection(result, null);
    }

    @Override
    public List<Material> materials(String taskId, long actor) {
        TaskInstanceDO task = visible(taskId, actor, false);
        List<Material> result = new ArrayList<>();
        for (TaskWorkEntryDO row : orderedEntries(task)) {
            if (row.getSubmittedJson() == null) continue;
            result.add(
                    groupRuntime.execute(
                            taskId,
                            row.getEntryKey(),
                            actor,
                            false,
                            () -> material(taskId, row, actor)));
        }
        return result;
    }

    @Override
    public List<Material> snapshot(String taskId, long actor) {
        TaskInstanceDO task = visible(taskId, actor, true);
        List<Material> result = new ArrayList<>();
        for (TaskWorkEntryDO row : orderedEntries(task)) {
            if (row.getSubmittedJson() == null) continue;
            Config config = read(row.getConfigJson(), Config.class);
            result.add(
                    new Material(
                            row.getEntryKey(),
                            config.name(),
                            List.of(),
                            read(
                                    row.getSubmittedJson(),
                                    new TypeReference<List<Submission>>() {})));
        }
        return List.copyOf(result);
    }

    @Override
    public List<Material> materials(String taskId, List<Material> snapshot, long actor) {
        visible(taskId, actor, false);
        List<Material> result = new ArrayList<>();
        for (Material saved : snapshot) {
            result.add(
                    groupRuntime.execute(
                            taskId,
                            saved.entryKey(),
                            actor,
                            false,
                            () -> material(taskId, saved.entryKey(), saved.submissions(), actor)));
        }
        return List.copyOf(result);
    }

    private Material material(String taskId, TaskWorkEntryDO row, long actor) {
        return material(
                taskId,
                row.getEntryKey(),
                read(row.getSubmittedJson(), new TypeReference<List<Submission>>() {}),
                actor);
    }

    private Material material(
            String taskId, String entryKey, List<Submission> snapshot, long actor) {
        Context c = context(taskId, entryKey, actor, false);
        List<Submission> submissions = new ArrayList<>();
        List<Item> items = new ArrayList<>();
        for (Submission item : snapshot) {
            try {
                ApplicationRecords.Aggregate current =
                        groupRuntime.active()
                                ? business.readIfPresent(
                                        item.binding(), c.config().binding(), actor)
                                : business.read(item.binding(), c.config().binding(), actor);
                if (current == null) continue;
                ApplicationRecords.Aggregate projected =
                        TaskEntryProjection.aggregate(
                                TaskMaterials.project(
                                        item.record(),
                                        current,
                                        business.currentDefinition(item.binding())),
                                c.config());
                submissions.add(
                        new Submission(
                                item.contributionId(), item.binding(), projected, item.sources()));
                items.add(
                        new Item(
                                item.contributionId(),
                                projected.record(),
                                null,
                                "EFFECTIVE",
                                item.sources()));
            } catch (ServiceException unavailable) {
                if (groupRuntime.active()) throw unavailable;
                // 固定材料不恢复当前撤销的行和字段权限。
            }
        }
        ApplicationRecords.Model model =
                TaskEntryProjection.model(
                        business.model(c.ref(), c.config().binding(), actor), c.config());
        ApplicationRecords.Model readOnly =
                new ApplicationRecords.Model(
                        model.object(),
                        false,
                        model.generatedKey(),
                        model.keyFieldId(),
                        model.keyType(),
                        model.details(),
                        model.permissions(),
                        model.managedFieldIds(),
                        model.orderedStates());
        return new Material(entryKey, c.config().name(), items, submissions, readOnly, c.ref());
    }

    private <T> T helper(Form target, long actor, Function<Context, T> action) {
        if (target == null) throw invalid("缺少任务表单上下文");
        if (!groupRuntime.active() && groupRuntime.enabled(target.taskId()))
            return groupRuntime.execute(
                    target.taskId(),
                    target.entryKey(),
                    actor,
                    false,
                    () -> helper(target, actor, action));
        Context c = context(target.taskId(), target.entryKey(), actor, false);
        BusinessRef ref = formRef(c, target, actor);
        business.formRecord(ref, c.config().binding(), actor);
        Context resolved = new Context(c.task(), c.row(), c.config(), ref);
        return published.withVersion(ref.resource(), () -> action.apply(resolved));
    }

    private void target(Context c, String app, String object, String form, String record) {
        if (!Objects.equals(c.ref().resource().applicationId(), app)
                || !Objects.equals(c.ref().object().objectId(), object)
                || !Objects.equals(c.ref().resource().resourceId(), form)
                || !Objects.equals(c.ref().recordId(), record))
            throw invalid("辅助查询必须属于当前入口固定表单和记录");
    }

    @Override
    public com.richuang.os.nocode.api.FieldRules.Evaluation fieldRules(
            TaskWorkEntries.FieldRules request, long actor) {
        if (request == null || request.query() == null) throw invalid("缺少表单求值条件");
        return helper(
                request.target(),
                actor,
                c -> {
                    com.richuang.os.nocode.api.FieldRules.EvaluateQuery query = request.query();
                    target(
                            c,
                            query.applicationId(),
                            query.objectId(),
                            query.formId(),
                            query.recordId());
                    if (query.details() != null && !query.details().isEmpty())
                        TaskEntryProjection.requireDetails(c.config());
                    ApplicationAuthorization.Capabilities caps =
                            TaskEntryProjection.caps(
                                    business.model(c.ref(), c.config().binding(), actor)
                                            .permissions(),
                                    c.config());
                    com.richuang.os.nocode.api.FieldRules.Evaluation result =
                            locator(c) == null
                                    ? records.evaluateRules(query, actor)
                                    : entries.fieldRules(
                                            new TaskEntries.FieldRules(locator(c), query), actor);
                    return new com.richuang.os.nocode.api.FieldRules.Evaluation(
                            result.results().stream()
                                    .filter(
                                            value ->
                                                    value.detailId() == null
                                                            ? caps.readFields()
                                                                    .contains(value.fieldId())
                                                            : caps.readDetails()
                                                                    .contains(value.detailId()))
                                    .toList());
                });
    }

    @Override
    public com.richuang.os.nocode.api.FieldRules.Evaluation relatedFieldRules(
            TaskWorkEntries.RelatedFieldRules request, long actor) {
        if (request == null
                || request.query() == null
                || request.query().context() == null
                || request.query().query() == null) throw invalid("缺少关联表单求值条件");
        return helper(
                request.target(),
                actor,
                c -> {
                    RelatedForms.Query context = request.query().context();
                    target(
                            c,
                            context.applicationId(),
                            context.objectId(),
                            context.formId(),
                            context.recordId());
                    TaskEntryProjection.requireDetails(c.config());
                    return locator(c) == null
                            ? relatedForms.fieldRules(request.query(), actor)
                            : entries.relatedFieldRules(
                                    new RelatedForms.TaskFieldRules(locator(c), request.query()),
                                    actor);
                });
    }

    @Override
    public SelectionFields.Result selection(Selection q, long actor) {
        return helper(
                q.target(),
                actor,
                c -> {
                    SelectionFields.Query v = q.query();
                    target(c, v.applicationId(), v.objectId(), v.formId(), v.recordId());
                    if (c.config().readableFieldIds() != null
                            && !c.config().readableFieldIds().contains(v.fieldId()))
                        throw invalid("当前入口不可读取此字段");
                    return locator(c) == null
                            ? records.selection(v, actor)
                            : entries.selection(new TaskEntries.Selection(locator(c), v), actor);
                });
    }

    @Override
    public Map<String, Object> fill(Fill q, long actor) {
        return helper(
                q.target(),
                actor,
                c -> {
                    FormFills.Query v = q.query();
                    target(c, v.applicationId(), v.objectId(), v.formId(), v.recordId());
                    Map<String, Object> result =
                            locator(c) == null
                                    ? records.formFill(v, actor)
                                    : entries.formFill(
                                            new TaskEntries.FormFill(locator(c), v), actor);
                    return TaskEntryProjection.values(result, c.config());
                });
    }

    @Override
    public RelatedForms.Result related(Related q, long actor) {
        return helper(
                q.target(),
                actor,
                c -> {
                    RelatedForms.Query v = q.query();
                    target(c, v.applicationId(), v.objectId(), v.formId(), v.recordId());
                    TaskEntryProjection.requireDetails(c.config());
                    return locator(c) == null
                            ? relatedForms.query(v, actor)
                            : entries.relatedForm(new RelatedForms.TaskQuery(locator(c), v), actor);
                });
    }

    @Override
    public SelectionFields.Result relatedSelection(RelatedSelection q, long actor) {
        return helper(
                q.target(),
                actor,
                c -> {
                    RelatedForms.Query v = q.query().context();
                    target(c, v.applicationId(), v.objectId(), v.formId(), v.recordId());
                    TaskEntryProjection.requireDetails(c.config());
                    return locator(c) == null
                            ? relatedForms.selection(q.query(), actor)
                            : entries.relatedSelection(
                                    new RelatedForms.TaskSelection(locator(c), q.query()), actor);
                });
    }

    @Override
    public Map<String, Object> relatedFill(RelatedFill q, long actor) {
        return helper(
                q.target(),
                actor,
                c -> {
                    RelatedForms.Query v = q.query().context();
                    target(c, v.applicationId(), v.objectId(), v.formId(), v.recordId());
                    TaskEntryProjection.requireDetails(c.config());
                    return locator(c) == null
                            ? relatedForms.fill(q.query(), actor)
                            : entries.relatedFill(
                                    new RelatedForms.TaskFill(locator(c), q.query()), actor);
                });
    }

    private String key(String value) {
        if (value == null || value.isBlank() || value.length() > 120) throw invalid("缺少有效的提交请求标识");
        return value;
    }

    private String hash(Object value) {
        return cn.hutool.crypto.digest.DigestUtil.sha256Hex(write(value));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception error) {
            throw invalid("入口内容无法保存");
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception error) {
            throw invalid("入口内容无法读取");
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception error) {
            throw invalid("入口内容无法读取");
        }
    }
}
