package com.lingan.ucp.nocode.runtime.service.work;

import com.lingan.ucp.nocode.api.SelectionFields;
import com.lingan.ucp.nocode.api.work.WorkDraftViews;
import com.lingan.ucp.nocode.api.work.WorkSourceRef;

/** 草稿工作区：所有列表、表单和候选查询重新验证当前权限。 */
public interface WorkDraftQueryService {
    WorkDraftViews.Page page(WorkDraftViews.Query query, long actor);

    default WorkDraftViews.Context context(String draftId, long actor) {
        return context(draftId, actor, WorkSourceRef.PERSONAL);
    }

    WorkDraftViews.Context context(String draftId, long actor, WorkSourceRef source);

    default SelectionFields.Result selection(WorkDraftViews.Selection request, long actor) {
        return selection(request, actor, WorkSourceRef.PERSONAL);
    }

    SelectionFields.Result selection(
            WorkDraftViews.Selection request, long actor, WorkSourceRef source);
}
