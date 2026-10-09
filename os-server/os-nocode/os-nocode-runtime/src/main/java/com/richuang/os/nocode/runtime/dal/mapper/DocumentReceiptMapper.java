package com.richuang.os.nocode.runtime.dal.mapper;

import com.richuang.os.framework.mybatis.core.mapper.BaseMapperX;
import com.richuang.os.nocode.runtime.dal.dataobject.DocumentReceiptDO;

import org.apache.ibatis.annotations.*;

/** 与工作提交复用 PostgreSQL 事务锁模式，相同请求跨进程串行后恢复成功结果。 */
@Mapper
public interface DocumentReceiptMapper extends BaseMapperX<DocumentReceiptDO> {
    String lock(String key);

    DocumentReceiptDO find(
            @Param("actor") String actor,
            @Param("app") String app,
            @Param("object") String object,
            @Param("operation") String operation,
            @Param("key") String key);

    void append(@Param("row") DocumentReceiptDO row, @Param("actor") String actor);
}
