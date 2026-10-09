package com.richuang.os.nocode.application.dal.mapper;

import com.richuang.os.framework.mybatis.core.mapper.BaseMapperX;
import com.richuang.os.nocode.application.dal.dataobject.TaskEntryAccessDO;

import org.apache.ibatis.annotations.*;

/** 与应用头写锁共用事务，实时读取授权；所有参数经 MyBatis 绑定。 */
@Mapper
public interface TaskEntryAccessMapper extends BaseMapperX<TaskEntryAccessDO> {
    TaskEntryAccessDO find(@Param("app") long app, @Param("entry") String entry);

    void save(
            @Param("app") long app,
            @Param("entry") String entry,
            @Param("enabled") boolean enabled,
            @Param("json") String json,
            @Param("actor") String actor);
}
