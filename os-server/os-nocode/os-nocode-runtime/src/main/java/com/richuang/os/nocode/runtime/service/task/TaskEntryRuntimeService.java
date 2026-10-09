package com.richuang.os.nocode.runtime.service.task;

import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.nocode.api.*;

import java.util.List;

/**
 * 任务入口运行契约。
 *
 * <p>每次请求从服务端发布定义构造可信上下文，叠加入口、成员及对象共享的实时授权。 actor 必须来自当前登录身份；请求中的入口定位不授予普通应用访问权，旧版本写入按原规则拒绝。
 */
public interface TaskEntryRuntimeService {
    /** 内部历史读取复用原入口范围；记录已删除时仍核验入口和 FORM 来源，不开放任意授权上下文。 */
    <T> T readHistoryScope(
            TaskEntries.Locator entry,
            String formId,
            String recordId,
            long actor,
            java.util.function.Supplier<T> action);

    /** 返回当前用户实际可用的入口，逐入口过滤无权访问或已失效的资源。 */
    List<TaskEntries.Card> mine(long actor);

    /** 读取本人入口草稿；游标只推进扫描，不能绕过当前入口权限。 */
    TaskEntries.DraftPage drafts(TaskEntries.DraftQuery query, long actor);

    /** 解析当前入口的模型、模式和可用表单/视图，供运行界面建立受控上下文。 */
    TaskEntries.Context context(TaskEntries.Locator entry, long actor);

    /** 在入口作用域读取关联区域；独立对象继续使用原共享与记录权限。 */
    RelatedForms.Result relatedForm(RelatedForms.TaskQuery query, long actor);

    /** 查询关联区域的可选记录，不以关联存在为由扩大目标对象的读取范围。 */
    SelectionFields.Result relatedSelection(RelatedForms.TaskSelection query, long actor);

    /** 按发布表单配置计算关联带入值，不接受客户端任意定义带入字段。 */
    java.util.Map<String, Object> relatedFill(RelatedForms.TaskFill query, long actor);

    /** 按入口绑定的对象/视图分页读取业务记录，同时应用行和字段授权。 */
    PageResult<ApplicationRecords.Row> page(TaskEntries.Query query, long actor);

    /** 读取可见主记录及明细；入口模式和原记录来源限制由实现重新校验。 */
    ApplicationRecords.Aggregate get(TaskEntries.Get query, long actor);

    /** 沿用公共整单保存及收据机制；成功写入才形成对应入口的办理历史。 */
    ApplicationRecords.Aggregate save(TaskEntries.Save command, long actor);

    /** 按对象办理策略提交直接生效或审批申请，返回实际办理状态。 */
    BusinessHandling.Result submit(TaskEntries.Save command, long actor);

    /** 按原请求键恢复办理结果，审批申请收据与记录保存收据分别处理。 */
    BusinessHandling.Result handlingReceipt(TaskEntries.Receipt query, long actor);

    /** 仅审批编排器的可信写入上下文可调用，仍重新解析入口实时权限。 */
    ApplicationRecords.Aggregate applyHandling(
            TaskEntries.Locator entry,
            ApplicationRecords.Save command,
            com.richuang.os.nocode.api.work.WorkDrafts.Submission material,
            long actor);

    /** 在入口权限及当前记录版本约束下删除，复用公共删除/关联保护语义。 */
    void delete(TaskEntries.Delete command, long actor);

    /** 在入口对应的表单与记录上下文中查询字段候选。 */
    SelectionFields.Result selection(TaskEntries.Selection query, long actor);

    /** 根据入口发布表单的关联填充规则读取带入值。 */
    java.util.Map<String, Object> formFill(TaskEntries.FormFill query, long actor);

    /** 入口表单的数据联动与公式默认值求值；规则取入口应用固定版本。 */
    FieldRules.Evaluation fieldRules(TaskEntries.FieldRules query, long actor);

    /** 关联区域求值复用已批准入口作用域，不开放任意跨对象查询。 */
    FieldRules.Evaluation relatedFieldRules(RelatedForms.TaskFieldRules query, long actor);

    /** 读取入口绑定视图的模型；不允许请求改用未授权视图。 */
    DataViews.Model viewModel(TaskEntries.ViewModel query, long actor);

    /** 独立分页读取指定视图子表，同时核验父记录和目标对象范围。 */
    PageResult<ApplicationRecords.Row> viewChildren(TaskEntries.ViewChildren query, long actor);

    /** 读取原保存请求的成功收据，结果仍按当前权限裁剪。 */
    ApplicationRecords.SaveReceipt receipt(TaskEntries.Receipt query, long actor);

    /** 恢复本人在当前入口下的草稿，不复用其他来源的草稿。 */
    com.richuang.os.nocode.api.work.WorkDrafts.Draft draft(TaskEntries.Locator entry, long actor);

    /** 暂存入口表单输入；不写正式业务记录，也不将暂存计为成功办理。 */
    com.richuang.os.nocode.api.work.WorkDrafts.Draft saveDraft(
            TaskEntries.Save command, long actor);

    /** 按游标读取本人通过该入口成功办理且当前仍可见的历史。 */
    TaskEntries.Activities activity(TaskEntries.ActivityQuery query, long actor);
}
