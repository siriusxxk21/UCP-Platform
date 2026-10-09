package com.lingan.ucp.nocode.runtime.dal.mapper;

import com.lingan.ucp.nocode.runtime.dal.query.*;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** 多对象视图沿用底座 Mapper 与同一事务，业务值由 MyBatis 绑定。 */
@Mapper
public interface DataViewMapper {
    /** 各关联来源独立聚合后执行外层筛选；根记录修订仍由原始根行计算。 */
    List<String> rows(DataViewStatement statement);

    /** 使用与 rows 一致的粒度、权限和条件计算总行数。 */
    long count(DataViewStatement statement);

    /** 绑定一个已授权主记录，独立读取指定来源的明细页。 */
    List<String> childRows(DataViewChildStatement statement);

    /** 与 childRows 复用主记录及来源范围。 */
    long childCount(DataViewChildStatement statement);
}
