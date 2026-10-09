package com.richuang.os.nocode.workflow.dal.mapper.task;

import com.richuang.os.framework.mybatis.core.mapper.BaseMapperX;
import com.richuang.os.nocode.workflow.dal.dataobject.task.FlowTaskBindingDO;

import org.apache.ibatis.annotations.*;

/** 按真实引擎 taskId 串行化来源提交；完成证据只允许首次设置。 */
@Mapper
public interface FlowTaskBindingMapper extends BaseMapperX<FlowTaskBindingDO> {
    FlowTaskBindingDO read(@Param("taskId") String taskId);

    Integer lock(@Param("key") String key);

    FlowTaskBindingDO get(@Param("taskId") String taskId);

    int create(
            @Param("taskId") String taskId,
            @Param("taskJson") String taskJson,
            @Param("actor") String actor);

    int submitted(
            @Param("taskId") String taskId,
            @Param("material") String material,
            @Param("actor") String actor,
            @Param("digest") String digest);
}
