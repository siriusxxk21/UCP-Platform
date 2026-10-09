package com.richuang.os.module.bpm.service.task;

import static com.richuang.os.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.richuang.os.module.bpm.enums.ErrorCodeConstants.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.richuang.os.framework.common.util.date.DateUtils;
import com.richuang.os.framework.common.util.json.JsonUtils;
import com.richuang.os.module.bpm.api.task.BpmBusinessTaskBinding;
import com.richuang.os.module.bpm.api.task.dto.*;
import com.richuang.os.module.bpm.dal.dataobject.definition.BpmFormDO;
import com.richuang.os.module.bpm.enums.definition.BpmModelFormTypeEnum;
import com.richuang.os.module.bpm.enums.task.BpmTaskStatusEnum;
import com.richuang.os.module.bpm.framework.flowable.core.util.*;
import com.richuang.os.module.bpm.service.definition.BpmProcessDefinitionService;
import com.richuang.os.module.system.api.user.AdminUserApi;

import jakarta.annotation.Resource;

import org.flowable.bpmn.model.*;
import org.flowable.engine.*;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskInfo;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.springframework.stereotype.Service;

import java.util.*;

/** 只按当前任务资格和已部署串行路径读取事实；不会从全局当前变量补造历史材料。 */
@Service
public class BpmMaterialContextServiceImpl implements BpmMaterialContextService {
    public static final String FORM_MATERIAL = "_BPM_TASK_FORM_MATERIAL_V1";
    public static final String START_MATERIAL = "_BPM_START_FORM_MATERIAL_V1";
    public static final String REJECT_ACTOR = "_BPM_MATERIAL_REJECT_ACTOR_V1";
    @Resource private TaskService taskService;
    @Resource private HistoryService historyService;
    @Resource private RepositoryService repositoryService;
    @Resource private RuntimeService runtimeService;
    @Resource private BpmProcessDefinitionService definitions;
    @Resource private AdminUserApi users;

    @Override
    public BpmMaterialReviewContextDTO context(
            long actor, String instanceId, String requestedTask) {
        if (instanceId == null && requestedTask != null) {
            var internalTask = taskService.createTaskQuery().taskId(requestedTask).singleResult();
            if (internalTask != null) instanceId = internalTask.getProcessInstanceId();
        }
        if (actor <= 0 || instanceId == null || instanceId.isBlank())
            throw exception(TASK_OPERATE_FAIL_ASSIGN_NOT_SELF);
        var instance =
                historyService
                        .createHistoricProcessInstanceQuery()
                        .processInstanceId(instanceId)
                        .singleResult();
        String tenant = FlowableUtils.getTenantId();
        if (instance == null || !Objects.equals(tenant, instance.getTenantId()))
            throw exception(TASK_OPERATE_FAIL_ASSIGN_NOT_SELF);
        Task running =
                requestedTask == null || requestedTask.isBlank()
                        ? null
                        : taskService.createTaskQuery().taskId(requestedTask).singleResult();
        if (requestedTask == null || requestedTask.isBlank()) {
            var assigned =
                    taskService
                            .createTaskQuery()
                            .processInstanceId(instanceId)
                            .taskAssignee(Long.toString(actor))
                            .list();
            if (assigned.size() > 1)
                throw exception(MODEL_NODE_FORM_INVALID, "审批材料", "存在多个待办，请选择具体任务");
            if (!assigned.isEmpty()) running = assigned.getFirst();
        }
        List<HistoricTaskInstance> history =
                historyService
                        .createHistoricTaskInstanceQuery()
                        .processInstanceId(instanceId)
                        .includeTaskLocalVariables()
                        .list();
        TaskInfo anchor = running;
        if (anchor == null) {
            anchor =
                    history.stream()
                            .filter(
                                    task ->
                                            Objects.equals(Long.toString(actor), task.getAssignee())
                                                    && task.getEndTime() != null
                                                    && (task.getDeleteReason() == null
                                                                    && Objects.equals(
                                                                            BpmTaskStatusEnum
                                                                                    .APPROVE
                                                                                    .getStatus(),
                                                                            FlowableUtils
                                                                                    .getTaskStatus(
                                                                                            task))
                                                            || Objects.equals(
                                                                            BpmTaskStatusEnum.REJECT
                                                                                    .getStatus(),
                                                                            FlowableUtils
                                                                                    .getTaskStatus(
                                                                                            task))
                                                                    && Objects.equals(
                                                                            Long.toString(actor),
                                                                            task.getTaskLocalVariables()
                                                                                    .get(
                                                                                            REJECT_ACTOR)))
                                                    && (requestedTask == null
                                                            || requestedTask.isBlank()
                                                            || requestedTask.equals(task.getId())))
                            .max(Comparator.comparing(HistoricTaskInstance::getEndTime))
                            .orElse(null);
        }
        if (anchor == null
                || !Objects.equals(instanceId, anchor.getProcessInstanceId())
                || !Objects.equals(tenant, anchor.getTenantId())
                || !Objects.equals(Long.toString(actor), anchor.getAssignee()))
            throw exception(TASK_OPERATE_FAIL_ASSIGN_NOT_SELF);
        if (running != null && running.isSuspended()) throw exception(TASK_IS_PENDING);
        var model = repositoryService.getBpmnModel(anchor.getProcessDefinitionId());
        var node = BpmnModelUtils.getFlowElementById(model, anchor.getTaskDefinitionKey());
        var resolved = BpmNodeFormUtils.resolved(node);
        var rule = resolved == null ? null : resolved.get("materialReview");
        String scope = rule == null ? "PREVIOUS" : rule.path("scope").asText();
        String access = rule == null ? "BUSINESS" : rule.path("access").asText();
        if (!Set.of("PREVIOUS", "NONE").contains(scope)
                || !Set.of("TASK", "BUSINESS").contains(access))
            throw exception(MODEL_NODE_FORM_INVALID, node.getName(), "材料查阅配置无效");
        boolean businessTask = binding(node, BpmBusinessTaskBinding.HANDLER_ATTRIBUTE) != null;
        // 初次发起人节点是发起动作的一部分；规则来自已部署结构和历史事实，不能由意见文字触发。
        if (com.richuang.os.module.bpm.framework.flowable.core.enums.BpmnModelConstants
                        .START_USER_NODE_ID
                        .equals(node.getId())
                && node instanceof UserTask start
                && start.getIncomingFlows().size() == 1
                && start.getIncomingFlows().getFirst().getSourceFlowElement() instanceof StartEvent
                && history.stream().noneMatch(task -> task.getEndTime() != null)) scope = "NONE";
        var facts = new ArrayList<BpmMaterialReviewContextDTO.Material>();
        String blocked = null;
        if ("PREVIOUS".equals(scope)) {
            var preceding = new LinkedHashSet<String>();
            var visited = new HashSet<String>();
            FlowElement cursor = node;
            while (cursor instanceof FlowNode flow) {
                if (!visited.add(flow.getId())
                        || flow instanceof Gateway
                        || flow instanceof SubProcess
                        || flow instanceof CallActivity
                        || flow instanceof UserTask user
                                && user.getLoopCharacteristics() != null
                                && !"1".equals(user.getLoopCharacteristics().getLoopCardinality())
                        || flow.getIncomingFlows().size() > 1) {
                    blocked = "此流程包含分支、会签或重复执行，材料路径尚不能可靠确认";
                    break;
                }
                if (flow instanceof StartEvent) break;
                if (flow.getIncomingFlows().size() != 1) {
                    blocked = "无法确认实际前序材料路径";
                    break;
                }
                cursor = flow.getIncomingFlows().getFirst().getSourceFlowElement();
                if (cursor instanceof UserTask) preceding.add(cursor.getId());
            }
            final String anchorNode = anchor.getTaskDefinitionKey();
            final String anchorId = anchor.getId();
            Date cutoff =
                    running == null
                            ? ((HistoricTaskInstance) anchor).getEndTime()
                            : anchor.getCreateTime();
            var occurrences = new HashMap<String, Integer>();
            for (var task : history) {
                if (!Objects.equals(task.getId(), anchorId)
                        && Objects.equals(task.getTaskDefinitionKey(), anchorNode))
                    blocked = "此流程包含退回或重新执行，当前材料回合需人工核对";
                if (task.getEndTime() == null
                        || task.getEndTime().after(cutoff)
                        || !preceding.contains(task.getTaskDefinitionKey())) continue;
                if (occurrences.merge(task.getTaskDefinitionKey(), 1, Integer::sum) > 1)
                    blocked = "此流程包含退回或重新执行，当前材料回合需人工核对";
                if (task.getDeleteReason() != null
                        || !Objects.equals(
                                FlowableUtils.getTaskStatus(task),
                                BpmTaskStatusEnum.APPROVE.getStatus())) continue;
                if (com.richuang.os.module.bpm.framework.flowable.core.enums.BpmnModelConstants
                        .START_USER_NODE_ID
                        .equals(task.getTaskDefinitionKey())) continue;
                var material = submittedMaterial(task, model, node);
                if (material != null) facts.add(material);
            }
            if (blocked != null) {
                boolean hasMaterial =
                        history.stream()
                                .anyMatch(
                                        task ->
                                                task.getEndTime() != null
                                                        && hasSubmittedMaterial(task, model));
                if (!hasMaterial) blocked = null;
                else facts.clear();
            }
            var startInfo = definitions.getProcessDefinitionInfo(anchor.getProcessDefinitionId());
            if (startInfo != null
                    && Objects.equals(
                            startInfo.getFormType(), BpmModelFormTypeEnum.NORMAL.getType())
                    && startInfo.getFormFields() != null
                    && !startInfo.getFormFields().isEmpty()) {
                var startValue =
                        historyService
                                .createHistoricVariableInstanceQuery()
                                .processInstanceId(instanceId)
                                .variableName(START_MATERIAL)
                                .singleResult();
                var startForm =
                        new BpmFormDO()
                                .setName("发起表单")
                                .setConf(startInfo.getFormConf())
                                .setFields(startInfo.getFormFields());
                var original =
                        startValue != null && startValue.getValue() instanceof String text
                                ? JsonUtils.parseObject(
                                        text, BpmMaterialReviewContextDTO.Form.class)
                                : new BpmMaterialReviewContextDTO.Form(
                                        "发起表单",
                                        startInfo.getFormConf(),
                                        startInfo.getFormFields(),
                                        Map.of());
                var startNode =
                        model.getMainProcess().getFlowElements().stream()
                                .filter(element -> element instanceof StartEvent)
                                .findFirst()
                                .orElse(node);
                var view =
                        projectForm(
                                startForm,
                                original,
                                model,
                                startNode,
                                node,
                                startValue == null ? "历史发起表单未封存初始提交值，不能以当前流程变量代替历史材料" : null);
                facts.add(
                        new BpmMaterialReviewContextDTO.Material(
                                "START:" + instanceId,
                                startNode.getId(),
                                "发起申请",
                                instance.getStartUserId(),
                                "发起人",
                                DateUtils.of(instance.getStartTime()),
                                null,
                                view.form(),
                                view.warning()));
            }
        }
        // 已办入口还应显示当前办理人自己封存的材料；不扩大为后续节点或其他人的任务。
        if (running == null
                && anchor instanceof HistoricTaskInstance completed
                && hasSubmittedMaterial(completed, model)
                && !com.richuang.os.module.bpm.framework.flowable.core.enums.BpmnModelConstants
                        .START_USER_NODE_ID
                        .equals(completed.getTaskDefinitionKey())) {
            var own = submittedMaterial(completed, model, node);
            if (own != null) facts.add(own);
        }
        facts.sort(
                Comparator.comparing(BpmMaterialReviewContextDTO.Material::submittedAt)
                        .thenComparing(BpmMaterialReviewContextDTO.Material::taskId));
        return new BpmMaterialReviewContextDTO(
                instanceId,
                anchor.getProcessDefinitionId(),
                anchor.getId(),
                tenant,
                running != null,
                businessTask,
                scope,
                access,
                blocked,
                facts);
    }

    private BpmMaterialReviewContextDTO.Material submittedMaterial(
            HistoricTaskInstance task, BpmnModel model, FlowElement reviewer) {
        var source = BpmnModelUtils.getFlowElementById(model, task.getTaskDefinitionKey());
        var form = materialForm(task, model, source, reviewer);
        String handler = binding(source, BpmBusinessTaskBinding.HANDLER_ATTRIBUTE);
        BpmBusinessTaskDTO business =
                handler == null
                        ? null
                        : new BpmBusinessTaskDTO(
                                task.getId(),
                                task.getProcessInstanceId(),
                                task.getProcessDefinitionId(),
                                task.getExecutionId(),
                                task.getTaskDefinitionKey(),
                                task.getAssignee(),
                                handler,
                                binding(source, BpmBusinessTaskBinding.CONFIGURATION_ATTRIBUTE));
        if (form == null && business == null) return null;
        String warning = form == null ? null : form.warning();
        var person =
                task.getAssignee() == null
                        ? null
                        : users.getUser(Long.parseLong(task.getAssignee()));
        return new BpmMaterialReviewContextDTO.Material(
                task.getId(),
                task.getTaskDefinitionKey(),
                task.getName(),
                task.getAssignee(),
                person == null ? "原办理人" : person.getNickname(),
                DateUtils.of(task.getEndTime()),
                business,
                form == null ? null : form.form(),
                warning);
    }

    /** 旧流程未写专用封存变量时，仅用部署字段与任务局部键交集判断材料存在，不读取全局值。 */
    private boolean hasSubmittedMaterial(HistoricTaskInstance task, BpmnModel model) {
        var source = BpmnModelUtils.getFlowElementById(model, task.getTaskDefinitionKey());
        if (binding(source, BpmBusinessTaskBinding.HANDLER_ATTRIBUTE) != null
                || task.getTaskLocalVariables().containsKey(FORM_MATERIAL)) return true;
        var form = deployedForm(task, source);
        return form != null
                && BpmNodeFormUtils.fields(form).keySet().stream()
                        .anyMatch(task.getTaskLocalVariables()::containsKey);
    }

    @Override
    public void sealForm(Task task, Map<String, Object> submitted) {
        var model = repositoryService.getBpmnModel(task.getProcessDefinitionId());
        var node = BpmnModelUtils.getFlowElementById(model, task.getTaskDefinitionKey());
        var form = deployedForm(task, node);
        if (form == null) return;
        var fields = BpmNodeFormUtils.fields(form).keySet();
        var values = new LinkedHashMap<String, Object>();
        if (submitted != null)
            submitted.forEach(
                    (key, value) -> {
                        if (fields.contains(key)) values.put(key, value);
                    });
        if (values.isEmpty()) return;
        taskService.setVariableLocal(
                task.getId(),
                FORM_MATERIAL,
                JsonUtils.toJsonString(
                        new BpmMaterialReviewContextDTO.Form(
                                form.getName(), form.getConf(), form.getFields(), values)));
    }

    private MaterialForm materialForm(
            HistoricTaskInstance task, BpmnModel model, FlowElement node, FlowElement reviewer) {
        BpmFormDO deployed = deployedForm(task, node);
        if (deployed == null || BpmNodeFormUtils.fields(deployed).isEmpty()) return null;
        Map<String, Object> local = task.getTaskLocalVariables();
        Object sealed = local.get(FORM_MATERIAL);
        BpmMaterialReviewContextDTO.Form original;
        String warning;
        if (sealed instanceof String text) {
            original = JsonUtils.parseObject(text, BpmMaterialReviewContextDTO.Form.class);
            warning = null;
        } else {
            Map<String, Object> values = new LinkedHashMap<>();
            BpmNodeFormUtils.fields(deployed)
                    .keySet()
                    .forEach(
                            key -> {
                                if (local.containsKey(key)) values.put(key, local.get(key));
                            });
            if (values.isEmpty()
                    && BpmNodeFormUtils.resolved(node) == null
                    && task.getFormKey() == null) return null;
            original =
                    new BpmMaterialReviewContextDTO.Form(
                            deployed.getName(), deployed.getConf(), deployed.getFields(), values);
            warning = "历史节点仅留存本次提交字段，未封存完整表单；缺失值未以流程当前变量补齐";
        }
        return projectForm(deployed, original, model, node, reviewer, warning);
    }

    private MaterialForm projectForm(
            BpmFormDO deployed,
            BpmMaterialReviewContextDTO.Form original,
            BpmnModel model,
            FlowElement node,
            FlowElement reviewer,
            String warning) {
        var allowed = new LinkedHashMap<String, Object>();
        var fields = new ArrayList<String>();
        var permissions = BpmnModelUtils.parseFormFieldsPermission(model, node.getId());
        var reviewPermissions = BpmnModelUtils.parseFormFieldsPermission(model, reviewer.getId());
        for (var entry : BpmNodeFormUtils.fields(deployed).entrySet()) {
            String id = entry.getKey();
            JsonNode field = entry.getValue();
            if (permissions != null
                            && permissions.containsKey(id)
                            && !Set.of("1", "2").contains(permissions.get(id))
                    || reviewPermissions != null
                            && reviewPermissions.containsKey(id)
                            && !Set.of("1", "2").contains(reviewPermissions.get(id))
                    || hidden(field)) continue;
            if (!original.values().containsKey(id)) {
                if (warning == null) warning = "材料仅包含本节点实际提交字段，未提交字段未从全局变量补齐";
                continue;
            }
            ObjectNode rendered = JsonUtils.parseObject("{}", ObjectNode.class);
            rendered.put("type", "input");
            rendered.put("field", id);
            rendered.put("title", field.path("title").asText(id));
            ObjectNode props = rendered.putObject("props");
            props.put("disabled", true);
            props.put("readonly", true);
            fields.add(rendered.toString());
            allowed.put(id, original.values().get(id));
        }
        return new MaterialForm(
                new BpmMaterialReviewContextDTO.Form(original.name(), "{}", fields, allowed),
                warning);
    }

    private boolean hidden(JsonNode field) {
        String type = field.path("type").asText("").toLowerCase(Locale.ROOT);
        String classification =
                field.path("classification")
                        .asText(field.path("props").path("classification").asText("NORMAL"));
        return field.path("hidden").asBoolean(false)
                || field.path("props").path("hidden").asBoolean(false)
                || "password".equals(field.path("props").path("type").asText())
                || Set.of("hidden", "upload", "image", "attachment", "password").contains(type)
                || !Set.of("NORMAL", "INTERNAL").contains(classification);
    }

    private BpmFormDO deployedForm(TaskInfo task, FlowElement node) {
        var resolved = BpmNodeFormUtils.resolved(node);
        if (resolved != null) return BpmNodeFormUtils.form(node);
        var info = definitions.getProcessDefinitionInfo(task.getProcessDefinitionId());
        if (info == null
                || !Objects.equals(info.getFormType(), BpmModelFormTypeEnum.NORMAL.getType())
                || info.getFormFields() == null
                || task.getFormKey() != null
                        && !Objects.equals(task.getFormKey(), String.valueOf(info.getFormId())))
            return null;
        return new BpmFormDO()
                .setId(info.getFormId())
                .setName("流程表单")
                .setConf(info.getFormConf())
                .setFields(info.getFormFields());
    }

    private String binding(FlowElement node, String attribute) {
        return node == null
                ? null
                : node.getAttributeValue(BpmBusinessTaskBinding.NAMESPACE, attribute);
    }

    private record MaterialForm(BpmMaterialReviewContextDTO.Form form, String warning) {}
}
