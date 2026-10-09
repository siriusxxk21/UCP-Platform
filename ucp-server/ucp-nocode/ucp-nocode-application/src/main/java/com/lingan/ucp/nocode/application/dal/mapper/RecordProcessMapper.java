package com.lingan.ucp.nocode.application.dal.mapper;

import com.lingan.ucp.framework.mybatis.core.mapper.BaseMapperX;
import com.lingan.ucp.nocode.application.dal.dataobject.*;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** 流程关联与启动在同一数据库事务；应用发布检查复用这份业务关联。 */
@Mapper
public interface RecordProcessMapper extends BaseMapperX<NocodeRecordProcessDO> {
    int create(@Param("row") NocodeRecordProcessDO row, @Param("actor") String actor);

    int attach(@Param("id") long id, @Param("instance") String instance);

    long running(@Param("object") long objectId, @Param("record") String recordId);

    List<NocodeRecordProcessDO> history(
            @Param("app") long app,
            @Param("object") long objectId,
            @Param("record") String recordId);

    List<NocodeRecordProcessDO> active(@Param("app") long app);

    NocodeRecordProcessDO byBusinessKey(@Param("key") String key);

    int complete(
            @Param("key") String key,
            @Param("definitionKey") String definitionKey,
            @Param("instance") String instance,
            @Param("status") String status);
}
