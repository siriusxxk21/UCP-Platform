package com.lingan.ucp.nocode.runtime.service.taskcenter;

import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.nocode.api.TaskEfficiency.*;

/** 老板任务统计入口；每次查询重新验证管理能力及实例创建范围。 */
public interface TaskEfficiencyService {
    Overview overview(Query query, long actor);

    PageResult<Employee> employees(Query query, long actor);

    PageResult<Task> tasks(Query query, long actor);

    PageResult<com.lingan.ucp.nocode.api.TaskEfficiency.Record> records(Query query, long actor);

    Options options(Query query, long actor);
}
