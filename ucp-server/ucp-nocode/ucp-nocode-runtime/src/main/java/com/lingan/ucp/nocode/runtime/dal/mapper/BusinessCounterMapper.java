package com.lingan.ucp.nocode.runtime.dal.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.lingan.ucp.framework.mybatis.core.mapper.BaseMapperX;
import com.lingan.ucp.nocode.runtime.dal.dataobject.*;

import org.apache.ibatis.annotations.*;

/** 原子递增在业务保存事务内执行。INSERT RETURNING 无查询行权限语义，由运行授权先行校验。 */
@Mapper
public interface BusinessCounterMapper extends BaseMapperX<NocodeBusinessCounterDO> {
    @InterceptorIgnore(dataPermission = "true")
    void lockField(@Param("name") String name);

    @InterceptorIgnore(dataPermission = "true")
    long nextConfigured(
            @Param("object") long objectId,
            @Param("field") long fieldId,
            @Param("period") String period,
            @Param("start") long start,
            @Param("actor") String actor);

    /** 标识符由底座白名单处理；检查包含已删除记录，避免重用历史编号。 */
    @InterceptorIgnore(dataPermission = "true")
    boolean numberExists(
            @Param("table") String quotedTable,
            @Param("column") String quotedColumn,
            @Param("value") String value);

    @InterceptorIgnore(dataPermission = "true")
    long next(
            @Param("object") long objectId,
            @Param("field") long fieldId,
            @Param("period") String period,
            @Param("actor") String actor);
}
