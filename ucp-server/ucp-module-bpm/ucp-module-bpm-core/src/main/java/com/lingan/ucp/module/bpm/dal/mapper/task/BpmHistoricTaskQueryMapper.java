package com.lingan.ucp.module.bpm.dal.mapper.task;

import com.lingan.ucp.module.bpm.framework.flowable.core.query.BpmHistoricTaskInstanceQuery;

import java.util.List;

/** Flowable 专用 Mapper，由引擎的 MyBatis 会话注册并参与引擎事务。 */
public interface BpmHistoricTaskQueryMapper {

    long count(BpmHistoricTaskInstanceQuery query);

    List<String> selectTaskIds(BpmHistoricTaskInstanceQuery query);
}
