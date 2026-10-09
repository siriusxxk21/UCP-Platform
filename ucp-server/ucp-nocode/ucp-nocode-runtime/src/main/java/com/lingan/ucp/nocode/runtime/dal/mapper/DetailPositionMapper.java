package com.lingan.ucp.nocode.runtime.dal.mapper;

import com.lingan.ucp.framework.mybatis.core.mapper.BaseMapperX;
import com.lingan.ucp.nocode.runtime.dal.dataobject.DetailPositionDO;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** 主单据已经加锁；排序变更不暴露独立写入口，所有外部值使用绑定参数。 */
@Mapper
public interface DetailPositionMapper extends BaseMapperX<DetailPositionDO> {
    List<String> order(
            @Param("object") String object,
            @Param("detail") String detail,
            @Param("parent") String parent);

    void clear(
            @Param("object") String object,
            @Param("detail") String detail,
            @Param("parent") String parent,
            @Param("actor") String actor);

    void append(
            @Param("object") String object,
            @Param("detail") String detail,
            @Param("parent") String parent,
            @Param("rows") List<String> rows,
            @Param("actor") String actor);
}
