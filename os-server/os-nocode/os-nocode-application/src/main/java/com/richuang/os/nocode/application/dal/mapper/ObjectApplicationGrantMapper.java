package com.richuang.os.nocode.application.dal.mapper;

import com.richuang.os.framework.mybatis.core.mapper.BaseMapperX;
import com.richuang.os.nocode.api.ObjectSharing;
import com.richuang.os.nocode.application.dal.dataobject.*;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** 使用应用头锁序列化授权修改与运行事务；不缓存跨请求授权。 */
@Mapper
public interface ObjectApplicationGrantMapper extends BaseMapperX<NocodeObjectApplicationGrantDO> {
    NocodeObjectApplicationGrantDO find(@Param("object") long object, @Param("app") long app);

    List<NocodeObjectApplicationGrantDO> forObject(@Param("object") long object);

    List<NocodeObjectApplicationGrantDO> forApplication(@Param("app") long app);

    List<ObjectSharing.Target> targets();

    int save(
            @Param("object") long object,
            @Param("app") long app,
            @Param("json") String json,
            @Param("reason") String reason,
            @Param("actor") String actor);

    int audit(@Param("object") long object, @Param("app") long app, @Param("actor") String actor);
}
