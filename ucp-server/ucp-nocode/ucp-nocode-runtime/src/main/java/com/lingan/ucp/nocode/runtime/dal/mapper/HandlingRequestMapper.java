package com.lingan.ucp.nocode.runtime.dal.mapper;

import com.lingan.ucp.framework.mybatis.core.mapper.BaseMapperX;
import com.lingan.ucp.nocode.runtime.dal.dataobject.HandlingRequestDO;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** 申请使用事务锁防止重复启动，状态 CAS 和业务写入共用事务。 */
@Mapper
public interface HandlingRequestMapper extends BaseMapperX<HandlingRequestDO> {

    String lockCommand(String key);

    HandlingRequestDO lock(String id);

    HandlingRequestDO byCommand(
            @Param("actor") String actor,
            @Param("app") String app,
            @Param("object") String object,
            @Param("key") String key);

    void create(@Param("r") HandlingRequestDO row, @Param("actor") String actor);

    int attach(@Param("id") String id, @Param("instance") String instance);

    int transition(
            @Param("id") String id,
            @Param("revision") int revision,
            @Param("state") String state,
            @Param("record") String record,
            @Param("error") String error,
            @Param("actor") String actor);

    boolean protects(
            @Param("object") String object,
            @Param("record") String record,
            @Param("except") String except);

    List<HandlingRequestDO> mine(
            @Param("actor") String actor,
            @Param("state") String state,
            @Param("app") String app,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countMine(
            @Param("actor") String actor, @Param("state") String state, @Param("app") String app);

    List<String> pendingApplication();
}
