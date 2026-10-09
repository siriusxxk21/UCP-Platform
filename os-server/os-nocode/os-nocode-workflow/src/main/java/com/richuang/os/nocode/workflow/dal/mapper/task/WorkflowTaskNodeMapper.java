package com.richuang.os.nocode.workflow.dal.mapper.task;

import com.richuang.os.framework.mybatis.core.mapper.BaseMapperX;
import com.richuang.os.nocode.workflow.dal.dataobject.task.WorkflowTaskNodeDO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 交接锁只串行同一执行；业务及引擎更新必须在同一数据库事务提交。 */
@Mapper
public interface WorkflowTaskNodeMapper extends BaseMapperX<WorkflowTaskNodeDO> {
    Integer lock(@Param("key") String key);

    WorkflowTaskNodeDO execution(@Param("id") String id, @Param("tenant") long tenant);

    WorkflowTaskNodeDO binding(@Param("id") String id, @Param("tenant") long tenant);

    WorkflowTaskNodeDO arrival(
            @Param("execution") String execution,
            @Param("process") String process,
            @Param("node") String node,
            @Param("tenant") long tenant);

    WorkflowTaskNodeDO byTask(@Param("rootId") String rootId, @Param("tenant") long tenant);

    List<WorkflowTaskNodeDO> byTasks(
            @Param("rootIds") List<String> rootIds, @Param("tenant") long tenant);

    List<WorkflowTaskNodeDO> process(@Param("id") String id, @Param("tenant") long tenant);

    @com.baomidou.mybatisplus.annotation.InterceptorIgnore(tenantLine = "true")
    List<WorkflowTaskNodeDO> pending();

    int create(@Param("row") WorkflowTaskNodeDO row);

    int save(@Param("row") WorkflowTaskNodeDO row);
}
