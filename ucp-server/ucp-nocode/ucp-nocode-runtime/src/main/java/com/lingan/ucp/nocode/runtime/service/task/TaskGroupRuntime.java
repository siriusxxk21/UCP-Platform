package com.lingan.ucp.nocode.runtime.service.task;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.module.system.api.user.dto.AdminUserRespDTO;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationAuthorization.ObjectGrant;
import com.lingan.ucp.nocode.api.TaskCenter.*;
import com.lingan.ucp.nocode.runtime.dal.dataobject.*;
import com.lingan.ucp.nocode.runtime.dal.mapper.*;
import com.lingan.ucp.nocode.runtime.service.taskcenter.*;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskDataPolicies.FrozenRoot;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskDataPolicyCompiler.ResourceGrant;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Supplier;

/** 总任务批准的临时记录能力；调用者必须仍有本节点身份，且不允许切换到兄弟节点执行。 */
@Component
public class TaskGroupRuntime {
    @Resource private TaskCenterMapper tasks;
    @Resource private TaskWorkflowProtection workflowProtection;
    @Resource private TaskWorkEntryMapper entries;
    @Resource private TaskDataPolicies policies;
    @Resource private TaskDataPolicyCompiler compiler;
    @Resource private TaskEntryRuntimeScope scope;
    @Resource private AdminUserApi users;
    @Resource private PermissionCommonApi permissions;
    @Resource private ObjectMapper json;

    public boolean active() {
        return scope.delegated();
    }

    public FrozenRoot policy(String taskId) {
        TaskInstanceDO task = tasks.get(taskId, false);
        if (task == null) throw invalid("任务不存在");
        TaskInstanceDO root = tasks.get(task.getRootId(), false);
        return root == null ? null : policies.decode(root.getAuthorizationJson());
    }

    public boolean enabled(String taskId) {
        return policy(taskId) != null;
    }

    public <T> T execute(String taskId, String key, long actor, boolean write, Supplier<T> action) {
        TaskInstanceDO task = tasks.get(taskId, false);
        if (task == null) throw invalid("任务不存在");
        TaskInstanceDO root = tasks.get(task.getRootId(), write);
        if (write) {
            workflowProtection.requireActive(task.getRootId());
            task = tasks.get(taskId, false);
            if (task == null) throw invalid("任务已删除，请刷新后重试");
            TaskPauses.requireActive(task, tasks.instance(task.getRootId()));
        }
        FrozenRoot frozen = root == null ? null : policies.decode(root.getAuthorizationJson());
        if (frozen == null) return action.get();
        TaskEntryRuntimeScope.Invocation current = scope.current();
        if (current != null) {
            if (current.data() == null
                    || current.actor() != actor
                    || !taskId.equals(current.data().taskId())
                    || !key.equals(current.data().entryKey())) throw invalid("不能在办理中切换任务数据授权");
            if (write) scope.requireWrite();
            return action.get();
        }
        AdminUserRespDTO user = users.getUser(actor);
        if (user == null || !Integer.valueOf(0).equals(user.getStatus())) throw invalid("当前账号已停用");
        boolean owner =
                Long.toString(actor).equals(root.getCreator())
                        || Long.toString(actor).equals(task.getCreator());
        boolean manager =
                permissions.hasAnyRoles(actor, "super_admin")
                        || permissions.hasAnyPermissions(actor, "nocode:task:manage-all");
        boolean acceptor;
        try {
            acceptor =
                    root.getConfigJson() != null
                            && Objects.equals(
                                    json.readValue(root.getConfigJson(), NodeInput.class)
                                            .acceptorId(),
                                    actor);
        } catch (Exception failure) {
            throw invalid("任务人员配置无法读取");
        }
        if (!Objects.equals(task.getAssigneeId(), actor) && !owner && !manager && !acceptor)
            throw invalid("仅当前任务执行人、创建人、验收人或任务管理人员可访问任务业务数据");
        if (write
                && (!Objects.equals(task.getAssigneeId(), actor)
                        || !"RUNNING".equals(task.getStatus())
                        || "PENDING_ACCEPTANCE".equals(root.getStatus())
                        || "COMPLETED".equals(root.getStatus())
                        || "CANCELLED".equals(root.getStatus())))
            throw invalid("仅进行中任务的负责人可以修改任务数据");
        ResourceGrant selected =
                frozen.grants().resources().stream()
                        .filter(g -> key.equals(g.entryKey()))
                        .findFirst()
                        .orElseThrow(() -> invalid("总任务没有配置此业务资源"));
        Map<String, List<ObjectGrant>> grants =
                new LinkedHashMap<>(
                        compiler.applicationGrants(selected, frozen.grants().grantorId()));
        Map<String, Set<String>> records = new HashMap<>();
        DataAccessMode selectedMode = TaskEntryScopes.root(frozen.task(), key);
        if (selectedMode == DataAccessMode.GROUP)
            grants.keySet().forEach(object -> records.put(object, new HashSet<>()));
        // 显式资源优先于应用补齐能力；失效资源不能借自动补齐继续访问。
        Set<String> configured = new HashSet<>();
        for (ResourceGrant grant : frozen.grants().resources()) {
            if (!selected.binding().applicationId().equals(grant.binding().applicationId()))
                continue;
            configured.add(grant.ref().object().objectId());
        }
        configured.forEach(grants::remove);
        configured.forEach(records::remove);
        for (ResourceGrant grant : frozen.grants().resources()) {
            if (!selected.binding().applicationId().equals(grant.binding().applicationId()))
                continue;
            String object = grant.ref().object().objectId();
            if (!grant.entryKey().equals(key) && object.equals(selected.ref().object().objectId()))
                continue;
            List<ObjectGrant> effective;
            try {
                effective = compiler.revalidate(grant, frozen.grants().grantorId());
            } catch (com.lingan.ucp.framework.common.exception.ServiceException
                    | org.springframework.security.access.AccessDeniedException denied) {
                if (grant.entryKey().equals(key)) throw denied;
                // 辅助资源撤权不连带关闭仍有效的办理项，也不能保留其旧读取授权。
                continue;
            }
            grants.computeIfAbsent(object, unused -> new ArrayList<>()).addAll(effective);
            DataAccessMode mode = TaskEntryScopes.root(frozen.task(), grant.entryKey());
            if (mode == DataAccessMode.GROUP)
                records.computeIfAbsent(object, unused -> new HashSet<>())
                        .addAll(members(root.getId(), grant));
        }
        // 同对象多个资源不能让辅助资源更宽范围抬升当前入口。
        if (selectedMode == DataAccessMode.GROUP)
            records.put(
                    selected.ref().object().objectId(),
                    new HashSet<>(members(root.getId(), selected)));
        else records.remove(selected.ref().object().objectId());
        TaskEntryRuntimeScope.TaskData data =
                new TaskEntryRuntimeScope.TaskData(
                        root.getId(),
                        taskId,
                        key,
                        frozen.grants().grantorId(),
                        records,
                        write,
                        configured);
        return scope.execute(
                new TaskEntryRuntimeScope.Invocation(
                        selected.binding().applicationId(),
                        key,
                        selected.ref().resource().applicationVersion(),
                        task.getTitle(),
                        selected.ref().object().objectId(),
                        actor,
                        grants,
                        data),
                action);
    }

    private Set<String> members(String rootId, ResourceGrant grant) {
        Set<String> ids = new HashSet<>();
        for (TaskWorkRecordDO contribution : entries.records(rootId + ":" + grant.entryKey())) {
            if (contribution.getSupersededBy() != null) continue;
            try {
                BusinessRef ref = json.readValue(contribution.getBusinessJson(), BusinessRef.class);
                if (!Objects.equals(ref.resource(), grant.ref().resource())
                        || !Objects.equals(ref.object(), grant.ref().object())) continue;
                String id =
                        ref.requestId() == null
                                ? ref.recordId()
                                : entries.approvedRecord(contribution.getId());
                if (id != null) ids.add(id);
            } catch (Exception failure) {
                throw invalid("任务数据目录无法读取");
            }
        }
        return ids;
    }
}
