package com.lingan.ucp.nocode.work.dal.mapper.submission;

import com.lingan.ucp.framework.mybatis.core.mapper.BaseMapperX;
import com.lingan.ucp.nocode.work.dal.dataobject.submission.WorkSubmissionDO;

import org.apache.ibatis.annotations.*;

/** 提交只插入和按归属读取，幂等键由数据库唯一约束兜底。 */
@Mapper
public interface WorkSubmissionMapper extends BaseMapperX<WorkSubmissionDO> {
    WorkSubmissionDO flowMaterial(
            @Param("id") String id,
            @Param("taskId") String taskId,
            @Param("actor") String actor,
            @Param("source") String source);

    boolean protectsRecord(
            @Param("objectId") String objectId,
            @Param("recordId") String recordId,
            @Param("source") String source);

    WorkSubmissionDO byDraft(@Param("draftId") String draftId, @Param("actor") String actor);

    Integer lockCommand(@Param("scope") String scope);

    WorkSubmissionDO byCommand(@Param("actor") String actor, @Param("key") String key);

    WorkSubmissionDO owned(@Param("id") String id, @Param("actor") String actor);

    int create(@Param("row") WorkSubmissionDO row, @Param("actor") String actor);
}
