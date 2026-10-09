package com.richuang.os.nocode.runtime.dal.query;

/** 子表查询绑定一个经授权的主记录，独立分页。 */
public record DataViewChildStatement(
        DataViewStatement view,
        int section,
        String recordId,
        String sortColumn,
        boolean descending,
        int limit,
        int offset) {}
