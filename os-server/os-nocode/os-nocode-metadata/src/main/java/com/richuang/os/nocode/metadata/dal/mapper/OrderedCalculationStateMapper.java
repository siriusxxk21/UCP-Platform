package com.richuang.os.nocode.metadata.dal.mapper;

import com.richuang.os.nocode.metadata.dal.dataobject.OrderedCalculationStateDO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 与发布、业务写入同库持久化，进度和每组结果由调用方在同一事务提交。 */
@Mapper
public interface OrderedCalculationStateMapper {
    List<OrderedCalculationStateDO> forObject(@Param("object") long object);

    OrderedCalculationStateDO find(@Param("object") long object, @Param("field") long field);

    void insert(@Param("row") OrderedCalculationStateDO row);

    int update(@Param("row") OrderedCalculationStateDO row, @Param("revision") long revision);
}
