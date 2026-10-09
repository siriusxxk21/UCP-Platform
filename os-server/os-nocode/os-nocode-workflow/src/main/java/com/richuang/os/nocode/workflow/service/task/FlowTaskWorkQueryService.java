package com.richuang.os.nocode.workflow.service.task;

import com.richuang.os.nocode.api.workflow.FlowTaskWorkViews;

/** 查询本人的流程草稿或提交材料；当前任务资格与业务权限共同决定可见性。 */
public interface FlowTaskWorkQueryService {
    FlowTaskWorkViews.Page page(FlowTaskWorkViews.Query query, long actor);
}
