package com.lingan.ucp.nocode.metadata.dal.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;

import org.apache.ibatis.annotations.Mapper;

/** 生命周期预检聚合数据；服务端先核实真实表列，不接收客户端物理标识或原值。 */
@Mapper
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface ObjectOperationDataMapper {
    record Table(String schema, String table) {}

    record Column(String schema, String table, String column) {}

    record Counts(long rows, long nonNulls) {}

    Long countRows(Table table);

    Counts countColumn(Column column);

    Long countDuplicates(Column column);
}
