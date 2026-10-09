package com.richuang.os.nocode.runtime.service.taskcenter;

import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.nocode.api.*;

import java.util.*;

/** 任务多入口生命周期与受控业务列表；不替代公共记录保存引擎。 */
public interface TaskWorkEntryService {
    TaskWorkEntries.HandlingLocation handlingLocation(String requestId, long actor);

    TaskWorkEntries.Saved receipt(TaskWorkEntries.Receipt query, long actor);

    void validate(List<TaskCenter.NodeInput> nodes, long actor);

    void publish(String templateId, int version, List<TaskCenter.NodeInput> nodes, long actor);

    void synchronize(String rootId, long actor);

    void complete(String taskId, long actor);

    /** 按真实入口逐项检查，不经列表的失权过滤，也不写入完成材料。 */
    List<TaskGuidance.Check> completionChecks(String taskId, long actor);

    List<TaskWorkEntries.Config> effectiveConfigs(String taskId);

    List<TaskWorkEntries.Entry> entries(String taskId, long actor);

    PageResult<TaskWorkEntries.Item> page(TaskWorkEntries.Query query, long actor);

    TaskCenter.FormContext form(TaskWorkEntries.Form query, long actor);

    TaskWorkEntries.Saved save(TaskWorkEntries.Save command, long actor);

    TaskWorkEntries.Saved link(TaskWorkEntries.Link command, long actor);

    boolean delete(TaskWorkEntries.Delete command, long actor);

    List<TaskWorkEntries.Material> materials(String taskId, long actor);

    /** 提交时封存本轮材料；读取历史时重新投影当前可读字段，不能复用后续轮次快照。 */
    List<TaskWorkEntries.Material> snapshot(String taskId, long actor);

    List<TaskWorkEntries.Material> materials(
            String taskId, List<TaskWorkEntries.Material> snapshot, long actor);

    SelectionFields.Result selection(TaskWorkEntries.Selection query, long actor);

    Map<String, Object> fill(TaskWorkEntries.Fill query, long actor);

    RelatedForms.Result related(TaskWorkEntries.Related query, long actor);

    SelectionFields.Result relatedSelection(TaskWorkEntries.RelatedSelection query, long actor);

    Map<String, Object> relatedFill(TaskWorkEntries.RelatedFill query, long actor);

    /** 任务内字段联动不要求执行人另有应用成员权限。 */
    FieldRules.Evaluation fieldRules(TaskWorkEntries.FieldRules query, long actor);

    FieldRules.Evaluation relatedFieldRules(TaskWorkEntries.RelatedFieldRules query, long actor);
}
