package com.richuang.os.nocode.runtime.service.taskcenter;

import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.nocode.api.TaskWorkEntries;

/** 逐次办理事实的受控读取，独立于当前业务记录是否仍存在。 */
public interface TaskHandlingHistoryService {
    PageResult<TaskWorkEntries.HandlingRow> page(TaskWorkEntries.HistoryQuery query, long actor);

    TaskWorkEntries.HandlingDetail detail(TaskWorkEntries.HistoryRef query, long actor);
}
