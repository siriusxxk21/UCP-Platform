package com.richuang.os.nocode.work.dal.mapper.event;

import com.richuang.os.framework.mybatis.core.mapper.BaseMapperX;
import com.richuang.os.nocode.work.dal.dataobject.event.WorkEventDO;

import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkEventMapper extends BaseMapperX<WorkEventDO> {
    int append(@Param("row") WorkEventDO row, @Param("actor") String actor);
}
