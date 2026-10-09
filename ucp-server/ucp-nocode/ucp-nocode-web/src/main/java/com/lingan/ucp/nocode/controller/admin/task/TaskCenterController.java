package com.lingan.ucp.nocode.controller.admin.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.lingan.ucp.framework.common.pojo.*;
import com.lingan.ucp.nocode.api.TaskCenter.*;
import com.lingan.ucp.nocode.api.TaskClaims;
import com.lingan.ucp.nocode.api.TaskGuidance;
import com.lingan.ucp.nocode.api.TaskManagement;
import com.lingan.ucp.nocode.api.TaskPlanning;
import com.lingan.ucp.nocode.api.TaskWorkTimes;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskCenterService;
import com.lingan.ucp.nocode.web.NocodeAccess;
import com.lingan.ucp.nocode.web.StrictRequestDecoder;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 常规任务控制器，只解析身份和契约；业务资格由任务服务重新判断。 */
@Tag(name = "无代码 - 常规任务")
@RestController
@RequestMapping("/nocode/tasks")
@PreAuthorize("isAuthenticated()")
public class TaskCenterController {
    @Resource private TaskCenterService tasks;
    @Resource private NocodeAccess access;
    @Resource private StrictRequestDecoder requests;

    @PostMapping("/work-time/context")
    @Operation(summary = "读取实例工时预算及调整资格")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<TaskWorkTimes.Context> workTimeContext(@RequestBody JsonNode body) {
        return Result.success(
                tasks.workTimeContext(requests.design(body, Ref.class).id(), access.actor()));
    }

    @PostMapping("/work-time/adjust")
    @Operation(summary = "调整当前实例后续工时标准并保留历史计量")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<TaskWorkTimes.Context> adjustWorkTime(@RequestBody JsonNode body) {
        return Result.success(
                tasks.adjustWorkTime(
                        requests.design(body, TaskWorkTimes.Change.class), access.actor()));
    }

    @PostMapping("/material")
    @Operation(summary = "按当前权限读取任务完成材料")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<CompletionMaterial> material(@RequestBody JsonNode body) {
        return Result.success(
                tasks.material(requests.design(body, MaterialRef.class), access.actor()));
    }

    @PostMapping("/page")
    @Operation(summary = "分页查询任务")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<PageResult<Row>> page(@RequestBody JsonNode body) {
        return Result.success(tasks.page(requests.design(body, Query.class), access.actor()));
    }

    @PostMapping("/management/page")
    @Operation(summary = "分页查询管理任务总览或员工指标明细")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<PageResult<Row>> managementPage(@RequestBody JsonNode body) {
        return Result.success(
                tasks.managementPage(
                        requests.design(body, TaskManagement.Query.class), access.actor()));
    }

    @PostMapping("/management/employees")
    @Operation(summary = "按完整管理范围汇总员工工作安排")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<PageResult<TaskManagement.Employee>> managementEmployees(
            @RequestBody JsonNode body) {
        return Result.success(
                tasks.managementEmployees(
                        requests.design(body, TaskManagement.Employees.class), access.actor()));
    }

    @PostMapping("/personal-tree-page")
    @Operation(summary = "按本人任务树入口分页查询")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<PageResult<PersonalTreeNode>> personalTreePage(@RequestBody JsonNode body) {
        return Result.success(
                tasks.personalTreePage(requests.design(body, Query.class), access.actor()));
    }

    @PostMapping("/personal-tree-children")
    @Operation(summary = "展开本人任务树的匹配下级")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<List<PersonalTreeNode>> personalTreeChildren(@RequestBody JsonNode body) {
        return Result.success(
                tasks.personalTreeChildren(
                        requests.design(body, PersonalTreeChildren.class), access.actor()));
    }

    @GetMapping("/entry-options")
    @Operation(summary = "读取可见任务的多个业务反馈入口筛选候选")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<List<EntryOption>> entryOptions() {
        return Result.success(tasks.entryOptions(access.actor()));
    }

    @PostMapping("/page-tasks")
    @Operation(summary = "查询发布页面任务")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<PageResult<Row>> pageTasks(@RequestBody JsonNode body) {
        return Result.success(
                tasks.pageTasks(requests.design(body, PageQuery.class), access.actor()));
    }

    @PostMapping("/detail")
    @Operation(summary = "查询任务详情")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<Detail> detail(@RequestBody JsonNode body) {
        return Result.success(tasks.detail(requests.design(body, Ref.class).id(), access.actor()));
    }

    @PostMapping("/readiness")
    @Operation(summary = "只读检查任务完成条件和取消影响")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<TaskGuidance.Readiness> readiness(@RequestBody JsonNode body) {
        return Result.success(
                tasks.readiness(requests.design(body, Ref.class).id(), access.actor()));
    }

    @PostMapping("/record-link-candidates")
    @Operation(summary = "分页选择当前业务记录可关联的既有任务")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<PageResult<Row>> linkCandidates(@RequestBody JsonNode body) {
        return Result.success(
                tasks.linkCandidates(requests.design(body, PageQuery.class), access.actor()));
    }

    @PostMapping("/record-link")
    @Operation(summary = "关联或解除当前记录与既有任务的显式关系")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<Detail> linkRecord(@RequestBody JsonNode body) {
        return Result.success(
                tasks.linkRecord(requests.design(body, LinkTask.class), access.actor()));
    }

    @PostMapping("/create")
    @Operation(summary = "创建或拆分任务")
    @PreAuthorize("@nocodeAccess.taskCreate()")
    public Result<Detail> create(@RequestBody JsonNode body) {
        return Result.success(tasks.create(requests.design(body, Create.class), access.actor()));
    }

    @PostMapping("/split")
    @Operation(summary = "将本人任务拆分为自己执行的子任务")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<Detail> split(@RequestBody JsonNode body) {
        return Result.success(tasks.split(requests.design(body, Create.class), access.actor()));
    }

    @PostMapping("/delete-subtask")
    @Operation(summary = "删除本人拆分且尚无执行事实的子任务")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<Boolean> deleteSubtask(@RequestBody JsonNode body) {
        tasks.deleteSubtask(requests.design(body, DeleteSubtask.class), access.actor());
        return Result.success(true);
    }

    @PostMapping("/transition")
    @Operation(summary = "执行任务")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<Detail> transition(@RequestBody JsonNode body) {
        return Result.success(
                tasks.transition(requests.design(body, Transition.class), access.actor()));
    }

    @PostMapping("/transition-recovery")
    @Operation(summary = "只读确认本人原任务操作回执")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<TransitionRecovery> transitionRecovery(@RequestBody JsonNode body) {
        return Result.success(
                tasks.transitionRecovery(requests.design(body, Transition.class), access.actor()));
    }

    @PostMapping("/claim")
    @Operation(summary = "原子领取尚未分配的开放任务")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<Detail> claim(@RequestBody JsonNode body) {
        return Result.success(tasks.claim(requests.design(body, Claim.class), access.actor()));
    }

    @PostMapping("/claimable-groups")
    @Operation(summary = "按真实总任务分页查询可领取工作")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<PageResult<TaskClaims.Group>> claimableGroups(@RequestBody JsonNode body) {
        return Result.success(
                tasks.claimableGroups(
                        requests.design(body, TaskClaims.Query.class), access.actor()));
    }

    @PostMapping("/claimable-children")
    @Operation(summary = "展开总任务中当前可领取的安全摘要")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<List<TaskClaims.Item>> claimableChildren(@RequestBody JsonNode body) {
        return Result.success(
                tasks.claimableChildren(
                        requests.design(body, TaskClaims.Root.class), access.actor()));
    }

    @PostMapping("/claim-preview")
    @Operation(summary = "预览整项领取的真实承接范围和实例修订")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<TaskClaims.Preview> claimPreview(@RequestBody JsonNode body) {
        return Result.success(
                tasks.claimPreview(requests.design(body, TaskClaims.Root.class), access.actor()));
    }

    @PostMapping("/claim-group")
    @Operation(summary = "原子领取总任务及默认随行分工")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<Detail> claimGroup(@RequestBody JsonNode body) {
        return Result.success(
                tasks.claimGroup(
                        requests.design(body, TaskClaims.ClaimGroup.class), access.actor()));
    }

    @PostMapping("/assign")
    @Operation(summary = "调整任务分工或显式转交在办子任务")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<Detail> assign(@RequestBody JsonNode body) {
        return Result.success(tasks.assign(requests.design(body, Assign.class), access.actor()));
    }

    @PostMapping("/comment")
    @Operation(summary = "评论回复任务")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<Comment> comment(@RequestBody JsonNode body) {
        return Result.success(
                tasks.comment(requests.design(body, AddComment.class), access.actor()));
    }

    @PostMapping("/form")
    @Operation(summary = "读取任务业务表单")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<FormContext> form(@RequestBody JsonNode body) {
        return Result.success(tasks.form(requests.design(body, Ref.class).id(), access.actor()));
    }

    @PostMapping("/form-preview")
    @Operation(summary = "预览任务业务表单")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<FormContext> formPreview(@RequestBody JsonNode body) {
        return Result.success(
                tasks.formPreview(requests.design(body, Binding.class), access.actor()));
    }

    @PostMapping("/business")
    @Operation(summary = "提交任务业务内容")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<FormContext> business(@RequestBody JsonNode body) {
        return Result.success(
                tasks.saveBusiness(requests.design(body, SaveBusiness.class), access.actor()));
    }

    @PostMapping("/schedule-preview")
    @Operation(summary = "预览任务草稿排期")
    @PreAuthorize("@nocodeAccess.taskCreate()")
    public Result<SchedulePreview> schedulePreview(@RequestBody JsonNode body) {
        return Result.success(
                tasks.schedulePreview(
                        requests.design(body, SchedulePreviewQuery.class), access.actor()));
    }

    @PostMapping("/adjust-preview")
    @Operation(summary = "预览实例调整")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<AdjustmentPreview> preview(@RequestBody JsonNode body) {
        return Result.success(tasks.preview(requests.design(body, Adjust.class), access.actor()));
    }

    @PostMapping("/adjust")
    @Operation(summary = "调整当前实例")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<Detail> adjust(@RequestBody JsonNode body) {
        return Result.success(tasks.adjust(requests.design(body, Adjust.class), access.actor()));
    }

    @PostMapping("/plan")
    @Operation(summary = "安排个人任务计划")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<Boolean> plan(@RequestBody JsonNode body) {
        tasks.plan(requests.design(body, SavePlan.class), access.actor());
        return Result.success(true);
    }

    @PostMapping("/plan-context")
    @Operation(summary = "读取当前执行计划、主管约束和计划历史")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<TaskPlanning.Context> planContext(@RequestBody JsonNode body) {
        return Result.success(
                tasks.planContext(
                        requests.design(body, TaskPlanning.ContextQuery.class), access.actor()));
    }

    @PostMapping("/schedule")
    @Operation(summary = "按修订原子安排、改期或取消明确计划")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<TaskPlanning.Result> schedule(@RequestBody JsonNode body) {
        return Result.success(
                tasks.schedule(requests.design(body, TaskPlanning.Change.class), access.actor()));
    }

    @PostMapping("/checklist-context")
    @Operation(summary = "读取今日、本周清单及旧安排历史")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<TaskPlanning.ChecklistContext> checklistContext(@RequestBody JsonNode body) {
        return Result.success(
                tasks.checklistContext(
                        requests.design(body, TaskPlanning.ContextQuery.class), access.actor()));
    }

    @PostMapping("/checklist")
    @Operation(summary = "按修订加入或移出独立的今日、本周清单")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<TaskPlanning.Result> checklist(@RequestBody JsonNode body) {
        return Result.success(
                tasks.checklist(
                        requests.design(body, TaskPlanning.ChecklistChange.class), access.actor()));
    }

    @GetMapping("/members")
    @Operation(summary = "查询有效任务成员")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<List<Member>> members(@RequestParam(required = false) String taskId) {
        return Result.success(tasks.members(taskId, access.actor()));
    }
}
