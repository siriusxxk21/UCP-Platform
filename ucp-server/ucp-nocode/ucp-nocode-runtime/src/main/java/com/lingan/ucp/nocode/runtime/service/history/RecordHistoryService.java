package com.lingan.ucp.nocode.runtime.service.history;

import com.lingan.ucp.nocode.api.RecordHistory;

/** 工作台跨表历史查询；当前业务授权继续约束历史快照。 */
public interface RecordHistoryService {
    RecordHistory.Summary query(RecordHistory.Query query, long actor);

    RecordHistory.Page page(RecordHistory.PageQuery query, long actor);

    RecordHistory.Detail detail(RecordHistory.DetailQuery query, long actor);
}
