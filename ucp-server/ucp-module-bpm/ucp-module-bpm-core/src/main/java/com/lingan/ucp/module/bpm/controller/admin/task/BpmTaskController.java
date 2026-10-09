package com.lingan.ucp.module.bpm.controller.admin.task;

import static com.lingan.ucp.framework.common.pojo.Result.success;
import static com.lingan.ucp.framework.common.util.collection.CollectionUtils.*;
import static com.lingan.ucp.framework.web.core.util.WebFrameworkUtils.getLoginUserId;

import cn.hutool.core.collection.CollUtil;

import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.framework.common.pojo.Result;
import com.lingan.ucp.framework.common.util.number.NumberUtils;
import com.lingan.ucp.module.bpm.controller.admin.task.vo.task.*;
import com.lingan.ucp.module.bpm.convert.task.BpmTaskConvert;
import com.lingan.ucp.module.bpm.dal.dataobject.definition.BpmFormDO;
import com.lingan.ucp.module.bpm.dal.dataobject.definition.BpmProcessDefinitionInfoDO;
import com.lingan.ucp.module.bpm.service.definition.BpmFormService;
import com.lingan.ucp.module.bpm.service.definition.BpmProcessDefinitionService;
import com.lingan.ucp.module.bpm.service.task.BpmProcessInstanceService;
import com.lingan.ucp.module.bpm.service.task.BpmTaskService;
import com.lingan.ucp.module.system.api.dept.DeptApi;
import com.lingan.ucp.module.system.api.dept.dto.DeptRespDTO;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.module.system.api.user.dto.AdminUserRespDTO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;

import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Tag(name = "管理后台 - 流程任务实例")
@RestController
@RequestMapping("/bpm/task")
@Validated
public class BpmTaskController {

    @Resource private BpmTaskService taskService;
    @Resource private BpmProcessInstanceService processInstanceService;
    @Resource private BpmFormService formService;
    @Resource private BpmProcessDefinitionService processDefinitionService;

    @Resource private AdminUserApi adminUserApi;
    @Resource private DeptApi deptApi;

    @GetMapping("todo-page")
    @Operation(summary = "获取 Todo 待办任务分页")
    //    @PreAuthorize("@ss.hasPermission('bpm:task:query')")
    public Result<PageResult<BpmTaskRespVO>> getTaskTodoPage(@Valid BpmTaskPageReqVO pageVO) {
        PageResult<Task> pageResult = taskService.getTaskTodoPage(getLoginUserId(), pageVO);
        if (CollUtil.isEmpty(pageResult.getList())) {
            return success(PageResult.empty());
        }

        // 拼接数据
        Map<String, ProcessInstance> processInstanceMap =
                processInstanceService.getProcessInstanceMap(
                        convertSet(pageResult.getList(), Task::getProcessInstanceId));
        Map<Long, AdminUserRespDTO> userMap =
                adminUserApi.getUserMap(
                        convertSet(
                                processInstanceMap.values(),
                                instance -> Long.valueOf(instance.getStartUserId())));
        Map<String, BpmProcessDefinitionInfoDO> processDefinitionInfoMap =
                processDefinitionService.getProcessDefinitionInfoMap(
                        convertSet(pageResult.getList(), Task::getProcessDefinitionId));
        return success(
                BpmTaskConvert.INSTANCE.buildTodoTaskPage(
                        pageResult, processInstanceMap, userMap, processDefinitionInfoMap));
    }

    @GetMapping("done-page")
    @Operation(summary = "获取 Done 已办任务分页")
    @PreAuthorize("@ss.hasPermission('bpm:task:query')")
    public Result<PageResult<BpmTaskRespVO>> getTaskDonePage(@Valid BpmTaskPageReqVO pageVO) {
        PageResult<HistoricTaskInstance> pageResult =
                taskService.getTaskDonePage(getLoginUserId(), pageVO);
        if (CollUtil.isEmpty(pageResult.getList())) {
            return success(PageResult.empty());
        }

        // 拼接数据
        Map<String, HistoricProcessInstance> processInstanceMap =
                processInstanceService.getHistoricProcessInstanceMap(
                        convertSet(
                                pageResult.getList(), HistoricTaskInstance::getProcessInstanceId));
        Map<Long, AdminUserRespDTO> userMap =
                adminUserApi.getUserMap(
                        convertSet(
                                processInstanceMap.values(),
                                instance -> Long.valueOf(instance.getStartUserId())));
        Map<String, BpmProcessDefinitionInfoDO> processDefinitionInfoMap =
                processDefinitionService.getProcessDefinitionInfoMap(
                        convertSet(
                                pageResult.getList(),
                                HistoricTaskInstance::getProcessDefinitionId));
        PageResult<BpmTaskRespVO> result =
                BpmTaskConvert.INSTANCE.buildTaskPage(
                        pageResult, processInstanceMap, userMap, null, processDefinitionInfoMap);
        Map<String, Boolean> withdrawable =
                taskService.getTaskWithdrawable(getLoginUserId(), pageResult.getList());
        result.getList()
                .forEach(
                        task ->
                                task.setWithdrawable(
                                        Boolean.TRUE.equals(withdrawable.get(task.getId()))));
        return success(result);
    }

    @GetMapping("manager-page")
    @Operation(summary = "获取全部任务的分页", description = "用于【流程任务】菜单")
    @PreAuthorize("@ss.hasPermission('bpm:task:manager-query')")
    public Result<PageResult<BpmTaskRespVO>> getTaskManagerPage(@Valid BpmTaskPageReqVO pageVO) {
        PageResult<HistoricTaskInstance> pageResult =
                taskService.getTaskPage(getLoginUserId(), pageVO);
        if (CollUtil.isEmpty(pageResult.getList())) {
            return success(PageResult.empty());
        }

        // 拼接数据
        Map<String, HistoricProcessInstance> processInstanceMap =
                processInstanceService.getHistoricProcessInstanceMap(
                        convertSet(
                                pageResult.getList(), HistoricTaskInstance::getProcessInstanceId));
        // 获得 User 和 Dept Map
        Set<Long> userIds =
                convertSet(
                        processInstanceMap.values(),
                        instance -> Long.valueOf(instance.getStartUserId()));
        userIds.addAll(
                convertSet(
                        pageResult.getList(), task -> NumberUtils.parseLong(task.getAssignee())));
        Map<Long, AdminUserRespDTO> userMap = adminUserApi.getUserMap(userIds);
        Map<Long, DeptRespDTO> deptMap =
                deptApi.getDeptMap(convertSet(userMap.values(), AdminUserRespDTO::getDeptId));
        Map<String, BpmProcessDefinitionInfoDO> processDefinitionInfoMap =
                processDefinitionService.getProcessDefinitionInfoMap(
                        convertSet(
                                pageResult.getList(),
                                HistoricTaskInstance::getProcessDefinitionId));
        return success(
                BpmTaskConvert.INSTANCE.buildTaskPage(
                        pageResult,
                        processInstanceMap,
                        userMap,
                        deptMap,
                        processDefinitionInfoMap));
    }

    @GetMapping("/list-by-process-instance-id")
    @Operation(summary = "获得指定流程实例的任务列表", description = "包括完成的、未完成的")
    @Parameter(name = "processInstanceId", description = "流程实例的编号", required = true)
    @PreAuthorize("@ss.hasPermission('bpm:task:query')")
    public Result<List<BpmTaskRespVO>> getTaskListByProcessInstanceId(
            @RequestParam("processInstanceId") String processInstanceId) {
        List<HistoricTaskInstance> taskList =
                taskService.getTaskListByProcessInstanceId(processInstanceId, true);
        if (CollUtil.isEmpty(taskList)) {
            return success(Collections.emptyList());
        }

        // 拼接数据
        Set<Long> userIds =
                convertSetByFlatMap(
                        taskList,
                        task ->
                                Stream.of(
                                        NumberUtils.parseLong(task.getAssignee()),
                                        NumberUtils.parseLong(task.getOwner())));
        Map<Long, AdminUserRespDTO> userMap = adminUserApi.getUserMap(userIds);
        Map<Long, DeptRespDTO> deptMap =
                deptApi.getDeptMap(convertSet(userMap.values(), AdminUserRespDTO::getDeptId));
        // 获得 Form Map
        Map<Long, BpmFormDO> formMap =
                formService.getFormMap(
                        convertSet(taskList, task -> NumberUtils.parseLong(task.getFormKey())));
        var result =
                BpmTaskConvert.INSTANCE.buildTaskListByProcessInstanceId(
                        taskList, formMap, userMap, deptMap);
        var definitions = new java.util.HashMap<String, org.flowable.bpmn.model.BpmnModel>();
        var tasks = convertMap(taskList, HistoricTaskInstance::getId);
        for (var row : result) {
            var task = tasks.get(row.getId());
            var model =
                    definitions.computeIfAbsent(
                            task.getProcessDefinitionId(),
                            processDefinitionService::getProcessDefinitionBpmnModel);
            var node =
                    com.lingan.ucp.module.bpm.framework.flowable.core.util.BpmnModelUtils
                            .getFlowElementById(model, task.getTaskDefinitionKey());
            var form =
                    com.lingan.ucp.module.bpm.framework.flowable.core.util.BpmNodeFormUtils.form(
                            node);
            if (form != null)
                row.setFormId(form.getId())
                        .setFormName(form.getName())
                        .setFormConf(form.getConf())
                        .setFormFields(form.getFields())
                        .setFormVariables(
                                com.lingan.ucp.module.bpm.framework.flowable.core.util
                                        .FlowableUtils.getTaskFormVariable(task));
        }
        return success(result);
    }

    @PutMapping("/approve")
    @Operation(summary = "通过任务")
    @PreAuthorize("@ss.hasPermission('bpm:task:update')")
    public Result<Boolean> approveTask(@Valid @RequestBody BpmTaskApproveReqVO reqVO) {
        taskService.approveTask(getLoginUserId(), reqVO);
        return success(true);
    }

    @PutMapping("/reject")
    @Operation(summary = "不通过任务")
    @PreAuthorize("@ss.hasPermission('bpm:task:update')")
    public Result<Boolean> rejectTask(@Valid @RequestBody BpmTaskRejectReqVO reqVO) {
        taskService.rejectTask(getLoginUserId(), reqVO);
        return success(true);
    }

    @GetMapping("/list-by-return")
    @Operation(summary = "获取所有可退回的节点", description = "用于【流程详情】的【退回】按钮")
    @Parameter(name = "taskId", description = "当前任务ID", required = true)
    @PreAuthorize("@ss.hasPermission('bpm:task:update')")
    public Result<List<BpmTaskRespVO>> getTaskListByReturn(@RequestParam("id") String id) {
        List<UserTask> userTaskList = taskService.getUserTaskListByReturn(id);
        return success(
                convertList(
                        userTaskList,
                        userTask -> // 只返回 id 和 name
                        new BpmTaskRespVO()
                                        .setName(userTask.getName())
                                        .setTaskDefinitionKey(userTask.getId())));
    }

    @PutMapping("/return")
    @Operation(summary = "退回任务", description = "用于【流程详情】的【退回】按钮")
    @PreAuthorize("@ss.hasPermission('bpm:task:update')")
    public Result<Boolean> returnTask(@Valid @RequestBody BpmTaskReturnReqVO reqVO) {
        taskService.returnTask(getLoginUserId(), reqVO);
        return success(true);
    }

    @PutMapping("/delegate")
    @Operation(summary = "委派任务", description = "用于【流程详情】的【委派】按钮")
    @PreAuthorize("@ss.hasPermission('bpm:task:update')")
    public Result<Boolean> delegateTask(@Valid @RequestBody BpmTaskDelegateReqVO reqVO) {
        taskService.delegateTask(getLoginUserId(), reqVO);
        return success(true);
    }

    @PutMapping("/transfer")
    @Operation(summary = "转派任务", description = "用于【流程详情】的【转派】按钮")
    @PreAuthorize("@ss.hasPermission('bpm:task:update')")
    public Result<Boolean> transferTask(@Valid @RequestBody BpmTaskTransferReqVO reqVO) {
        taskService.transferTask(getLoginUserId(), reqVO);
        return success(true);
    }

    @PutMapping("/create-sign")
    @Operation(summary = "加签", description = "before 前加签，after 后加签")
    @PreAuthorize("@ss.hasPermission('bpm:task:update')")
    public Result<Boolean> createSignTask(@Valid @RequestBody BpmTaskSignCreateReqVO reqVO) {
        taskService.createSignTask(getLoginUserId(), reqVO);
        return success(true);
    }

    @DeleteMapping("/delete-sign")
    @Operation(summary = "减签")
    @PreAuthorize("@ss.hasPermission('bpm:task:update')")
    public Result<Boolean> deleteSignTask(@Valid @RequestBody BpmTaskSignDeleteReqVO reqVO) {
        taskService.deleteSignTask(getLoginUserId(), reqVO);
        return success(true);
    }

    @PutMapping("/copy")
    @Operation(summary = "抄送任务")
    @PreAuthorize("@ss.hasPermission('bpm:task:update')")
    public Result<Boolean> copyTask(@Valid @RequestBody BpmTaskCopyReqVO reqVO) {
        taskService.copyTask(getLoginUserId(), reqVO);
        return success(true);
    }

    @PutMapping("/withdraw")
    @Operation(summary = "撤回任务")
    @PreAuthorize("@ss.hasPermission('bpm:task:update')")
    public Result<Boolean> withdrawTask(@RequestParam("taskId") String taskId) {
        taskService.withdrawTask(getLoginUserId(), taskId);
        return success(true);
    }

    @GetMapping("/list-by-parent-task-id")
    @Operation(summary = "获得指定父级任务的子任务列表") // 目前用于，减签的时候，获得子任务列表
    @Parameter(name = "parentTaskId", description = "父级任务编号", required = true)
    @PreAuthorize("@ss.hasPermission('bpm:task:query')")
    public Result<List<BpmTaskRespVO>> getTaskListByParentTaskId(
            @RequestParam("parentTaskId") String parentTaskId) {
        List<Task> taskList = taskService.getTaskListByParentTaskId(parentTaskId);
        if (CollUtil.isEmpty(taskList)) {
            return success(Collections.emptyList());
        }
        // 拼接数据
        Map<Long, AdminUserRespDTO> userMap =
                adminUserApi.getUserMap(
                        convertSetByFlatMap(
                                taskList,
                                user ->
                                        Stream.of(
                                                NumberUtils.parseLong(user.getAssignee()),
                                                NumberUtils.parseLong(user.getOwner()))));
        Map<Long, DeptRespDTO> deptMap =
                deptApi.getDeptMap(convertSet(userMap.values(), AdminUserRespDTO::getDeptId));
        return success(
                BpmTaskConvert.INSTANCE.buildTaskListByParentTaskId(taskList, userMap, deptMap));
    }
}
