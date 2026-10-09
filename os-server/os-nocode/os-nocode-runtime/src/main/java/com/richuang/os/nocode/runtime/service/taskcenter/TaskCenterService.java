package com.richuang.os.nocode.runtime.service.taskcenter;

import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.nocode.api.BusinessHandling;
import com.richuang.os.nocode.api.TaskCenter.*;
import com.richuang.os.nocode.api.TaskClaims;
import com.richuang.os.nocode.api.TaskForms;
import com.richuang.os.nocode.api.TaskGuidance;
import com.richuang.os.nocode.api.TaskManagement;
import com.richuang.os.nocode.api.TaskPlanning;
import com.richuang.os.nocode.api.TaskWorkTimes;

import java.util.List;

/** 任务命令统一入口，操作者只取底座可信身份。 */
public interface TaskCenterService {
    PageResult<Row> page(Query query, long actor);

    /** 管理总览按匹配节点归根；员工下钻可选择授权根归组，缺省兼容匹配节点平铺。 */
    PageResult<Row> managementPage(TaskManagement.Query query, long actor);

    /** 全量授权任务先汇总再按员工分页，不提供个人清单写入能力。 */
    PageResult<TaskManagement.Employee> managementEmployees(
            TaskManagement.Employees query, long actor);

    /** 按本人关系和筛选确定任务组，分页总数为真实总任务数。 */
    PageResult<PersonalTreeNode> personalTreePage(Query query, long actor);

    /** 展开已命中任务组的完整直属层级；他人节点只返回结构摘要，不扩大内容与操作权限。 */
    List<PersonalTreeNode> personalTreeChildren(PersonalTreeChildren query, long actor);

    List<EntryOption> entryOptions(long actor);

    PageResult<Row> pageTasks(PageQuery query, long actor);

    PageResult<Row> linkCandidates(PageQuery query, long actor);

    Detail linkRecord(LinkTask command, long actor);

    Detail detail(String id, long actor);

    /** 只读完成条件和取消影响；不保存材料，也不替代提交时的实时校验。 */
    TaskGuidance.Readiness readiness(String id, long actor);

    CompletionMaterial material(MaterialRef command, long actor);

    Detail create(Create command, long actor);

    /** 流程发布时冻结配置；不产生任务实例，不对外暴露授权快照写入能力。 */
    com.richuang.os.nocode.api.workflow.WorkflowTaskNodes.Definition prepareWorkflow(
            NodeInput task, List<NodeInput> nodes, String templateId, Integer version, long actor);

    /** 仅可信流程适配器调用，使用发布快照和已解析的人员生成一次任务组。 */
    Detail createWorkflow(
            com.richuang.os.nocode.api.workflow.WorkflowTaskNodes.Definition definition,
            NodeInput task,
            List<NodeInput> nodes,
            long initiator,
            String requestKey,
            java.time.LocalDateTime activatedAt);

    /** 员工细分本人任务；不授予发起总任务、改同事分工或配置数据资源的权限。 */
    Detail split(Create command, long actor);

    /** 不扩大实例管理权限；服务端核对本人拆分来源、执行事实和引用后逻辑删除单个节点。 */
    void deleteSubtask(DeleteSubtask command, long actor);

    Detail transition(Transition command, long actor);

    /** 与状态执行共用根锁，仅确认本人完整原请求，不执行或重建操作。 */
    TransitionRecovery transitionRecovery(Transition command, long actor);

    Detail claim(Claim command, long actor);

    PageResult<TaskClaims.Group> claimableGroups(TaskClaims.Query query, long actor);

    List<TaskClaims.Item> claimableChildren(TaskClaims.Root query, long actor);

    TaskClaims.Preview claimPreview(TaskClaims.Root query, long actor);

    Detail claimGroup(TaskClaims.ClaimGroup command, long actor);

    Detail assign(Assign command, long actor);

    void plan(SavePlan command, long actor);

    TaskPlanning.Context planContext(TaskPlanning.ContextQuery query, long actor);

    TaskPlanning.Result schedule(TaskPlanning.Change command, long actor);

    TaskPlanning.ChecklistContext checklistContext(TaskPlanning.ContextQuery query, long actor);

    TaskPlanning.Result checklist(TaskPlanning.ChecklistChange command, long actor);

    Comment comment(AddComment command, long actor);

    List<Member> members(String taskId, long actor);

    FormContext form(String id, long actor);

    FormContext formPreview(Binding binding, long actor);

    FormContext saveBusiness(SaveBusiness command, long actor);

    BusinessHandling.Result businessReceipt(TaskForms.Receipt query, long actor);

    TaskForms.CreatedReceipt createReceipt(TaskForms.CreateReceipt query, long actor);

    List<Template> templates(long actor);

    /** 模板维护权限不扩大实例范围，使用原任务参与可见规则归组分页。 */
    PageResult<TemplateInstance> templateInstances(TemplateInstances query, long actor);

    Template saveTemplate(SaveTemplate command, long actor);

    TemplateVersion publish(PublishTemplate command, long actor);

    TemplateVersion version(String id, Integer version, long actor);

    /** 返回可见模板的全部不可变发布版本，不暴露私有草稿。 */
    List<TemplateVersionSummary> versions(String id, long actor);

    /** 仅所有者或全局管理员可切换默认发起版本，使用模板修订号保护并发。 */
    Template setPrimaryVersion(SetPrimaryTemplateVersion command, long actor);

    /** 只计算当前草稿，不读取或修改已有任务实例。 */
    SchedulePreview schedulePreview(SchedulePreviewQuery command, long actor);

    AdjustmentPreview preview(Adjust command, long actor);

    Detail adjust(Adjust command, long actor);

    TaskWorkTimes.Context workTimeContext(String id, long actor);

    TaskWorkTimes.Context adjustWorkTime(TaskWorkTimes.Change command, long actor);
}
