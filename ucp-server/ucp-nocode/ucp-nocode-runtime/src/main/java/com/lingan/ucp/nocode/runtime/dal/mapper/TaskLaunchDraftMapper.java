package com.lingan.ucp.nocode.runtime.dal.mapper;

import com.lingan.ucp.framework.mybatis.core.mapper.BaseMapperX;
import com.lingan.ucp.nocode.api.TaskCenter.DraftSummary;
import com.lingan.ucp.nocode.runtime.dal.dataobject.TaskLaunchDraftDO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 任务编排草稿持久化；所有自定义 SQL 限制创建者和逻辑删除状态。 */
@Mapper
public interface TaskLaunchDraftMapper extends BaseMapperX<TaskLaunchDraftDO> {
    /** 只列出当前操作者未发布的个人草稿，已发布行保留用于幂等回执。 */
    List<DraftSummary> listOwned(@Param("actor") String actor);

    /** 首次保存尚无行可锁，按客户端草稿标识串行化首次写入和断网重试。 */
    void lockCreate(@Param("id") String id);

    /** 写事务使用行锁，读详情使用相同的私有边界。 */
    TaskLaunchDraftDO getOwned(
            @Param("id") String id, @Param("actor") String actor, @Param("lock") boolean lock);

    int createDraft(@Param("row") TaskLaunchDraftDO row, @Param("actor") String actor);

    int saveDraft(
            @Param("row") TaskLaunchDraftDO row,
            @Param("actor") String actor,
            @Param("expected") int expected);

    int deleteDraft(
            @Param("id") String id, @Param("actor") String actor, @Param("expected") int expected);

    /** 与任务及业务写入处于同一事务，发布后不再允许保存或删除。 */
    int markPublished(
            @Param("row") TaskLaunchDraftDO row,
            @Param("actor") String actor,
            @Param("expected") int expected);
}
