package com.lingan.ucp.nocode.work.dal.mapper.event;

import com.lingan.ucp.framework.mybatis.core.mapper.BaseMapperX;
import com.lingan.ucp.nocode.work.dal.dataobject.event.WorkEventDO;

import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkEventMapper extends BaseMapperX<WorkEventDO> {
    int append(@Param("row") WorkEventDO row, @Param("actor") String actor);
}
