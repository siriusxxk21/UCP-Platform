package com.lingan.ucp.nocode.application.dal.mapper;

import com.lingan.ucp.framework.mybatis.core.mapper.BaseMapperX;
import com.lingan.ucp.nocode.application.dal.dataobject.*;

import org.apache.ibatis.annotations.*;

/** 授权写入在应用头锁内完成，读取不使用跨请求缓存。 */
@Mapper
public interface ApplicationAccessMapper extends BaseMapperX<NocodeApplicationAccessDO> {
    NocodeApplicationAccessDO policy(@Param("id") long id);

    int save(@Param("id") long id, @Param("json") String json, @Param("actor") String actor);
}
