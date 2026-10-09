package com.richuang.os.nocode.work.dal.mapper.draft;

import com.richuang.os.framework.mybatis.core.mapper.BaseMapperX;
import com.richuang.os.nocode.work.dal.dataobject.draft.WorkDraftDO;

import org.apache.ibatis.annotations.*;

/** 草稿所有写入均校验归属、状态与修订；JSON 使用参数绑定转换。 */
@Mapper
public interface WorkDraftMapper extends BaseMapperX<WorkDraftDO> {
    String currentSourceDraft(
            @Param("actor") String actor,
            @Param("source") String source,
            @Param("sourceId") String sourceId,
            @Param("state") String state);

    java.util.List<com.richuang.os.nocode.api.work.WorkDraftViews.Candidate> candidates(
            @Param("query") com.richuang.os.nocode.api.work.WorkDraftViews.Query query,
            @Param("actor") String actor,
            @Param("source") String source,
            @Param("count") int count);

    java.util.List<com.richuang.os.nocode.api.work.WorkDraftViews.SourceCandidate> sourceCandidates(
            @Param("query") com.richuang.os.nocode.api.work.WorkDraftViews.SourceQuery query,
            @Param("actor") String actor,
            @Param("source") String source,
            @Param("count") int count);

    WorkDraftDO lockOwned(@Param("id") String id, @Param("actor") String actor);

    int create(@Param("row") WorkDraftDO row, @Param("actor") String actor);

    int save(
            @Param("row") WorkDraftDO row,
            @Param("actor") String actor,
            @Param("expected") int expected,
            @Param("state") String state);

    int transition(
            @Param("id") String id,
            @Param("actor") String actor,
            @Param("expected") int expected,
            @Param("current") String current,
            @Param("next") String next);
}
