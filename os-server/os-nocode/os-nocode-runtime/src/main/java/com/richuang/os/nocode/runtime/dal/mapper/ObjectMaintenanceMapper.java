package com.richuang.os.nocode.runtime.dal.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.richuang.os.nocode.runtime.dal.query.RecordStatement;

import org.apache.ibatis.annotations.Mapper;

/** 数据维护与结构发布使用同一把建模锁，防止校验后发布版本变化。 */
@Mapper
public interface ObjectMaintenanceMapper {
    void lockDefinitions();

    /** 仅使用 RuntimeSchema 生成的可信表、主键和归属列，避免把明细行 ID 当成主记录导航。 */
    String parentRecordId(RecordStatement statement);

    /** 参数仅由已发布 RuntimeSchema 和实际数据库目录构造，不能接受请求中的表列名。 */
    record ColumnScope(
            String schema,
            String table,
            String keyColumn,
            String parentColumn,
            String column,
            String objectId,
            String actor) {}

    record ColumnStats(long activeRows, long deletedRows, String fingerprint) {}

    /** PostgreSQL 表锁不经过行权限 SQL 改写；对象管理授权已由维护入口完成。 */
    @InterceptorIgnore(dataPermission = "true", tenantLine = "true")
    void lockColumnTable(ColumnScope scope);

    ColumnStats columnStats(ColumnScope scope);

    java.util.List<String> affectedMainIds(ColumnScope scope);

    boolean hasProtectedRows(ColumnScope scope);

    int clearColumn(ColumnScope scope);
}
